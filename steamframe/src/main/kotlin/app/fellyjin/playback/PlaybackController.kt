package app.fellyjin.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import app.fellyjin.BuildInfo
import app.fellyjin.data.ConfigStore
import app.fellyjin.data.JellyfinRepository
import app.fellyjin.data.PlaybackMode
import app.fellyjin.data.resolveHwdec
import app.fellyjin.mpv.EndReason
import app.fellyjin.mpv.LibMpv
import app.fellyjin.mpv.MpvEvent
import app.fellyjin.mpv.MpvPlayer
import app.fellyjin.input.UiAction
import app.fellyjin.vr.VrDiagnostics
import app.fellyjin.vr.XrCinemaOutput
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed interface PlaybackState {
	data object Idle : PlaybackState
	data class Starting(val item: BaseItemDto) : PlaybackState
	data class Playing(val item: BaseItemDto, val output: String, val notice: String? = null) : PlaybackState
	data class Failed(val message: String) : PlaybackState
}

/**
 * Runs playback on a dedicated thread. The VR output owns an OpenGL context and the OpenXR frame loop, both bound
 * to that thread, which also keeps the Compose UI responsive while playing.
 */
class PlaybackController(
	private val repository: JellyfinRepository,
	private val configStore: ConfigStore,
) {
	private val logger = LoggerFactory.getLogger(PlaybackController::class.java)
	private val api: ApiClient get() = repository.api
	private val resolver by lazy { StreamResolver(repository.api) }
	private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
	val state: StateFlow<PlaybackState> = _state.asStateFlow()

	@Volatile
	private var thread: Thread? = null

	@Volatile
	private var stopRequested = false

	@Volatile
	private var activePlayer: MpvPlayer? = null

	val isActive: Boolean get() = thread?.isAlive == true

	/** Surface in the app window for windowed playback, set once the window exists. */
	@Volatile
	var videoHost: VideoHost? = null

	/** True while playing in a window, where the app's controls drive mpv (VR has its own controller input). */
	@Volatile
	var windowedPlayback = false
		private set

	/** Handle a controller/keyboard action during windowed playback. Returns false when not playing in a window. */
	fun handle(action: UiAction): Boolean {
		val player = activePlayer ?: return false
		if (!windowedPlayback) return false
		when (action) {
			UiAction.ACTIVATE -> {
				player.commandString("cycle pause")
				player.commandString("show-progress")
			}
			UiAction.BACK -> stop()
			UiAction.LEFT -> player.commandString("osd-msg-bar seek -10")
			UiAction.RIGHT -> player.commandString("osd-msg-bar seek 30")
			UiAction.UP -> player.commandString("osd-msg-bar add volume 5")
			UiAction.DOWN -> player.commandString("osd-msg-bar add volume -5")
		}
		return true
	}

	/**
	 * Show the cinema with a colorful generated test video, without a server: lets users try the theater and screen
	 * sizes, and shows right away whether VR starts on this device.
	 */
	fun preview() = play(PREVIEW_ITEM, resume = false)

	fun play(item: BaseItemDto, resume: Boolean, tracks: TrackSelection = TrackSelection()) {
		if (isActive) {
			logger.warn("Playback already active, ignoring request for ${item.name}")
			return
		}
		val start = if (resume) item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.ticks else null

		stopRequested = false
		_state.value = PlaybackState.Starting(item)
		thread = Thread({ runPlayback(item, start, tracks) }, "fellyjin-playback").apply {
			isDaemon = true
			start()
		}
	}

	fun stop() {
		stopRequested = true
		activePlayer?.wakeup()
	}

	fun dismissError() {
		if (_state.value is PlaybackState.Failed) _state.value = PlaybackState.Idle
	}

	private fun runPlayback(item: BaseItemDto, start: Duration?, tracks: TrackSelection) {
		if (!LibMpv.isAvailable) {
			_state.value = PlaybackState.Failed("libmpv could not be loaded. Install mpv or use the bundled build.")
			return
		}

		val settings = configStore.current
		var notice: String? = null
		val output: VideoOutput = when (settings.playback.mode) {
			PlaybackMode.VR_CINEMA -> try {
				XrCinemaOutput(settings.cinema)
			} catch (err: Throwable) {
				logger.warn("Unable to start the VR cinema, falling back to a flat window", err)
				VrDiagnostics.failed(err.message ?: err.toString())
				notice = "VR cinema unavailable: ${err.message}. Playing in a window instead, see Settings → VR status."
				FlatWindowOutput(videoHost)
			}

			PlaybackMode.FLAT -> FlatWindowOutput(videoHost)
		}

		var player: MpvPlayer? = null
		try {
			player = createPlayer(output)
			activePlayer = player
			windowedPlayback = output is FlatWindowOutput
			output.attach(player)

			var stream = if (item.id == PREVIEW_ITEM.id) previewStream()
			else runBlocking { resolver.resolve(item, start, settings.playback.maxBitrateMbps, tracks) }
			load(player, output, stream)
			_state.value = PlaybackState.Playing(stream.item, output.name, notice)

			val session = ReportingSession(stream)
			var lastPoll = TimeSource.Monotonic.markNow()
			var running = true

			while (running) {
				// Leave directly instead of waiting for mpv's END_FILE, which never comes when nothing is loaded yet
				if (stopRequested || !output.frame(player)) {
					session.poll(player, report = false)
					session.stop(failed = false, ended = false)
					break
				}

				// Drain the mpv event queue
				var timeout = output.eventTimeoutSeconds
				while (running) {
					val event = player.pollEvent(timeout) ?: break
					timeout = 0.0
					when (event) {
						MpvEvent.FileLoaded -> {
							if (settings.playback.showStats) player.commandString("script-binding stats/display-stats-toggle")
							stream.externalSubtitles.forEach { player.addSubtitle(it.url, it.title, it.language, select = it.selected) }
							output.onStreamLoaded(player, stream)
							player.command("show-text", "\${media-title}", "3000")
							session.start(player)
						}

						MpvEvent.VideoReconfig -> output.onVideoReconfig(player)

						is MpvEvent.EndFile -> {
							session.stop(failed = event.reason == EndReason.ERROR, ended = event.reason == EndReason.EOF)
							val next = if (event.reason == EndReason.EOF) nextItem(stream.item) else null
							when {
								next != null -> {
									stream = runBlocking { resolver.resolve(next, null, settings.playback.maxBitrateMbps) }
									session.reset(stream)
									load(player, output, stream)
									_state.value = PlaybackState.Playing(stream.item, output.name, notice)
								}

								event.reason == EndReason.ERROR -> {
									_state.value = PlaybackState.Failed("Unable to play ${stream.item.name}")
									running = false
								}

								else -> running = false
							}
						}

						MpvEvent.Shutdown -> {
							session.stop(failed = false, ended = false)
							running = false
						}

						else -> Unit
					}
				}

				if (lastPoll.elapsedNow() >= 1.seconds) {
					lastPoll = TimeSource.Monotonic.markNow()
					session.poll(player)
				}
			}
		} catch (err: Throwable) {
			logger.error("Playback failed", err)
			_state.value = PlaybackState.Failed(err.message ?: err.toString())
		} finally {
			activePlayer = null
			windowedPlayback = false
			if (output is FlatWindowOutput) {
				// mpv draws into a child of the video surface: destroy mpv first, removing the surface while mpv still
				// uses it is an X11 error, which ends the whole process
				player?.close()
				player?.let { runCatching { output.close(it) }.onFailure { err -> logger.warn("Failed to close output", err) } }
			} else {
				// The VR output frees mpv's render context, which must happen before mpv is destroyed
				player?.let { runCatching { output.close(it) }.onFailure { err -> logger.warn("Failed to close output", err) } }
				player?.close()
			}
			if (_state.value !is PlaybackState.Failed) _state.value = PlaybackState.Idle
			thread = null
		}
	}

	/** Create mpv, trying the window output's GPU contexts in order when one can't open the video output. */
	private fun createPlayer(output: VideoOutput): MpvPlayer {
		while (true) {
			try {
				return MpvPlayer(baseOptions() + output.mpvOptions()).also {
					(output as? FlatWindowOutput)?.gpuContext?.let { context -> logger.info("Window video output uses $context") }
				}
			} catch (err: IllegalStateException) {
				if ((output as? FlatWindowOutput)?.fallBack() != true) throw err
			}
		}
	}

	private fun load(player: MpvPlayer, output: VideoOutput, stream: ResolvedStream) {
		logger.info("Playing ${stream.item.name} using ${stream.playMethod} on ${output.name}")
		// Track choices made on the detail page, mapped to mpv's per type track numbers
		player.setProperty("aid", stream.mpvAudioTrack?.toString() ?: "auto")
		player.setProperty("sid", when (val sid = stream.mpvSubtitleTrack) {
			null -> "auto"
			0 -> "no"
			else -> sid.toString()
		})
		player.load(stream.url, stream.startPosition, displayTitle(stream.item))
	}

	private fun nextItem(item: BaseItemDto): BaseItemDto? {
		if (item.type != BaseItemKind.EPISODE || !configStore.current.playback.autoPlayNextEpisode) return null
		return runCatching { runBlocking { repository.nextEpisode(item) } }
			.onFailure { logger.warn("Unable to fetch next episode", it) }
			.getOrNull()
	}

	private fun baseOptions(): Map<String, String> {
		val playback = configStore.current.playback
		val configDir = ConfigStore.defaultConfigFile().parent.resolve("mpv")
		return mapOf(
			// Users can tweak mpv with ~/.config/fellyjin-steamframe/mpv/mpv.conf
			"config" to "yes",
			"config-dir" to configDir.toString(),
			"terminal" to "no",
			"msg-level" to "all=warn",
			"idle" to "yes",
			"keep-open" to "no",
			"ytdl" to "no",
			"hwdec" to resolveHwdec(playback.hwdec),
			// Decode with all cores when software decoding is needed, and skip strict but slow decoder checks
			"vd-lavc-threads" to "0",
			"vd-lavc-fast" to "yes",
			// 4K remuxes run at 60-100 Mbps: buffer generously so Wi-Fi dips don't stall playback
			"cache" to "yes",
			"cache-secs" to "60",
			"demuxer-readahead-secs" to "30",
			"demuxer-max-bytes" to "1GiB",
			"demuxer-max-back-bytes" to "256MiB",
			"network-timeout" to "30",
			"user-agent" to BuildInfo.CLIENT_NAME,
			"osd-bar-h" to "2.5",
			"osd-font-size" to "42",
			"sub-auto" to "no",
		)
	}

	private fun displayTitle(item: BaseItemDto): String = when (item.type) {
		BaseItemKind.EPISODE -> buildString {
			item.seriesName?.let { append(it).append(" - ") }
			if (item.parentIndexNumber != null && item.indexNumber != null) {
				append("S%02dE%02d ".format(item.parentIndexNumber, item.indexNumber))
			}
			append(item.name.orEmpty())
		}

		else -> item.name.orEmpty()
	}

	private fun previewStream() = ResolvedStream(
		item = PREVIEW_ITEM,
		url = PREVIEW_URL,
		playSessionId = null,
		mediaSourceId = null,
		playMethod = PlayMethod.DIRECT_PLAY,
		externalSubtitles = emptyList(),
		startPosition = null,
		stereoLayout = StereoLayout.MONO,
	)

	/** Reports the playback state of a single stream to the server. */
	private inner class ReportingSession(private var stream: ResolvedStream) {
		private var started = false
		private var position: Duration = stream.startPosition ?: Duration.ZERO
		private var duration: Duration? = null
		private var paused = false
		private var lastReport = TimeSource.Monotonic.markNow()

		fun reset(newStream: ResolvedStream) {
			stream = newStream
			started = false
			position = newStream.startPosition ?: Duration.ZERO
			duration = null
			paused = false
		}

		fun start(player: MpvPlayer) {
			if (started) return
			started = true
			poll(player, report = false)
			val info = PlaybackStartInfo(
				itemId = stream.item.id,
				mediaSourceId = stream.mediaSourceId,
				playSessionId = stream.playSessionId,
				canSeek = true,
				isPaused = paused,
				isMuted = player.isMuted,
				volumeLevel = player.volume,
				positionTicks = position.inWholeTicks,
				playMethod = stream.playMethod,
				repeatMode = RepeatMode.REPEAT_NONE,
				playbackOrder = PlaybackOrder.DEFAULT,
			)
			report("start") { api.sessionApi.reportPlaybackStart(info) }
			lastReport = TimeSource.Monotonic.markNow()
		}

		fun poll(player: MpvPlayer, report: Boolean = true) {
			player.position?.let { position = it }
			player.duration?.let { duration = it }
			val nowPaused = player.isPaused
			val pauseChanged = nowPaused != paused
			paused = nowPaused

			if (report && started && (pauseChanged || lastReport.elapsedNow() >= 10.seconds)) {
				lastReport = TimeSource.Monotonic.markNow()
				val info = PlaybackProgressInfo(
					itemId = stream.item.id,
					mediaSourceId = stream.mediaSourceId,
					playSessionId = stream.playSessionId,
					canSeek = true,
					isPaused = paused,
					isMuted = player.isMuted,
					volumeLevel = player.volume,
					positionTicks = position.inWholeTicks,
					playMethod = stream.playMethod,
					repeatMode = RepeatMode.REPEAT_NONE,
					playbackOrder = PlaybackOrder.DEFAULT,
				)
				report("progress") { api.sessionApi.reportPlaybackProgress(info) }
			}
		}

		fun stop(failed: Boolean, ended: Boolean) {
			if (!started) return
			started = false
			// When the file ended naturally report the full runtime so the server marks the item as played
			val finalPosition = if (ended) duration ?: position else position
			val info = PlaybackStopInfo(
				itemId = stream.item.id,
				mediaSourceId = stream.mediaSourceId,
				playSessionId = stream.playSessionId,
				positionTicks = finalPosition.inWholeTicks,
				failed = failed,
			)
			report("stop") { api.sessionApi.reportPlaybackStopped(info) }
		}

		private fun report(name: String, block: suspend () -> Unit) {
			if (stream.item.id == PREVIEW_ITEM.id) return
			reportScope.launch {
				runCatching { block() }.onFailure { logger.warn("Failed to report playback $name", it) }
			}
		}
	}
}

/** Audio and subtitle streams chosen on the detail page (Jellyfin stream indexes), null for the defaults. */
data class TrackSelection(
	val audioStreamIndex: Int? = null,
	/** [SUBTITLES_OFF] turns subtitles off. */
	val subtitleStreamIndex: Int? = null,
) {
	companion object {
		const val SUBTITLES_OFF = -1
	}
}

private val PREVIEW_ITEM = BaseItemDto(
	id = UUID(0, 0x5f1e11),
	name = "Cinema preview",
	type = BaseItemKind.VIDEO,
)

// A colorful moving test pattern with a slowly cycling hue, so the screen visibly lights the theater
internal const val PREVIEW_URL = "av://lavfi:testsrc2=size=1920x1080:rate=24:duration=600,hue=H=2*PI*t/12:s=1.4"
