package com.example

import java.util.Locale

/** Pure formatting helpers for network rates. Kept free of Android APIs so they are unit-testable. */
object SpeedFormatter {

    /**
     * Formats a rate for full-width display, e.g. ("12.4", "Mbps") or ("340", "kbps").
     * Bits use decimal SI prefixes (1 kbps = 1000 bit/s), bytes use binary prefixes.
     */
    fun format(bytesPerSec: Long, useBits: Boolean = true): Pair<String, String> {
        val capped = bytesPerSec.coerceAtLeast(0L)
        return if (useBits) {
            val bits = capped * 8
            when {
                bits >= 1_000_000_000L -> Pair(oneDecimal(bits / 1e9), "Gbps")
                bits >= 1_000_000L -> Pair(oneDecimal(bits / 1e6), "Mbps")
                bits >= 1_000L -> Pair(String.format(Locale.US, "%.0f", bits / 1e3), "kbps")
                else -> Pair(bits.toString(), "bps")
            }
        } else {
            when {
                capped >= 1024L * 1024 * 1024 -> Pair(oneDecimal(capped / (1024.0 * 1024 * 1024)), "GB/s")
                capped >= 1024L * 1024 -> Pair(oneDecimal(capped / (1024.0 * 1024)), "MB/s")
                capped >= 1024L -> Pair(String.format(Locale.US, "%.0f", capped / 1024.0), "kB/s")
                else -> Pair(capped.toString(), "B/s")
            }
        }
    }

    /** "12.4 Mbps" */
    fun formatFull(bytesPerSec: Long, useBits: Boolean = true): String {
        val (value, unit) = format(bytesPerSec, useBits)
        return "$value $unit"
    }

    /**
     * Very short form for the tiny status bar icon: at most 3 digits/dot plus a unit letter,
     * never going below kilo so that any trickle of traffic is still visible ("0.4k").
     * Examples (bits): 0 -> "0k", 50 B/s -> "0.4k", 42 kB/s -> "336k", 1.5 MB/s -> "12M".
     */
    fun formatCompact(bytesPerSec: Long, useBits: Boolean = true): String {
        val capped = bytesPerSec.coerceAtLeast(0L)
        val base = if (useBits) 1000.0 else 1024.0
        var value = if (useBits) capped * 8.0 else capped.toDouble()
        value /= base // start at kilo
        val units = if (useBits) listOf("k", "M", "G", "T") else listOf("K", "M", "G", "T")
        var i = 0
        // Promote to the next unit once the rounded value would need 4 digits.
        while (i < units.lastIndex && value >= 999.5) {
            value /= base
            i++
        }
        val number = when {
            value == 0.0 -> "0"
            value < 9.95 -> oneDecimal(value)
            else -> String.format(Locale.US, "%.0f", value)
        }
        return number + units[i]
    }

    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)
}
