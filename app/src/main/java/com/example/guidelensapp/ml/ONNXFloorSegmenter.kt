package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.*
import com.example.guidelensapp.utils.MemoryManager
import com.example.guidelensapp.utils.ThreadManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

class ONNXFloorSegmenter(
    private val context: Context,
    private val useQuantized: Boolean = true
) {
    private val TAG = "ONNXFloorSegmenter"
    private val MODEL_NAME =
        if (useQuantized) "floor_segmentation_int8.onnx" else "floor_segmentation.onnx"

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private val isInitialized = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    // PPLiteSeg model input dimensions - FIXED TO MATCH WORKING APP
    private val inputWidth = 256
    private val inputHeight = 256
    private val inputChannels = 3

    // Performance tracking
    private var frameCount = 0
    private var totalInferenceTime = 0L
    private val memoryManager = MemoryManager.getInstance()

    // Quality settings
    private var currentQuality = 1.0f
    private val qualityLock = Any()

    init {
        try {
            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            close()
        }
    }

    private fun loadModel() {
        try {
            Log.d(TAG, "Loading model: $MODEL_NAME")

            // Initialize environment
            env = OrtEnvironment.getEnvironment()

            // Load model
            val modelBytes = context.assets.open(MODEL_NAME).use {
                it.readBytes()
            }

            Log.d(TAG, "✅ Model loaded: ${modelBytes.size / (1024 * 1024)} MB")

            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)  // Match working app
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }

            session = env?.createSession(modelBytes, sessionOptions)
            isInitialized.set(true)

            Log.i(TAG, "✅ ONNX Model initialized successfully")
            Log.i(TAG, "📊 Model type: ${if (useQuantized) "INT8 Quantized" else "FP32"}")
            Log.i(TAG, "📊 Input names: ${session?.inputNames}")
            Log.i(TAG, "📊 Output names: ${session?.outputNames}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            isInitialized.set(false)
            throw e
        }
    }

    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        if (isClosed.get() || !isInitialized.get()) {
            Log.w(TAG, "Segmenter not ready")
            return null
        }

        val currentSession = session ?: return null
        val currentEnv = env ?: return null
        val startTotal = System.currentTimeMillis()

        var tensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

        try {
            // Preprocess - FIXED TO MATCH WORKING APP
            val startPrep = System.currentTimeMillis()
            val resizedBitmap = Bitmap.createScaledBitmap(
                bitmap,
                inputWidth,
                inputHeight,
                true
            )

            val inputTensor = preprocessBitmap(resizedBitmap)
            val prepTime = System.currentTimeMillis() - startPrep

            if (isClosed.get()) return null

            // Create input tensor
            val inputName = currentSession.inputNames?.iterator()?.next() ?: return null
            val shape = longArrayOf(1, inputChannels.toLong(), inputHeight.toLong(), inputWidth.toLong())
            tensor = OnnxTensor.createTensor(currentEnv, FloatBuffer.wrap(inputTensor), shape)

            // Run inference
            val startInfer = System.currentTimeMillis()
            outputs = currentSession.run(mapOf(inputName to tensor))
            val inferTime = System.currentTimeMillis() - startInfer

            if (isClosed.get()) return null

            // Post-process - FIXED TO MATCH WORKING APP
            val startPost = System.currentTimeMillis()
            val output = outputs?.get(0) as? OnnxTensor ?: return null
            val outputArray = output.floatBuffer.array()
            val maskShape = output.info.shape
            val maskHeight = maskShape[2].toInt()
            val maskWidth = maskShape[3].toInt()

            // Apply sigmoid
            val processedMask = FloatArray(outputArray.size)
            for (i in outputArray.indices) {
                processedMask[i] = sigmoid(outputArray[i])
            }

            // Create bitmap from mask
            val resultBitmap = createMaskBitmap(
                processedMask,
                maskWidth,
                maskHeight,
                bitmap.width,
                bitmap.height
            )

            val postTime = System.currentTimeMillis() - startPost
            val totalTime = System.currentTimeMillis() - startTotal

            // Performance logging
            frameCount++
            totalInferenceTime += inferTime

            if (frameCount % 30 == 0) {
                val avgInference = totalInferenceTime / frameCount
                val fps = 1000f / totalTime
                Log.i(TAG, "📊 Avg inference: ${avgInference}ms | FPS: %.1f".format(fps))
            }

            return resultBitmap

        } catch (e: Exception) {
            if (!isClosed.get()) {
                Log.e(TAG, "Inference error", e)
            }
            return null
        } finally {
            try {
                tensor?.close()
                outputs?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    // FIXED PREPROCESSING TO MATCH WORKING APP
    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val floatArray = FloatArray(inputChannels * inputHeight * inputWidth)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Extract RGB and normalize to [0, 1]
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // CHW format (channels first)
            floatArray[i] = r
            floatArray[pixels.size + i] = g
            floatArray[2 * pixels.size + i] = b
        }

        return floatArray
    }

    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + exp(-x)))
    }

    private fun createMaskBitmap(
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        // Create bitmap at mask resolution first
        val maskBitmap = memoryManager.getBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(maskWidth * maskHeight)
        for (i in mask.indices) {
            val value = mask[i]
            // Green overlay for floor with confidence-based alpha
            if (value > 0.5f) {
                val alpha = ((value * 0.4f).coerceIn(0.2f, 0.5f) * 255).toInt()
                pixels[i] = Color.argb(alpha, 0, 255, 0)
            } else {
                pixels[i] = Color.TRANSPARENT
            }
        }

        maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        // Scale to target size
        val scaledBitmap = Bitmap.createScaledBitmap(maskBitmap, targetWidth, targetHeight, true)

        // Recycle intermediate bitmap
        if (maskBitmap != scaledBitmap) {
            memoryManager.recycleBitmap(maskBitmap)
        }

        return scaledBitmap
    }

    fun createMockFloorMask(width: Int, height: Int): Bitmap {
        val bitmap = memoryManager.getBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint().apply {
            color = Color.argb(100, 0, 255, 0)
            style = Paint.Style.FILL
        }

        // Draw bottom 60% as floor
        canvas.drawRect(0f, height * 0.4f, width.toFloat(), height.toFloat(), paint)

        return bitmap
    }

    fun isReady(): Boolean = isInitialized.get() && !isClosed.get()

    fun setQuality(quality: Float) {
        synchronized(qualityLock) {
            currentQuality = quality.coerceIn(0.1f, 1.0f)
            Log.d(TAG, "Quality set to ${(currentQuality * 100).toInt()}%")
        }
    }

    fun forceCleanup() {
        Log.d(TAG, "Force cleanup requested")
        memoryManager.forceGarbageCollection()
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.d(TAG, "Closing ONNX session...")
            try {
                session?.close()
                session = null
                isInitialized.set(false)
                Log.d(TAG, "ONNX session closed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ONNX session", e)
            }
        }
    }
}
