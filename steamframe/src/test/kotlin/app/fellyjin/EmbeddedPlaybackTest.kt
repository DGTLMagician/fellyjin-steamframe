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

		val host = AwtVideoHost(frame)
		val output = FlatWindowOutput(host)
		assertTrue(output.name == "Window", "The video surface could not be embedded")
		val base = mapOf("ao" to "null", "idle" to "yes", "terminal" to "no", "hwdec" to "no")
		// Same fallback as the app: Vulkan first, EGL when the Vulkan output cannot open
		var player: MpvPlayer? = null
		while (player == null) {
			player = runCatching { MpvPlayer(base + output.mpvOptions()) }.getOrElse { err -> if (output.fallBack()) null else throw err }
		}
		println("Embedded playback uses ${output.gpuContext}")
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
			val robot = Robot()
			fun bottomBrightness(image: java.awt.image.BufferedImage) = (0 until image.width step 4).sumOf { x ->
				(image.height - 40 until image.height step 4).sumOf { y ->
					val rgb = image.getRGB(x, y)
					((rgb shr 16) and 0xff) + ((rgb shr 8) and 0xff) + (rgb and 0xff)
				}
			}
			val before = robot.createScreenCapture(bounds)

			// Like moving the laser pointer: mpv's on-screen controller must appear over the bottom of the video
			robot.mouseMove(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
			Thread.sleep(150)
			robot.mouseMove(bounds.x + bounds.width / 2 + 40, bounds.y + bounds.height - 30)
			Thread.sleep(800)
			val shot = robot.createScreenCapture(bounds)
			ImageIO.write(shot, "png", File("build/snapshots/embedded-playback.png").apply { parentFile.mkdirs() })
			val colors = (0 until shot.width step 8).flatMap { x -> (0 until shot.height step 8).map { y -> shot.getRGB(x, y) } }.toSet()
			println("Embedded playback shows ${colors.size} distinct colors")
			assertTrue(colors.size > 20, "The window shows no video (${colors.size} colors)")
			val dimmed = bottomBrightness(shot).toDouble() / bottomBrightness(before)
			println("Bottom brightness with controls vs without: $dimmed")
			assertTrue(dimmed < 0.8, "mpv's on-screen controller did not appear over the video ($dimmed)")
		} finally {
			// Same order as the app: mpv first, then the surface it draws into
			player.close()
			runCatching { output.close(player) }
			EventQueue.invokeAndWait { frame.dispose() }
		}
	}
}
