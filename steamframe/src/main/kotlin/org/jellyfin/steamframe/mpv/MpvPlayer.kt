package org.jellyfin.steamframe.mpv

import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import java.lang.ref.Reference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * libmpv refuses to start (mpv_create returns NULL) unless LC_NUMERIC is "C", because it parses numbers with the C
 * library. The JVM adopts the system locale at startup, so on e.g. a Dutch system LC_NUMERIC is nl_NL with a decimal
 * comma. Java's own formatting does not use the C locale, so switching it is safe.
 */
internal object CLocale {
	private interface LibC : Library {
		fun setlocale(category: Int, locale: String?): String?
	}

	private val libc by lazy { Native.load("c", LibC::class.java) }

	// LC_NUMERIC is 1 in glibc and 4 on macOS
	private val lcNumeric = if (Platform.isMac()) 4 else 1

	fun numeric(): String? = libc.setlocale(lcNumeric, null)

	@Synchronized
	fun ensureNumericC() {
		if (numeric() != "C") libc.setlocale(lcNumeric, "C")
	}
}

sealed interface MpvEvent {
	/** An event this app does not handle. */
	data object Other : MpvEvent
	data object Shutdown : MpvEvent
	data object StartFile : MpvEvent
	data object FileLoaded : MpvEvent
	data object VideoReconfig : MpvEvent
	data class EndFile(val reason: EndReason) : MpvEvent
}

enum class EndReason { EOF, STOP, QUIT, ERROR, OTHER }

/**
 * A single mpv instance. The client API functions are thread safe, the render functions must be called from the
 * thread that owns the OpenGL context passed to [createGlRenderContext].
 */
