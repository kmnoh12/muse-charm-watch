plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
