plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:model"))

    implementation(project(":core:playback-api"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation(project(":core:network"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
}
