
package com.example.gleo_type.vehicle

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VehicleValueDto(
    val type: String,
    val value: Any?
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("value", value ?: JSONObject.NULL)
        }
    }
}

data class VehicleEventDto(
    val eventId: String = UUID.randomUUID().toString(),
    val domain: String,
    val apiName: String,
    val eventName: String,
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val values: List<VehicleValueDto>
) {
    fun toJson(): JSONObject {
        val jsonValues = JSONArray()

        for (value in values) {
            jsonValues.put(value.toJson())
        }

        return JSONObject().apply {
            put("eventId", eventId)
            put("domain", domain)
            put("apiName", apiName)
            put("eventName", eventName)
            put("eventType", eventType)
            put("timestamp", timestamp)
            put("values", jsonValues)
        }
    }
}

object VehicleValueConverter {

    fun convert(value: Any?): VehicleValueDto {
        if (value == null) {
            return VehicleValueDto("NULL", null)
        }

        return when (value) {
            is Boolean -> VehicleValueDto("BOOLEAN", value)
            is Byte, is Short, is Int, is Long ->
                VehicleValueDto("INTEGER", value)

            is Float -> VehicleValueDto(
                "NUMBER",
                if (value.isFinite()) value.toDouble() else null
            )

            is Double -> VehicleValueDto(
                "NUMBER",
                if (value.isFinite()) value else null
            )

            is String -> VehicleValueDto("STRING", value)
            is Char -> VehicleValueDto("STRING", value.toString())
            is Enum<*> -> VehicleValueDto("ENUM", value.name)

            else -> VehicleValueDto(
                "OBJECT",
                value.toString()
            )
        }
    }
}
