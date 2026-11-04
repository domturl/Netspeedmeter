package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedFormatterTest {

  @Test
  fun compact_bits_never_drops_below_kilo() {
    assertEquals("0k", SpeedFormatter.formatCompact(0L))
    assertEquals("0.4k", SpeedFormatter.formatCompact(50L)) // 400 bit/s
    assertEquals("8.0k", SpeedFormatter.formatCompact(1_000L))
  }

  @Test
  fun compact_bits_switches_units_before_four_digits() {
    assertEquals("336k", SpeedFormatter.formatCompact(42_000L))
    assertEquals("999k", SpeedFormatter.formatCompact(124_900L)) // 999.2 kbps
    assertEquals("1.0M", SpeedFormatter.formatCompact(124_990L)) // 999.92 kbps rounds up -> promote
    assertEquals("12M", SpeedFormatter.formatCompact(1_530_920L)) // 12.25 Mbps
    assertEquals("800M", SpeedFormatter.formatCompact(100_000_000L))
    assertEquals("1.6G", SpeedFormatter.formatCompact(200_000_000L))
  }

  @Test
  fun compact_bytes_uses_binary_prefixes() {
    assertEquals("1.0K", SpeedFormatter.formatCompact(1024L, useBits = false))
    assertEquals("1.5M", SpeedFormatter.formatCompact(1_572_864L, useBits = false))
  }

  @Test
  fun compact_negative_is_zero() {
    assertEquals("0k", SpeedFormatter.formatCompact(-5L))
  }

  @Test
  fun full_format_shows_kbps_and_mbps() {
    assertEquals("12.2 Mbps", SpeedFormatter.formatFull(1_530_920L))
    assertEquals("754 kbps", SpeedFormatter.formatFull(94_208L))
    assertEquals("0 bps", SpeedFormatter.formatFull(0L))
  }
}
