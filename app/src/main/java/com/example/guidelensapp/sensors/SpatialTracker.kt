package com.example.guidelensapp.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.guidelensapp.ml.DetectionResult
import kotlin.math.*

class SpatialTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var currentAzimuth = 0f
    private var currentPitch = 0f
    private var currentRoll = 0f

    private val CAMERA_HORIZONTAL_FOV = 60f
    private val CAMERA_VERTICAL_FOV = 45f

    private val spatialObjects = mutableMapOf<String, SpatialObject>()
    private var isTracking = false

    companion object {
        private const val TAG = "SpatialTracker"

        // INCREASED memory duration and decay rate for better persistence
        private const val OBJECT_MEMORY_DURATION = 120_000L // Remember for 2 minutes
        private const val CONFIDENCE_DECAY_RATE = 0.98f // Much slower decay (was 0.95)
        private const val MIN_CONFIDENCE_THRESHOLD = 0.15f // Lower threshold (was 0.1)
    }

    data class SpatialObject(
        val label: String,
        val azimuth: Float,
        val pitch: Float,
        val distance: Float,
        var confidence: Float,
        var lastSeen: Long,
        var isVisible: Boolean,
        var detectionCount: Int = 1 // NEW: Track how many times detected
    )

    fun startTracking() {
        if (isTracking) return

        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        if (rotationVectorSensor == null) {
            magneticSensor?.let { mag ->
                sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            }
            accelerometerSensor?.let { acc ->
                sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        isTracking = true
        Log.d(TAG, "🧭 Spatial tracking started")
    }

    fun stopTracking() {
        if (!isTracking) return
        sensorManager.unregisterListener(this)
        isTracking = false
        Log.d(TAG, "🛑 Spatial tracking stopped")
    }

    /**
     * Clear all spatial memory (call when navigation stops)
     */
    fun clearMemory() {
        spatialObjects.clear()
        Log.d(TAG, "🗑️ Cleared all spatial memory")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                updateOrientationFromRotationVector(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER -> {
                updateOrientationFromMagAccel(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} -> $accuracy")
    }

    private fun updateOrientationFromRotationVector(rotationVector: FloatArray) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        currentAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        currentPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        currentRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

        if (currentAzimuth < 0) currentAzimuth += 360f
    }

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private fun updateOrientationFromMagAccel(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
        }

        if (gravity[0] != 0f && geomagnetic[0] != 0f) {
            val R = FloatArray(9)
            val I = FloatArray(9)

            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)

                currentAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                currentPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                currentRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

                if (currentAzimuth < 0) currentAzimuth += 360f
            }
        }
    }

    fun updateWithDetections(
        detections: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val currentTime = System.currentTimeMillis()

        // Mark all existing objects as not visible
        spatialObjects.values.forEach { it.isVisible = false }

        // Process each detection
        detections.forEach { detection ->
            val centerX = detection.boundingBox.centerX()
            val centerY = detection.boundingBox.centerY()

            val relativeAzimuth = calculateRelativeAzimuth(centerX, imageWidth)
            val relativePitch = calculateRelativePitch(centerY, imageHeight)

            val absoluteAzimuth = normalizeAngle(currentAzimuth + relativeAzimuth)
            val absolutePitch = currentPitch + relativePitch

            val boxSize = detection.boundingBox.width() * detection.boundingBox.height()
            val relativeDistance = estimateDistance(boxSize, imageWidth * imageHeight)

            // Use label + approximate azimuth for grouping (±10 degrees)
            val azimuthBucket = (absoluteAzimuth / 10).toInt() * 10
            val key = "${detection.label}_${azimuthBucket}"

            // Update existing or create new
            val existing = spatialObjects[key]
            if (existing != null) {
                // Object seen again - boost confidence and update position
                existing.confidence = min(1.0f, existing.confidence + 0.1f)
                existing.lastSeen = currentTime
                existing.isVisible = true
                existing.detectionCount++
                Log.d(TAG, "📍 Re-detected: ${detection.label} (count: ${existing.detectionCount})")
            } else {
                spatialObjects[key] = SpatialObject(
                    label = detection.label,
                    azimuth = absoluteAzimuth,
                    pitch = absolutePitch,
                    distance = relativeDistance,
                    confidence = detection.confidence,
                    lastSeen = currentTime,
                    isVisible = true,
                    detectionCount = 1
                )
                Log.d(TAG, "📍 New track: ${detection.label} at azimuth=${absoluteAzimuth.toInt()}°")
            }
        }

        // Decay confidence and remove old objects
        cleanupOldObjects(currentTime)
    }

    fun getAllTrackedObjects(): List<SpatialObject> {
        return spatialObjects.values.sortedByDescending { it.confidence }
    }

    fun getOffScreenObjects(): List<SpatialObject> {
        return spatialObjects.values
            .filter { !it.isVisible && it.confidence > 0.3f }
            .sortedByDescending { it.confidence }
    }

    fun getDirectionToObject(targetLabel: String): DirectionalGuidance? {
        val target = spatialObjects.values
            .filter { it.label.equals(targetLabel, ignoreCase = true) }
            .maxByOrNull { it.confidence }
            ?: return null

        val azimuthDiff = calculateAngleDifference(currentAzimuth, target.azimuth)
        val pitchDiff = target.pitch - currentPitch

        return DirectionalGuidance(
            targetObject = target,
            azimuthDifference = azimuthDiff,
            pitchDifference = pitchDiff,
            isVisible = target.isVisible,
            direction = getCardinalDirection(azimuthDiff, pitchDiff)
        )
    }

    private fun calculateRelativeAzimuth(objectX: Float, imageWidth: Int): Float {
        val normalizedX = (objectX - imageWidth / 2f) / (imageWidth / 2f)
        return normalizedX * (CAMERA_HORIZONTAL_FOV / 2f)
    }

    private fun calculateRelativePitch(objectY: Float, imageHeight: Int): Float {
        val normalizedY = (objectY - imageHeight / 2f) / (imageHeight / 2f)
        return -normalizedY * (CAMERA_VERTICAL_FOV / 2f)
    }

    private fun estimateDistance(boxArea: Float, imageArea: Int): Float {
        val areaRatio = boxArea / imageArea
        return when {
            areaRatio > 0.25f -> 1f
            areaRatio > 0.10f -> 2f
            areaRatio > 0.05f -> 3f
            areaRatio > 0.02f -> 4f
            else -> 5f
        }
    }

    private fun cleanupOldObjects(currentTime: Long) {
        val iterator = spatialObjects.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val obj = entry.value

            val timeSinceLastSeen = (currentTime - obj.lastSeen) / 1000f

            // IMPROVED: Slower decay for objects seen multiple times
            if (!obj.isVisible) {
                val decayRate = if (obj.detectionCount > 5) {
                    CONFIDENCE_DECAY_RATE.pow(0.5f) // Even slower decay for frequently seen objects
                } else {
                    CONFIDENCE_DECAY_RATE
                }
                obj.confidence *= decayRate.pow(timeSinceLastSeen)
            }

            // Remove only if very old or very low confidence
            if (timeSinceLastSeen > OBJECT_MEMORY_DURATION / 1000f || obj.confidence < MIN_CONFIDENCE_THRESHOLD) {
                Log.d(TAG, "🗑️ Removed: ${obj.label} (age: ${timeSinceLastSeen.toInt()}s, conf: ${obj.confidence})")
                iterator.remove()
            }
        }
    }

    private fun getCardinalDirection(azimuthDiff: Float, pitchDiff: Float): String {
        val horizontal = when {
            abs(azimuthDiff) < 15 -> "ahead"
            abs(azimuthDiff) < 45 -> if (azimuthDiff > 0) "slightly right" else "slightly left"
            abs(azimuthDiff) < 90 -> if (azimuthDiff > 0) "to your right" else "to your left"
            abs(azimuthDiff) < 135 -> if (azimuthDiff > 0) "behind right" else "behind left"
            else -> "behind you"
        }

        val vertical = when {
            abs(pitchDiff) < 10 -> ""
            pitchDiff > 0 -> " and above"
            else -> " and below"
        }

        return horizontal + vertical
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        return normalized
    }

    private fun calculateAngleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle2 - angle1
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }

    fun getCurrentOrientation() = Triple(currentAzimuth, currentPitch, currentRoll)

    data class DirectionalGuidance(
        val targetObject: SpatialObject,
        val azimuthDifference: Float,
        val pitchDifference: Float,
        val isVisible: Boolean,
        val direction: String
    )
}
