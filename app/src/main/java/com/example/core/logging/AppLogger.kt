package com.example.core.logging

import android.content.Context
import android.util.Log
import com.example.core.models.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

object AppLogger {
    private const val MAX_MEMORY_ENTRIES = 500
    private val idGenerator = AtomicLong(0L)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    var verboseDebugLogging: Boolean = false

    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "drop_collector.log")
        if ((logFile?.length() ?: 0L) > 2 * 1024 * 1024) {
            logFile?.writeText("")
        }
        info("AppLogger", "Logger initialized")
    }

    fun debug(category: String, message: String) {
        if (!verboseDebugLogging) return
        record("DEBUG", category, message)
        Log.d("SDC/$category", message)
    }

    fun info(category: String, message: String) {
        record("INFO", category, message)
        Log.i("SDC/$category", message)
    }

    fun warn(category: String, message: String) {
        record("WARN", category, message)
        Log.w("SDC/$category", message)
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message (${throwable.javaClass.simpleName}: ${throwable.message})" else message
        record("ERROR", category, full)
        Log.e("SDC/$category", message, throwable)
    }

    fun redactToken(token: String?): String {
        if (token.isNullOrEmpty()) return "(null/empty)"
        if (token.length <= 14) return "(redacted, len=${token.length})"
        return "${token.take(6)}...${token.takeLast(4)} (len=${token.length})"
    }

    fun exportAllText(): String {
        val entriesText = _entries.value.joinToString("\n") { "[${it.timestamp}] [${it.level}] [${it.category}] ${it.message}" }
        return entriesText.ifBlank { "No diagnostic logs recorded yet." }
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching { logFile?.writeText("") }
    }

    @Synchronized
    private fun record(level: String, category: String, message: String) {
        val entry = LogEntry(
            id = idGenerator.incrementAndGet(),
            timestamp = timeFormatter.format(LocalTime.now()),
            level = level,
            category = category,
            message = message
        )
        val current = _entries.value
        val updated = if (current.size >= MAX_MEMORY_ENTRIES) {
            current.drop(current.size - MAX_MEMORY_ENTRIES + 1) + entry
        } else {
            current + entry
        }
        _entries.value = updated
        runCatching {
            logFile?.appendText("[${entry.timestamp}] [${entry.level}] [${entry.category}] ${entry.message}\n")
        }
    }
}
