package app.fellyjin

import app.fellyjin.mpv.LibMpv
import app.fellyjin.mpv.MpvEvent
import app.fellyjin.mpv.MpvPlayer
import app.fellyjin.playback.AwtVideoHost
import app.fellyjin.playback.FlatWindowOutput
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.system.Platform
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Windowed playback must draw inside the app window (gamescope shows one window per app). Plays a test pattern
 * through [FlatWindowOutput] into an [AwtVideoHost] surface and checks the window actually shows the video.
 * Needs Linux with an X display (xvfb-run with Mesa) and libmpv.
 */
class EmbeddedPlaybackTest {
	@Test
	fun videoIsVisibleInsideTheAppWindow() {
		assumeTrue(Platform.get() == Platform.LINUX && System.getenv("DISPLAY") != null, "Needs Linux with an X display")
		assumeTrue(LibMpv.isAvailable, "libmpv not installed")

		lateinit var frame: JFrame
		EventQueue.invokeAndWait {
			frame = JFrame("Fellyjin test").apply {
				setBounds(0, 0, 640, 360)
				isVisible = true
			}
		}
		Thread.sleep(500)

		val output = FlatWindowOutput(AwtVideoHost(frame))
		assertTrue(output.name == "Window", "The video surface could not be embedded")
		val player = MpvPlayer(mapOf("ao" to "null", "idle" to "yes", "terminal" to "no", "hwdec" to "no") + output.mpvOptions())
		try {
			output.attach(player)
			player.load("av://lavfi:testsrc2=size=640x360:rate=30:duration=10", start = null, title = "test")
			val start = TimeSource.Monotonic.markNow()
			var loaded = false
			while (start.elapsedNow() < 15.seconds && !(loaded && start.elapsedNow() > 3.seconds)) {
				val event = player.pollEvent(0.1) ?: continue
				if (event == MpvEvent.FileLoaded) loaded = true
				assertTrue(event !is MpvEvent.EndFile, "Playback failed: $event")
			}
			assertTrue(loaded, "The test video did not load")

			val bounds = frame.contentPane.locationOnScreen.let { Rectangle(it.x, it.y, frame.contentPane.width, frame.contentPane.height) }
			val shot = Robot().createScreenCapture(bounds)
			ImageIO.write(shot, "png", File("build/snapshots/embedded-playback.png").apply { parentFile.mkdirs() })
			val colors = (0 until shot.width step 8).flatMap { x -> (0 until shot.height step 8).map { y -> shot.getRGB(x, y) } }.toSet()
			println("Embedded playback shows ${colors.size} distinct colors")
			assertTrue(colors.size > 20, "The window shows no video (${colors.size} colors)")
		} finally {
			runCatching { output.close(player) }
			player.close()
			EventQueue.invokeAndWait { frame.dispose() }
		}
	}
}
