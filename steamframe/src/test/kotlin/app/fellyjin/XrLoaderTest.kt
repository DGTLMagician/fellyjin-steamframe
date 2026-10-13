package app.fellyjin

import app.fellyjin.vr.XrCinemaOutput
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.openxr.XR
import org.lwjgl.system.Platform
import kotlin.test.Test

/**
 * Regression test: LWJGL loads the OpenXR loader when the XR class initializes, and a second XR.create() used to
 * throw "OpenXR has already been created", which made the VR cinema fail on every start.
 */
class XrLoaderTest {
	@Test
	fun loaderCanBeEnsuredAfterLwjglLoadedIt() {
		assumeTrue(Platform.get() == Platform.LINUX, "The OpenXR loader is only bundled for Linux")
		XR.getFunctionProvider() // triggers LWJGL's automatic loader creation, like any OpenXR call does
		XrCinemaOutput.ensureLoader()
		XrCinemaOutput.ensureLoader()
	}
}
