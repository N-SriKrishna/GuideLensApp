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

    // Lock to prevent concurrent access
    private val lock = Any()

    // Throttling
    private var isProcessing = false

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "yolo_world.tflite")
            val options = Interpreter.Options().apply {
                numThreads = 2 // Reduce threads to prevent CPU overload
            }
            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Failed to load model", e)
        }

        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: intArrayOf(0, 0, 0, 0)

        isNCHW = inputShape[1] == 3 || inputShape[1] < 10

        if (isNCHW) {
            inputHeight = inputShape[2]
            inputWidth = inputShape[3]
            Log.d("ObjectDetector", "Model input: NCHW [$inputWidth x $inputHeight]")
        } else {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            Log.d("ObjectDetector", "Model input: NHWC [$inputWidth x $inputHeight]")
        }

        setCustomVocabulary(listOf("chair", "table", "person", "door"))
    }

    fun setCustomVocabulary(customLabels: List<String>) {
        this.labels = customLabels
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        // Skip if already processing
        if (isProcessing) {
            Log.d("ObjectDetector", "Skipping frame - already processing")
            return emptyList()
        }

        val localInterpreter = interpreter ?: return emptyList()

        synchronized(lock) {
            isProcessing = true
            try {
                val startTime = System.currentTimeMillis()

                // Resize bitmap
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

                // Convert to ByteBuffer
                val inputBuffer = if (isNCHW) {
                    convertBitmapToByteBufferNCHW(resizedBitmap)
                } else {
                    convertBitmapToByteBufferNHWC(resizedBitmap)
                }

                // Get output info
                val outputTensor = localInterpreter.getOutputTensor(0)
                val outputShape = outputTensor.shape()
                val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
                outputBuffer.order(ByteOrder.nativeOrder())

                // Run inference
                localInterpreter.run(inputBuffer, outputBuffer)

                // Process output
                val results = processYOLOOutput(outputBuffer, outputShape, bitmap.width, bitmap.height)

                val endTime = System.currentTimeMillis()
                Log.d("ObjectDetector", "Detection took ${endTime - startTime}ms, found ${results.size} objects")

                return results

            } catch (e: Exception) {
                Log.e("ObjectDetector", "Error during detection", e)
                return emptyList()
            } finally {
                isProcessing = false
            }
        }
    }

    private fun convertBitmapToByteBufferNCHW(bitmap: Bitmap): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val byteBuffer = ByteBuffer.allocateDirect(4 * 3 * width * height)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        // Write all R, then all G, then all B
        for (channel in 0..2) {
            for (pixelValue in intValues) {
                val value = when (channel) {
                    0 -> (pixelValue shr 16) and 0xFF
                    1 -> (pixelValue shr 8) and 0xFF
                    else -> pixelValue and 0xFF
                }
                byteBuffer.putFloat(value / 255.0f)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun convertBitmapToByteBufferNHWC(bitmap: Bitmap): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val byteBuffer = ByteBuffer.allocateDirect(4 * width * height * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun processYOLOOutput(
        buffer: ByteBuffer,
        outputShape: IntArray,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        buffer.rewind()

        // YOLO output: [1, 84, 8400] means:
        // - 1 batch
        // - 84 values per prediction (4 bbox + 80 classes)
        // - 8400 predictions

        val numClasses = outputShape[1] - 4  // 80 classes
        val numPredictions = outputShape[2]  // 8400 predictions

        val boxes = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.4f  // Increased threshold for better performance
        val maxBoxes = 20  // Limit max boxes for performance

        // Read predictions efficiently
        for (i in 0 until numPredictions) {
            // Read bbox coordinates (4 values)
            val cx = buffer.float
            val cy = buffer.float
            val w = buffer.float
            val h = buffer.float

            // Find max class score and index
            var maxScore = 0f
            var maxIndex = -1

            for (c in 0 until numClasses) {
                val score = buffer.float
                if (score > maxScore) {
                    maxScore = score
                    maxIndex = c
                }
            }

            // Only keep high confidence detections
            if (maxScore > confidenceThreshold && boxes.size < maxBoxes) {
                // Convert from normalized coords to pixel coords
                val centerX = cx * originalWidth
                val centerY = cy * originalHeight
                val width = w * originalWidth
                val height = h * originalHeight

                val left = centerX - width / 2
                val top = centerY - height / 2
                val right = centerX + width / 2
                val bottom = centerY + height / 2

                // Validate bounds
                if (left >= 0 && top >= 0 && right <= originalWidth && bottom <= originalHeight) {
                    val label = if (maxIndex >= 0 && maxIndex < labels.size) {
                        labels[maxIndex]
                    } else {
                        "Object"
                    }

                    boxes.add(DetectionResult(
                        RectF(left, top, right, bottom),
                        label,
                        maxScore
                    ))
                }
            }
        }

        return nonMaxSuppression(boxes, iouThreshold = 0.4f)
    }

    private fun nonMaxSuppression(boxes: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        if (boxes.isEmpty()) return emptyList()

        val sortedBoxes = boxes.sortedByDescending { it.confidence }
        val selectedBoxes = mutableListOf<DetectionResult>()

        for (box in sortedBoxes) {
            if (selectedBoxes.size >= 10) break  // Limit to 10 boxes max

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