class MpvPlayer(options: Map<String, String>) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(MpvPlayer::class.java)
	private val mpv = LibMpv.instance
	private val handle: Pointer = run {
		CLocale.ensureNumericC()
		mpv.mpv_create() ?: error("mpv_create failed (LC_NUMERIC is ${CLocale.numeric()})")
	}

	@Volatile
	private var renderContext: Pointer? = null
	// Kept as fields so the JNA callbacks are not garbage collected while mpv may call them
	private var getProcAddress: LibMpv.GetProcAddress? = null
	private var updateCallback: LibMpv.UpdateCallback? = null
	private var closed = false

	init {
		for ((name, value) in options) {
			val result = mpv.mpv_set_option_string(handle, name, value)
			if (result < 0) logger.warn("Unable to set mpv option $name=$value: ${mpv.mpv_error_string(result)}")
		}
		check(mpv.mpv_initialize(handle), "mpv_initialize")
	}

	fun command(vararg args: String) {
		val result = mpv.mpv_command(handle, arrayOf(*args, null))
		if (result < 0) logger.warn("mpv command ${args.joinToString(" ")} failed: ${mpv.mpv_error_string(result)}")
	}

	/** Run a command in input.conf syntax, which supports prefixes like `osd-msg-bar`. */
	fun commandString(command: String) {
		val result = mpv.mpv_command_string(handle, command)
		if (result < 0) logger.warn("mpv command $command failed: ${mpv.mpv_error_string(result)}")
	}

	fun setProperty(name: String, value: String) {
		val result = mpv.mpv_set_property_string(handle, name, value)
		if (result < 0) logger.warn("Unable to set mpv property $name=$value: ${mpv.mpv_error_string(result)}")
	}

	/** Set a property without logging failures, returns whether mpv accepted it (e.g. options renamed between versions). */
	fun trySetProperty(name: String, value: String): Boolean = mpv.mpv_set_property_string(handle, name, value) >= 0

	fun getProperty(name: String): String? {
		val ptr = mpv.mpv_get_property_string(handle, name) ?: return null
		return try {
			ptr.getString(0, "UTF-8")
		} finally {
			mpv.mpv_free(ptr)
		}
	}

	val position: Duration? get() = getProperty("time-pos")?.toDoubleOrNull()?.seconds
	val duration: Duration? get() = getProperty("duration")?.toDoubleOrNull()?.seconds
	val isPaused: Boolean get() = getProperty("pause") == "yes"
	val volume: Int get() = getProperty("volume")?.toDoubleOrNull()?.toInt() ?: 100
	val isMuted: Boolean get() = getProperty("mute") == "yes"

	/** Video size after rotation and aspect correction, null until the video output is configured. */
	val videoSize: Pair<Int, Int>?
		get() {
			val width = getProperty("dwidth")?.toIntOrNull() ?: return null
			val height = getProperty("dheight")?.toIntOrNull() ?: return null
			return if (width > 0 && height > 0) width to height else null
		}

	fun load(url: String, start: Duration?, title: String?) {
		setProperty("start", start?.let { "%.3f".format(java.util.Locale.ROOT, it.inWholeMilliseconds / 1000.0) } ?: "none")
		setProperty("force-media-title", title ?: "")
		command("loadfile", url, "replace")
	}

	fun addSubtitle(url: String, title: String?, language: String?) {
		// Add without selecting, mpv chooses the track based on the slang/sid options
		command("sub-add", url, "auto", title ?: "", language ?: "")
	}

	/** Poll a single event, returns null when the queue is empty after waiting [timeoutSeconds]. */
	fun pollEvent(timeoutSeconds: Double): MpvEvent? {
		val event = mpv.mpv_wait_event(handle, timeoutSeconds)
		return when (event.getInt(0)) {
			LibMpv.EVENT_NONE -> null
			LibMpv.EVENT_SHUTDOWN -> MpvEvent.Shutdown
			LibMpv.EVENT_START_FILE -> MpvEvent.StartFile
			LibMpv.EVENT_FILE_LOADED -> MpvEvent.FileLoaded
			LibMpv.EVENT_VIDEO_RECONFIG -> MpvEvent.VideoReconfig
			LibMpv.EVENT_END_FILE -> {
				// struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
				val data = event.getPointer(16)
				val reason = when (data?.getInt(0)) {
					LibMpv.END_FILE_REASON_EOF -> EndReason.EOF
					LibMpv.END_FILE_REASON_STOP -> EndReason.STOP
					LibMpv.END_FILE_REASON_QUIT -> EndReason.QUIT
					LibMpv.END_FILE_REASON_ERROR -> EndReason.ERROR
					else -> EndReason.OTHER
				}
				MpvEvent.EndFile(reason)
			}

			else -> MpvEvent.Other
		}
	}

	fun wakeup() = mpv.mpv_wakeup(handle)

	// Render API

	/**
	 * Create an OpenGL render context, requires `vo=libmpv` and a current OpenGL context on the calling thread.
	 */
	fun createGlRenderContext(procAddress: (String) -> Long, x11Display: Long?, onUpdate: (() -> Unit)? = null) {
		check(renderContext == null) { "Render context already created" }

		val callback = LibMpv.GetProcAddress { _, name -> procAddress(name).takeIf { it != 0L }?.let(::Pointer) }
		getProcAddress = callback

		val apiType = Memory(16).apply { setString(0, "opengl", "UTF-8") }
		// struct mpv_opengl_init_params { void *(*get_proc_address)(void *, const char *); void *get_proc_address_ctx; }
		val initParams = Memory(2L * Native.POINTER_SIZE).apply {
			setPointer(0, CallbackReference.getFunctionPointer(callback))
			setPointer(Native.POINTER_SIZE.toLong(), Pointer.NULL)
		}
		val advancedControl = intPointer(0)

		val params = renderParams(
			LibMpv.RENDER_PARAM_API_TYPE to apiType,
			LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS to initParams,
			LibMpv.RENDER_PARAM_ADVANCED_CONTROL to advancedControl,
			LibMpv.RENDER_PARAM_X11_DISPLAY to x11Display?.takeIf { it != 0L }?.let(::Pointer),
		)

		val result = PointerByReference()
		check(mpv.mpv_render_context_create(result, handle, params), "mpv_render_context_create")
		renderContext = result.value
		if (onUpdate != null) {
			// Only signals the render thread, mpv forbids calling its API from inside this callback
			val update = LibMpv.UpdateCallback { onUpdate() }
			updateCallback = update
			mpv.mpv_render_context_set_update_callback(result.value, update, null)
		}
		// The params only hold raw addresses, keep the backing memory alive until mpv copied them
		Reference.reachabilityFence(apiType)
		Reference.reachabilityFence(initParams)
		Reference.reachabilityFence(advancedControl)
	}

	/** Returns true when mpv has a new video frame that should be rendered. */
	fun renderUpdateAvailable(): Boolean {
		val ctx = renderContext ?: return false
		return mpv.mpv_render_context_update(ctx) and LibMpv.RENDER_UPDATE_FRAME != 0L
	}

	private val fboStruct = Memory(16)
	private val flipY = intPointer(1)
	private val blockForTargetTime = intPointer(0)

	/**
	 * Render the current frame into the OpenGL framebuffer [fbo] of the given size. With [blockForTargetTime] mpv
	 * waits until the frame is due, which gives the most even frame pacing on a dedicated render thread.
	 */
	fun render(fbo: Int, width: Int, height: Int, flip: Boolean, blockForTargetTime: Boolean = false) {
		val ctx = renderContext ?: return
		// struct mpv_opengl_fbo { int fbo; int w; int h; int internal_format; }
		fboStruct.setInt(0, fbo)
		fboStruct.setInt(4, width)
		fboStruct.setInt(8, height)
		fboStruct.setInt(12, 0)
		flipY.setInt(0, if (flip) 1 else 0)
		this.blockForTargetTime.setInt(0, if (blockForTargetTime) 1 else 0)

		val params = renderParams(
			LibMpv.RENDER_PARAM_OPENGL_FBO to fboStruct,
			LibMpv.RENDER_PARAM_FLIP_Y to flipY,
			LibMpv.RENDER_PARAM_BLOCK_FOR_TARGET_TIME to this.blockForTargetTime,
		)
		mpv.mpv_render_context_render(ctx, params)
	}

	fun reportSwap() {
		renderContext?.let(mpv::mpv_render_context_report_swap)
	}

	/** Free the render context, must be called on the OpenGL thread before the context is destroyed. */
	fun freeRenderContext() {
		renderContext?.let {
			mpv.mpv_render_context_set_update_callback(it, null, null)
			mpv.mpv_render_context_free(it)
		}
		renderContext = null
		updateCallback = null
	}

	override fun close() {
		if (closed) return
		closed = true
		freeRenderContext()
		mpv.mpv_terminate_destroy(handle)
		getProcAddress = null
	}

	private fun check(result: Int, name: String) {
		if (result < 0) error("$name failed: ${mpv.mpv_error_string(result)}")
	}

	private fun intPointer(value: Int) = Memory(4).apply { setInt(0, value) }

	/** Build a zero terminated `mpv_render_param[]`, entries with a null value are skipped. */
	private fun renderParams(vararg entries: Pair<Int, Pointer?>): Memory {
		val present = entries.filter { it.second != null }
		val entrySize = 2L * Native.POINTER_SIZE // int type (padded) + void *data
		val memory = Memory(entrySize * (present.size + 1))
		memory.clear()
		present.forEachIndexed { index, (type, data) ->
			memory.setInt(index * entrySize, type)
			memory.setPointer(index * entrySize + Native.POINTER_SIZE, data)
		}
		// Terminator: MPV_RENDER_PARAM_INVALID with null data, already zeroed by clear()
		return memory
	}
}
