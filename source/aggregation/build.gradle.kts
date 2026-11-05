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

    implementation(project(":core:network"))
    implementation(project(":source:stremio"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(project(":core:metadata"))

    testImplementation(testFixtures(project(":source:stremio")))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
}
