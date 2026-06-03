package com.example

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.archive.InputSource

object ApkOptimizerRam {
    private const val TAG = "ApkOptimizerRam"

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

    fun optimizeModule(apkModule: ApkModule, config: OptimizationConfig) {
        val zipFilesToRemove = mutableSetOf<String>()
        val tableBlock = apkModule.tableBlock
        
        if (tableBlock != null) {
            cleanResourcesInRam(tableBlock, config, zipFilesToRemove)
            cleanUpArscGarbage(tableBlock)
        } else {
            Log.w(TAG, "No resources.arsc found. Skipping resource clean.")
        }

        cleanLibsAndJunk(apkModule, config, zipFilesToRemove)
    }

    private fun cleanResourcesInRam(tableBlock: TableBlock, config: OptimizationConfig, zipFilesToRemove: MutableSet<String>) {
        Log.i(TAG, "Starting ARSC binary sweep (ID-Centric Approach)...")
        var droppedConfigs = 0
        var nullifiedEntries = 0
        val targetRank = DENSITY_PRIORITY[config.targetDpi] ?: 5

        tableBlock.packages.forEach { pkgItem ->
            (pkgItem as PackageBlock).listSpecTypePairs().forEach { typePairItem ->
                val typePair = typePairItem as SpecTypePair
                val typeName = typePair.typeName
                val typeBlocks = typePair.typeBlockArray.childes.map { it as TypeBlock } 

                for (entryId in 0 until typePair.highestEntryCount) {
                    val activeEntries = typeBlocks.mapNotNull { it.getEntry(entryId.toShort()) }.filter { !it.isNull }

                    // STRICT RULE: Never delete solitary IDs to prevent ResourceNotFoundException
                    if (activeEntries.size <= 1) continue 

                    val survivors = mutableListOf<com.reandroid.arsc.value.Entry>()
                    activeEntries.forEach { entry ->
                        if (hasBadQualifiers(entry.typeBlock.resConfig.qualifiers, config)) {
                            nullifyAndMarkForDeletion(entry, zipFilesToRemove)
                            droppedConfigs++
                        } else {
                            survivors.add(entry)
                        }
                    }

                    if (typeName !in TARGET_FOLDERS || survivors.size <= 1) continue

                    // Density Ranking: Native Android Fallback (Downscale before Upscale)
                    survivors.groupBy { getFamilyKey(it.typeBlock.resConfig.qualifiers) }
                        .values.filter { it.size > 1 }
                        .forEach { family ->
                            val exactMatch = family.find { getDensityRank(it.typeBlock.resConfig.qualifiers) == targetRank }
                            val bestAbove = family.filter { getDensityRank(it.typeBlock.resConfig.qualifiers) > targetRank }.minByOrNull { getDensityRank(it.typeBlock.resConfig.qualifiers) }
                            val bestBelow = family.filter { getDensityRank(it.typeBlock.resConfig.qualifiers) < targetRank }.maxByOrNull { getDensityRank(it.typeBlock.resConfig.qualifiers) }
                            
                            val winner = exactMatch ?: bestAbove ?: bestBelow ?: family.first()

                            family.filter { it != winner }.forEach { loser ->
                                nullifyAndMarkForDeletion(loser, zipFilesToRemove)
                                nullifiedEntries++
                            }
                        }
                }
            }
        }
        Log.i(TAG, "Vaporized $droppedConfigs bad configs and nullified $nullifiedEntries redundant density files from ARSC.")
    }

    private fun cleanUpArscGarbage(tableBlock: TableBlock) {
        tableBlock.packages.forEach { pkgItem ->
            (pkgItem as PackageBlock).listSpecTypePairs().forEach { typePairItem ->
                val typeBlockArray = (typePairItem as SpecTypePair).typeBlockArray
                val emptyBlocks = typeBlockArray.childes.map { it as TypeBlock }.filter { typeBlock ->
                    typeBlock.entryArray.childes.all { (it as com.reandroid.arsc.value.Entry).isNull }
                }
                emptyBlocks.forEach { typeBlockArray.remove(it) }
            }
        }
        tableBlock.stringPool.removeUnusedStrings()
        tableBlock.refresh()
    }

    private fun cleanLibsAndJunk(apkModule: ApkModule, config: OptimizationConfig, zipFilesToRemove: Set<String>) {
        var deletedLibs = 0; var deletedJunk = 0; var deletedOrphanedRes = 0

        val pathsToRemove = apkModule.listInputSources().mapNotNull { item ->
            val path = (item as InputSource).alias
            when {
                path in zipFilesToRemove -> { deletedOrphanedRes++; path }
                path.startsWith("lib/") && path.split("/").getOrNull(1) !in listOf(null, config.targetArch) -> { deletedLibs++; path }
                isJunkZipPath(path) -> { deletedJunk++; path }
                else -> null
            }
        }
        
        pathsToRemove.forEach { targetPath ->
            try {
                apkModule.zipEntryMap.remove(targetPath)
            } catch (e: Exception) {
                Log.e(TAG, "Crash while natively removing $targetPath: ${e.message}")
            }
        }
        Log.i(TAG, "Stripped $deletedLibs libs, $deletedJunk strict junk files, and $deletedOrphanedRes orphaned res from ZIP.")
    }

    private fun isJunkZipPath(path: String): Boolean {
        val fileName = path.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val baseName = fileName.substringBeforeLast('.', fileName).lowercase()
        
        val parentPath = if (path.contains('/')) path.substringBeforeLast('/') else ""

        if (baseName in JUNK_BASENAMES) return true
        if (ext == "properties" && parentPath == "") return true
        if ((ext == "version" || ext == "textproto") && parentPath == "META-INF") return true
        
        return false
    }

    private fun nullifyAndMarkForDeletion(entry: com.reandroid.arsc.value.Entry, zipFilesToRemove: MutableSet<String>) {
        entry.resValue?.valueAsString?.let { path ->
            if (path.startsWith("res/")) zipFilesToRemove.add(path)
        }
        entry.setNull(true) 
    }

    private fun hasBadQualifiers(qualifiers: String, config: OptimizationConfig): Boolean {
        if (qualifiers.isEmpty()) return false
        val parts = qualifiers.split("-")
        if (parts.any { it in DROP_QUALIFIERS }) return true

        return parts.any { q ->
            SW_REGEX.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()?.let { it > config.maxSwDp } == true ||
            W_REGEX.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()?.let { it > config.maxDimensionDp } == true ||
            H_REGEX.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()?.let { it > config.maxDimensionDp } == true ||
            (LANG_REGEX.matches(q) && q !in config.keepLangs)
        }
    }

    private fun getDensityRank(qualifiers: String) = DENSITY_PRIORITY[qualifiers.split("-").firstOrNull { it in DENSITY_PRIORITY }] ?: -1
    
    // Perfectly synced with File mode grouping behavior
    private fun getFamilyKey(qualifiers: String) = qualifiers.split("-").filter { it !in DENSITY_PRIORITY.keys && it.isNotEmpty() }.sorted().joinToString("-")
}
