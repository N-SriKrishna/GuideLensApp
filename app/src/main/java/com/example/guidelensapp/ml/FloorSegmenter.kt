package com.example.guidelensapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Wrapper class that uses ONNX-based floor segmentation instead of EdgeSAM
 */
class FloorSegmenter(private val context: Context) {

    companion object {
        private const val TAG = "FloorSegmenter"
    }

    private val onnxSegmenter: ONNXFloorSegmenter = ONNXFloorSegmenter(context, useQuantized = true)

    // Emulator detection - use mock on emulator
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("Emulator")

    /**
     * Segment the floor using ONNX model
     * This maintains the same interface as the original EdgeSAM-based segmentFloor
     */
    fun segmentFloor(bitmap: Bitmap): Bitmap? {
        return try {
            if (isEmulator) {
                // Use mock mask on emulator to avoid performance issues
                Log.d(TAG, "Creating mock floor mask for emulator testing")
                createMockFloorMask(bitmap.width, bitmap.height)
            } else {
                // Real segmentation on physical device
                Log.d(TAG, "Running ONNX floor segmentation...")
                onnxSegmenter.segmentFloor(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in floor segmentation", e)
            // Return mock mask as fallback
            createMockFloorMask(bitmap.width, bitmap.height)
        }
    }

    /**
     * Create a mock floor mask for emulator testing or fallback
     */
    fun createMockFloorMask(width: Int, height: Int): Bitmap {
        return onnxSegmenter.createMockFloorMask(width, height)
    }

    fun close() {
        try {
            onnxSegmenter.close()
            Log.d(TAG, "FloorSegmenter closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FloorSegmenter", e)
        }
    }
}