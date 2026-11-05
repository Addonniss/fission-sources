plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:sync"))
    implementation(project(":core:playback-api"))

    implementation(project(":core:network"))

    implementation(project(":source:aggregation"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(testFixtures(project(":core:playback-api")))
    testImplementation(project(":core:testing"))

    testImplementation(project(":core:metadata"))

    testImplementation(project(":source:stremio"))
}
