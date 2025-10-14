package com.example.guidelensapp.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.guidelensapp.Config
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale

class ObjectDetector(private val context: Context) {
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private val inputSize = Config.MODEL_INPUT_SIZE
    private val confidenceThreshold = Config.DETECTION_CONFIDENCE_THRESHOLD
    private val iouThreshold = Config.DETECTION_IOU_THRESHOLD

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val labelToIndex = labels.withIndex().associate { it.value to it.index }

    var imageWidth = 0
    var imageHeight = 0

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelName = "yolov8s-worldv2_int8.onnx"
            val modelBytes = context.assets.open(modelName).readBytes()

            val sessionOptions = OrtSession.SessionOptions().apply {
                // VALID ONNX Runtime 1.17.0 API calls only

                // 1. NNAPI (Neural Networks API) - Uses Snapdragon NPU
                if (Config.USE_NNAPI) {
                    try {
                        addNnapi()
                        Log.d(TAG, "✅ NNAPI enabled (Snapdragon NPU)")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ NNAPI not available: ${e.message}")
                    }
                }

                // 2. Thread optimization for Snapdragon 8 Gen 2
                setIntraOpNumThreads(Config.ML_INFERENCE_THREADS)
                setInterOpNumThreads(2)

                // 3. Optimization level (VALID method)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                // REMOVED: These methods don't exist in ONNX Runtime 1.17.0
                // setGraphOptimizationLevel()
                // setMemoryPatternOptimization()
                // setCpuMemArena()
            }

            session = env.createSession(modelBytes, sessionOptions)

