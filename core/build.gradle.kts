// Pure Kotlin/JVM: the Frigate HTTP API (models, client, session renewal, media URLs) and address parsing.
// No Android dependencies, so all of it is unit-tested on the JVM (also against a live Frigate, see AGENTS.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    // LiveFrigateTest runs only when FRIGATE_IT_URL / FRIGATE_IT_USER / FRIGATE_IT_PASSWORD are set.
    listOf("FRIGATE_IT_URL", "FRIGATE_IT_USER", "FRIGATE_IT_PASSWORD", "FRIGATE_IT_CERT_SHA256").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    inputs.property("liveTarget", System.getenv("FRIGATE_IT_URL") ?: "")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
