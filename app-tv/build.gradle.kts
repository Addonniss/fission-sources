import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val infusionSigningVariableNames = listOf(
    "INFUSION_KEYSTORE_PATH",
    "INFUSION_KEYSTORE_PASSWORD",
    "INFUSION_KEY_ALIAS",
    "INFUSION_KEY_PASSWORD",
)

val infusionSigningEnvironment: Map<String, String> = infusionSigningVariableNames
    .mapNotNull { name -> System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { name to it } }
    .toMap()
val infusionSigningAbsentVariables: List<String> =
    infusionSigningVariableNames.filterNot { it in infusionSigningEnvironment }
val infusionSigningConfigured: Boolean = infusionSigningAbsentVariables.isEmpty()

if (infusionSigningConfigured) {
    logger.lifecycle(
        "Release signing (R2/D3): INFUSION_KEYSTORE_PATH, INFUSION_KEYSTORE_PASSWORD, " +
            "INFUSION_KEY_ALIAS, INFUSION_KEY_PASSWORD all present — the 'infusion' " +
            "configuration signs BOTH debug and release with one identity (v2 + v3).",
    )
} else {
    logger.lifecycle(
        "Release signing (R2/D3): absent ${infusionSigningAbsentVariables.joinToString(", ")} — " +
            "debug keeps AGP's default debug signing and release stays unsigned.",
    )
}

val infusionReleaseVersionFile = rootProject.layout.projectDirectory.file("release-version.properties")
val infusionReleaseVersion: Properties = Properties().also { properties ->
    val source = infusionReleaseVersionFile.asFile
    if (!source.isFile) {
        throw GradleException(
            "R3: release-version.properties is missing at $source — the version has exactly ONE source and " +
                "it cannot be absent (docs/release/VERSIONING.md).",
        )
    }
    source.inputStream().use { stream -> properties.load(stream) }
}
val infusionVersionName: String = infusionReleaseVersion.getProperty("versionName")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw GradleException("R3: release-version.properties declares no non-empty versionName.")
val infusionVersionCode: Int = infusionReleaseVersion.getProperty("versionCode")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.toIntOrNull()
    ?: throw GradleException("R3: release-version.properties declares no integer versionCode.")

