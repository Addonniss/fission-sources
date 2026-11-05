plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {

    api(project(":core:lanweb"))

    api(project(":core:pairing"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
