package com.example.gleo_type.location

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

class FusedLocationMonitor(
    context: Context,
    // 이벤트 파이프라인(LocationEventSource)으로 전달
    private val onLocation: (LocationData) -> Unit = {}
) {
    companion object {
        private const val TAG = "GleoFused"
        private const val PERMISSION =
            "pleos.car.permission.FUSED_LOCATION"
    }

    private val appContext = context.applicationContext

    private var fusedLocation: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var started = false

    fun start() {
        if (started) {
            Log.i(TAG, "[ALREADY_STARTED]")
            return
        }

        val granted = appContext.checkSelfPermission(
            PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            Log.e(TAG, "[PERMISSION_DENIED] $PERMISSION")
            return
        }

        try {
            val sdk = FusedLocation(appContext)

            if (!sdk.isSupported()) {
                Log.e(TAG, "[NOT_SUPPORTED]")
                return
            }

            sdk.initialize()
            fusedLocation = sdk

            val callback = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location == null) {
                        Log.w(TAG, "[LOCATION_NULL]")
                        return
                    }

                    Log.i(
                        TAG,
                        "[LOCATION_CHANGED] $location"
                    )

                    onLocation(location)
                }

                override fun onError(exception: Exception) {
                    Log.e(
                        TAG,
                        "[LOCATION_ERROR]",
                        exception
                    )
                }
            }

            listener = callback
            sdk.registerFusedLocationCallback(callback)
            started = true

            Log.i(TAG, "[REGISTER_SUCCESS]")
        } catch (e: Exception) {
            Log.e(TAG, "[START_FAILED]", e)
            stop()
        }
    }

    fun stop() {
        val sdk = fusedLocation

        if (sdk != null) {
            val callback = listener

            if (callback != null) {
                try {
                    sdk.unregisterFusedLocationCallback(callback)
                } catch (e: Exception) {
                    Log.e(TAG, "[UNREGISTER_FAILED]", e)
                }
            }

            try {
                sdk.release()
            } catch (e: Exception) {
                Log.e(TAG, "[RELEASE_FAILED]", e)
            }
        }

        listener = null
        fusedLocation = null
        started = false

        Log.i(TAG, "[STOPPED]")
    }
}
