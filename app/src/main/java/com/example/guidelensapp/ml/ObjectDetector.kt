package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(val boundingBox: RectF, val label: String, val confidence: Float)

class ObjectDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private val inputWidth: Int
    private val inputHeight: Int
    private var labels: List<String> = emptyList()
    private val isNCHW: Boolean

    // Lock to prevent concurrent access to the interpreter
    private val lock = Any()

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "yolo_world.tflite")
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Failed to load model", e)
        }

        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: intArrayOf(0, 0, 0, 0)

        // Detect format: NCHW [1, 3, H, W] or NHWC [1, H, W, 3]
        isNCHW = inputShape[1] == 3 || inputShape[1] < 10

        if (isNCHW) {
            // Format: [batch, channels, height, width]
            inputHeight = inputShape[2]
            inputWidth = inputShape[3]
            Log.d("ObjectDetector", "Detected NCHW format: [${inputShape[0]}, ${inputShape[1]}, $inputHeight, $inputWidth]")
        } else {
            // Format: [batch, height, width, channels]
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            Log.d("ObjectDetector", "Detected NHWC format: [${inputShape[0]}, $inputHeight, $inputWidth, ${inputShape[3]}]")
        }

        setCustomVocabulary(listOf("chair", "table", "person", "door"))
    }

    fun setCustomVocabulary(customLabels: List<String>) {
        this.labels = customLabels
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val localInterpreter = interpreter ?: return emptyList()

        // Synchronize to prevent concurrent access
        synchronized(lock) {
            try {
                // ✅ Resize the bitmap to model input size
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

                Log.d("ObjectDetector", "Processing frame: ${resizedBitmap.width} x ${resizedBitmap.height}")

                // ✅ Convert bitmap to ByteBuffer
                val inputBuffer = if (isNCHW) {
                    convertBitmapToByteBufferNCHW(resizedBitmap)
                } else {
                    convertBitmapToByteBufferNHWC(resizedBitmap)
                }

                // ✅ Get output tensor info
                val outputTensor = localInterpreter.getOutputTensor(0)
                val outputShape = outputTensor.shape()

                Log.d("ObjectDetector", "Output shape: ${outputShape.contentToString()}")

                // ✅ Prepare output buffer
                val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
                outputBuffer.order(ByteOrder.nativeOrder())

                // ✅ Run inference
                localInterpreter.run(inputBuffer, outputBuffer)

                // ✅ Post-process results
                return processOutput(outputBuffer, outputShape, bitmap.width, bitmap.height)

            } catch (e: Exception) {
                Log.e("ObjectDetector", "Error during detection", e)
                return emptyList()
            }
        }
    }

    // For models with NCHW format (channels first): [batch, channels, height, width]
    private fun convertBitmapToByteBufferNCHW(bitmap: Bitmap): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val byteBuffer = ByteBuffer.allocateDirect(4 * 3 * width * height)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        // YOLO models typically expect values normalized to [0, 1]
        // Write all R values, then all G values, then all B values
        for (channel in 0..2) {
            for (pixelValue in intValues) {
                val value = when (channel) {
                    0 -> (pixelValue shr 16) and 0xFF  // R
                    1 -> (pixelValue shr 8) and 0xFF   // G
                    else -> pixelValue and 0xFF        // B
                }
                byteBuffer.putFloat(value / 255.0f)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    // For models with NHWC format (channels last): [batch, height, width, channels]
    private fun convertBitmapToByteBufferNHWC(bitmap: Bitmap): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val byteBuffer = ByteBuffer.allocateDirect(4 * width * height * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        // Write R, G, B for each pixel in sequence
        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)  // R
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)   // G
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)           // B
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun processOutput(
        buffer: ByteBuffer,
        outputShape: IntArray,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        buffer.rewind()

        // YOLO output format is typically [1, num_predictions, num_classes+4]
        // or [1, num_classes+4, num_predictions]
        val boxes = mutableListOf<DetectionResult>()

        try {
            // Common YOLO format: [batch, predictions, (x, y, w, h, class_scores...)]
            if (outputShape.size >= 3) {
                val numPredictions = if (outputShape[1] > outputShape[2]) outputShape[2] else outputShape[1]
                val numValues = if (outputShape[1] > outputShape[2]) outputShape[1] else outputShape[2]
                val numClasses = numValues - 4

                Log.d("ObjectDetector", "Predictions: $numPredictions, Classes: $numClasses")

                for (i in 0 until minOf(numPredictions, 100)) { // Limit to first 100 predictions
                    val floatArray = FloatArray(numValues)
                    for (j in 0 until numValues) {
                        floatArray[j] = buffer.float
                    }

                    // Find class with highest score
                    var maxScore = -1f
                    var labelIndex = -1
                    for (j in 4 until numValues) {
                        if (floatArray[j] > maxScore) {
                            maxScore = floatArray[j]
                            labelIndex = j - 4
                        }
                    }

                    if (maxScore > 0.3f) { // Confidence threshold
                        // YOLO typically outputs normalized coordinates [0, 1]
                        val cx = floatArray[0] * originalWidth
                        val cy = floatArray[1] * originalHeight
                        val w = floatArray[2] * originalWidth
                        val h = floatArray[3] * originalHeight

                        val left = cx - w / 2
                        val top = cy - h / 2
                        val right = cx + w / 2
                        val bottom = cy + h / 2

                        val label = if (labelIndex >= 0 && labelIndex < labels.size) {
                            labels[labelIndex]
                        } else {
                            "Object"
                        }

                        boxes.add(DetectionResult(RectF(left, top, right, bottom), label, maxScore))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error processing output", e)
        }

        return nonMaxSuppression(boxes)
    }

    private fun nonMaxSuppression(boxes: List<DetectionResult>, iouThreshold: Float = 0.5f): List<DetectionResult> {
        if (boxes.isEmpty()) return emptyList()

        val sortedBoxes = boxes.sortedByDescending { it.confidence }
        val selectedBoxes = mutableListOf<DetectionResult>()

        for (box in sortedBoxes) {
            var shouldSelect = true
            for (selected in selectedBoxes) {
                if (calculateIoU(box.boundingBox, selected.boundingBox) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selectedBoxes.add(box)
            }
        }

        Log.d("ObjectDetector", "NMS: ${boxes.size} -> ${selectedBoxes.size} boxes")
        return selectedBoxes
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val xA = max(box1.left, box2.left)
        val yA = max(box1.top, box2.top)
        val xB = min(box1.right, box2.right)
        val yB = min(box1.bottom, box2.bottom)

        val intersectionArea = max(0f, xB - xA) * max(0f, yB - yA)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
        }
    }
}