package app.findus.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** A location snapshot as seen by the two people in a pair. Never stored on the server in plain text. */
data class LocationPayload(
  val name: String,
  val lat: Double,
  val lon: Double,
  val accuracyMeters: Float,
  val timeMillis: Long,
  val batteryPercent: Int? = null,
)

/**
 * End-to-end encryption for location updates.
 *
 * The 256-bit key is created on the phone that starts the pair and travels to the other phone only
 * inside the invite code. It is never uploaded, so the server (Firebase) only ever stores ciphertext.
 * AES-256-GCM authenticates every update; the pair id and sender id are bound in as associated data
 * so an update can't be replayed under another person's name.
 */
object PairCrypto {
  private const val KEY_BYTES = 32
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private val random = SecureRandom()
  private val b64 = Base64.getUrlEncoder().withoutPadding()
  private val b64d = Base64.getUrlDecoder()

  fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

  /** Random, unguessable id for the pair's Firestore document (128 bits). */
  fun newPairId(): String = b64.encodeToString(ByteArray(16).also(random::nextBytes))

  fun encrypt(key: ByteArray, pairId: String, senderUid: String, payload: LocationPayload): EncryptedBlob {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(aad(pairId, senderUid))
    val ct = cipher.doFinal(toJson(payload).toByteArray(Charsets.UTF_8))
    return EncryptedBlob(iv = b64.encodeToString(iv), ciphertext = b64.encodeToString(ct))
  }

  /** Returns null if the blob was tampered with or encrypted with a different key. */
  fun decrypt(key: ByteArray, pairId: String, senderUid: String, blob: EncryptedBlob): LocationPayload? =
    runCatching {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(TAG_BITS, b64d.decode(blob.iv)),
      )
      cipher.updateAAD(aad(pairId, senderUid))
      fromJson(String(cipher.doFinal(b64d.decode(blob.ciphertext)), Charsets.UTF_8))
    }.getOrNull()

  private fun aad(pairId: String, uid: String) = "findus/v1|$pairId|$uid".toByteArray(Charsets.UTF_8)

  private fun toJson(p: LocationPayload): String =
    JSONObject()
      .put("n", p.name)
      .put("la", p.lat)
      .put("lo", p.lon)
      .put("ac", p.accuracyMeters.toDouble())
      .put("t", p.timeMillis)
      .apply { p.batteryPercent?.let { put("b", it) } }
      .toString()

  private fun fromJson(s: String): LocationPayload {
    val o = JSONObject(s)
    return LocationPayload(
      name = o.getString("n"),
      lat = o.getDouble("la"),
      lon = o.getDouble("lo"),
      accuracyMeters = o.getDouble("ac").toFloat(),
      timeMillis = o.getLong("t"),
      batteryPercent = if (o.has("b")) o.getInt("b") else null,
    )
  }
}

data class EncryptedBlob(val iv: String, val ciphertext: String)

/**
 * The invite code one person sends the other: `FINDUS-<pairId>.<key>`.
 * Whoever holds it can join the pair (once) and read locations, so share it privately.
 */
data class InviteCode(val pairId: String, val key: ByteArray) {
  fun encode(): String = PREFIX + pairId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(key)

  override fun equals(other: Any?) =
    other is InviteCode && other.pairId == pairId && other.key.contentEquals(key)

  override fun hashCode() = 31 * pairId.hashCode() + key.contentHashCode()

  companion object {
    private const val PREFIX = "FINDUS-"
    private val PAIR_ID = Regex("^[A-Za-z0-9_-]{22}$")

    /** Accepts the code with surrounding text/whitespace (e.g. pasted from a message). */
    fun parse(raw: String): InviteCode? {
      val start = raw.indexOf(PREFIX).takeIf { it >= 0 } ?: return null
      val body = raw.substring(start + PREFIX.length).takeWhile { !it.isWhitespace() }
      val parts = body.split('.')
      if (parts.size != 2 || !PAIR_ID.matches(parts[0])) return null
      val key = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrNull() ?: return null
      if (key.size != 32) return null
      return InviteCode(parts[0], key)
    }
  }
}
