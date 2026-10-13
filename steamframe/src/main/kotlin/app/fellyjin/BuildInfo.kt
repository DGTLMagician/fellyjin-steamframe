package app.fellyjin

object BuildInfo {
	const val APP_NAME = "Fellyjin"
	const val CLIENT_NAME = "Fellyjin Steam Frame"
	const val VERSION = "0.4.0"
}

/** Read a `FELLYJIN_*` environment variable, falling back to the `JELLYFIN_*` name used before the rename. */
fun appEnv(name: String): String? = System.getenv("FELLYJIN_$name") ?: System.getenv("JELLYFIN_$name")
