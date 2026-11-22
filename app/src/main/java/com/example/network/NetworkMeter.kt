package com.example.network

import com.example.core.models.NetworkTelemetryState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class AccountNetworkMeter {
    private val totalBytes = AtomicLong(0L)
    @Volatile
    private var startedAtMs: Long = System.currentTimeMillis()
    private val samples = ArrayDeque<Pair<Long, Long>>() // (timestampMs, totalBytesAtTime)

    fun recordBytes(bytes: Long) {
        if (bytes > 0L) {
            totalBytes.addAndGet(bytes)
        }
    }

    @Synchronized
    fun reset() {
        totalBytes.set(0L)
        startedAtMs = System.currentTimeMillis()
        samples.clear()
    }

    @Synchronized
    fun sample(): NetworkTelemetryState {
        val now = System.currentTimeMillis()
        val total = totalBytes.get()
        if (total == 0L && samples.isEmpty()) {
            return NetworkTelemetryState(hasUsage = false)
        }

        samples.addLast(now to total)
        while (samples.size > 1 && now - samples.first().first > 10_000L) {
            samples.removeFirst()
        }

        val liveMbps = if (samples.size >= 2) {
            val oldest = samples.first()
            val dtSec = max(0.5, (now - oldest.first) / 1000.0)
            val deltaBytes = (total - oldest.second).coerceAtLeast(0L)
            (deltaBytes * 8.0) / dtSec / 1_000_000.0
        } else {
            0.0
        }

        val sinceResetSec = max(1.0, (now - startedAtMs) / 1000.0)
        val avgMbps = (total * 8.0) / sinceResetSec / 1_000_000.0

        return NetworkTelemetryState(
            liveRateText = formatRate(liveMbps),
            averageRateText = formatRate(avgMbps),
            totalBytesText = formatBytes(total),
            hasUsage = true
        )
    }

    private fun formatRate(mbps: Double): String =
        if (mbps >= 1.0) "%.2f Mbps".format(mbps) else "%.0f Kbps".format(mbps * 1000.0)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

object NetworkMeterRegistry {
    private val meters = ConcurrentHashMap<String, AccountNetworkMeter>()

    fun forAccount(accountId: String): AccountNetworkMeter =
        meters.getOrPut(accountId) { AccountNetworkMeter() }

    fun reset(accountId: String) {
        meters[accountId]?.reset()
    }
}
