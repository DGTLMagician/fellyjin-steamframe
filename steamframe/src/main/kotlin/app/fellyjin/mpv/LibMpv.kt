package app.fellyjin.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import app.fellyjin.appEnv
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Minimal JNA binding of the libmpv client API (client.h) and render API (render.h, render_gl.h).
 * Only the functions used by this app are declared.
 */
@Suppress("FunctionName")
interface LibMpv : Library {
	fun mpv_client_api_version(): Long
	fun mpv_error_string(error: Int): String

	fun mpv_create(): Pointer?
	fun mpv_initialize(ctx: Pointer): Int
	fun mpv_terminate_destroy(ctx: Pointer)
	fun mpv_wakeup(ctx: Pointer)

	fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
	fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
	fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
	fun mpv_free(data: Pointer)

	/** [args] must be terminated by a null entry. */
	fun mpv_command(ctx: Pointer, args: Array<String?>): Int
	fun mpv_command_string(ctx: Pointer, args: String): Int
	fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
	fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer

	fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
	fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, callbackCtx: Pointer?)
	fun mpv_render_context_update(ctx: Pointer): Long
	fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
	fun mpv_render_context_report_swap(ctx: Pointer)
	fun mpv_render_context_free(ctx: Pointer)

	/** `void (*mpv_render_update_fn)(void *cb_ctx)`, called from an mpv thread when a new frame can be rendered. */
	fun interface UpdateCallback : Callback {
		fun invoke(ctx: Pointer?)
	}

	/** `void *(*get_proc_address)(void *ctx, const char *name)` from render_gl.h. */
	fun interface GetProcAddress : Callback {
		fun invoke(ctx: Pointer?, name: String): Pointer?
	}

	companion object {
		// mpv_event_id
		const val EVENT_NONE = 0
		const val EVENT_SHUTDOWN = 1
		const val EVENT_START_FILE = 6
		const val EVENT_END_FILE = 7
		const val EVENT_FILE_LOADED = 8
		const val EVENT_VIDEO_RECONFIG = 17
		const val EVENT_PROPERTY_CHANGE = 22

		// mpv_format
		const val FORMAT_FLAG = 3

		// mpv_end_file_reason
		const val END_FILE_REASON_EOF = 0
		const val END_FILE_REASON_STOP = 2
		const val END_FILE_REASON_QUIT = 3
		const val END_FILE_REASON_ERROR = 4

		// mpv_render_param_type
		const val RENDER_PARAM_INVALID = 0
		const val RENDER_PARAM_API_TYPE = 1
		const val RENDER_PARAM_OPENGL_INIT_PARAMS = 2
		const val RENDER_PARAM_OPENGL_FBO = 3
		const val RENDER_PARAM_FLIP_Y = 4
		const val RENDER_PARAM_X11_DISPLAY = 8
		const val RENDER_PARAM_ADVANCED_CONTROL = 10
		const val RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12

		// mpv_render_update_flag
		const val RENDER_UPDATE_FRAME = 1L

		private val logger = LoggerFactory.getLogger(LibMpv::class.java)

		val instance: LibMpv by lazy { load() }

		val isAvailable: Boolean by lazy { runCatching { instance }.onFailure { logger.error("libmpv not available", it) }.isSuccess }

		private fun load(): LibMpv {
			val candidates = buildList {
				appEnv("MPV_LIBRARY")?.let(::add)
				// Library bundled next to the application by packaging/bundle-libmpv.sh
				System.getProperty("compose.application.resources.dir")?.let { add(File(it, "lib/libmpv.so.2").path) }
				System.getProperty("fellyjin.libdir")?.let { add(File(it, "libmpv.so.2").path) }
				add("mpv")
				add("libmpv.so.2")
				add("libmpv.so.1")
				add("/opt/homebrew/lib/libmpv.dylib")
			}

			var lastError: Throwable? = null
			for (candidate in candidates) {
				if (candidate.startsWith("/") && !File(candidate).exists()) continue
				try {
					val lib = Native.load(candidate, LibMpv::class.java)
					val version = lib.mpv_client_api_version()
					logger.info("Loaded libmpv from $candidate (client API ${version shr 16}.${version and 0xffff})")
					return lib
				} catch (err: UnsatisfiedLinkError) {
					lastError = err
				}
			}
			throw UnsatisfiedLinkError("Unable to load libmpv, tried $candidates").apply { initCause(lastError) }
		}
	}
}
