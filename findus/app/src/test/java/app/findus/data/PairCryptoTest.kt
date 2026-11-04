package app.findus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairCryptoTest {
  private val payload = LocationPayload("Sam", 51.5007, -0.1246, 12.5f, 1_700_000_000_000, 81)

  @Test
  fun roundTrip() {
    val key = PairCrypto.newKey()
    val pairId = PairCrypto.newPairId()
    val blob = PairCrypto.encrypt(key, pairId, "uidA", payload)
    assertEquals(payload, PairCrypto.decrypt(key, pairId, "uidA", blob))
  }

  @Test
  fun ciphertextHidesCoordinates() {
    val blob = PairCrypto.encrypt(PairCrypto.newKey(), PairCrypto.newPairId(), "uidA", payload)
    assert(!blob.ciphertext.contains("51.5"))
    assert(!blob.ciphertext.contains("Sam"))
  }

  @Test
  fun wrongKeyOrSenderFails() {
    val key = PairCrypto.newKey()
    val pairId = PairCrypto.newPairId()
    val blob = PairCrypto.encrypt(key, pairId, "uidA", payload)
    assertNull(PairCrypto.decrypt(PairCrypto.newKey(), pairId, "uidA", blob))
    assertNull(PairCrypto.decrypt(key, pairId, "uidB", blob))
    assertNull(PairCrypto.decrypt(key, PairCrypto.newPairId(), "uidA", blob))
  }

  @Test
  fun payloadWithoutBattery() {
    val key = PairCrypto.newKey()
    val p = payload.copy(batteryPercent = null)
    assertEquals(p, PairCrypto.decrypt(key, "x", "u", PairCrypto.encrypt(key, "x", "u", p)))
  }

  @Test
  fun inviteCodeRoundTripAndParsing() {
    val invite = InviteCode(PairCrypto.newPairId(), PairCrypto.newKey())
    val code = invite.encode()
    assertEquals(invite, InviteCode.parse(code))
    // Works when pasted along with the share message.
    val parsed = InviteCode.parse("Join me on Find Us with this code:\n  $code \n")
    assertNotNull(parsed)
    assertEquals(invite, parsed)
  }

  @Test
  fun rejectsBadCodes() {
    assertNull(InviteCode.parse("hello"))
    assertNull(InviteCode.parse("FINDUS-short.abc"))
    val id = PairCrypto.newPairId()
    assertNull(InviteCode.parse("FINDUS-$id.AAAA"))
  }
}
