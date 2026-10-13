package app.fellyjin.input

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs

/** UI actions produced by the controller. */
enum class UiAction { UP, DOWN, LEFT, RIGHT, ACTIVATE, BACK }

sealed interface GamepadEvent {
	data class Action(val action: UiAction) : GamepadEvent
	data class Connected(val name: String) : GamepadEvent
	data object Disconnected : GamepadEvent
}

/** What the gamepad reader currently sees, shown on the controller check screen. */
data class GamepadStatus(
	val sdl: String = "Starting…",
	val devices: List<String> = emptyList(),
	val active: String? = null,
	val lastInput: String? = null,
	val inputCount: Int = 0,
)

/**
 * Lets the default Steam Frame (Steam Input gamepad) controls drive the UI. AWT does not read gamepads, so this polls
 * them through SDL2 (bundled with libmpv) and publishes [events] that the UI handles directly in Compose:
 *
 * - D-pad / left stick: move focus (with repeat)
 * - A: activate the focused item, B: back
 *
 * Devices with a known gamepad mapping (like Steam's virtual gamepad) are read through SDL's GameController API.
 * Joysticks without a mapping fall back to the raw joystick API with the common layout (button 0 = A, 1 = B, hat or
 * first stick for directions). All SDL calls happen on one dedicated thread.
 */
class GamepadInput : AutoCloseable {
	private val logger = LoggerFactory.getLogger(GamepadInput::class.java)

	@Volatile
	private var running = true
	private val thread = Thread(::run, "gamepad-input").apply { isDaemon = true }

	private val _events = MutableSharedFlow<GamepadEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	val events: SharedFlow<GamepadEvent> = _events.asSharedFlow()

	private val _status = MutableStateFlow(GamepadStatus())
	val status: StateFlow<GamepadStatus> = _status.asStateFlow()

	@Suppress("FunctionName")
	private interface Sdl : Library {
		fun SDL_SetHint(name: String, value: String): Boolean
		fun SDL_InitSubSystem(flags: Int): Int
		fun SDL_GetError(): String
		fun SDL_GetRevision(): String
		fun SDL_PumpEvents()
		fun SDL_FlushEvents(minType: Int, maxType: Int)
		fun SDL_NumJoysticks(): Int
		fun SDL_JoystickNameForIndex(index: Int): String?
		fun SDL_JoystickGetDeviceVendor(index: Int): Short
		fun SDL_JoystickGetDeviceProduct(index: Int): Short
		fun SDL_IsGameController(index: Int): Boolean
		fun SDL_GameControllerOpen(index: Int): Pointer?
		fun SDL_GameControllerGetAttached(controller: Pointer): Boolean
		fun SDL_GameControllerClose(controller: Pointer)
		fun SDL_GameControllerName(controller: Pointer): String?
		fun SDL_GameControllerUpdate()
		fun SDL_GameControllerGetButton(controller: Pointer, button: Int): Byte
		fun SDL_GameControllerGetAxis(controller: Pointer, axis: Int): Short
		fun SDL_JoystickOpen(index: Int): Pointer?
		fun SDL_JoystickGetAttached(joystick: Pointer): Boolean
		fun SDL_JoystickClose(joystick: Pointer)
		fun SDL_JoystickUpdate()
		fun SDL_JoystickNumButtons(joystick: Pointer): Int
		fun SDL_JoystickNumHats(joystick: Pointer): Int
		fun SDL_JoystickNumAxes(joystick: Pointer): Int
		fun SDL_JoystickGetButton(joystick: Pointer, button: Int): Byte
		fun SDL_JoystickGetHat(joystick: Pointer, hat: Int): Byte
		fun SDL_JoystickGetAxis(joystick: Pointer, axis: Int): Short
	}

	/** The opened device, either through the GameController API or as a raw joystick. */
	private sealed interface Device {
		val name: String

		data class Controller(val handle: Pointer, override val name: String) : Device
		data class Joystick(val handle: Pointer, override val name: String) : Device
	}

	/** Snapshot of the inputs this app uses. */
	private data class Inputs(
		val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean,
		val a: Boolean, val b: Boolean,
	)

	/** Repeat state per action: when it went down and when it last fired. */
	private val pressedSince = mutableMapOf<UiAction, Long>()
	private val lastRepeat = mutableMapOf<UiAction, Long>()

	fun start() = thread.start()

