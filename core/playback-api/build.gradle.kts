import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xexplicit-api=warning")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.truth)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test> {

    maxHeapSize = "384m"

    timeout = Duration.ofMinutes(3)
}
