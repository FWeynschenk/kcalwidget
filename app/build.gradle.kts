import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.flwe.kcalwidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.flwe.kcalwidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    // No toolchain: compile on whichever JDK runs Gradle (Studio's JBR 25) and emit 17.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.health.connect.client)
    implementation(libs.glance.appwidget)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/**
 * Fails the build when a Health Connect read is wrapped so that a failure is
 * indistinguishable from an empty result.
 *
 * `runCatching { client.aggregate(...) }.getOrNull()` reads as defensive and is the
 * opposite: a timeout, a revoked permission and a day with no data all come back as
 * null, the UI reports zero, and there is nothing anywhere saying otherwise. That has
 * caused three separate bugs in this app -- a burn aggregate that silently timed out, a
 * carry that cancelled a 700 kcal penalty, and an intake that read as "nothing logged".
 *
 * Catch the failure and record it. Where discarding it really is right, say why with a
 * trailing `// ALLOW-SWALLOW: reason` on the runCatching line.
 */
val verifyErrorHandling = tasks.register("verifyErrorHandling") {
    group = "verification"
    description = "Rejects Health Connect reads whose failures are silently discarded."

    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    val stamp = layout.buildDirectory.file("reports/verifyErrorHandling.txt")
    outputs.file(stamp)
    // Resolved now rather than reached for inside doLast, which the configuration cache
    // refuses to serialise.
    val root = projectDir
    val kotlinFiles = sources.files.sorted()

    doLast {
        val healthCall = Regex(
            """\b(aggregate|aggregateGroupByPeriod|aggregateGroupByDuration|readRecords|""" +
                """getGrantedPermissions|permissionController|getSdkStatus)\b"""
        )
        val discards = Regex("""\.(getOrNull\(\)|getOrDefault\(|getOrElse\s*\{)""")
        val violations = mutableListOf<String>()

        kotlinFiles.forEach { file ->
            val raw = file.readLines()
            // Comments are blanked before scanning. Without that the rule flags the
            // documentation explaining why the rule exists, which is a short route to
            // somebody deleting the rule.
            var inBlock = false
            val lines = raw.map { line ->
                val trimmed = line.trim()
                when {
                    inBlock -> {
                        if (trimmed.contains("*/")) inBlock = false
                        ""
                    }
                    trimmed.startsWith("//") -> ""
                    trimmed.startsWith("/*") -> {
                        if (!trimmed.contains("*/")) inBlock = true
                        ""
                    }
                    else -> line.substringBefore("//")
                }
            }

            lines.forEachIndexed { index, line ->
                if (!line.contains("runCatching")) return@forEachIndexed
                if (raw[index].contains("ALLOW-SWALLOW")) return@forEachIndexed

                // The block and whatever it is unwrapped with, a handful of lines on.
                val window = lines.subList(index, minOf(index + 12, lines.size)).joinToString("\n")
                if (healthCall.containsMatchIn(window) && discards.containsMatchIn(window)) {
                    violations += "${file.relativeTo(root)}:${index + 1}: " +
                        "a Health Connect read whose failure is discarded"
                }
            }
        }

        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                if (violations.isEmpty()) "clean\n" else violations.joinToString("\n")
            )
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Health Connect failures are being discarded:")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("A failed read must not look like a day with no data.")
                    appendLine("Record the error, or justify it with // ALLOW-SWALLOW: reason")
                }
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyErrorHandling) }