	private fun run() {
		val sdl = loadSdl() ?: return
		logSteamEnvironment()
		// Keep reading the controller when the window is not focused (gamescope focus can lag behind)
		sdl.SDL_SetHint("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1")
		if (sdl.SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {
			val error = sdl.SDL_GetError()
			logger.warn("SDL game controller init failed: $error")
			_status.update { it.copy(sdl = "SDL init failed: $error") }
			return
		}
		_status.update { it.copy(sdl = "SDL ${runCatching { sdl.SDL_GetRevision() }.getOrDefault("")}".trim()) }

		var device: Device? = null
		var lastScan = 0L
		var lastDevices: List<String>? = null
		while (running) {
			sdl.SDL_PumpEvents()
			// State is read directly, the event queue isn't used: keep it from filling up
			sdl.SDL_FlushEvents(0, 0xFFFF)
			sdl.SDL_GameControllerUpdate()
			sdl.SDL_JoystickUpdate()

			if (device != null && !attached(sdl, device)) {
				logger.info("Controller ${device.name} disconnected")
				close(sdl, device)
				device = null
				_status.update { it.copy(active = null) }
				_events.tryEmit(GamepadEvent.Disconnected)
			}

			val now = System.currentTimeMillis()
			if (now - lastScan >= SCAN_INTERVAL_MS) {
				lastScan = now
				val devices = describeDevices(sdl)
				if (devices != lastDevices) {
					lastDevices = devices
					logger.info("SDL sees ${devices.size} joystick device(s): $devices")
					_status.update { it.copy(devices = devices) }
				}
				if (device == null) {
					device = open(sdl)
					device?.let {
						logger.info("Using ${it.name} for the UI")
						_status.update { status -> status.copy(active = it.name) }
						_events.tryEmit(GamepadEvent.Connected(it.name))
					}
				}
			}

			device?.let { read(sdl, it) }?.let { inputs ->
				update(UiAction.UP, inputs.up, repeat = true)
				update(UiAction.DOWN, inputs.down, repeat = true)
				update(UiAction.LEFT, inputs.left, repeat = true)
				update(UiAction.RIGHT, inputs.right, repeat = true)
				update(UiAction.ACTIVATE, inputs.a, repeat = false)
				update(UiAction.BACK, inputs.b, repeat = false)
			}

			Thread.sleep(POLL_INTERVAL_MS)
		}
		device?.let { close(sdl, it) }
	}

	private fun describeDevices(sdl: Sdl): List<String> = (0 until sdl.SDL_NumJoysticks()).map { index ->
		val name = sdl.SDL_JoystickNameForIndex(index) ?: "Unknown"
		val vendor = sdl.SDL_JoystickGetDeviceVendor(index).toInt() and 0xffff
		val product = sdl.SDL_JoystickGetDeviceProduct(index).toInt() and 0xffff
		val kind = if (sdl.SDL_IsGameController(index)) "gamepad" else "joystick, no mapping"
		"%s [%04x:%04x, %s]".format(name, vendor, product, kind)
	}

	/** Prefer a device with a gamepad mapping, otherwise any joystick with buttons. */
	private fun open(sdl: Sdl): Device? {
		val count = sdl.SDL_NumJoysticks()
		(0 until count).firstOrNull { sdl.SDL_IsGameController(it) }?.let { index ->
			sdl.SDL_GameControllerOpen(index)?.let { handle ->
				return Device.Controller(handle, sdl.SDL_GameControllerName(handle) ?: "Gamepad")
			}
		}
		for (index in 0 until count) {
			val handle = sdl.SDL_JoystickOpen(index) ?: continue
			if (sdl.SDL_JoystickNumButtons(handle) >= 2) {
				return Device.Joystick(handle, (sdl.SDL_JoystickNameForIndex(index) ?: "Joystick") + " (raw)")
			}
			sdl.SDL_JoystickClose(handle)
		}
		return null
	}

	private fun attached(sdl: Sdl, device: Device) = when (device) {
		is Device.Controller -> sdl.SDL_GameControllerGetAttached(device.handle)
		is Device.Joystick -> sdl.SDL_JoystickGetAttached(device.handle)
	}

	private fun close(sdl: Sdl, device: Device) = when (device) {
		is Device.Controller -> sdl.SDL_GameControllerClose(device.handle)
		is Device.Joystick -> sdl.SDL_JoystickClose(device.handle)
	}

	private fun read(sdl: Sdl, device: Device): Inputs = when (device) {
		is Device.Controller -> {
			fun button(id: Int) = sdl.SDL_GameControllerGetButton(device.handle, id).toInt() != 0
			val (x, y) = stick(
				sdl.SDL_GameControllerGetAxis(device.handle, AXIS_LEFTX),
				sdl.SDL_GameControllerGetAxis(device.handle, AXIS_LEFTY),
			)
			Inputs(
				up = button(BUTTON_DPAD_UP) || y < 0,
				down = button(BUTTON_DPAD_DOWN) || y > 0,
				left = button(BUTTON_DPAD_LEFT) || x < 0,
				right = button(BUTTON_DPAD_RIGHT) || x > 0,
				a = button(BUTTON_A),
				b = button(BUTTON_B),
			)
		}

		is Device.Joystick -> {
			val handle = device.handle
			fun button(id: Int) = id < sdl.SDL_JoystickNumButtons(handle) && sdl.SDL_JoystickGetButton(handle, id).toInt() != 0
			val hat = if (sdl.SDL_JoystickNumHats(handle) > 0) sdl.SDL_JoystickGetHat(handle, 0).toInt() else 0
			val (x, y) = if (sdl.SDL_JoystickNumAxes(handle) >= 2) {
				stick(sdl.SDL_JoystickGetAxis(handle, 0), sdl.SDL_JoystickGetAxis(handle, 1))
			} else {
				0f to 0f
			}
			Inputs(
				up = hat and HAT_UP != 0 || y < 0,
				down = hat and HAT_DOWN != 0 || y > 0,
				left = hat and HAT_LEFT != 0 || x < 0,
				right = hat and HAT_RIGHT != 0 || x > 0,
				a = button(0),
				b = button(1),
			)
		}
	}

	/** Dominant stick direction past the threshold, or 0. */
	private fun stick(rawX: Short, rawY: Short): Pair<Float, Float> {
		val x = rawX / 32768f
		val y = rawY / 32768f
		return when {
			abs(x) > STICK_THRESHOLD && abs(x) > abs(y) -> x to 0f
			abs(y) > STICK_THRESHOLD -> 0f to y
			else -> 0f to 0f
		}
	}

	private fun update(action: UiAction, down: Boolean, repeat: Boolean) {
		val now = System.currentTimeMillis()
		val since = pressedSince[action]
		when {
			down && since == null -> {
				pressedSince[action] = now
				lastRepeat[action] = now
				emit(action)
			}

			down && repeat && since != null && now - since >= REPEAT_DELAY_MS && now - lastRepeat.getValue(action) >= REPEAT_INTERVAL_MS -> {
				lastRepeat[action] = now
				emit(action)
			}

			!down && since != null -> pressedSince.remove(action)
		}
	}

	private fun emit(action: UiAction) {
		_status.update { it.copy(lastInput = action.name, inputCount = it.inputCount + 1) }
		_events.tryEmit(GamepadEvent.Action(action))
	}

	/** Steam passes its controller setup to SDL through environment variables, log them for diagnosis. */
	private fun logSteamEnvironment() {
		val keys = System.getenv().keys.filter { it.startsWith("SDL_") || it.startsWith("Steam") || it == "SteamAppId" }
		logger.info("Steam/SDL environment: " + keys.sorted().joinToString { "$it=${System.getenv(it).take(200)}" })
	}

	private fun loadSdl(): Sdl? {
		val candidates = buildList {
			System.getProperty("compose.application.resources.dir")?.let { add(File(it, "lib/libSDL2-2.0.so.0").path) }
			add("SDL2-2.0")
			add("SDL2")
			add("libSDL2-2.0.so.0")
		}
		for (candidate in candidates) {
			if (candidate.startsWith("/") && !File(candidate).exists()) continue
			try {
				return Native.load(candidate, Sdl::class.java).also { logger.info("Loaded SDL from $candidate") }
			} catch (_: UnsatisfiedLinkError) {
			}
		}
		logger.warn("SDL2 not found, gamepad navigation in the UI is unavailable (use the laser pointer or keyboard)")
		_status.update { it.copy(sdl = "SDL2 not found") }
		return null
	}

	override fun close() {
		running = false
	}

	private companion object {
		const val SDL_INIT_GAMECONTROLLER = 0x00002000

		// SDL_GameControllerButton
		const val BUTTON_A = 0
		const val BUTTON_B = 1
		const val BUTTON_DPAD_UP = 11
		const val BUTTON_DPAD_DOWN = 12
		const val BUTTON_DPAD_LEFT = 13
		const val BUTTON_DPAD_RIGHT = 14

		// SDL_GameControllerAxis
		const val AXIS_LEFTX = 0
		const val AXIS_LEFTY = 1

		// SDL hat bits
		const val HAT_UP = 1
		const val HAT_RIGHT = 2
		const val HAT_DOWN = 4
		const val HAT_LEFT = 8

		const val STICK_THRESHOLD = 0.6f
		const val POLL_INTERVAL_MS = 8L
		const val SCAN_INTERVAL_MS = 1_000L
		const val REPEAT_DELAY_MS = 380L
		const val REPEAT_INTERVAL_MS = 110L
	}
}
