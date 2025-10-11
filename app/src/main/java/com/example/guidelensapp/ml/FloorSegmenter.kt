package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloorSegmenter(
    private val context: Context,
    private val modelName: String = MODEL_FILE
) {

    companion object {
        private const val TAG = "FloorSegmenter"
        private const val MODEL_FILE = "deeplabv3.tflite"
    }

    private var interpreter: Interpreter? = null
    private var inputWidth: Int = 0
    private var inputHeight: Int = 0
    private lateinit var imageProcessor: ImageProcessor

    init {
        try {
            val model = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options().apply { numThreads = 4 }
            interpreter = Interpreter(model, options)

            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape()
            // Shape is [1, H, W, C], get H and W
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]

            // Deeplab models typically expect input normalized to [-1, 1] for float models
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(127.5f, 127.5f))
                .build()

            Log.d(TAG, "Interpreter for FloorSegmenter initialized. Input shape: ${inputShape.contentToString()}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite Interpreter for FloorSegmenter.", e)
            interpreter = null
        }
    }

    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        val segmenter = interpreter ?: return null

        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

        val outputTensor = segmenter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).apply {
            order(ByteOrder.nativeOrder())
        }

        try {
            segmenter.run(tensorImage.buffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error during segmentation inference", e)
            return null
        }

        return processOutput(outputBuffer, outputShape)
    }

    private fun processOutput(buffer: ByteBuffer, shape: IntArray): Bitmap {
        buffer.rewind()
        val height = shape[1]
        val width = shape[2]
        val numClasses = shape[3]

        // For Deeplab, the "floor" or "background" is often class index 0.
        // This might need adjustment depending on the exact model training.
        val floorClassIndex = 0

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                var maxScore = -Float.MAX_VALUE
                var maxIndex = -1

                // Find the class with the highest score for this pixel
                for (c in 0 until numClasses) {
                    // output is in shape [1, H, W, numClasses]
                    val score = buffer.getFloat((pixelIndex * numClasses + c) * 4)
                    if (score > maxScore) {
                        maxScore = score
                        maxIndex = c
                    }
                }

                pixels[pixelIndex] = if (maxIndex == floorClassIndex) {
                    0x8000FF00.toInt() // Green with 50% transparency
                } else {
                    0x00000000 // Fully transparent
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "FloorSegmenter Interpreter closed.")
    }
}
