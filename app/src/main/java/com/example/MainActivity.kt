package com.example

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class OptimizationMode {
    FAST,   // In-RAM Binary Modification
    STABLE  // Decompile -> File Sweep -> Recompile
}

data class OptimizationConfig(
    val maxSwDp: Int,
    val maxDimensionDp: Int,
    val keepLangs: Set<String>,
    val targetArch: String,
    val targetDpi: String,
    val mode: OptimizationMode
)

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var pickApkButton: MaterialButton
    private lateinit var saveButton: MaterialButton

    private lateinit var inputSmallestWidth: TextInputEditText
    private lateinit var inputMaxDimen: TextInputEditText
    private lateinit var inputTargetDpi: Spinner
    private lateinit var inputCpuArch: Spinner
    private lateinit var inputKeepLocales: TextInputEditText
    private lateinit var engineModeGroup: RadioGroup

    private var optimizedFile: File? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleApkUri(it) }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        uri?.let { exportFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("OptimumCrash", "Uncaught exception", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            setContentView(R.layout.activity_main)
            
            // Find Views
            statusText = findViewById(R.id.tvStatus)
            logText = findViewById(R.id.tvLogs)
            pickApkButton = findViewById(R.id.btnImportApk)
            saveButton = findViewById(R.id.btnExportApk)
            inputSmallestWidth = findViewById(R.id.inputSmallestWidth)
            inputMaxDimen = findViewById(R.id.inputMaxDimen)
            inputTargetDpi = findViewById(R.id.inputTargetDpi)
            inputCpuArch = findViewById(R.id.inputCpuArch)
            inputKeepLocales = findViewById(R.id.inputKeepLocales)
            engineModeGroup = findViewById(R.id.engineModeGroup)

            setupDefaults()

            pickApkButton.setOnClickListener { apkPicker.launch("*/*") }
            saveButton.setOnClickListener { saveLauncher.launch("optimized_app.apk") }
        } catch (e: Throwable) {
            Log.e("OptimumCrash", "Fatal error during onCreate", e)
            val msg = "Startup Crash: ${e.javaClass.simpleName} - ${e.message}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupDefaults() {
        val config = resources.configuration
        val exactDpi = resources.displayMetrics.densityDpi
        val detectedArch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val detectedDpi = when {
            exactDpi <= 120 -> "ldpi"
            exactDpi <= 160 -> "mdpi"
            exactDpi <= 240 -> "hdpi"
            exactDpi <= 320 -> "xhdpi"
            exactDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi" 
        }

        inputSmallestWidth.setText(config.smallestScreenWidthDp.toString())
        inputMaxDimen.setText(maxOf(config.screenWidthDp, config.screenHeightDp).toString())

        val dpiOptions = arrayOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val dpiAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dpiOptions)
        inputTargetDpi.adapter = dpiAdapter
        inputTargetDpi.setSelection(dpiOptions.indexOf(detectedDpi).coerceAtLeast(0))

        val archOptions = arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        val archAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, archOptions)
        inputCpuArch.adapter = archAdapter
        inputCpuArch.setSelection(archOptions.indexOf(detectedArch).coerceAtLeast(0))
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logText.append("\n> $msg")
            (logText.parent as? ScrollView)?.post { 
                (logText.parent as ScrollView).fullScroll(View.FOCUS_DOWN) 
            }
        }
    }

    private fun handleApkUri(uri: Uri) {
        val keepLocalesStr = inputKeepLocales.text?.toString() ?: ""
        
        val mode = if (engineModeGroup.checkedRadioButtonId == R.id.btnFastMode) OptimizationMode.FAST else OptimizationMode.STABLE

        val optConfig = OptimizationConfig(
            maxSwDp = inputSmallestWidth.text?.toString()?.toIntOrNull() ?: 400,
            maxDimensionDp = inputMaxDimen.text?.toString()?.toIntOrNull() ?: 800,
            keepLangs = keepLocalesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            targetArch = inputCpuArch.selectedItem?.toString()?.trim() ?: "arm64-v8a",
            targetDpi = inputTargetDpi.selectedItem?.toString()?.trim() ?: "xxhdpi",
            mode = mode
        )

        logText.text = "Starting optimization..."
        appendLog("Selected URI: $uri")
        appendLog("Engine: ${optConfig.mode} | Arch: ${optConfig.targetArch} | DPI: ${optConfig.targetDpi}")
        
        statusText.text = "Processing..."
        pickApkButton.isEnabled = false
        saveButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val inputFile = File(cacheDir, "input_raw.apk")
            val output = File(cacheDir, "output_optimized.apk")

            try {
                inputFile.delete()
                output.delete()

                contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().use { input.copyTo(it) }
                }

                val engine = OptimizationEngine { progress ->
                    appendLog(progress)
                    lifecycleScope.launch(Dispatchers.Main) { statusText.text = progress }
                }
                
                val result = engine.runPipeline(inputFile, output, optConfig)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        optimizedFile = output
                        statusText.text = "Success!"
                        saveButton.isEnabled = true
                    } else {
                        statusText.text = "Failed"
                    }
                    pickApkButton.isEnabled = true
                }
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Status: Error"
                    pickApkButton.isEnabled = true
                }
            }
        }
    }

    private fun exportFile(destinationUri: Uri) {
        val fileToSave = optimizedFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    fileToSave.inputStream().use { it.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("Save failed: ${e.message}")
            }
        }
    }
}
