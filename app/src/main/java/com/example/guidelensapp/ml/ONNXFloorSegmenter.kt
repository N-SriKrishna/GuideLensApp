package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class ONNXFloorSegmenter(
    private val context: Context,
    private val useQuantized: Boolean = true
) {
    private val TAG = "ONNXFloorSegmenter"

    private val MODEL_NAME = if (useQuantized)
        "floor_segmentation_int8.onnx"
    else
        "floor_segmentation.onnx"

    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private val isInitialized = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val isFirstInference = AtomicBoolean(true) // Track first inference

    // PPLiteSeg model input dimensions
    private val inputWidth = 256
    private val inputHeight = 256
    private val inputChannels = 3

    // Performance tracking
    private var frameCount = 0
    private var totalInferenceTime = 0L

    // Lock for thread safety
    private val lock = Any()

    // Warm-up job
    private var warmupJob: Job? = null

    init {
        try {
            loadModel()
            // Start warm-up in background
            startWarmup()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            close()
        }
    }

    private fun loadModel() {
        try {
            Log.d(TAG, "Loading model: $MODEL_NAME")

            // Initialize environment
            environment = OrtEnvironment.getEnvironment()

            // Try to open the model
            val modelBytes = try {
                context.assets.open(MODEL_NAME).use {
                    it.readBytes()
                }
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "❌ Model file not found: $MODEL_NAME")
                throw RuntimeException("Model file '$MODEL_NAME' not found in assets folder")
            }

            Log.d(TAG, "✅ Model loaded: ${modelBytes.size / (1024 * 1024)} MB")

            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2) // Reduced from 4 to 2 for better stability
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }

            session = environment?.createSession(modelBytes, sessionOptions)
            isInitialized.set(true)

            Log.i(TAG, "✅ ONNX Floor Segmentation Model initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            isInitialized.set(false)
            throw e
        }
    }

    /**
     * Warm up the model with a dummy input to avoid first-time inference delay
     */
    private fun startWarmup() {
        warmupJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                delay(500) // Wait a bit for UI to settle
                Log.d(TAG, "Starting model warm-up...")

                // Create a dummy bitmap for warm-up
                val dummyBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                dummyBitmap.eraseColor(Color.GRAY)

                // Run a dummy inference to warm up the model
                val warmupStart = System.currentTimeMillis()
                val result = segmentFloorInternal(dummyBitmap, isWarmup = true)
                val warmupTime = System.currentTimeMillis() - warmupStart

                if (result != null) {
                    Log.i(TAG, "✅ Model warm-up completed in ${warmupTime}ms")
                    isFirstInference.set(false) // Mark warm-up as done
                } else {
                    Log.w(TAG, "⚠️ Model warm-up returned null")
                }

                dummyBitmap.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Model warm-up failed", e)
            }
        }
    }

    /**
     * Main floor segmentation function - replaces EdgeSAM segmentFloor
     */
    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        return segmentFloorInternal(bitmap, isWarmup = false)
    }

    private fun segmentFloorInternal(bitmap: Bitmap, isWarmup: Boolean = false): Bitmap? {
        if (bitmap == null || isClosed.get() || !isInitialized.get()) {
            return null
        }

        val currentSession = session ?: return null
        val currentEnvironment = environment ?: return null

        // If this is the first real inference and warmup hasn't completed, wait a bit
        if (!isWarmup && isFirstInference.get()) {
            Log.d(TAG, "Waiting for warm-up to complete...")
            // Give warmup a chance to complete
            Thread.sleep(100)
        }

        synchronized(lock) {
            val startTotal = System.currentTimeMillis()
            var tensor: OnnxTensor? = null
            var outputs: OrtSession.Result? = null

            try {
                // Preprocess - make it faster for real inference
                val resizedBitmap = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
                    bitmap // Already correct size
                } else {
                    Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
                }

                val inputTensor = preprocessBitmap(resizedBitmap)

                // Check if closed during preprocessing
                if (isClosed.get()) return null

                // Create input tensor
                val inputName = currentSession.inputNames?.iterator()?.next() ?: return null
                val shape = longArrayOf(1, inputChannels.toLong(), inputHeight.toLong(), inputWidth.toLong())
                tensor = OnnxTensor.createTensor(currentEnvironment, FloatBuffer.wrap(inputTensor), shape)

                // Run inference
                val startInfer = System.currentTimeMillis()
                outputs = currentSession.run(mapOf(inputName to tensor))
                val inferTime = System.currentTimeMillis() - startInfer

                // Check if closed during inference
                if (isClosed.get()) return null

                // Post-process
                val output = outputs?.get(0) as? OnnxTensor ?: return null
                val outputArray = output.floatBuffer.array()
                val maskShape = output.info.shape
                val maskHeight = maskShape[2].toInt()
                val maskWidth = maskShape[3].toInt()

                // Apply sigmoid and create bitmap only if not warmup
                val maskBitmap = if (isWarmup) {
                    // For warmup, create a simple dummy bitmap
                    createDummyMask(bitmap.width, bitmap.height)
                } else {
                    // Apply sigmoid
                    val processedMask = FloatArray(outputArray.size)
                    for (i in outputArray.indices) {
                        processedMask[i] = sigmoid(outputArray[i])
                    }

                    // Convert to bitmap
                    createFloorMaskBitmap(
                        processedMask,
                        maskWidth,
                        maskHeight,
                        bitmap.width,
                        bitmap.height
                    )
                }

                val totalTime = System.currentTimeMillis() - startTotal

                if (!isWarmup) {
                    // Performance logging only for real inference
                    frameCount++
                    totalInferenceTime += inferTime

                    Log.d(TAG, "⏱️ Floor Segmentation: Total=${totalTime}ms (Infer=${inferTime}ms)")

                    if (frameCount % 30 == 0) {
                        val avgInference = totalInferenceTime / frameCount
                        Log.i(TAG, "📊 Avg inference: ${avgInference}ms | Frame count: $frameCount")
                    }

                    // Mark first real inference as complete
                    isFirstInference.set(false)
                }

                return maskBitmap

            } catch (e: Exception) {
                if (!isClosed.get() && !isWarmup) {
                    Log.e(TAG, "Floor segmentation error", e)
                }
                return null
            } finally {
                // Clean up resources
                try {
                    tensor?.close()
                    outputs?.close()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
        }
    }

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

            // CHW format
            floatArray[i] = r
            floatArray[pixels.size + i] = g
            floatArray[2 * pixels.size + i] = b
        }

        return floatArray
    }

    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + kotlin.math.exp(-x)))
    }

    /**
     * Create a simple dummy mask for warmup
     */
    private fun createDummyMask(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Convert the segmentation mask to a colored bitmap for visualization
     */
    private fun createFloorMaskBitmap(
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        // Create mask bitmap
        val maskPixels = IntArray(maskWidth * maskHeight)

        for (i in mask.indices) {
            val confidence = mask[i]
            maskPixels[i] = if (confidence > 0.5f) {
                // Semi-transparent green for floor areas
                val alpha = (confidence * 128).toInt().coerceIn(32, 128)
                Color.argb(alpha, 0, 255, 0)
            } else {
                Color.TRANSPARENT
            }
        }

        val maskBitmap = Bitmap.createBitmap(
            maskPixels,
            maskWidth,
            maskHeight,
            Bitmap.Config.ARGB_8888
        )

        // Scale to target size
        return Bitmap.createScaledBitmap(maskBitmap, targetWidth, targetHeight, true)
    }

    /**
     * Create a mock floor mask for emulator testing
     */
    fun createMockFloorMask(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            // Fill bottom 60% of image with semi-transparent green to simulate floor
            val floorHeight = (height * 0.6f).toInt()
            val paint = Paint().apply {
                color = Color.argb(128, 0, 255, 0)
            }
            canvas.drawRect(
                0f,
                (height - floorHeight).toFloat(),
                width.toFloat(),
                height.toFloat(),
                paint
            )
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.d(TAG, "Closing ONNX floor segmentation session...")

            // Cancel warmup if running
            warmupJob?.cancel()

            synchronized(lock) {
                try {
                    session?.close()
                    session = null
                    // Don't close environment - it's a singleton
                    isInitialized.set(false)
                    Log.d(TAG, "ONNX floor segmentation session closed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing ONNX session", e)
                }
            }
        }
    }
}