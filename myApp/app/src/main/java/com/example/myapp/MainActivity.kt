package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var captureJob: Job? = null
    private var imageCapture: ImageCapture? = null

    private val captureDuration = 60
    private val captureInterval = 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MaterialTheme {
                CameraApp(
                    requestPermissions = { requestPermissions() },
                    startCapture = { startCapturing() },
                    stopCapture = { stopCapturing() }
                )
            }
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
            101
        )
    }

    private fun startCapturing() {
        if (captureJob != null) {
            Toast.makeText(this, "Capture is already running.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions()
            return
        }

        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CapturedImages")
        if (!outputDir.exists()) outputDir.mkdirs()

        // File to store the image names and locations
        val metadataFile = File(outputDir, "ImageMetadata.txt")
        if (!metadataFile.exists()) metadataFile.createNewFile()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to bind camera use case: ${e.message}", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            var elapsedTime = 0

            captureJob = CoroutineScope(Dispatchers.IO).launch {
                while (elapsedTime < captureDuration && isActive) {
                    // Fetch location before taking a picture
                    val locationString = fetchCurrentLocation()
                    if (locationString == null) {
                        Log.e("MainActivity", "Failed to fetch location, skipping image capture.")
                        continue
                    }

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val photoFile = File(outputDir, "IMG_$timestamp.jpg")

                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    imageCapture?.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(this@MainActivity),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                // Append image name and location to the metadata text file
                                val metadataLine = "Image: ${photoFile.name}, Location: $locationString\n"
                                metadataFile.appendText(metadataLine)

                                Log.d("MainActivity", "Image captured: ${photoFile.name}, Location: $locationString")
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e("MainActivity", "Image capture failed: ${exception.message}", exception)
                            }
                        }
                    )

                    delay(captureInterval)
                    elapsedTime += (captureInterval / 1000).toInt()
                }

                Log.d("MainActivity", "Capture completed.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCapturing() {
        captureJob?.cancel()
        captureJob = null
        Toast.makeText(this, "Capture stopped.", Toast.LENGTH_SHORT).show()
    }

    // Make fetchCurrentLocation a suspending function
    private suspend fun fetchCurrentLocation(): String? {
        return suspendCancellableCoroutine { continuation ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission required.", Toast.LENGTH_SHORT).show()
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val locationString = location?.let {
                    "Lat: ${it.latitude}, Long: ${it.longitude}"
                }
                continuation.resume(locationString)
            }.addOnFailureListener {
                Log.e("MainActivity", "Failed to fetch location: ${it.message}")
                continuation.resume(null)
            }
        }
    }

    @Composable
    fun CameraApp(
        requestPermissions: () -> Unit,
        startCapture: () -> Unit,
        stopCapture: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = startCapture, modifier = Modifier.fillMaxWidth()) {
                Text("Start Capture")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = stopCapture, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Capture")
            }
        }
    }
}
