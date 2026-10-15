package snaky

import java.util.Properties

/** Version of the engine, defined in gradle.properties: v1.0-dev, v1.0, v1.01, v1.07.6... */
object Version {

    val number: String = Properties().run {
        Version::class.java.getResourceAsStream("/snaky/version.properties")?.use { load(it) }
        getProperty("version") ?: "unknown"
    }

    /** Engine name with its version, such as `Snaky v1.0`. */
    val name: String = "Snaky v$number"

    const val AUTHOR = "Carleslc"
}
