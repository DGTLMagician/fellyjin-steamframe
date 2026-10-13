import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	kotlin("jvm") version "2.4.21"
	kotlin("plugin.compose") version "2.4.21"
	kotlin("plugin.serialization") version "2.4.21"
	id("org.jetbrains.compose") version "1.12.1"
}

group = "app.fellyjin"
version = "0.7.0"

java {
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}

val composeVersion = "1.12.1"
val lwjglVersion = "3.4.3"
// Steam Frame runs SteamOS on ARM64, but allow building for x86_64 Linux and the host for development.
val lwjglNatives = listOf("natives-linux-arm64", "natives-linux", "natives-macos-arm64")
val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openxr")

dependencies {
	implementation(compose.desktop.currentOs)
	// Always include the Steam Frame (SteamOS ARM64) natives, also when building on another platform
	implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:$composeVersion")
	implementation("org.jetbrains.compose.material3:material3:1.9.0")
	implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

	implementation("org.jellyfin.sdk:jellyfin-core:1.9.2")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

	implementation("io.coil-kt.coil3:coil-compose:3.6.3")
	implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

	implementation("net.java.dev.jna:jna:5.19.1")

	implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
	lwjglModules.forEach { module ->
		implementation("org.lwjgl:$module")
		lwjglNatives.forEach { natives ->
			// lwjgl-openxr has no macOS natives; OpenXR is only used on Linux
			if (!(module == "lwjgl-openxr" && natives.startsWith("natives-macos"))) {
				runtimeOnly("org.lwjgl:$module::$natives")
			}
		}
	}

	implementation("org.slf4j:slf4j-simple:2.0.17")

	testImplementation(kotlin("test"))
}

compose.desktop {
	application {
		mainClass = "app.fellyjin.MainKt"
		jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true", "--enable-native-access=ALL-UNNAMED")

		nativeDistributions {
			targetFormats(TargetFormat.Deb)
			// Filled by packaging/bundle-libmpv.sh, ends up in the compose.application.resources.dir
			appResourcesRootDir.set(layout.buildDirectory.dir("native-bundle"))
			packageName = "fellyjin-steamframe"
			packageVersion = project.version.toString()
			description = "Fellyjin: a Jellyfin client for the Steam Frame with a virtual cinema screen"
			vendor = "Fellyjin"
			licenseFile.set(rootProject.file("../LICENSE"))
			modules("java.net.http", "jdk.unsupported", "java.naming")

			// Only for local development builds, jpackage on macOS rejects a 0.x version
			macOS {
				packageVersion = "1.0.0"
			}

			linux {
				iconFile.set(rootProject.file("packaging/icon.png"))
				appCategory = "AudioVideo"
				menuGroup = "AudioVideo"
			}
		}
	}
}

// Portable tarball for SteamOS: extract anywhere (e.g. ~/Applications) and add bin/fellyjin-steamframe to Steam
tasks.register<Tar>("packageTarball") {
	group = "distribution"
	description = "Packages the distributable app with its bundled runtime as a tar.gz"
	dependsOn("createDistributable")
	val arch = when (System.getProperty("os.arch")) {
		"aarch64", "arm64" -> "arm64"
		else -> "x86_64"
	}
	archiveFileName.set("fellyjin-steamframe-${project.version}-linux-$arch.tar.gz")
	destinationDirectory.set(layout.buildDirectory.dir("dist"))
	compression = Compression.GZIP
	// Gradle normalizes archive permissions by default, keep the executable bits of the launcher and runtime
	useFileSystemPermissions()
	from(layout.buildDirectory.dir("compose/binaries/main/app"))
	from("packaging") {
		include("fellyjin-steamframe.desktop", "icon.png", "install.sh")
		into("fellyjin-steamframe")
	}
}

tasks.test {
	useJUnitPlatform()
}
