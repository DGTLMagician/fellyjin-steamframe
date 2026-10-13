package app.fellyjin.vr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What happened the last time the VR cinema started, shown in the settings so failures are never silent. */
data class VrStatus(
	val state: State = State.NOT_STARTED,
	val reason: String? = null,
	val runtime: String? = null,
	val system: String? = null,
	val graphics: String? = null,
	val features: List<String> = emptyList(),
	val refreshRate: String? = null,
	val controller: String? = null,
) {
	enum class State { NOT_STARTED, RUNNING, FAILED }
}

object VrDiagnostics {
	private val _status = MutableStateFlow(VrStatus())
	val status: StateFlow<VrStatus> = _status.asStateFlow()

	fun update(transform: (VrStatus) -> VrStatus) = _status.update(transform)

	fun failed(reason: String) = _status.update { it.copy(state = VrStatus.State.FAILED, reason = reason) }
}
