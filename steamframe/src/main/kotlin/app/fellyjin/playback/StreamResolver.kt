package app.fellyjin.playback

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.Video3dFormat
import org.jellyfin.sdk.model.extensions.inWholeTicks
import kotlin.time.Duration

data class ExternalSubtitle(val url: String, val title: String?, val language: String?)

data class ResolvedStream(
	val item: BaseItemDto,
	val url: String,
	val playSessionId: String?,
	val mediaSourceId: String?,
	val playMethod: PlayMethod,
	val externalSubtitles: List<ExternalSubtitle>,
	val startPosition: Duration?,
	val stereoLayout: StereoLayout,
)

/** How a stereoscopic (3D) video is packed into a single frame. */
enum class StereoLayout { MONO, SIDE_BY_SIDE_HALF, SIDE_BY_SIDE_FULL, TOP_BOTTOM_HALF, TOP_BOTTOM_FULL }

/**
 * Resolves Jellyfin items to playable URLs. mpv (ffmpeg) can play practically every container and codec, so the
 * device profile allows direct play for everything and only asks for a transcode when the bitrate is too high.
 */
class StreamResolver(private val api: ApiClient) {
	private fun deviceProfile(maxBitrate: Int) = DeviceProfile(
		name = "Fellyjin Steam Frame (mpv)",
		maxStreamingBitrate = maxBitrate,
		maxStaticBitrate = maxBitrate,
		// An empty container or codec matches everything
		directPlayProfiles = listOf(
			DirectPlayProfile(container = "", type = DlnaProfileType.VIDEO),
			DirectPlayProfile(container = "", type = DlnaProfileType.AUDIO),
		),
		transcodingProfiles = listOf(
			TranscodingProfile(
				container = "mp4",
				type = DlnaProfileType.VIDEO,
				videoCodec = "hevc,h264",
				audioCodec = "aac,ac3,eac3,opus,flac",
				protocol = MediaStreamProtocol.HLS,
				context = EncodingContext.STREAMING,
				maxAudioChannels = "8",
				minSegments = 1,
				breakOnNonKeyFrames = true,
				conditions = emptyList(),
			),
		),
		containerProfiles = emptyList(),
		codecProfiles = emptyList(),
		subtitleProfiles = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub", "sub", "smi", "ttml")
			.flatMap { format ->
				listOf(
					SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED),
					SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL),
				)
			},
	)

	suspend fun resolve(item: BaseItemDto, startPosition: Duration?, maxBitrateMbps: Int): ResolvedStream {
		val maxBitrate = maxBitrateMbps.coerceAtLeast(1) * 1_000_000
		val response by api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				userId = null,
				maxStreamingBitrate = maxBitrate,
				startTimeTicks = startPosition?.inWholeTicks,
				deviceProfile = deviceProfile(maxBitrate),
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = true,
			),
		)

		response.errorCode?.let { error("The server refused playback: $it") }

		val source = response.mediaSources.firstOrNull { it.protocol == MediaProtocol.FILE || it.protocol == MediaProtocol.HTTP }
			?: error("No playable media source for ${item.name}")

		val (url, method) = when {
			source.supportsDirectPlay || (source.supportsDirectStream && source.transcodingUrl == null) -> {
				val url = api.videoApi.getVideoStreamUrl(
					itemId = item.id,
					container = source.container,
					mediaSourceId = source.id,
					static = true,
					tag = source.eTag,
					liveStreamId = source.liveStreamId,
				)
				url.withAccessToken() to PlayMethod.DIRECT_PLAY
			}

			source.transcodingUrl != null -> {
				val url = api.createUrl(source.transcodingUrl!!, ignorePathParameters = true)
				val method = if (source.supportsDirectStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
				url.withAccessToken() to method
			}

			else -> error("The server did not offer a playable stream for ${item.name}")
		}

		val externalSubtitles = source.mediaStreams.orEmpty()
			.filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL }
			.mapNotNull { stream ->
				val deliveryUrl = stream.deliveryUrl ?: return@mapNotNull null
				val subtitleUrl = if (stream.isExternalUrl == true) deliveryUrl
				else api.createUrl(deliveryUrl, ignorePathParameters = true).withAccessToken()
				ExternalSubtitle(subtitleUrl, stream.displayTitle ?: stream.title, stream.language)
			}

		return ResolvedStream(
			item = item,
			url = url,
			playSessionId = response.playSessionId,
			mediaSourceId = source.id,
			playMethod = method,
			externalSubtitles = externalSubtitles,
			startPosition = startPosition,
			stereoLayout = when (item.video3dFormat) {
				Video3dFormat.HALF_SIDE_BY_SIDE -> StereoLayout.SIDE_BY_SIDE_HALF
				Video3dFormat.FULL_SIDE_BY_SIDE -> StereoLayout.SIDE_BY_SIDE_FULL
				Video3dFormat.HALF_TOP_AND_BOTTOM -> StereoLayout.TOP_BOTTOM_HALF
				Video3dFormat.FULL_TOP_AND_BOTTOM -> StereoLayout.TOP_BOTTOM_FULL
				// MVC needs a decoder mpv doesn't have, play the base view
				Video3dFormat.MVC, null -> StereoLayout.MONO
			},
		)
	}

	/** mpv does not send the Jellyfin authorization header, so authenticate with the token in the url instead. */
	private fun String.withAccessToken(): String {
		val token = api.accessToken ?: return this
		if (contains("${ApiClient.QUERY_ACCESS_TOKEN}=", ignoreCase = true) || contains("api_key=", ignoreCase = true)) return this
		val separator = if (contains('?')) '&' else '?'
		return "$this$separator${ApiClient.QUERY_ACCESS_TOKEN}=$token"
	}
}
