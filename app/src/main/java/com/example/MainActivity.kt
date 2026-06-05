package com.example

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
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

    // Views
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var pickApkButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var inputSmallestWidth: TextInputEditText
    private lateinit var inputMaxDimen: TextInputEditText
    private lateinit var inputTargetDpi: AutoCompleteTextView
    private lateinit var inputCpuArch: AutoCompleteTextView
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
        setupCrashHandler()
        DynamicColors.applyToActivityIfAvailable(this)

        try {
            setContentView(R.layout.activity_main)
            initViews()
            setupDefaults()
            setupListeners()
        } catch (e: Throwable) {
            Log.e("OptimumCrash", "Fatal error during onCreate", e)
            Toast.makeText(this, "Startup Crash: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("OptimumCrash", "Uncaught exception", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun initViews() {
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
    }

    private fun setupListeners() {
        pickApkButton.setOnClickListener { apkPicker.launch("*/*") }
        saveButton.setOnClickListener { saveLauncher.launch("optimized_app.apk") }
    }

    private fun setupDefaults() {
        val config = resources.configuration
        val density = resources.displayMetrics.densityDpi
        
        inputSmallestWidth.setText(config.smallestScreenWidthDp.toString())
        inputMaxDimen.setText(maxOf(config.screenWidthDp, config.screenHeightDp).toString())

        val detectedArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val detectedDpi = when {
            density <= 120 -> "ldpi"
            density <= 160 -> "mdpi"
            density <= 240 -> "hdpi"
            density <= 320 -> "xhdpi"
            density <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }

        setupDropdown(inputTargetDpi, arrayOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"), detectedDpi)
        setupDropdown(inputCpuArch, arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"), detectedArch)
    }

    private fun setupDropdown(view: AutoCompleteTextView, options: Array<String>, defaultOption: String) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, options))
        view.setText(defaultOption, false)
    }

    private fun buildConfig(): OptimizationConfig {
        val mode = if (engineModeGroup.checkedRadioButtonId == R.id.btnFastMode) OptimizationMode.FAST else OptimizationMode.STABLE
        val locales = inputKeepLocales.text?.toString().orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return OptimizationConfig(
            maxSwDp = inputSmallestWidth.text?.toString()?.toIntOrNull() ?: 400,
            maxDimensionDp = inputMaxDimen.text?.toString()?.toIntOrNull() ?: 800,
            keepLangs = locales,
            targetArch = inputCpuArch.text?.toString()?.trim() ?: "arm64-v8a",
            targetDpi = inputTargetDpi.text?.toString()?.trim() ?: "xxhdpi",
            mode = mode
        )
    }

    private fun setProcessingState(isProcessing: Boolean, isSuccess: Boolean = false) {
        pickApkButton.isEnabled = !isProcessing
        saveButton.isEnabled = !isProcessing && isSuccess
        if (isProcessing) statusText.text = "Processing..."
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logText.append("\n> $msg")
            (logText.parent as? ScrollView)?.let { scroll ->
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun handleApkUri(uri: Uri) {
        val config = buildConfig()

        logText.text = "Starting optimization..."
        appendLog("Selected URI: $uri")
        appendLog("Engine: ${config.mode} | Arch: ${config.targetArch} | DPI: ${config.targetDpi}")
        
        setProcessingState(isProcessing = true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputFile = File(cacheDir, "input_raw.apk").apply { 
                    delete()
                    contentResolver.openInputStream(uri)?.use { input -> outputStream().use { input.copyTo(it) } }
                }
                val outputFile = File(cacheDir, "output_optimized.apk").apply { delete() }

                val engine = OptimizationEngine { progress ->
                    appendLog(progress)
                    lifecycleScope.launch(Dispatchers.Main) { statusText.text = progress }
                }
                
                val result = engine.runPipeline(inputFile, outputFile, config)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        optimizedFile = outputFile
                        statusText.text = "Success!"
                    } else {
                        statusText.text = "Failed"
                    }
                    setProcessingState(isProcessing = false, isSuccess = result.isSuccess)
                }
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Status: Error"
                    setProcessingState(isProcessing = false)
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