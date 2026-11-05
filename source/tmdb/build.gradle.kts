plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    implementation(project(":core:playback-api"))
    implementation(libs.okhttp)
    implementation(libs.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"

    systemProperty("infusion.tmdb.live", System.getProperty("infusion.tmdb.live") ?: "false")
    System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }?.let { environment("TMDB_API_KEY", it) }
}
