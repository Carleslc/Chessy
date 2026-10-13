plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.github.carleslc"
version = "0.1.0"

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
    mainClass.set("chessy.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss16m")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Deep perft tests are slow: ./gradlew test -Pslow
    systemProperty("chessy.slow", project.hasProperty("slow"))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
