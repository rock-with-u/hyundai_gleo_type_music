package com.example.gleo_type.vehicle

import org.json.JSONObject
import java.util.UUID

data class VehicleEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val source: String = "PLEOS_VEHICLE_SDK",
    val domain: String,
    val eventType: String,
    val area: String? = null,
    val value: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("source", source)
        put("domain", domain)
        put("eventType", eventType)
        put("area", area ?: JSONObject.NULL)
        put("value", value ?: JSONObject.NULL)
        put("timestamp", timestamp)
    }
}
