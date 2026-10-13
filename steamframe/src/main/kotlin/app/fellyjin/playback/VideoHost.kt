package app.fellyjin.playback

import com.sun.jna.Native
import org.slf4j.LoggerFactory
import java.awt.Canvas
import java.awt.Color
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JLayeredPane
import javax.swing.RootPaneContainer

/**
 * A native surface inside the app window for mpv to draw into (`--wid`).
 *
 * Gamescope (SteamOS gaming mode) shows a single window per app. A separate mpv window ends up hidden behind the
 * Fellyjin window, so you only hear the audio. Drawing into a child of our own window always shows the video.
 */
interface VideoHost {
	/** Show the surface and return its native window id, or null when embedding is not possible. */
	fun show(): Long?

	fun hide()
}

class AwtVideoHost(private val window: RootPaneContainer) : VideoHost {
	private val logger = LoggerFactory.getLogger(AwtVideoHost::class.java)
	private var canvas: Canvas? = null
	private val resizeListener = object : ComponentAdapter() {
		override fun componentResized(e: ComponentEvent) = fitCanvas()
	}

	override fun show(): Long? {
		var id: Long? = null
		EventQueue.invokeAndWait {
			val layers = window.layeredPane
			val surface = Canvas().apply {
				background = Color.BLACK
				isFocusable = false
			}
			// Above the Compose content, so the video covers the UI while it plays
			layers.add(surface, JLayeredPane.DRAG_LAYER)
			canvas = surface
			fitCanvas()
			layers.addComponentListener(resizeListener)
			layers.revalidate()
			id = runCatching { Native.getComponentID(surface) }
				.onFailure { logger.warn("No native window id for the video surface", it) }
				.getOrNull()
				?.takeIf { it != 0L }
		}
		logger.info("Video surface window id: $id")
		if (id == null) hide()
		return id
	}

	override fun hide() {
		EventQueue.invokeAndWait {
			val layers = window.layeredPane
			layers.removeComponentListener(resizeListener)
			canvas?.let(layers::remove)
			canvas = null
			layers.revalidate()
			layers.repaint()
		}
	}

	private fun fitCanvas() {
		val layers = window.layeredPane
		canvas?.setBounds(0, 0, layers.width, layers.height)
	}
}
