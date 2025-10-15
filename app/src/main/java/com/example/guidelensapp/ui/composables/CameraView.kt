package com.example.guidelensapp.ui.composables

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap

@Composable
fun CameraView(onFrame: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy, onFrame)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera binding failed", e)
                }
            }, context.mainExecutor)
            previewView
        }
    )
}

private fun processImageProxy(imageProxy: ImageProxy, onFrame: (Bitmap) -> Unit) {
    try {
        // Get rotation degrees from image metadata
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        // Convert to bitmap
        val bitmap = imageProxyToBitmap(imageProxy)
        // Rotate bitmap to match preview orientation
        val rotatedBitmap = if (rotationDegrees != 0) {
            rotateBitmap(bitmap, rotationDegrees.toFloat())
        } else {
            bitmap
        }

        onFrame(rotatedBitmap)
    } catch (e: Exception) {
        Log.e(TAG, "Error processing frame", e)
    } finally {
        imageProxy.close()
    }
}

private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * imageProxy.width
    val bitmap = createBitmap(imageProxy.width + rowPadding / pixelStride, imageProxy.height)
    bitmap.copyPixelsFromBuffer(buffer)
    return if (rowPadding > 0) {
        Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height).also {
            bitmap.recycle()
        }
    } else {
        bitmap
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply {
        postRotate(degrees)
    }

    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
    ).also {
        // Recycle original bitmap after rotation
        if (it != bitmap) {
            bitmap.recycle()
        }
    }
}

private const val TAG = "CameraView"