            // Log model info
            val inputInfo = session?.inputInfo?.entries?.firstOrNull()
            Log.d(TAG, "✅ Model loaded: $modelName")
            Log.d(TAG, "   Input: ${inputInfo?.key}")
            Log.d(TAG, "   Optimizations: NNAPI=${Config.USE_NNAPI}, Threads=${Config.ML_INFERENCE_THREADS}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load model: ${e.message}", e)
            throw RuntimeException("Failed to load ONNX model: ${e.message}")
        }
    }

    fun detectObjects(bitmap: Bitmap, filterClasses: List<String> = emptyList()): List<DetectionResult> {
        // CRASH PROTECTION
        if (session == null) {
            Log.e(TAG, "❌ Session is null, model not loaded")
            return emptyList()
        }

        return try {
            detectObjectsInternal(bitmap, filterClasses)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory during detection", e)
            System.gc()
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Detection crashed", e)
            emptyList()
        }
    }

    private fun detectObjectsInternal(bitmap: Bitmap, filterClasses: List<String>): List<DetectionResult> {
        // ... rest of your existing detectObjects code ...

        val startTime = System.currentTimeMillis()

        try {
            imageWidth = bitmap.width
            imageHeight = bitmap.height

            val targetIndices = if (filterClasses.isEmpty()) {
                null
            } else {
                filterClasses.mapNotNull { className ->
                    labelToIndex[className.lowercase()]
                }.toSet()
            }

            if (targetIndices != null && targetIndices.isEmpty()) {
                Log.w(TAG, "No valid target classes found in: $filterClasses")
                return emptyList()
            }

            // Preprocessing
            val prepStart = System.currentTimeMillis()
            val inputTensor = preprocessImageOptimized(bitmap)
            val prepTime = System.currentTimeMillis() - prepStart

            // Inference
            val inferStart = System.currentTimeMillis()
            val outputs = session?.run(mapOf("images" to inputTensor))
            val inferTime = System.currentTimeMillis() - inferStart

            if (outputs == null || outputs.size() == 0) {
                Log.e(TAG, "❌ No outputs from model")
                inputTensor.close()
                return emptyList()
            }

            // Post-processing
            val postStart = System.currentTimeMillis()
            val outputValue = outputs[0].value
            val detections = when (outputValue) {
                is Array<*> -> {
                    when (val firstElement = outputValue[0]) {
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            postProcess(firstElement as Array<FloatArray>, targetIndices)
                        }
                        is FloatArray -> {
                            @Suppress("UNCHECKED_CAST")
                            postProcess(outputValue as Array<FloatArray>, targetIndices)
                        }
                        else -> emptyList()
                    }
                }
                is FloatBuffer -> {
                    val buffer = outputValue
                    buffer.rewind()
                    val floatArray = FloatArray(buffer.remaining())
                    buffer.get(floatArray)
                    val output2D = Array(84) { i ->
                        FloatArray(8400) { j ->
                            floatArray[i * 8400 + j]
                        }
                    }
                    postProcess(output2D, targetIndices)
                }
                else -> emptyList()
            }
            val postTime = System.currentTimeMillis() - postStart

            inputTensor.close()
            outputs.close()

            val totalTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "⚡ Detection: ${totalTime}ms (prep:${prepTime}ms, infer:${inferTime}ms, post:${postTime}ms) -> ${detections.size} objects")

            return detections

        } catch (e: Exception) {
            Log.e(TAG, "❌ Detection error: ${e.message}", e)
            return emptyList()
        }
    }

    private fun preprocessImageOptimized(bitmap: Bitmap): OnnxTensor {
        val resizedBitmap = bitmap.scale(inputSize, inputSize)

        val floatArray = FloatArray(1 * 3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Optimized single-pass RGB extraction
        var idxR = 0
        var idxG = inputSize * inputSize
        var idxB = inputSize * inputSize * 2

        for (pixel in pixels) {
            floatArray[idxR++] = ((pixel shr 16) and 0xFF) * 0.003921569f
            floatArray[idxG++] = ((pixel shr 8) and 0xFF) * 0.003921569f
            floatArray[idxB++] = (pixel and 0xFF) * 0.003921569f
        }

        resizedBitmap.recycle()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val floatBuffer = FloatBuffer.wrap(floatArray)
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    private fun postProcess(
        output: Array<FloatArray>,
        targetIndices: Set<Int>?
    ): List<DetectionResult> {
        val numPredictions = 8400

        val predictions = Array(numPredictions) { FloatArray(84) }
        for (i in 0 until 84) {
            for (j in 0 until numPredictions) {
                predictions[j][i] = output[i][j]
            }
        }

        val scaleX: Float
        val scaleY: Float
        val padX: Float
        val padY: Float

        if (imageWidth.toFloat() / imageHeight > 1.0f) {
            scaleX = imageWidth.toFloat() / inputSize
            scaleY = scaleX
            padX = 0f
            padY = (inputSize - (imageHeight.toFloat() / scaleX)) / 2f
        } else {
            scaleY = imageHeight.toFloat() / inputSize
            scaleX = scaleY
            padX = (inputSize - (imageWidth.toFloat() / scaleY)) / 2f
            padY = 0f
        }

        val detectionsList = mutableListOf<DetectionResult>()

        for (i in 0 until numPredictions) {
            val xCenter = predictions[i][0]
            val yCenter = predictions[i][1]
            val width = predictions[i][2]
            val height = predictions[i][3]

            val scores = predictions[i].sliceArray(4 until 84)

            var maxScore = 0f
            var classId = -1

            if (targetIndices == null) {
                for (idx in scores.indices) {
                    if (scores[idx] > maxScore) {
                        maxScore = scores[idx]
                        classId = idx
                    }
                }
            } else {
                for (idx in targetIndices) {
                    if (idx < scores.size && scores[idx] > maxScore) {
                        maxScore = scores[idx]
                        classId = idx
                    }
                }
            }

            if (maxScore > confidenceThreshold && classId >= 0 && classId < labels.size) {
                val xCenterUnpadded = xCenter - padX
                val yCenterUnpadded = yCenter - padY

                val x1 = xCenterUnpadded - width / 2
                val y1 = yCenterUnpadded - height / 2
                val x2 = xCenterUnpadded + width / 2
                val y2 = yCenterUnpadded + height / 2

                val bbox = RectF(
                    (x1 * scaleX).coerceIn(0f, imageWidth.toFloat()),
                    (y1 * scaleY).coerceIn(0f, imageHeight.toFloat()),
                    (x2 * scaleX).coerceIn(0f, imageWidth.toFloat()),
                    (y2 * scaleY).coerceIn(0f, imageHeight.toFloat())
                )

                detectionsList.add(
                    DetectionResult(
                        boundingBox = bbox,
                        confidence = maxScore,
                        label = labels[classId]
                    )
                )
            }
        }

        return applyNMS(detectionsList)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        val groupedDetections = detections.groupBy { it.label }
        val finalDetections = mutableListOf<DetectionResult>()

        for ((_, classDetections) in groupedDetections) {
            if (classDetections.size == 1) {
                finalDetections.add(classDetections[0])
                continue
            }

            val sorted = classDetections.sortedByDescending { it.confidence }
            val keep = BooleanArray(sorted.size) { true }

            for (i in sorted.indices) {
                if (!keep[i]) continue
                for (j in i + 1 until sorted.size) {
                    if (!keep[j]) continue

                    val iou = calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox)
                    if (iou > iouThreshold) {
                        keep[j] = false
                    }
                }
            }

            for (i in sorted.indices) {
                if (keep[i]) finalDetections.add(sorted[i])
            }
        }

        return finalDetections.take(Config.MAX_DETECTIONS_PER_FRAME)
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        session?.close()
        Log.d(TAG, "ObjectDetector closed")
    }

    companion object {
        private const val TAG = "ObjectDetector"
    }
}

data class DetectionResult(
    val boundingBox: RectF,
    val confidence: Float,
    val label: String
)
