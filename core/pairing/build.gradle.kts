plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {

    api(project(":core:lanweb"))
    implementation(project(":core:network"))
    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
