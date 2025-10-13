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
    private val isFirstInference = AtomicBoolean(true)

    // Model input dims
    private val inputWidth = 256
    private val inputHeight = 256
    private val inputChannels = 3

    // Thread & memory managers
    private val threadMgr = ThreadManager.getInstance()
    private val memMgr = MemoryManager.getInstance()

    // Pre-allocated buffers
    @Volatile private var inputArray: FloatArray? = null
    @Volatile private var pixelArray: IntArray? = null

    // Buffer sizes
    private var inputSize = inputChannels * inputHeight * inputWidth

    // Performance tracking
    private val frameCount = AtomicLong(0)
    private val totalTimeAcc = AtomicLong(0L)
    private val totalPrepAcc = AtomicLong(0L)
    private val totalInferAcc = AtomicLong(0L)
    private val totalPostAcc = AtomicLong(0L)

    // Concurrency limit
    private val inFlight = AtomicInteger(0)
    private val MAX_INFLIGHT = 2

    // Quality control
    @Volatile private var quality = 1.0f

    // Health monitoring
    private val recentFrames = mutableListOf<Long>()
    private val MAX_HIST = 10

    private var warmupJob: Job? = null
    private val lock = Any()

    init {
        threadMgr.backgroundScope.launch {
            try {
                loadModel()
                allocateBuffers()
                warmUp()
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                close()
            }
        }
    }

    private suspend fun loadModel() = withContext(threadMgr.ioDispatcher) {
        env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_NAME).use { it.readBytes() }

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // pick a thread count based on cores
            val cores = Runtime.getRuntime().availableProcessors()
            setIntraOpNumThreads(when {
                cores >= 8 -> 4
                cores >= 4 -> 2
                else -> 1
            })
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }

        session = env!!.createSession(modelBytes, opts)
        isInitialized.set(true)
        Log.i(TAG, "Model loaded: $MODEL_NAME")
    }

    private suspend fun allocateBuffers() = withContext(threadMgr.ioDispatcher) {
        inputArray = FloatArray(inputSize)
        pixelArray = IntArray(inputWidth * inputHeight)
    }

    private fun warmUp() {
        warmupJob = threadMgr.backgroundScope.launch {
            delay(500)
            val bmp = memMgr.getBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.GRAY)
            segment(bmp, true)
            memMgr.recycleBitmap(bmp)
            isFirstInference.set(false)
            Log.i(TAG, "Warm-up done")
        }
    }

    /**
     * Public API: returns a mask bitmap or null on error
     */
    fun segmentFloor(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || isClosed.get() || !isInitialized.get()) return null
        if (inFlight.incrementAndGet() > MAX_INFLIGHT) {
            inFlight.decrementAndGet()
            return null
        }
        return try {
            runBlocking {
                threadMgr.executeMLInference {
                    segment(bitmap, false)
                }.await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "segmentFloor() error", e)
            null
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun segment(bitmap: Bitmap, isWarmup: Boolean): Bitmap? =
        withContext(threadMgr.mlDispatcher) {
            synchronized(lock) {
                val t0 = System.currentTimeMillis()

                // Step 1: Resize for quality
                val w = (inputWidth * quality).toInt().coerceAtLeast(128)
                val h = (inputHeight * quality).toInt().coerceAtLeast(128)
                val resized = if (bitmap.width == w && bitmap.height == h) bitmap
                else {
                    val b = memMgr.getBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(b).drawBitmap(bitmap, null, android.graphics.Rect(0,0,w,h), Paint())
                    b
                }

                // Pre-process CHW
                val pix = pixelArray!!
                resized.getPixels(pix, 0, w, 0, 0, w, h)
                val arr = inputArray!!
                val norm = 1f/255f
                for (i in pix.indices) {
                    val p = pix[i]
                    arr[i] = ((p shr 16) and 0xFF)*norm
                    arr[i+pix.size] = ((p shr 8) and 0xFF)*norm
                    arr[i+2*pix.size] = (p and 0xFF)*norm
                }
                if (resized !== bitmap) memMgr.recycleBitmap(resized)
                val t1 = System.currentTimeMillis()

                // Step 2: Inference
                val inputName = session!!.inputNames.iterator().next()
                val shape = longArrayOf(1, inputChannels.toLong(), h.toLong(), w.toLong())
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), shape)
                val out = session!!.run(mapOf(inputName to tensor))
                val t2 = System.currentTimeMillis()

                // Step 3: Post-process
                @Suppress("UNCHECKED_CAST")
                val oto = out[0] as OnnxTensor
                val logits = oto.floatBuffer.array()
                val maskW = (oto.info as TensorInfo).shape[3].toInt()
                val maskH = (oto.info as TensorInfo).shape[2].toInt()
                val pixels = IntArray(maskW*maskH)
                for (i in logits.indices) {
                    val prob = 1f/(1f+exp(-logits[i]))
                    pixels[i] = if (prob>0.5f) Color.argb((prob*128).toInt(),0,255,0) else Color.TRANSPARENT
                }
                val maskBmp = memMgr.getBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
                maskBmp.setPixels(pixels,0,maskW,0,0,maskW,maskH)
                val finalBmp = if (maskW!=bitmap.width||maskH!=bitmap.height) {
                    val fb = memMgr.getBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                    Canvas(fb).drawBitmap(maskBmp,null,
                        android.graphics.Rect(0,0,bitmap.width,bitmap.height),Paint())
                    memMgr.recycleBitmap(maskBmp)
                    fb
                } else maskBmp
                val t3 = System.currentTimeMillis()

                // Logging & stats
                val prep = t1-t0; val infer = t2-t1; val post = t3-t2; val tot = t3-t0
                if (!isWarmup) {
                    frameCount.incrementAndGet()
                    totalPrepAcc.addAndGet(prep)
                    totalInferAcc.addAndGet(infer)
                    totalPostAcc.addAndGet(post)
                    totalTimeAcc.addAndGet(tot)
                    updatePerformanceTracking(tot)
                }

                // Clean up
                tensor.close()
                out.close()

                finalBmp
            }
        }

    /**
     * Create a mock floor mask for emulator testing or fallback
     */
    fun createMockFloorMask(width: Int, height: Int): Bitmap {
        val bitmap = memMgr.getBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill bottom 60% with semi-transparent green
        val floorHeight = (height * 0.6f).toInt()
        val paint = Paint().apply {
            color = Color.argb(128, 0, 255, 0)
            isAntiAlias = false
        }
        canvas.drawRect(
            0f,
            (height - floorHeight).toFloat(),
            width.toFloat(),
            height.toFloat(),
            paint
        )

        return bitmap
    }

    /**
     * Check if ready for inference
     */
    fun isReady(): Boolean = isInitialized.get() && !isClosed.get() && !isFirstInference.get()

    /**
     * Check if warmup complete
     */
    fun isWarmedUp(): Boolean = !isFirstInference.get()

    /**
     * Set quality (0.25 to 1.0)
     */
    fun setQuality(q: Float) {
        quality = q.coerceIn(0.25f, 1.0f)
        Log.i(TAG, "Quality set to ${(quality * 100).toInt()}%")
    }

    /**
     * Get current quality
     */
    fun getQuality(): Float = quality

    /**
     * Force cleanup for memory pressure
     */
    /**
     * Force cleanup for memory pressure
     */
    fun forceCleanup() {
        if (!isClosed.get()) {
            synchronized(recentFrames) {
                recentFrames.clear()
            }
            memMgr.performGcIfNeeded(context)
            System.gc()
            Log.d(TAG, "Force cleanup completed")
        }
    }

    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): PerformanceStats {
        val frames = frameCount.get()
        return if (frames > 0) {
            PerformanceStats(
                totalFrames = frames,
                avgInferenceTime = totalInferAcc.get() / frames,
                avgPreprocessTime = totalPrepAcc.get() / frames,
                avgPostprocessTime = totalPostAcc.get() / frames,
                isWarmupComplete = !isFirstInference.get(),
                currentQuality = quality,
                concurrentRequests = inFlight.get()
            )
        } else {
            PerformanceStats()
        }
    }

    /**
     * Get health status
     */
    fun getHealth(): SegmenterHealth {
        val stats = getPerformanceStats()
        val memInfo = memMgr.getMemoryInfo(context)

        // Simple health check based on response time and memory
        val isHealthy = stats.avgTotalTime < 1000 && // Less than 1s per frame
                memInfo.getUsagePercentage() < 85f && // Less than 85% memory
                isInitialized.get() && !isClosed.get()

        return SegmenterHealth(
            isHealthy = isHealthy,
            errorRate = 0f, // Simplified - not tracking errors in this version
            avgResponseTime = stats.avgTotalTime,
            memoryPressure = memInfo.getUsagePercentage(),
            lastError = null,
            quality = quality,
            isWarmupComplete = stats.isWarmupComplete
        )
    }

    /**
     * Update performance tracking
     */
    private fun updatePerformanceTracking(frameTime: Long) {
        synchronized(recentFrames) {
            recentFrames.add(frameTime)
            if (recentFrames.size > MAX_HIST) {
                recentFrames.removeAt(0)
            }

            if (recentFrames.size >= 5) {
                val avg = recentFrames.average()

                // Dynamic quality adjustment
                when {
                    avg > 500 && quality > 0.5f -> {
                        quality = 0.5f
                        Log.i(TAG, "⚡ Reducing quality to 50% (avg: ${avg.toInt()}ms)")
                    }
                    avg > 300 && quality > 0.75f -> {
                        quality = 0.75f
                        Log.i(TAG, "⚡ Reducing quality to 75% (avg: ${avg.toInt()}ms)")
                    }
                    avg < 150 && quality < 1.0f -> {
                        quality = 1.0f
                        Log.i(TAG, "⚡ Restoring full quality (avg: ${avg.toInt()}ms)")
                    }
                }
            }
        }
    }

    /** Release all resources */
    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        try { warmupJob?.cancel() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        env = null
        Log.i(TAG, "ONNX segmenter closed")
        System.gc()
    }

    /**
     * Performance statistics data class
     */
    data class PerformanceStats(
        val totalFrames: Long = 0,
        val avgInferenceTime: Long = 0,
        val avgPreprocessTime: Long = 0,
        val avgPostprocessTime: Long = 0,
        val isWarmupComplete: Boolean = false,
        val currentQuality: Float = 1.0f,
        val concurrentRequests: Int = 0
    ) {
        val avgTotalTime: Long get() = avgPreprocessTime + avgInferenceTime + avgPostprocessTime
        val avgFPS: Float get() = if (avgTotalTime > 0) 1000f / avgTotalTime else 0f

        override fun toString(): String {
            return "PerformanceStats(frames=$totalFrames, " +
                    "inference=${avgInferenceTime}ms, " +
                    "preprocess=${avgPreprocessTime}ms, " +
                    "postprocess=${avgPostprocessTime}ms, " +
                    "total=${avgTotalTime}ms, " +
                    "fps=${String.format("%.1f", avgFPS)}, " +
                    "quality=${(currentQuality * 100).toInt()}%, " +
                    "concurrent=$concurrentRequests, " +
                    "warmedUp=$isWarmupComplete)"
        }
    }

    /**
     * Health monitoring data class
     */
    data class SegmenterHealth(
        val isHealthy: Boolean = true,
        val errorRate: Float = 0f,
        val avgResponseTime: Long = 0,
        val memoryPressure: Float = 0f,
        val lastError: String? = null,
        val quality: Float = 1.0f,
        val isWarmupComplete: Boolean = false
    ) {
        override fun toString(): String {
            return "SegmenterHealth(healthy=$isHealthy, " +
                    "errors=${String.format("%.1f", errorRate)}%, " +
                    "avgTime=${avgResponseTime}ms, " +
                    "memory=${String.format("%.1f", memoryPressure)}%, " +
                    "quality=${(quality * 100).toInt()}%, " +
                    "warmedUp=$isWarmupComplete" +
                    (lastError?.let { ", lastError='$it'" } ?: "") + ")"
        }
    }
}