android {
    namespace = "dev.infusion.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.infusion.tv"
        minSdk = 26
        targetSdk = 34

        versionCode = infusionVersionCode
        versionName = infusionVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {

        if (infusionSigningConfigured) {
            create("infusion") {
                storeFile = file(infusionSigningEnvironment.getValue("INFUSION_KEYSTORE_PATH"))
                storePassword = infusionSigningEnvironment.getValue("INFUSION_KEYSTORE_PASSWORD")
                keyAlias = infusionSigningEnvironment.getValue("INFUSION_KEY_ALIAS")
                keyPassword = infusionSigningEnvironment.getValue("INFUSION_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        if (infusionSigningConfigured) {

            getByName("debug").signingConfig = signingConfigs.getByName("infusion")
            getByName("release").signingConfig = signingConfigs.getByName("infusion")
        }
        release {

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true

        unitTests.all { test ->
            test.maxHeapSize = "768m"

            test.forkEvery = 1
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:sync"))
    implementation(project(":core:database"))
    implementation(project(":core:playback-api"))
    implementation(project(":core:playback-media3"))

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(project(":core:testing"))
    implementation(project(":feature:vertical"))
    implementation(project(":source:jellyfin"))

    implementation(project(":tracking:simkl"))

    implementation(project(":tracking:trakt"))

    implementation(project(":tracking:mdblist"))

    implementation(project(":core:metadata"))
    implementation(project(":source:tmdb"))

    implementation(project(":source:communityratings"))

    implementation(project(":source:segments"))

    implementation(project(":source:addon"))
    implementation(project(":source:aggregation"))

    implementation(project(":source:acquisition"))
    implementation(project(":source:stremio"))

    implementation(project(":core:network"))
    implementation(project(":core:security"))

    implementation(project(":core:update"))

    implementation(project(":core:artwork-cache"))

    implementation(project(":core:artwork-image"))

    implementation(project(":feature:pairing"))

    implementation(project(":feature:configweb"))
    implementation(project(":core:pairing-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.media3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(project(":core:network"))
    testImplementation(project(":core:security"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    testImplementation("androidx.test.espresso:espresso-core:3.5.0")

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        exclude("**/*UiTest.class")
    }
}

val tmdbApiKeyFromEnvironment: String = System.getenv("TMDB_API_KEY").orEmpty().trim()

val tmdbCredentialSourceDir = layout.buildDirectory.dir("generated/tmdbCredential/kotlin")

val generateTmdbCredential = tasks.register("generateTmdbCredential") {
    description = "Generates the build-time TMDB credential seam from TMDB_API_KEY."
    val outputDir = tmdbCredentialSourceDir
    outputs.dir(outputDir)

    outputs.upToDateWhen { false }
    val raw = tmdbApiKeyFromEnvironment
    doLast {
        val target = outputDir.get().asFile.resolve("dev/infusion/tv/di/GeneratedTmdbCredential.kt")
        target.parentFile.mkdirs()
        val chunks = if (raw.isEmpty()) emptyList() else raw.chunked(8)
        val chunksLiteral = chunks.joinToString(", ") { "\"$it\"" }
        target.writeText(
            """
            |package dev.infusion.tv.di
            |
            |import dev.infusion.source.tmdb.TmdbApiKey
            |
            |/*
            | * GENERATED FILE — do not edit, do not commit (Canonical Metadata P6 / D5).
            | *
            | * The build-time TMDB application credential seam. The value comes from the
            | * BUILD environment (TMDB_API_KEY) and is deliberately chunked so it is never
            | * stored as one contiguous literal. Absent/blank at build time yields
            | * [TmdbApiKey.Absent]: the provider then reports UNAUTHORIZED and canonical
            | * Details degrade honestly, section by section.
            | */
            |internal object GeneratedTmdbCredential {
            |    /** The build-time credential (absent when the build env carried none). */
            |    fun apiKey(): TmdbApiKey = TmdbApiKey.of(CHUNKS.joinToString(""))
            |
            |    private val CHUNKS: List<String> = listOf($chunksLiteral)
            |}
            |
            """.trimMargin(),
        )
    }
}

android.sourceSets.getByName("main").java.srcDir(tmdbCredentialSourceDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateTmdbCredential)
}

val simklClientIdFromEnvironment: String = System.getenv("SIMKL_CLIENT_ID").orEmpty().trim()

val simklCredentialSourceDir = layout.buildDirectory.dir("generated/simklCredential/kotlin")

val generateSimklCredential = tasks.register("generateSimklCredential") {
    description = "Generates the build-time Simkl public client-id seam from SIMKL_CLIENT_ID."
    val outputDir = simklCredentialSourceDir
    outputs.dir(outputDir)

    outputs.upToDateWhen { false }
    val raw = simklClientIdFromEnvironment
    doLast {
        val target = outputDir.get().asFile.resolve("dev/infusion/tv/tracking/GeneratedSimklCredential.kt")
        target.parentFile.mkdirs()
        val chunks = if (raw.isEmpty()) emptyList() else raw.chunked(8)
        val chunksLiteral = chunks.joinToString(", ") { "\"$it\"" }
        target.writeText(
            """
            |package dev.infusion.tv.tracking
            |
            |/*
            | * GENERATED FILE — do not edit, do not commit (Phase II.1).
            | *
            | * The build-time Simkl PUBLIC client id. The value comes from the BUILD
            | * environment (SIMKL_CLIENT_ID) and is deliberately chunked so it is
            | * never stored as one contiguous literal. Absent/blank at build time
            | * yields an EMPTY id: the Simkl provider then reports the missing
            | * registration instead of attempting a request, and no live account
            | * connection is claimed.
            | *
            | * This is a PUBLIC application identifier, not a secret. Simkl's device
            | * flow requires no client_secret and none is embedded anywhere.
            | */
            |internal object GeneratedSimklCredential {
            |    /** The build-time public client id (empty when the build env carried none). */
            |    fun clientId(): String = CHUNKS.joinToString("")
            |
            |    private val CHUNKS: List<String> = listOf($chunksLiteral)
            |}
            |
            """.trimMargin(),
        )
    }
}

android.sourceSets.getByName("main").java.srcDir(simklCredentialSourceDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateSimklCredential)
}

val traktClientIdFromEnvironment: String = System.getenv("TRAKT_CLIENT_ID").orEmpty().trim()
val traktClientSecretFromEnvironment: String = System.getenv("TRAKT_CLIENT_SECRET").orEmpty().trim()

val traktCredentialSourceDir = layout.buildDirectory.dir("generated/traktCredential/kotlin")

val generateTraktCredential = tasks.register("generateTraktCredential") {
    description = "Generates the build-time Trakt application seam from TRAKT_CLIENT_ID/TRAKT_CLIENT_SECRET."
    val outputDir = traktCredentialSourceDir
    outputs.dir(outputDir)

    outputs.upToDateWhen { false }
    val rawId = traktClientIdFromEnvironment
    val rawSecret = traktClientSecretFromEnvironment
    doLast {
        val target = outputDir.get().asFile.resolve("dev/infusion/tv/tracking/GeneratedTraktCredential.kt")
        target.parentFile.mkdirs()
        fun chunksOf(raw: String): String =
            if (raw.isEmpty()) "" else raw.chunked(8).joinToString(", ") { "\"$it\"" }
        target.writeText(
            """
            |package dev.infusion.tv.tracking
            |
            |/*
            | * GENERATED FILE — do not edit, do not commit (Phase II.4).
            | *
            | * The build-time values of INFUSION's OWN Trakt application. Both come from
            | * the BUILD environment (TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET) and are
            | * deliberately chunked so no value is ever stored as one contiguous literal.
            | * An absent/blank value at build time yields an EMPTY registration: the Trakt
            | * provider then reports the missing registration instead of attempting a
            | * request, and no live account connection is claimed.
            | *
            | * A value packaged into an APK is extractable. These are NOT claimed to be
            | * confidential on a device the user controls; see app-tv/build.gradle.kts.
            | */
            |internal object GeneratedTraktCredential {
            |    /** The build-time Trakt application client id (empty when the build env carried none). */
            |    fun clientId(): String = ID_CHUNKS.joinToString("")
            |
            |    /** The build-time Trakt application client secret (empty when the build env carried none). */
            |    fun clientSecret(): String = SECRET_CHUNKS.joinToString("")
            |
            |    private val ID_CHUNKS: List<String> = listOf(${chunksOf(rawId)})
            |
            |    private val SECRET_CHUNKS: List<String> = listOf(${chunksOf(rawSecret)})
            |}
            |
            """.trimMargin(),
        )

        logger.lifecycle(
            "Trakt registration: TRAKT_CLIENT_ID=${if (rawId.isBlank()) "ABSENT" else "present"} " +
                "TRAKT_CLIENT_SECRET=${if (rawSecret.isBlank()) "ABSENT" else "present"}",
        )
    }
}

android.sourceSets.getByName("main").java.srcDir(traktCredentialSourceDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateTraktCredential)
}

val mdblistClientIdFromEnvironment: String = System.getenv("MDBLIST_CLIENT_ID").orEmpty().trim()

val mdblistCredentialSourceDir = layout.buildDirectory.dir("generated/mdblistCredential/kotlin")

val generateMdblistCredential = tasks.register("generateMdblistCredential") {
    description = "Generates the build-time MDBList public client-id seam from MDBLIST_CLIENT_ID."
    val outputDir = mdblistCredentialSourceDir
    outputs.dir(outputDir)

    outputs.upToDateWhen { false }
    val raw = mdblistClientIdFromEnvironment
    doLast {
        val target = outputDir.get().asFile.resolve("dev/infusion/tv/tracking/GeneratedMDBListCredential.kt")
        target.parentFile.mkdirs()
        val chunks = if (raw.isEmpty()) emptyList() else raw.chunked(8)
        val chunksLiteral = chunks.joinToString(", ") { "\"$it\"" }
        target.writeText(
            """
            |package dev.infusion.tv.tracking
            |
            |/*
            | * GENERATED FILE — do not edit, do not commit (Phase II.5).
            | *
            | * The build-time MDBList PUBLIC client id of INFUSION's own Device Code App.
            | * The value comes from the BUILD environment (MDBLIST_CLIENT_ID) and is
            | * deliberately chunked so it is never stored as one contiguous literal.
            | * Absent/blank at build time yields an EMPTY id: the MDBList provider then
            | * reports the missing registration instead of attempting a request, and no
            | * live account connection is claimed.
            | *
            | * This is a PUBLIC application identifier, not a secret. MDBList's device
            | * flow requires no client_secret and none is embedded anywhere. The
            | * provider's personal API key / `?apikey=` method is NOT used by this
            | * adapter at all.
            | */
            |internal object GeneratedMDBListCredential {
            |    /** The build-time public client id (empty when the build env carried none). */
            |    fun clientId(): String = CHUNKS.joinToString("")
            |
            |    private val CHUNKS: List<String> = listOf($chunksLiteral)
            |}
            |
            """.trimMargin(),
        )

        logger.lifecycle(
            "MDBList registration: MDBLIST_CLIENT_ID=${if (raw.isBlank()) "ABSENT" else "present"}",
        )
    }
}

android.sourceSets.getByName("main").java.srcDir(mdblistCredentialSourceDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateMdblistCredential)
}

tasks.register("verifyMainApkPackaging") {
    group = "verification"
    description = "Fails if the main debug/release APK omits required pure-JVM Stremio class definitions (test APK is not the provider)."
    dependsOn("assembleDebug", "assembleDebugAndroidTest", "assembleRelease")
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-tv-debug.apk")

    val releaseApkName =
        if (infusionSigningConfigured) "app-tv-release.apk" else "app-tv-release-unsigned.apk"
    val releaseApkFile = layout.buildDirectory.file("outputs/apk/release/$releaseApkName")
    val testApkFile = layout.buildDirectory.file("outputs/apk/androidTest/debug/app-tv-debug-androidTest.apk")

    val releaseMappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val requiredClasses = listOf(
        "dev.infusion.source.stremio.policy.StremioContentType",
        "dev.infusion.source.stremio.url.ConfiguredAddonUrl",
        "dev.infusion.source.stremio.StremioClient",
    )

    val negativeControl = "dev.infusion.source.stremio.policy.NEGATIVE_TEST_DOES_NOT_EXIST"
    inputs.file(apkFile)
    inputs.file(releaseApkFile)
    inputs.file(testApkFile)

    outputs.upToDateWhen { false }
    doLast {
        val sdkDir = android.sdkDirectory
        val apkanalyzer = sdkDir.resolve("cmdline-tools/latest/bin/apkanalyzer")
        check(apkanalyzer.exists()) { "apkanalyzer not found at $apkanalyzer (set sdk.dir / ANDROID_HOME)" }
        fun dexDefinitions(apk: java.io.File, filterPrefix: String? = "dev.infusion.source.stremio."): List<String> {
            val proc = ProcessBuilder(apkanalyzer.absolutePath, "dex", "packages", apk.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            check(exit == 0) { "apkanalyzer dex packages failed (exit $exit) on $apk:\n${out.take(2000)}" }
            return out.lineSequence()
                .filter { it.startsWith("C d ") }
                .mapNotNull { line -> line.split('\t').getOrNull(3) }
                .filter { line -> filterPrefix == null || line.startsWith(filterPrefix) }
                .toList()
        }
        val mainApk = apkFile.get().asFile
        val releaseApk = releaseApkFile.get().asFile
        val testApk = testApkFile.get().asFile
        check(mainApk.exists()) { "main APK not found at $mainApk — run :app-tv:assembleDebug first" }
        check(releaseApk.exists()) { "release APK not found at $releaseApk — run :app-tv:assembleRelease first" }
        check(testApk.exists()) { "androidTest APK not found at $testApk — run :app-tv:assembleDebugAndroidTest first" }
        val mainDefs = dexDefinitions(mainApk)

        val releaseDefs = dexDefinitions(releaseApk, filterPrefix = null)
        val testDefs = dexDefinitions(testApk)
        val mainSet = mainDefs.toSet()
        val releaseSet = releaseDefs.toSet()
        val testSet = testDefs.toSet()
        val releaseStremio = releaseDefs.filter { it.startsWith("dev.infusion.source.stremio.") }
        println("main debug APK stremio class definitions: ${mainDefs.size}")
        println("main release APK class definitions scanned: ${releaseDefs.size} (obfuscated names)")
        println("main release APK stremio class definitions still carrying original names: ${releaseStremio.size}")
        println("androidTest APK stremio class definitions: ${testDefs.size}")

        check(negativeControl !in mainSet) { "negative control $negativeControl unexpectedly present in $mainApk — scanner broken" }
        check(negativeControl !in releaseSet) { "negative control $negativeControl unexpectedly present in $releaseApk — scanner broken" }
        check(negativeControl !in testSet) { "negative control $negativeControl unexpectedly present in $testApk — scanner broken" }
        val missingDebug = requiredClasses.filterNot { it in mainSet }
        check(missingDebug.isEmpty()) {
            "MAIN debug APK packaging defect: required class definitions missing from $mainApk: $missingDebug. " +
                "The androidTest APK is NOT an acceptable provider (it has ${testSet.size} stremio definitions). " +
                "Likely cause: a stale/poisoned desugar-transform cache dropped :source:stremio classes — clean " +
                "source/stremio/build/.transforms and rebuild with --no-build-cache."
        }

        val mapping = releaseMappingFile.get().asFile
        val resolvedRelease = mutableMapOf<String, String>()
        if (mapping.isFile) {
            mapping.useLines { lines ->
                lines.takeWhile { resolvedRelease.size < requiredClasses.size }.forEach { line ->
                    if (line.isEmpty() || line[0].isWhitespace() || line.startsWith("#")) return@forEach
                    val arrow = line.indexOf(" -> ")
                    if (arrow <= 0) return@forEach
                    val original = line.substring(0, arrow)
                    if (original in requiredClasses) {
                        resolvedRelease[original] = line.substring(arrow + 4).removeSuffix(":")
                    }
                }
            }
            println("release mapping: ${mapping.absolutePath} — resolved ${resolvedRelease.size}/${requiredClasses.size} required definitions")
            resolvedRelease.forEach { (original, obfuscated) -> println("  $original -> $obfuscated") }
        } else {
            println("release mapping: absent ($mapping) — the release APK is not minified, asserting the original names")
        }
        val missingRelease = requiredClasses.filterNot { original ->
            val lookedUpAs = resolvedRelease[original] ?: original
            lookedUpAs in releaseSet
        }
        check(missingRelease.isEmpty()) {
            "MAIN release APK packaging defect: required class definitions missing from $releaseApk: " +
                missingRelease.joinToString { original -> "$original (looked up as ${resolvedRelease[original] ?: original})" } +
                ". The androidTest APK is NOT an acceptable provider (it has ${testSet.size} stremio definitions)."
        }

        val onlyInTest = requiredClasses.filter { it in testSet && it !in mainSet }
        check(onlyInTest.isEmpty()) { "test APK would be the sole provider for: $onlyInTest" }
        println("PASS: main debug + release APKs independently contain all required Stremio definitions: $requiredClasses")
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Asserts the release APK's package/version, debuggable=false, v2+v3 schemes, and the expected signer SHA-256 (-PinfusionExpectedSignerSha256)."
    dependsOn("assembleRelease")

    val signedReleaseFileName = "outputs/apk/release/app-tv-release.apk"
    val unsignedReleaseFileName = "outputs/apk/release/app-tv-release-unsigned.apk"
    val apkFile = layout.buildDirectory.file(if (infusionSigningConfigured) signedReleaseFileName else unsignedReleaseFileName)
    val expectedPackageName: String = android.defaultConfig.applicationId ?: android.namespace ?: "dev.infusion.tv"
    val expectedVersionCode: Int? = android.defaultConfig.versionCode
    val expectedVersionName: String? = android.defaultConfig.versionName
    val expectedSignerSha256: String? = providers.gradleProperty("infusionExpectedSignerSha256").orNull
    val sdkDirectory = android.sdkDirectory
    inputs.file(apkFile).withPropertyName("releaseApk")
    inputs.property("expectedSignerSha256", expectedSignerSha256 ?: "ABSENT")
    doLast {
        if (!infusionSigningConfigured) {
            throw GradleException(
                "verifyReleaseSigning: the environment carries no complete INFUSION_* signing identity, so the " +
                    "release APK is UNSIGNED and there is no signature to verify. Set all four of " +
                    "${infusionSigningVariableNames.joinToString(", ")} — variable NAMES only are documented " +
                    "in docs/release/SIGNING.md.",
            )
        }
        val apk = apkFile.get().asFile
        if (!apk.isFile) {
            throw GradleException("verifyReleaseSigning: release APK not found at $apk — run :app-tv:assembleRelease first.")
        }
        val expectedDigest = expectedSignerSha256
            ?.replace(":", "")
            ?.replace(" ", "")
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: throw GradleException(
                "verifyReleaseSigning: -PinfusionExpectedSignerSha256=<SHA-256 of the expected signing " +
                    "certificate> is required. There is no default: the expected identity must come from the caller.",
            )

        fun execute(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException(
                    "`${command.joinToString(" ")}` failed (exit $exit):\n${output.take(4000)}",
                )
            }
            return output
        }

        fun buildTool(name: String): java.io.File {
            val buildTools = sdkDirectory.resolve("build-tools")
            val found = buildTools.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedByDescending { it.name }
                .map { it.resolve(name) }
                .firstOrNull { it.isFile }
            return found ?: throw GradleException("$name not found under $buildTools (set sdk.dir / ANDROID_HOME)")
        }

        val apkanalyzer = sdkDirectory.resolve("cmdline-tools/latest/bin/apkanalyzer")
        if (!apkanalyzer.isFile) {
            throw GradleException("apkanalyzer not found at $apkanalyzer (set sdk.dir / ANDROID_HOME)")
        }
        val apksigner = buildTool("apksigner")
        val aapt2 = buildTool("aapt2")

        val problems = mutableListOf<String>()

        val badging = execute(aapt2.absolutePath, "dump", "badging", apk.absolutePath)
        val packageLine = badging.lineSequence().firstOrNull { it.startsWith("package: ") }
            ?: throw GradleException("verifyReleaseSigning: aapt2 produced no 'package:' line for $apk")
        fun badgingAttribute(attribute: String): String? =
            Regex("""\b$attribute='([^']*)'""").find(packageLine)?.groupValues?.get(1)
        val actualPackage = badgingAttribute("name")
        val actualVersionCode = badgingAttribute("versionCode")
        val actualVersionName = badgingAttribute("versionName")
        if (actualPackage != expectedPackageName) {
            problems += "package name: expected '$expectedPackageName', artifact reports '$actualPackage'"
        }
        if (expectedVersionCode == null) {
            problems += "versionCode: defaultConfig declares none, so the artifact's '$actualVersionCode' cannot be asserted"
        } else if (actualVersionCode != expectedVersionCode.toString()) {
            problems += "versionCode: expected '$expectedVersionCode' (defaultConfig), artifact reports '$actualVersionCode'"
        }
        if (expectedVersionName == null) {
            problems += "versionName: defaultConfig declares none, so the artifact's '$actualVersionName' cannot be asserted"
        } else if (actualVersionName != expectedVersionName) {
            problems += "versionName: expected '$expectedVersionName' (defaultConfig), artifact reports '$actualVersionName'"
        }

        val debuggable = execute(apkanalyzer.absolutePath, "manifest", "debuggable", apk.absolutePath).trim()
        if (debuggable != "false") {
            problems += "debuggable: expected 'false', artifact reports '$debuggable'"
        }

        val signatureReport = execute(apksigner.absolutePath, "verify", "-v", "--print-certs", apk.absolutePath)
        for (scheme in listOf("v2", "v3")) {
            val verified = Regex("""Verified using $scheme scheme \(APK Signature Scheme $scheme\): (true|false)""")
                .find(signatureReport)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            when (verified) {
                true -> Unit
                false -> problems += "$scheme scheme: apksigner reports 'Verified using $scheme scheme: false'"
                null -> problems += "$scheme scheme: apksigner reported no v$scheme verification result"
            }
        }
        val actualSignerDigest = Regex("""Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)""")
            .find(signatureReport)?.groupValues?.get(1)?.replace(":", "")?.uppercase()
        if (actualSignerDigest == null) {
            problems += "signer certificate SHA-256: apksigner reported no 'Signer #1 certificate SHA-256 digest' line"
        } else if (actualSignerDigest != expectedDigest) {
            problems += "signer certificate SHA-256: expected '$expectedDigest' " +
                "(-PinfusionExpectedSignerSha256), artifact is signed by '$actualSignerDigest'"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyReleaseSigning FAILED for ${apk.name}:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle(
            "verifyReleaseSigning PASS: ${apk.name} — package=$actualPackage " +
                "versionCode=$actualVersionCode versionName=$actualVersionName debuggable=false " +
                "v2=true v3=true signerSHA256=$actualSignerDigest",
        )
    }
}
