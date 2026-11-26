package com.bingbaihanji.flowplayer.core.data.file

import java.text.SimpleDateFormat
import java.util.*

/** 文件库中统一的数值展示格式（大小/日期/时长）。 */
object FileFormatters {

    /** 26.98MB / 2.34MB / 500B。 */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes}B"
        } else {
            String.format(Locale.US, "%.2f%s", value, units[unitIndex])
        }
    }

    /** 2025-08-19 22:50。 */
    fun formatDate(epochMillis: Long): String {
        if (epochMillis <= 0L) return "--"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return formatter.format(Date(epochMillis))
    }

    /** 00:01:10（始终包含小时位，便于与缩略图角标对齐）。 */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
