package com.example.myapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var captureJob: Job? = null
    private var benchmarks by mutableStateOf("")
    private val capturedImages = mutableListOf<File>()
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions.all { it.value }
            if (!granted) {
                Toast.makeText(this, "Permissions denied. Some features may not work!", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            MaterialTheme {
                CameraApp(
                    startCapture = { checkPermissionsAndStartCapture() },
                    stopCapture = { stopCapturing() },
                    runInference = { runInference() },
                    benchmarks = benchmarks
                )
            }
        }
    }

    private fun checkPermissionsAndStartCapture() {
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val deniedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(deniedPermissions.toTypedArray())
        } else {
            startCapturing()
        }
    }

    private fun startCapturing() {
        if (captureJob != null) return
        capturedImages.clear()

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this@MainActivity)
            val cameraProvider = cameraProviderFuture.get()

            // Ensure camera operations are executed on the main thread
            withContext(Dispatchers.Main) {
                val preview = Preview.Builder().build()
                val imageCapture = ImageCapture.Builder().build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll() // Must be on the main thread
                cameraProvider.bindToLifecycle(this@MainActivity, cameraSelector, preview, imageCapture)

                while (isActive) {
                    val file = File(cacheDir, "image_${System.currentTimeMillis()}.jpg")
                    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                    imageCapture.takePicture(outputFileOptions, executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                capturedImages.add(file)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                            }
                        }
                    )

                    delay(2000)  // Capture every 2 seconds
                }
            }
        }
    }


    private fun stopCapturing() {
        captureJob?.cancel()
        captureJob = null
        Toast.makeText(this, "Capture stopped. ${capturedImages.size} images captured.", Toast.LENGTH_SHORT).show()
    }

    private fun runInference() {
        // Stop capturing before inference
        stopCapturing()

        CoroutineScope(Dispatchers.Main).launch {
            val results = withContext(Dispatchers.IO) { runInferenceWithModel() }

            benchmarks = results.joinToString("\n") { "Model: ${it.modelName}, Time: ${it.processingTime}, FPS: ${it.fps}" }
        }
    }

    private suspend fun runInferenceWithModel(): List<BenchmarkResult> {
        val results = mutableListOf<BenchmarkResult>()
        val modelPaths = listOf(
            "unetplusplus_efficientnet_b0_057401.onnx",
            "unetplusplus_efficientnet_b4_058698.onnx",
            "unetplusplus_timm-mobilenetv3_small_075_056002.onnx"
        )

        if (capturedImages.isEmpty()) {
            return listOf(BenchmarkResult("No images", "N/A", "N/A"))
        }

        modelPaths.forEach { modelPath ->
            val modelFile = copyAssetToCache(modelPath)
            if (modelFile == null) {
                results.add(BenchmarkResult(modelPath, "Failed to load", "N/A"))
                return@forEach
            }

            var totalTime = 0L
            capturedImages.forEach { imageFile ->
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                val inputTensor = preprocessImage(bitmap)

                val startTime = System.nanoTime()
                runModelInference(modelFile, inputTensor)
                val endTime = System.nanoTime()

                totalTime += (endTime - startTime) / 1_000_000
            }

            val avgTime = totalTime / capturedImages.size
            val fps = if (avgTime > 0) 1000.0 / avgTime else 0.0

            results.add(BenchmarkResult(modelPath, "$avgTime ms", String.format("%.2f", fps)))
        }
        return results
    }

    private fun runModelInference(modelPath: String, inputTensor: FloatBuffer): FloatArray {
        return try {
            OrtEnvironment.getEnvironment().use { env ->
                OrtSession.SessionOptions().use { options ->
                    env.createSession(modelPath, options).use { session ->
                        val inputName = session.inputNames.iterator().next()
                        val input = OnnxTensor.createTensor(env, inputTensor)
                        val result = session.run(mapOf(inputName to input))

                        val output = result[0].value as FloatArray
                        input.close()
                        result.close()

                        output
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            FloatArray(1) { -1f }
        }
    }

    private fun preprocessImage(image: Bitmap): FloatBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(image, 224, 224, true)
        val floatValues = FloatArray(3 * 224 * 224)

        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                val index = (y * 224 + x) * 3
                floatValues[index] = r
                floatValues[index + 1] = g
                floatValues[index + 2] = b
            }
        }

        return FloatBuffer.wrap(floatValues)
    }

    private fun copyAssetToCache(assetName: String): String? {
        return try {
            val file = File(cacheDir, assetName)
            if (!file.exists()) {
                assets.open(assetName).use { inputStream ->
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun CameraApp(
    startCapture: () -> Unit,
    stopCapture: () -> Unit,
    runInference: () -> Unit,
    benchmarks: String
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Benchmark Results", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        BasicTextField(
            value = benchmarks,
            onValueChange = {},
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { startCapture() }) {
                Text("Start Capture")
            }
            Button(onClick = { stopCapture() }) {
                Text("Stop Capture")
            }
            Button(onClick = { runInference() }) {
                Text("Run Inference")
            }
        }
    }
}

data class BenchmarkResult(val modelName: String, val processingTime: String, val fps: String)
