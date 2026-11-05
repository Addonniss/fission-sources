plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))

    api(project(":core:playback-api"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
