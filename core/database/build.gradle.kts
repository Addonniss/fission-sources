plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.infusion.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val roomSchemaLocation: String = layout.projectDirectory.dir("schemas").asFile.absolutePath
tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    commandLineArgumentProviders.add(
        object : org.gradle.process.CommandLineArgumentProvider {
            override fun asArguments(): Iterable<String> =
                listOf("room.schemaLocation=$roomSchemaLocation")
        },
    )
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:sync"))

    implementation(project(":core:playback-api"))

    implementation(project(":core:security"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(project(":core:metadata"))
    testImplementation(libs.truth)
}

android.sourceSets.getByName("test").assets.srcDirs("$projectDir/schemas")
