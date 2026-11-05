plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {

    implementation(project(":core:network"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
