package com.example.gleo_type.event.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ==========================================
// 숫자·불리언·시간 → 문자열 변환은 이 파일에서만 한다.
//
// 소수 규칙: 최대 N자리에서 HALF_UP 반올림 → 끝의 0 제거 → 소수점 아래 최소 1자리
//   0.0 → "0.0", 0.392612 → "0.3926", 2000.0 → "2000.0", 37.3977 → "37.3977"
// 정수(ms, sec, m 등)는 소수점 없이 그대로.
// 불리언은 소문자 "true" / "false".
// NaN / Infinity 는 JSON null.
// ==========================================

/** 필드별 최대 소수 자릿수 */
object DecimalPlaces {
    const val COMPLETION_RATE = 4
    const val COORDINATE = 8
    const val ALTITUDE = 2
    const val HEADING = 2
    const val PERCENT = 2
    const val SPEED = 1
    const val ANGLE = 1
    const val DISTANCE = 1
}

/** 이벤트 timestamp 시간대 (+09:00) */
val EVENT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

fun Instant.toEventTimestamp(zone: ZoneId = EVENT_ZONE): String =
    TIMESTAMP_FORMAT.format(atZone(zone))

fun Boolean?.toEventString(): String? = when (this) {
    null -> null
    true -> "true"
    false -> "false"
}

fun Int?.toEventString(): String? = this?.toString()

fun Long?.toEventString(): String? = this?.toString()

fun Double?.toEventString(maxFractionDigits: Int): String? {
    if (this == null || isNaN() || isInfinite()) return null
    return BigDecimal.valueOf(this).toEventDecimal(maxFractionDigits)
}

fun Float?.toEventString(maxFractionDigits: Int): String? {
    if (this == null || isNaN() || isInfinite()) return null
    // Float.toString()은 최단 표현(308.21f → "308.21")이라 Double 확장 시의 오차가 없다.
    return BigDecimal(toString()).toEventDecimal(maxFractionDigits)
}

private fun BigDecimal.toEventDecimal(maxFractionDigits: Int): String {
    val rounded = setScale(maxFractionDigits, RoundingMode.HALF_UP).stripTrailingZeros()
    val normalized = if (rounded.scale() < 1) rounded.setScale(1) else rounded
    return normalized.toPlainString()
}
