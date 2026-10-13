package org.jellyfin.steamframe.playback

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
import org.jellyfin.steamframe.data.ConfigStore
import org.jellyfin.steamframe.data.JellyfinRepository
import org.jellyfin.steamframe.data.PlaybackMode
import org.jellyfin.steamframe.mpv.EndReason
import org.jellyfin.steamframe.mpv.LibMpv
import org.jellyfin.steamframe.mpv.MpvEvent
import org.jellyfin.steamframe.mpv.MpvPlayer
import org.jellyfin.steamframe.vr.XrCinemaOutput
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

	fun play(item: BaseItemDto, resume: Boolean) {
		if (isActive) {
			logger.warn("Playback already active, ignoring request for ${item.name}")
			return
		}
		val start = if (resume) item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.ticks else null

		stopRequested = false
		_state.value = PlaybackState.Starting(item)
		thread = Thread({ runPlayback(item, start) }, "jellyfin-playback").apply {
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

	private fun runPlayback(item: BaseItemDto, start: Duration?) {
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
				notice = "VR cinema unavailable (${err.message}), playing in a window"
				FlatWindowOutput()
			}

			PlaybackMode.FLAT -> FlatWindowOutput()
		}

		var player: MpvPlayer? = null
		try {
			player = MpvPlayer(baseOptions() + output.mpvOptions())
			activePlayer = player
			output.attach(player)

			var stream = runBlocking { resolver.resolve(item, start, settings.playback.maxBitrateMbps) }
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
							stream.externalSubtitles.forEach { player.addSubtitle(it.url, it.title, it.language) }
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
			player?.let { runCatching { output.close(it) }.onFailure { err -> logger.warn("Failed to close output", err) } }
			player?.close()
			if (_state.value !is PlaybackState.Failed) _state.value = PlaybackState.Idle
			thread = null
		}
	}

	private fun load(player: MpvPlayer, output: VideoOutput, stream: ResolvedStream) {
		logger.info("Playing ${stream.item.name} using ${stream.playMethod} on ${output.name}")
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
			// Users can tweak mpv with ~/.config/jellyfin-steamframe/mpv/mpv.conf
			"config" to "yes",
			"config-dir" to configDir.toString(),
			"terminal" to "no",
			"msg-level" to "all=warn",
			"idle" to "yes",
			"keep-open" to "no",
			"ytdl" to "no",
			"hwdec" to playback.hwdec,
			"cache" to "yes",
			"demuxer-max-bytes" to "512MiB",
			"demuxer-max-back-bytes" to "128MiB",
			"user-agent" to "Jellyfin Steam Frame",
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
			reportScope.launch {
				runCatching { block() }.onFailure { logger.warn("Failed to report playback $name", it) }
			}
		}
	}
}
