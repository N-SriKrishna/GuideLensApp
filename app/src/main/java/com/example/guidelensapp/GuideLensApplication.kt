package com.example.guidelensapp

// app/src/main/java/com/example/guidelensapp/GuideLensApplication.kt

import android.app.Application
import android.util.Log
import com.example.guidelensapp.utils.MemoryManager
import com.example.guidelensapp.utils.ThreadManager

class GuideLensApplication : Application() {

    companion object {
        private const val TAG = "GuideLensApplication"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Initializing GuideLens application")

        // Initialize global managers early
        try {
            ThreadManager.getInstance()
            MemoryManager.getInstance()
            Log.d(TAG, "Global managers initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize global managers", e)
        }

        // Set up uncaught exception handler for debugging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            // You can add crash reporting here
            System.exit(1)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Application low memory warning")

        // Force cleanup across the app
        MemoryManager.getInstance().performGcIfNeeded(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "Application terminating")

        // Shutdown thread pools
        try {
            ThreadManager.getInstance().shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}