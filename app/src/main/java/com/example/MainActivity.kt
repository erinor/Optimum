package com.example

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// --- GLOBAL MODELS ---
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
    private lateinit var pickApkButton: Button
    private lateinit var saveButton: Button

    // Configuration Inputs
    private lateinit var swInput: EditText
    private lateinit var dimInput: EditText
    private lateinit var archInput: EditText
    private lateinit var langInput: EditText
    private lateinit var dpiInput: EditText
    private lateinit var fastRadio: RadioButton

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
            setContentView(createLayout())
            pickApkButton.setOnClickListener { apkPicker.launch("*/*") }
            saveButton.setOnClickListener { saveLauncher.launch("optimized_app.apk") }
        } catch (e: Throwable) {
            Log.e("OptimumCrash", "Fatal error during onCreate", e)
            val msg = "Startup Crash: ${e.javaClass.simpleName} - ${e.message}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            
            // Fallback error UI
            setContentView(TextView(this).apply {
                text = "$msg\n\n(Check Logcat for the full stack trace)"
                setTextColor(Color.RED)
                textSize = 16f
                setPadding(50, 50, 50, 50)
                setBackgroundColor(Color.BLACK)
            })
        }
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logText.append("\n> $msg")
            (logText.parent as? ScrollView)?.post { 
                (logText.parent as ScrollView).fullScroll(View.FOCUS_DOWN) 
            }
        }
    }

    private fun createLabeledInput(container: LinearLayout, labelText: String, defValue: String): EditText {
        val input = EditText(this).apply {
            setText(defValue)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
            addView(TextView(this@MainActivity).apply {
                text = labelText
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(Color.LTGRAY)
            })
            addView(input)
        })
        return input
    }

    private fun createLayout(): View {
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

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#121212"))

            val configPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            
            // --- Engine Mode Selector ---
            val modeGroup = RadioGroup(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                
                fastRadio = RadioButton(this@MainActivity).apply { 
                    id = View.generateViewId() // CRITICAL FIX: RadioGroup needs unique IDs to work!
                    text = "Fast"
                    setTextColor(Color.WHITE)
                    isChecked = true 
                }
                
                val stableRadio = RadioButton(this@MainActivity).apply { 
                    id = View.generateViewId() // CRITICAL FIX
                    text = "Stable"
                    setTextColor(Color.WHITE) 
                }
                
                addView(fastRadio)
                addView(stableRadio)
            }
            
            configPanel.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
                addView(TextView(this@MainActivity).apply {
                    text = "Engine Mode:"
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setTextColor(Color.LTGRAY)
                })
                addView(modeGroup)
            })

            swInput = createLabeledInput(configPanel, "Max SW (dp):", config.smallestScreenWidthDp.toString())
            dimInput = createLabeledInput(configPanel, "Max Height/Width:", maxOf(config.screenWidthDp, config.screenHeightDp).toString())
            dpiInput = createLabeledInput(configPanel, "Target DPI:", detectedDpi)
            archInput = createLabeledInput(configPanel, "Target CPU Arch:", detectedArch)
            langInput = createLabeledInput(configPanel, "Keep Langs (comma sep):", "")
            
            addView(configPanel)

            pickApkButton = Button(this@MainActivity).apply { text = "Pick APK to Optimize" }
            addView(pickApkButton)

            statusText = TextView(this@MainActivity).apply { 
                text = "Status: Idle" 
                textSize = 16f
                setPadding(0, 20, 0, 20)
                setTextColor(Color.WHITE)
            }
            addView(statusText)

            addView(ScrollView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
                setBackgroundColor(Color.BLACK)
                logText = TextView(this@MainActivity).apply {
                    setTextColor(Color.GREEN)
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setPadding(10, 10, 10, 10)
                }
                addView(logText)
            })

            saveButton = Button(this@MainActivity).apply { text = "Save Optimized APK"; isEnabled = false }
            addView(saveButton)
        }
    }

    private fun handleApkUri(uri: Uri) {
        val optConfig = OptimizationConfig(
            maxSwDp = swInput.text.toString().toIntOrNull() ?: 400,
            maxDimensionDp = dimInput.text.toString().toIntOrNull() ?: 800,
            keepLangs = langInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            targetArch = archInput.text.toString().trim(),
            targetDpi = dpiInput.text.toString().trim(),
            mode = if (fastRadio.isChecked) OptimizationMode.FAST else OptimizationMode.STABLE
        )

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
                    statusText.text = "Error"
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
                    Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("Save failed: ${e.message}")
            }
        }
    }
}