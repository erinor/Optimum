package com.example

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OptimizationEngine(
    private val onProgress: (String) -> Unit
) {
    suspend fun runPipeline(inputFile: File, outputFile: File, config: OptimizationConfig): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (config.mode == OptimizationMode.FAST) {
                    // --- STRATEGY 1: IN-RAM BINARY (Extremely Fast) ---
                    onProgress("Stage 1/3: Loading APK into RAM...")
                    val apkModule = ApkModule.loadApkFile(inputFile)

                    onProgress("Stage 2/3: Surgically removing bloat from ARSC & ZIP (Fast Mode)...")
                    ApkOptimizerRam.optimizeModule(apkModule, config)

                    onProgress("Stage 3/3: Recompiling optimized APK...")
                    apkModule.writeApk(outputFile)

                    onProgress("Optimization Successful! (Ready for ZipAlign & Sign)")
                    true

                } else {
                    // --- STRATEGY 2: DECOMPILE/RECOMPILE (Structurally Perfect) ---
                    val tempWorkDir = File(inputFile.parentFile, "apk_temp_${System.currentTimeMillis()}")
                    try {
                        if (tempWorkDir.exists()) tempWorkDir.deleteRecursively()
                        
                        onProgress("Stage 1/4: Decompiling APK to raw files...")
                        val apkModule = ApkModule.loadApkFile(inputFile)
                        val decoder = ApkModuleXmlDecoder(apkModule)
                        decoder.decode(tempWorkDir)

                        onProgress("Stage 2/4: Hunting and sweeping resources (Stable Mode)...")
                        
                        val resourcesDir = File(tempWorkDir, "resources")
                        if (resourcesDir.exists()) {
                            val resFolders = resourcesDir.walkTopDown()
                                .filter { it.isDirectory && it.name == "res" }
                                .toList()
                                
                            for (resDir in resFolders) {
                                ApkOptimizerFile.cleanResFolder(resDir, config)
                            }
                        }

                        // FIX: Convert sequence to a List BEFORE iterating so we can call suspend functions safely
                        val rootDir = File(tempWorkDir, "root")
                        if (rootDir.exists()) {
                            val libFolders = rootDir.walkTopDown()
                                .filter { it.isDirectory && it.name == "lib" }
                                .toList()
                                
                            for (libDir in libFolders) {
                                ApkOptimizerFile.cleanLibFolder(libDir, config)
                            }
                        }

                        // Execute the strict path-aware junk sweeper
                        ApkOptimizerFile.cleanJunkFiles(tempWorkDir)

                        onProgress("Stage 3/4: Compiling structurally perfect resources.arsc...")
                        val encoder = ApkModuleXmlEncoder()
                        encoder.scanDirectory(tempWorkDir)

                        onProgress("Stage 4/4: Writing optimized APK...")
                        encoder.apkModule.writeApk(outputFile)

                        onProgress("Optimization Successful! (Ready for ZipAlign & Sign)")
                        true
                    } finally {
                        if (tempWorkDir.exists()) {
                            tempWorkDir.deleteRecursively()
                        }
                    }
                }
            }.onFailure { e ->
                onProgress("\n--- FATAL CRASH ---\n${Log.getStackTraceString(e)}")
            }
        }
    }
}