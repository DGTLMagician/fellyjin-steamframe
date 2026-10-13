package org.jellyfin.steamframe

import org.jellyfin.steamframe.mpv.EndReason
import org.jellyfin.steamframe.mpv.LibMpv
import org.jellyfin.steamframe.mpv.MpvEvent
import org.jellyfin.steamframe.mpv.MpvPlayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Exercises the JNA binding against a real libmpv. Skipped when libmpv is not installed. */
class MpvPlayerTest {
	@Test
	fun playsGeneratedVideoToTheEnd() {
		assumeTrue(LibMpv.isAvailable, "libmpv not installed")

		MpvPlayer(mapOf("vo" to "null", "ao" to "null", "idle" to "yes", "terminal" to "no")).use { player ->
			player.load("av://lavfi:testsrc=duration=1:size=320x240:rate=25", start = null, title = "Test pattern")

			val seen = mutableListOf<MpvEvent>()
			var videoSize: Pair<Int, Int>? = null
			val start = TimeSource.Monotonic.markNow()
			while (start.elapsedNow() < 15.seconds) {
				val event = player.pollEvent(0.5) ?: continue
				seen += event
				if (event == MpvEvent.VideoReconfig) videoSize = player.videoSize ?: videoSize
				if (event is MpvEvent.EndFile) break
			}

			assertTrue(MpvEvent.FileLoaded in seen, "FILE_LOADED not received: $seen")
			assertEquals(MpvEvent.EndFile(EndReason.EOF), seen.last(), "Expected EOF, got $seen")
			assertEquals(320 to 240, videoSize)
			assertEquals("Test pattern", player.getProperty("force-media-title"))
		}
	}
}
