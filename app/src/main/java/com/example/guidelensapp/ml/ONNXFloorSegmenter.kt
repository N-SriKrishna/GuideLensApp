package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.*
import android.content.ContentValues.TAG
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class ONNXFloorSegmenter(
    private val context: Context

) {
    private val TAG = "ONNXFloorSegmenter"
    private val MODEL_NAME = "floor_segmentation_int8.onnx"  // Make sure this file exists!

    private val MODEL_INPUT_WIDTH = 256
    private val MODEL_INPUT_HEIGHT = 256

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private val isInitialized = AtomicBoolean(false)
    private val isCrashed = AtomicBoolean(false)

    init {
        initializeAsync()
    }

    private fun initializeAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🔧 Initializing floor segmenter...")
                env = OrtEnvironment.getEnvironment()

                // Check if model exists
                val modelStream = try {
                    context.assets.open(MODEL_NAME)
                } catch (_: Exception) {
                    Log.e(TAG, "❌ Model file NOT FOUND: $MODEL_NAME")
                    Log.e(TAG, "Available assets: ${context.assets.list("")?.joinToString()}")
                    isCrashed.set(true)
                    return@launch
                }

                val modelBytes = modelStream.readBytes()
                modelStream.close()

                Log.d(TAG, "✓ Model loaded: ${modelBytes.size} bytes")

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }

                session = env?.createSession(modelBytes, sessionOptions)
                isInitialized.set(true)

                Log.d(TAG, "✅ Floor segmentation ready!")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Initialization failed", e)
                isCrashed.set(true)
                isInitialized.set(false)
            }
        }
    }

    fun isReady(): Boolean {
        val ready = isInitialized.get() && !isCrashed.get()
        Log.d(TAG, "isReady check: initialized=${isInitialized.get()}, crashed=${isCrashed.get()}, ready=$ready")
        return ready
    }

    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        Log.d(TAG, "🟢 segmentFloor called - ready: ${isReady()}")

        if (isCrashed.get()) {
            Log.w(TAG, "⚠️ Segmenter crashed, using mock")
            return createMockFloorMask(bitmap.width, bitmap.height)
        }

        if (!isInitialized.get()) {
            Log.w(TAG, "⏳ Not ready yet")
            return createMockFloorMask(bitmap.width, bitmap.height)  // Use mock for now
        }

        return try {
            runInference(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Segmentation failed", e)
            isCrashed.set(true)
            createMockFloorMask(bitmap.width, bitmap.height)
        }
    }

    private fun runInference(bitmap: Bitmap): Bitmap? {
        val inputTensor = preprocessImage(bitmap) ?: return null

        return try {
            Log.d(TAG, "Running ONNX inference...")
            val outputs = session?.run(mapOf("input" to inputTensor))

            if (outputs == null || outputs.size() == 0) {
                Log.e(TAG, "No output from model")
                inputTensor.close()
                return null
            }

            val result = postProcess(outputs[0].value, bitmap.width, bitmap.height)

            inputTensor.close()
            outputs.close()

            Log.d(TAG, "✓ Inference complete")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            inputTensor.close()
            null
        }
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor? {
        return try {
            val resized = bitmap.scale(MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)
            val floatArray = FloatArray(1 * 3 * MODEL_INPUT_HEIGHT * MODEL_INPUT_WIDTH)
            val pixels = IntArray(MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT)
            resized.getPixels(pixels, 0, MODEL_INPUT_WIDTH, 0, 0, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)

            var idx = 0
            for (c in 0..2) {
                for (y in 0 until MODEL_INPUT_HEIGHT) {
                    for (x in 0 until MODEL_INPUT_WIDTH) {
                        val pixel = pixels[y * MODEL_INPUT_WIDTH + x]
                        floatArray[idx++] = when(c) {
                            0 -> ((pixel shr 16) and 0xFF) * 0.003921569f
                            1 -> ((pixel shr 8) and 0xFF) * 0.003921569f
                            else -> (pixel and 0xFF) * 0.003921569f
                        }
                    }
                }
            }

            resized.recycle()

            val shape = longArrayOf(1, 3, MODEL_INPUT_HEIGHT.toLong(), MODEL_INPUT_WIDTH.toLong())
            val floatBuffer = FloatBuffer.wrap(floatArray)
            OnnxTensor.createTensor(env, floatBuffer, shape)

        } catch (e: Exception) {
            Log.e(TAG, "Preprocessing error", e)
            null
        }
    }

    private fun postProcess(output: Any, width: Int, height: Int): Bitmap? {
        return try {
            val maskBitmap: Bitmap = when (output) {
                is Array<*> -> {
                    // Handle nested array (typical ONNX segmentation output)
                    @Suppress("UNCHECKED_CAST")
                    val array4d = output as? Array<Array<Array<FloatArray>>>
                    if (array4d != null) {
                        convertOutputToBitmap(array4d)
                    } else {
                        Log.e(TAG, "❌ Unexpected array structure: ${output::class.java}")
                        return null
                    }
                }
                is FloatArray -> {
                    // Handle flat array (rare)
                    val mask = createBitmap(MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)
                    val pixels = IntArray(MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT)
                    for (i in output.indices) {
                        pixels[i] = if (output[i] > 0.5f)
                            Color.argb(200, 0, 255, 0)
                        else
                            Color.TRANSPARENT
                    }
                    mask.setPixels(pixels, 0, MODEL_INPUT_WIDTH, 0, 0, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)
                    mask
                }
                else -> {
                    Log.e(TAG, "❌ Unsupported output type: ${output::class.java}")
                    return null
                }
            }

            val scaledMask = maskBitmap.scale(width, height)
            maskBitmap.recycle()

            Log.d(TAG, "✅ Mask processed ${scaledMask.width}x${scaledMask.height}")
            scaledMask

        } catch (e: Exception) {
            Log.e(TAG, "Post-processing error", e)
            null
        }
    }



    fun createMockFloorMask(width: Int, height: Int): Bitmap {
        Log.d(TAG, "🎭 Creating MOCK floor mask ${width}x${height}")
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.argb(180, 0, 255, 0)  // ✅ FIXED: Increased alpha from 128 to 180
            style = Paint.Style.FILL
        }

        // Draw floor as bottom 60% of screen
        canvas.drawRect(
            0f,
            height * 0.4f,
            width.toFloat(),
            height.toFloat(),
            paint
        )

        return bitmap
    }


}

private fun convertOutputToBitmap(outputTensor: Array<Array<Array<FloatArray>>>): Bitmap {
    val height = outputTensor[0][0].size
    val width = outputTensor[0][0][0].size
    val maskBitmap = createBitmap(width, height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val value = outputTensor[0][0][y][x]
            // Convert probability to color
            val color = if (value > 0.5f) {
                Color.argb(200, 0, 255, 0)  // visible green overlay
            } else {
                Color.TRANSPARENT
            }
            maskBitmap[x, y] = color
        }
    }
    Log.d(TAG, "✅ Mask generated $width x $height")
    return maskBitmap
}

