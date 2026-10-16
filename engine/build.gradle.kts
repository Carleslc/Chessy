plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "me.carleslc"
// version: see gradle.properties

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("snaky.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss16m")
}

// The license and its notice go with the engine (installDist, distZip)
distributions {
    main {
        contents {
            from(rootDir.resolve("../LICENSE"), rootDir.resolve("../NOTICE"))
        }
    }
}

tasks.processResources {
    // The engine reads its version from this resource
    inputs.property("version", project.version)
    filesMatching("snaky/version.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Deep perft tests are slow: ./gradlew test -Pslow
    systemProperty("snaky.slow", project.hasProperty("slow"))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
