package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun formatSpeed_uses_lowercase_k_for_kilo() {
    // Bits mode: kilo should be "kbps"
    val (bitsSpeed, bitsUnit) = OverlayService.formatSpeed(50_000L, useBits = true)
    assertEquals("kbps", bitsUnit)
    assertEquals("400", bitsSpeed)

    // Bytes mode: kilo should be "kB/s"
    val (bytesSpeed, bytesUnit) = OverlayService.formatSpeed(50_000L, useBits = false)
    assertEquals("kB/s", bytesUnit)
    assertEquals("49", bytesSpeed)

    // Verify mega remains uppercase 'M'
    val (_, megaBitsUnit) = OverlayService.formatSpeed(2_000_000L, useBits = true)
    assertEquals("Mbps", megaBitsUnit)

    val (_, megaBytesUnit) = OverlayService.formatSpeed(2_000_000L, useBits = false)
    assertEquals("MB/s", megaBytesUnit)
  }
}
