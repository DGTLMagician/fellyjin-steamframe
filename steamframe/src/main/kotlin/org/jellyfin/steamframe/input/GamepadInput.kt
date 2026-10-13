package org.jellyfin.steamframe.input

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.awt.Component
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.KeyEvent
import java.io.File
import kotlin.math.abs

/**
 * Lets the default Steam Frame (Steam Input gamepad) controls drive the UI. AWT does not read gamepads, so this polls
 * them through SDL2 (bundled with libmpv) and translates them into the keys the UI already handles:
 *
 * - D-pad / left stick: arrow keys (focus navigation, with key repeat)
 * - A: Enter (activate), B: Escape (back)
 *
 * All SDL calls happen on one dedicated thread.
 */
class GamepadInput(private val window: Window) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(GamepadInput::class.java)

	@Volatile
	private var running = true
	private val thread = Thread(::run, "gamepad-input").apply { isDaemon = true }

	@Suppress("FunctionName")
	private interface Sdl : Library {
		fun SDL_SetHint(name: String, value: String): Boolean
		fun SDL_InitSubSystem(flags: Int): Int
		fun SDL_GetError(): String
		fun SDL_PumpEvents()
		fun SDL_NumJoysticks(): Int
		fun SDL_IsGameController(index: Int): Boolean
		fun SDL_GameControllerOpen(index: Int): Pointer?
		fun SDL_GameControllerGetAttached(controller: Pointer): Boolean
		fun SDL_GameControllerClose(controller: Pointer)
		fun SDL_GameControllerName(controller: Pointer): String?
		fun SDL_GameControllerGetButton(controller: Pointer, button: Int): Byte
		fun SDL_GameControllerGetAxis(controller: Pointer, axis: Int): Short
	}

	private enum class UiKey(val keyCode: Int) {
		UP(KeyEvent.VK_UP),
		DOWN(KeyEvent.VK_DOWN),
		LEFT(KeyEvent.VK_LEFT),
		RIGHT(KeyEvent.VK_RIGHT),
		ACTIVATE(KeyEvent.VK_ENTER),
		BACK(KeyEvent.VK_ESCAPE),
	}

	/** Key repeat state per key: when it went down and when it last fired. */
	private val pressedSince = mutableMapOf<UiKey, Long>()
	private val lastRepeat = mutableMapOf<UiKey, Long>()

	fun start() = thread.start()

	private fun run() {
		val sdl = loadSdl() ?: return
		// Keep reading the controller when the window is not focused (gamescope focus can lag behind)
		sdl.SDL_SetHint("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1")
		if (sdl.SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {
			logger.warn("SDL game controller init failed: ${sdl.SDL_GetError()}")
			return
		}

		var controller: Pointer? = null
		while (running) {
			sdl.SDL_PumpEvents()

			if (controller == null || !sdl.SDL_GameControllerGetAttached(controller)) {
				controller?.let(sdl::SDL_GameControllerClose)
				controller = (0 until sdl.SDL_NumJoysticks())
					.firstOrNull { sdl.SDL_IsGameController(it) }
					?.let(sdl::SDL_GameControllerOpen)
				controller?.let { logger.info("Using controller ${sdl.SDL_GameControllerName(it)} for the UI") }
			}

			val active = controller
			if (active != null) {
				fun button(id: Int) = sdl.SDL_GameControllerGetButton(active, id).toInt() != 0
				val x = sdl.SDL_GameControllerGetAxis(active, AXIS_LEFTX) / 32768f
				val y = sdl.SDL_GameControllerGetAxis(active, AXIS_LEFTY) / 32768f
				val stickX = if (abs(x) > STICK_THRESHOLD && abs(x) > abs(y)) x else 0f
				val stickY = if (abs(y) > STICK_THRESHOLD && abs(y) >= abs(x)) y else 0f

				update(UiKey.UP, button(BUTTON_DPAD_UP) || stickY < 0, repeat = true)
				update(UiKey.DOWN, button(BUTTON_DPAD_DOWN) || stickY > 0, repeat = true)
				update(UiKey.LEFT, button(BUTTON_DPAD_LEFT) || stickX < 0, repeat = true)
				update(UiKey.RIGHT, button(BUTTON_DPAD_RIGHT) || stickX > 0, repeat = true)
				update(UiKey.ACTIVATE, button(BUTTON_A), repeat = false)
				update(UiKey.BACK, button(BUTTON_B), repeat = false)
			}

			Thread.sleep(POLL_INTERVAL_MS)
		}
		controller?.let(sdl::SDL_GameControllerClose)
	}

	private fun update(key: UiKey, down: Boolean, repeat: Boolean) {
		val now = System.currentTimeMillis()
		val since = pressedSince[key]
		when {
			down && since == null -> {
				pressedSince[key] = now
				lastRepeat[key] = now
				send(key, KeyEvent.KEY_PRESSED)
				if (repeat) send(key, KeyEvent.KEY_RELEASED)
			}

			down && repeat && since != null && now - since >= REPEAT_DELAY_MS && now - lastRepeat.getValue(key) >= REPEAT_INTERVAL_MS -> {
				lastRepeat[key] = now
				send(key, KeyEvent.KEY_PRESSED)
				send(key, KeyEvent.KEY_RELEASED)
			}

			!down && since != null -> {
				pressedSince.remove(key)
				// Activate/back fire their release on button up, buttons click on key release in Compose
				if (!repeat) send(key, KeyEvent.KEY_RELEASED)
			}
		}
	}

	private fun send(key: UiKey, id: Int) {
		EventQueue.invokeLater {
			if (!window.isShowing) return@invokeLater
			val target: Component = window.focusOwner ?: window.mostRecentFocusOwner ?: window
			target.dispatchEvent(KeyEvent(target, id, System.currentTimeMillis(), 0, key.keyCode, KeyEvent.CHAR_UNDEFINED))
		}
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

		const val STICK_THRESHOLD = 0.6f
		const val POLL_INTERVAL_MS = 8L
		const val REPEAT_DELAY_MS = 380L
		const val REPEAT_INTERVAL_MS = 110L
	}
}
