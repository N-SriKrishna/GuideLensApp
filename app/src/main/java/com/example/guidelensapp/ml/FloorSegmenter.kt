package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Wrapper class that uses ONNX-based floor segmentation
 */
class FloorSegmenter(private val context: Context) {

    companion object {
        private const val TAG = "FloorSegmenter"
    }

    private val onnxSegmenter: ONNXFloorSegmenter = ONNXFloorSegmenter(context, useQuantized = true)

    // Emulator detection
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("Emulator")

    /**
     * Segment the floor using ONNX model
     */
    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        return try {
            if (isEmulator) {
                Log.d(TAG, "Creating mock floor mask for emulator")
                onnxSegmenter.createMockFloorMask(bitmap.width, bitmap.height)
            } else {
                // Check if segmenter is ready
                if (!onnxSegmenter.isReady()) {
                    Log.d(TAG, "Segmenter not ready yet, returning mock")
                    onnxSegmenter.createMockFloorMask(bitmap.width, bitmap.height)
                } else {
                    onnxSegmenter.segmentFloor(bitmap)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in floor segmentation", e)
            onnxSegmenter.createMockFloorMask(bitmap.width, bitmap.height)
        }
    }

    /**
     * Get performance statistics
     */
    fun getPerformanceStats() = onnxSegmenter.getPerformanceStats()

    /**
     * Get health status
     */
    fun getHealth() = onnxSegmenter.getHealth()

    /**
     * Check if ready
     */
    fun isReady() = onnxSegmenter.isReady()

    /**
     * Set quality (0.25 to 1.0)
     */
    fun setQuality(quality: Float) = onnxSegmenter.setQuality(quality)

    /**
     * Force cleanup if memory pressure
     */
    fun forceCleanup() = onnxSegmenter.forceCleanup()

    fun close() {
        try {
            onnxSegmenter.close()
            Log.d(TAG, "FloorSegmenter closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FloorSegmenter", e)
        }
    }
}