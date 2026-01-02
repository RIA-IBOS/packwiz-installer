package link.infra.packwiz.installer.config

import link.infra.packwiz.installer.util.Log
import java.util.Properties

/**
 * Configuration for CurseForge API endpoints with mirror support.
 * Loads configuration from bundled packwiz-installer.properties file.
 */
object CurseForgeConfig {
	private const val CONFIG_FILE = "/packwiz-installer.properties"
	private const val PRIMARY_KEY = "curseforge.api.primary"
	private const val MIRROR_KEY = "curseforge.api.mirror"

	// Default values as fallback if properties file is missing
	private const val DEFAULT_PRIMARY = "https://api.curseforge.com/v1"
	private const val DEFAULT_MIRROR = "https://mod.mcimirror.top/curseforge/v1"

	private val config: Properties by lazy {
		val props = Properties()
		try {
			val stream = CurseForgeConfig::class.java.getResourceAsStream(CONFIG_FILE)
			if (stream != null) {
				stream.use { props.load(it) }
				Log.info("Loaded CurseForge configuration from $CONFIG_FILE")
			} else {
				Log.warn("Configuration file $CONFIG_FILE not found, using defaults")
			}
		} catch (e: Exception) {
			Log.warn("Failed to load CurseForge configuration, using defaults", e)
		}
		props
	}

	/**
	 * Primary CurseForge API base URL (without trailing slash)
	 */
	val primaryApiUrl: String
		get() = config.getProperty(PRIMARY_KEY, DEFAULT_PRIMARY).trimEnd('/')

	/**
	 * Mirror CurseForge API base URL (without trailing slash)
	 */
	val mirrorApiUrl: String
		get() = config.getProperty(MIRROR_KEY, DEFAULT_MIRROR).trimEnd('/')

	/**
	 * Get all API URLs in order of preference (primary first, then mirror)
	 */
	fun getApiUrls(): List<String> = listOf(primaryApiUrl, mirrorApiUrl)
}
