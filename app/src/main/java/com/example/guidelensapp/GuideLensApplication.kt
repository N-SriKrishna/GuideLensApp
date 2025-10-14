package com.example.guidelensapp

import android.app.Application
import android.util.Log
import com.example.guidelensapp.utils.MemoryManager
import com.example.guidelensapp.utils.ThreadManager
import kotlin.system.exitProcess

class GuideLensApplication : Application() {
    companion object {
        private const val TAG = "GuideLensApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Initializing GuideLens application")

        // Initialize device-adaptive configuration FIRST
        try {
            Config.initialize(this)
            Log.d(TAG, "Device configuration:\n${Config.getDeviceInfo()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Config", e)
        }

        // Initialize global managers
        try {
            ThreadManager.getInstance()
            MemoryManager.getInstance()
            Log.d(TAG, "Global managers initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize global managers", e)
        }

        // Enhanced crash handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "═══════════════════════════════════════")
            Log.e(TAG, "FATAL CRASH in thread: ${thread.name}")
            Log.e(TAG, "Exception: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Stack trace:", throwable)
            Log.e(TAG, "═══════════════════════════════════════")

            // Force cleanup
            try {
                MemoryManager.getInstance().forceGarbageCollection()
            } catch (_: Exception) {
                // Ignore
            }

            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}
