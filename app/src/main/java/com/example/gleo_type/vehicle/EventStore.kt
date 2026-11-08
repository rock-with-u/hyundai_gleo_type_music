package com.example.gleo_type.vehicle

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 네트워크 실패 시 앱 재시작 후에도 재전송할 수 있는 최소 영속 큐. */
class EventStore(context: Context) : SQLiteOpenHelper(context, "vehicle_events.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun enqueue(event: VehicleEvent) {
        val db = writableDatabase
        val stmt = db.compileStatement("INSERT OR IGNORE INTO pending(id, payload, created_at) VALUES(?, ?, ?)")
        try {
            stmt.bindString(1, event.eventId)
            stmt.bindString(2, event.toJson().toString())
            stmt.bindLong(3, event.timestamp)
            stmt.executeInsert()
        } finally { stmt.close() }
    }

    @Synchronized fun oldest(limit: Int = 30): List<Pair<String, String>> {
        val items = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT id,payload FROM pending ORDER BY created_at ASC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) items.add(cursor.getString(0) to cursor.getString(1))
        }
        return items
    }

    @Synchronized fun remove(id: String) {
        writableDatabase.delete("pending", "id=?", arrayOf(id))
    }

    @Synchronized fun count(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use {
            it.moveToFirst()
            return it.getLong(0)
        }
    }
}
