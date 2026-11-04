package app.findus.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await

/** What one phone currently knows about its pair. */
data class PairState(
  val myUid: String,
  /** How many people have joined (1 = still waiting for the other person). */
  val memberCount: Int,
  /** The other person's latest location, if they're sharing. */
  val partner: LocationPayload?,
  /** True if this phone is no longer a member (e.g. the pair was left). */
  val removed: Boolean = false,
)

/**
 * Firestore layout (see firebase/firestore.rules for who may read/write what):
 *
 *   pairs/{pairId}                  { members: [uidA, uidB], createdAt }
 *   pairs/{pairId}/locations/{uid}  { iv, ct, updatedAt }   <- AES-GCM ciphertext only
 */
class PairRepository(private val context: Context) {

  /** False until app/google-services.json has been added (see README). */
  val isConfigured: Boolean
    get() = FirebaseApp.getApps(context).isNotEmpty()

  private val auth by lazy { FirebaseAuth.getInstance() }
  private val db by lazy { FirebaseFirestore.getInstance() }

  private fun pairDoc(pairId: String) = db.collection("pairs").document(pairId)

  private fun locationDoc(pairId: String, uid: String) =
    pairDoc(pairId).collection("locations").document(uid)

  /** Each phone gets a private anonymous account; no email or phone number needed. */
  suspend fun uid(): String = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid

  suspend fun createPair(): InviteCode {
    val me = uid()
    val invite = InviteCode(PairCrypto.newPairId(), PairCrypto.newKey())
    pairDoc(invite.pairId)
      .set(mapOf("members" to listOf(me), "createdAt" to FieldValue.serverTimestamp()))
      .await()
    return invite
  }

  /** Throws [JoinException] with a human-readable message if the code can't be used. */
  suspend fun joinPair(invite: InviteCode) {
    val me = uid()
    try {
      pairDoc(invite.pairId).update("members", FieldValue.arrayUnion(me)).await()
    } catch (e: FirebaseFirestoreException) {
      when (e.code) {
        FirebaseFirestoreException.Code.NOT_FOUND ->
          throw JoinException("That invite code doesn't match any pair.")
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
          // Either we're already in this pair (fine) or it already has two people.
          val alreadyMember =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (pairDoc(invite.pairId).get().await().get("members") as? List<String>)
                  ?.contains(me) == true
              }
              .getOrDefault(false)
          if (!alreadyMember) {
            throw JoinException("This pair already has two people. Ask for a new invite code.")
          }
        }
        else -> throw JoinException("Couldn't join: ${e.message}")
      }
    }
  }

  suspend fun publish(invite: InviteCode, payload: LocationPayload) {
    val me = uid()
    val blob = PairCrypto.encrypt(invite.key, invite.pairId, me, payload)
    locationDoc(invite.pairId, me)
      .set(mapOf("iv" to blob.iv, "ct" to blob.ciphertext, "updatedAt" to FieldValue.serverTimestamp()))
      .await()
  }

  /** Removes this phone's last known location from the server (used when sharing is turned off). */
  suspend fun clearMyLocation(invite: InviteCode) {
    locationDoc(invite.pairId, uid()).delete().await()
  }

  suspend fun leave(invite: InviteCode) {
    val me = uid()
    runCatching { locationDoc(invite.pairId, me).delete().await() }
    runCatching { pairDoc(invite.pairId).update("members", FieldValue.arrayRemove(me)).await() }
  }

  fun observe(invite: InviteCode, me: String): Flow<PairState> {
    val members: Flow<List<String>?> = callbackFlow {
      val reg =
        pairDoc(invite.pairId).addSnapshotListener { snap, err ->
          if (err != null) {
            // Permission denied => we're no longer a member.
            trySend(null)
            return@addSnapshotListener
          }
          @Suppress("UNCHECKED_CAST")
          trySend(snap?.get("members") as? List<String> ?: emptyList())
        }
      awaitClose { reg.remove() }
    }

    val partner: Flow<LocationPayload?> = callbackFlow {
      val reg =
        pairDoc(invite.pairId).collection("locations").addSnapshotListener { snap, err ->
          if (err != null || snap == null) {
            trySend(null)
            return@addSnapshotListener
          }
          val other = snap.documents.firstOrNull { it.id != me }
          val decoded =
            other?.let {
              val iv = it.getString("iv")
              val ct = it.getString("ct")
              if (iv == null || ct == null) null
              else PairCrypto.decrypt(invite.key, invite.pairId, it.id, EncryptedBlob(iv, ct))
            }
          trySend(decoded)
        }
      awaitClose { reg.remove() }
    }

    return combine(members, partner) { m, p ->
      PairState(
        myUid = me,
        memberCount = m?.size ?: 0,
        partner = p,
        removed = m != null && me !in m,
      )
    }
  }
}

class JoinException(message: String) : Exception(message)
