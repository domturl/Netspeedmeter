package app.findus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.findus.data.InviteCode
import app.findus.data.PairRepository
import app.findus.data.PairState
import app.findus.data.PairStore
import app.findus.location.SharingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
  val name: String = "",
  val invite: InviteCode? = null,
  val sharing: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val store = PairStore(app)
  val repo = PairRepository(app)

  private val _ui =
    MutableStateFlow(
      UiState(name = store.displayName, invite = store.invite, sharing = store.sharingEnabled)
    )
  val ui: StateFlow<UiState> = _ui.asStateFlow()

  /** Live state of the pair (null while loading or when not paired). */
  val pair: StateFlow<PairState?> =
    _ui
      .map { it.invite }
      .distinctUntilChanged()
      .flatMapLatest { invite ->
        if (invite == null || !repo.isConfigured) flowOf(null)
        else flow { emit(repo.uid()) }.flatMapLatest { me -> repo.observe(invite, me) }
      }
      .catch { emit(null) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun setName(name: String) {
    store.displayName = name.trim()
    _ui.update { it.copy(name = name) }
  }

  fun clearError() = _ui.update { it.copy(error = null) }

  fun createPair() = runBusy {
    val invite = repo.createPair()
    store.invite = invite
    _ui.update { it.copy(invite = invite) }
  }

  fun joinPair(code: String) {
    val invite = InviteCode.parse(code)
    if (invite == null) {
      _ui.update { it.copy(error = "That doesn't look like a Find Us invite code.") }
      return
    }
    runBusy {
      repo.joinPair(invite)
      store.invite = invite
      _ui.update { it.copy(invite = invite) }
    }
  }

  fun setSharing(on: Boolean) {
    val ctx = getApplication<Application>()
    if (on) SharingService.start(ctx) else SharingService.stop(ctx)
    _ui.update { it.copy(sharing = on) }
  }

  /** Re-start the service after the app is reopened, if the user left sharing on. */
  fun resumeSharingIfWanted(hasPermission: Boolean) {
    if (store.sharingEnabled && store.invite != null && hasPermission && repo.isConfigured) {
      SharingService.start(getApplication())
    }
  }

  fun leavePair() {
    val invite = store.invite ?: return
    setSharing(false)
    runBusy {
      repo.leave(invite)
      store.invite = null
      _ui.update { it.copy(invite = null) }
    }
  }

  private fun runBusy(block: suspend () -> Unit) {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      try {
        block()
      } catch (e: Exception) {
        _ui.update { it.copy(error = e.message ?: "Something went wrong. Check your connection.") }
      } finally {
        _ui.update { it.copy(busy = false) }
      }
    }
  }
}
