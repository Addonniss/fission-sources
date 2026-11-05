import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Fails unless release-version.properties versionCode is strictly greater than every versionCode in docs/release/records/*.json."
    val versionFile = layout.projectDirectory.file("release-version.properties")
    val recordsDirectory = layout.projectDirectory.dir("docs/release/records")
    doLast {
        val source = versionFile.asFile
        if (!source.isFile) {
            throw GradleException("verifyReleaseVersion: release-version.properties is missing at $source.")
        }
        val properties = Properties().also { loaded -> source.inputStream().use { loaded.load(it) } }
        val currentVersionCode = properties.getProperty("versionCode")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: throw GradleException(
                "verifyReleaseVersion: release-version.properties declares no integer versionCode — " +
                    "monotonicity cannot be asserted.",
            )

        fun collectVersionCodes(node: Any?, sink: MutableList<Int>) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    if (key == "versionCode") {
                        when (value) {
                            is Number -> sink += value.toInt()
                            is String -> value.trim().toIntOrNull()?.let { sink += it }
                        }
                    }
                    collectVersionCodes(value, sink)
                }
                is Iterable<*> -> node.forEach { collectVersionCodes(it, sink) }
                is Array<*> -> node.forEach { collectVersionCodes(it, sink) }
            }
        }

        val recordsDir = recordsDirectory.asFile
        if (!recordsDir.isDirectory) {
            logger.lifecycle(
                "verifyReleaseVersion PASS: release-version.properties versionCode=$currentVersionCode — " +
                    "no release records directory exists here, so there is no previously recorded " +
                    "versionCode to compare against.",
            )
            return@doLast
        }
        val recordFiles = recordsDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
        if (recordFiles.isEmpty()) {
            logger.lifecycle(
                "verifyReleaseVersion PASS: release-version.properties versionCode=$currentVersionCode — " +
                    "no release records present, so there is no previously recorded versionCode.",
            )
            return@doLast
        }

        val slurper = groovy.json.JsonSlurper()
        val problems = mutableListOf<String>()
        var highestCode = Int.MIN_VALUE
        var highestSource: String? = null
        for (file in recordFiles) {
            val parsed = try {
                slurper.parse(file)
            } catch (failure: Exception) {
                problems += "${file.name}: not parseable as JSON — ${failure.message}"
                continue
            }
            val codes = mutableListOf<Int>()
            collectVersionCodes(parsed, codes)
            if (codes.isEmpty()) {
                problems += "${file.name}: no versionCode field — a record that cannot be read must not pass silently"
                continue
            }
            for (code in codes) {
                if (code > highestCode) {
                    highestCode = code
                    highestSource = file.name
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyReleaseVersion FAILED: the release records could not be read.\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        if (currentVersionCode <= highestCode) {
            throw GradleException(
                "verifyReleaseVersion FAILED: release-version.properties declares versionCode=$currentVersionCode, " +
                    "which is NOT strictly greater than the highest recorded versionCode=$highestCode " +
                    "($highestSource).",
            )
        }
        logger.lifecycle(
            "verifyReleaseVersion PASS: versionCode=$currentVersionCode is strictly greater than the " +
                "highest recorded versionCode=$highestCode ($highestSource); records parsed: ${recordFiles.size}.",
        )
    }
}

tasks.register("verifyPackageIdentity") {
    group = "verification"
    description = "Asserts the Android TV shell keeps its exact applicationId, declares no applicationIdSuffix or product flavour, and resolves the single-source versionCode."
    val versionFile = layout.projectDirectory.file("release-version.properties")
    doLast {
        val sourceVersionCode = run {
            val source = versionFile.asFile
            if (!source.isFile) {
                throw GradleException("verifyPackageIdentity: release-version.properties is missing at $source.")
            }
            val properties = Properties().also { loaded -> source.inputStream().use { loaded.load(it) } }
            properties.getProperty("versionCode")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntOrNull()
                ?: throw GradleException("verifyPackageIdentity: release-version.properties declares no integer versionCode.")
        }
        val expectedShells = linkedMapOf(":app-tv" to "dev.infusion.tv")
        val problems = mutableListOf<String>()
        val resolvedCodes = linkedMapOf<String, Int?>()
        val resolvedNames = linkedMapOf<String, String?>()

        for ((path, expectedApplicationId) in expectedShells) {
            val shell = rootProject.project(path)
            val android = shell.extensions.findByType(com.android.build.api.dsl.ApplicationExtension::class.java)
                ?: throw GradleException(
                    "verifyPackageIdentity: $path has no Android application extension — the module structure " +
                        "changed, so the identity contract cannot be asserted.",
                )
            val declaredApplicationId = android.defaultConfig.applicationId
            val declaredSuffix = android.defaultConfig.applicationIdSuffix
            resolvedCodes[path] = android.defaultConfig.versionCode
            resolvedNames[path] = android.defaultConfig.versionName

            if (declaredApplicationId != expectedApplicationId) {
                problems += "$path applicationId: expected '$expectedApplicationId', the DSL declares '$declaredApplicationId'"
            }
            if (!declaredSuffix.isNullOrBlank()) {
                problems += "$path applicationIdSuffix: expected absent, defaultConfig declares '$declaredSuffix'"
            }
            android.buildTypes.forEach { buildType ->
                val suffix = buildType.applicationIdSuffix
                if (!suffix.isNullOrBlank()) {
                    problems += "$path applicationIdSuffix: expected absent, build type '${buildType.name}' declares '$suffix'"
                }
            }
            if (android.productFlavors.isNotEmpty()) {
                problems += "$path productFlavors: expected none, the DSL declares ${android.productFlavors.names}"
            }
            if (android.flavorDimensions.isNotEmpty()) {
                problems += "$path flavorDimensions: expected none, the DSL declares ${android.flavorDimensions}"
            }
        }

        resolvedCodes.forEach { (path, code) ->
            if (code != sourceVersionCode) {
                problems += "$path versionCode: expected $sourceVersionCode from release-version.properties, " +
                    "the DSL resolves $code — the version has one source only"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyPackageIdentity FAILED — the upgrade identity is not intact:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle(
            "verifyPackageIdentity PASS: " + expectedShells.entries.joinToString("; ") { (path, applicationId) ->
                "$path applicationId=$applicationId applicationIdSuffix=absent productFlavors=none " +
                    "versionCode=${resolvedCodes[path]} versionName=${resolvedNames[path]}"
            } + " — single source (release-version.properties: versionCode=$sourceVersionCode).",
        )
    }
}
