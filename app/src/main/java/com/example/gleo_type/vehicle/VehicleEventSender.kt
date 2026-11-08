package com.example.gleo_type.vehicle

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 주의: 테스트용 앱 프로세스 큐 워커. 앱 종료 후 자동 전송은 WorkManager/서비스 필요. */
class VehicleEventSender(context: Context, private val endpoint: String) {
    private val store = EventStore(context.applicationContext)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)

    fun start() {
        worker.scheduleWithFixedDelay({ flush() }, 0, 5, TimeUnit.SECONDS)
    }

    fun emit(event: VehicleEvent) {
        worker.execute {
            store.enqueue(event)
            Log.i("GleoEvent", "[QUEUE] ${event.domain}.${event.eventType} value=${event.value} pending=${store.count()}")
            flush()
        }
    }

    fun pendingCount(): Long = store.count()

    private fun flush() {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://10.0.2.2:")) {
                Log.w("GleoEvent", "[CONFIG] endpoint must be HTTPS (or emulator 10.0.2.2): $endpoint")
                return
            }
            for ((id, body) in store.oldest()) {
                if (!post(body)) break
                store.remove(id)
                Log.i("GleoEvent", "[SENT] id=$id remaining=${store.count()}")
            }
        } catch (e: Exception) {
            Log.e("GleoEvent", "[FLUSH_FAILED] ${e.message}", e)
        } finally {
            busy.set(false)
        }
    }

    private fun post(body: String): Boolean {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 200..299 || status == 409) return true // 409는 서버가 동일 eventId 중복으로 정의한 경우만 허용
            Log.w("GleoEvent", "[HTTP_FAILED] status=$status")
            return false
        } finally { connection.disconnect() }
    }

    fun stop() {
        worker.shutdown()
        // 아직 전송되지 않은 이벤트는 SQLite에 남는다.
    }
}
