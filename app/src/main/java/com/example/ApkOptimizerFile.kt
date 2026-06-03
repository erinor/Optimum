package com.example

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object ApkOptimizerFile {
    private const val TAG = "ApkOptimizerFile"
    
    private val DENSITY_PRIORITY = mapOf(
        "ldpi" to 0, "mdpi" to 1, "hdpi" to 2, 
        "xhdpi" to 3, "xxhdpi" to 4, "xxxhdpi" to 5
    )
    private val TARGET_FOLDERS = setOf("drawable", "mipmap")
    private val DROP_QUALIFIERS = setOf("watch", "tv", "television", "car", "small", "large", "xlarge", "round", "notround")
    private val JUNK_BASENAMES = setOf("app-metadata", "license", "notice", "readme")

    private val LANG_REGEX = Regex("^([a-z]{2,3})(?:-r[A-Z]{2,3})?$|^b\\+([a-zA-Z0-9]+).*$")
    private val SW_REGEX = Regex("^sw(\\d+)dp$")
    private val W_REGEX = Regex("^w(\\d+)dp$")
    private val H_REGEX = Regex("^h(\\d+)dp$")

    private data class ParsedFolder(
        val drop: Boolean, 
        val density: String? = null, 
        val densityRank: Int = -1, 
        val familyKey: String = ""
    )

    private fun parseResourceFolder(folderName: String, config: OptimizationConfig): ParsedFolder? {
        val parts = folderName.split("-")
        val baseType = parts.first()
        val qualifiers = parts.drop(1)

        if (qualifiers.any { it in DROP_QUALIFIERS }) return ParsedFolder(drop = true)

        for (q in qualifiers) {
            SW_REGEX.matchEntire(q)?.let { if (it.groupValues[1].toInt() > config.maxSwDp) return ParsedFolder(drop = true) }
            W_REGEX.matchEntire(q)?.let { if (it.groupValues[1].toInt() > config.maxDimensionDp) return ParsedFolder(drop = true) }
            H_REGEX.matchEntire(q)?.let { if (it.groupValues[1].toInt() > config.maxDimensionDp) return ParsedFolder(drop = true) }
            
            val match = LANG_REGEX.matchEntire(q)
            if (match != null && q !in config.keepLangs) return ParsedFolder(drop = true)
        }

        if (baseType !in TARGET_FOLDERS) return null

        val density = qualifiers.firstOrNull { it in DENSITY_PRIORITY }
        val familyQualifiers = qualifiers.filter { it != density }.sorted().joinToString("-")
        val familyKey = if (familyQualifiers.isEmpty()) baseType else "$baseType-$familyQualifiers"
        
        return ParsedFolder(false, density, DENSITY_PRIORITY[density] ?: -1, familyKey)
    }

    private data class ResEntry(
        val file: File, val filename: String, val density: String?, 
        val densityRank: Int, val familyKey: String, val drop: Boolean
    )

    suspend fun cleanResFolder(resDir: File, config: OptimizationConfig) = coroutineScope {
        if (!resDir.exists() || !resDir.isDirectory) return@coroutineScope
        
        val entries = resDir.listFiles()?.map { folder ->
            async(Dispatchers.IO) {
                if (!folder.isDirectory) return@async null
                val parsed = parseResourceFolder(folder.name, config) ?: return@async null
                
                if (parsed.drop) {
                    folder.deleteRecursively()
                    null
                } else {
                    folder.listFiles()?.filter { it.isFile }?.map { file ->
                        ResEntry(file, file.name, parsed.density, parsed.densityRank, parsed.familyKey, parsed.drop)
                    }
                }
            }
        }?.awaitAll()?.filterNotNull()?.flatten() ?: emptyList()

        val densityGroups = entries.filter { !it.drop && it.density != null }.groupBy { Pair(it.familyKey, it.filename) }
        val targetRank = DENSITY_PRIORITY[config.targetDpi] ?: 5
        val deletedCount = AtomicInteger(0)

        // Density Ranking: Native Android Fallback (Downscale before Upscale)
        densityGroups.values.map { items ->
            async(Dispatchers.IO) {
                if (items.size > 1) {
                    val exactMatch = items.find { it.densityRank == targetRank }
                    val bestAbove = items.filter { it.densityRank > targetRank }.minByOrNull { it.densityRank }
                    val bestBelow = items.filter { it.densityRank < targetRank }.maxByOrNull { it.densityRank }
                    
                    val winner = exactMatch ?: bestAbove ?: bestBelow ?: items.first()

                    items.filter { it != winner }.forEach { 
                        if (it.file.delete()) deletedCount.incrementAndGet() 
                    }
                }
            }
        }.awaitAll()

        resDir.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
        Log.i(TAG, "Deleted ${deletedCount.get()} redundant density files in ${resDir.name}.")
    }

    suspend fun cleanLibFolder(libDir: File, config: OptimizationConfig) = coroutineScope {
        if (!libDir.exists() || !libDir.isDirectory) return@coroutineScope
        val deletedLibs = AtomicInteger(0)
        
        libDir.listFiles()?.map { archFolder ->
            async(Dispatchers.IO) {
                if (archFolder.isDirectory && archFolder.name != config.targetArch) {
                    if (archFolder.deleteRecursively()) deletedLibs.incrementAndGet()
                }
            }
        }?.awaitAll()
        Log.i(TAG, "Stripped ${deletedLibs.get()} unnecessary architecture folders.")
    }
    
    suspend fun cleanJunkFiles(workDir: File) = coroutineScope {
        val rootDir = File(workDir, "root")
        if (!rootDir.exists() || !rootDir.isDirectory) return@coroutineScope

        val metaInfDir = File(rootDir, "META-INF")
        Log.i(TAG, "Starting STRICT PARALLEL sweep of junk files in root/...")

        val junkFiles = rootDir.walkTopDown().filter { it.isFile && isJunkFile(it, rootDir, metaInfDir) }.toList()

        junkFiles.map { file ->
            async(Dispatchers.IO) {
                if (file.delete()) Log.d(TAG, "Vaporized junk: ${file.name}")
            }
        }.awaitAll()

        rootDir.walkBottomUp().filter { it.isDirectory && it.list()?.isEmpty() == true }.forEach { it.delete() }
        Log.i(TAG, "Swept ${junkFiles.size} strict junk files.")
    }

    private fun isJunkFile(file: File, rootDir: File, metaInfDir: File): Boolean {
        val ext = file.extension.lowercase()
        val baseName = file.nameWithoutExtension.lowercase()
        val parent = file.parentFile

        if (baseName in JUNK_BASENAMES) return true
        if (ext == "properties" && parent == rootDir) return true
        if ((ext == "version" || ext == "textproto") && parent == metaInfDir) return true

        return false
    }
}
