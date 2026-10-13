package app.fellyjin.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

data class KeyboardStatus(
	val windowFocused: Boolean = false,
	val lastKey: String? = null,
	val keyCount: Int = 0,
)

/**
 * Records whether the window has keyboard focus and which keys arrive. Steam Input can send keys instead of gamepad
 * input, and under gamescope a Java window doesn't always get focus, so this shows which of the two is happening.
 */
object KeyboardDiagnostics {
	private val logger = LoggerFactory.getLogger(KeyboardDiagnostics::class.java)
	private val _status = MutableStateFlow(KeyboardStatus())
	val status: StateFlow<KeyboardStatus> = _status.asStateFlow()

	fun install(window: Window) {
		_status.update { it.copy(windowFocused = window.isFocused) }
		logger.info("Window focused at start: ${window.isFocused}")

		window.addWindowFocusListener(object : WindowFocusListener {
			override fun windowGainedFocus(e: WindowEvent) = focusChanged(true)
			override fun windowLostFocus(e: WindowEvent) = focusChanged(false)
		})

		Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
			if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED) {
				val key = KeyEvent.getKeyText(event.keyCode)
				if (_status.value.keyCount < 40) logger.info("Key pressed: $key")
				_status.update { it.copy(lastKey = key, keyCount = it.keyCount + 1) }
			}
		}, AWTEvent.KEY_EVENT_MASK)
	}

	private fun focusChanged(focused: Boolean) {
		logger.info("Window focus changed: $focused")
		_status.update { it.copy(windowFocused = focused) }
	}
}
