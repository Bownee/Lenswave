import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Lenswave's own UI is built from Views. The Compose toolchain exists only because Proton
    // Core's presentation-compose artifact needs an AppTheme binding (see LenswaveTheme).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    jacoco
}

// Formatting and style are enforced by ktlint (rules configured in .editorconfig).
// `ktlintFormat` rewrites the sources; `ktlintCheck` runs in CI.
ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    filter { exclude { entry -> entry.file.path.contains("/build/") || entry.file.path.contains("\\build\\") } }
}

// Validated once in the root build script from version.properties.
val lenswaveVersionName = rootProject.extra["lenswaveVersionName"] as String
val lenswaveVersionCode = rootProject.extra["lenswaveVersionCode"] as Int

val releaseStoreFile = providers.environmentVariable("LENSWAVE_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("LENSWAVE_SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LENSWAVE_SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LENSWAVE_SIGNING_KEY_PASSWORD").orNull
val hasReleaseSigning =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

// Build knobs used by CI (see .github/workflows/android.yml):
//  -Plenswave.instrumentationBuildType=minified  runs device tests against the R8-optimised build
//  -Plenswave.includeX86TestAbi=true             adds an x86_64 split for the emulator
val instrumentationBuildType =
    providers
        .gradleProperty("lenswave.instrumentationBuildType")
        .orElse("debug")
        .get()
val includeX86TestAbi =
    providers
        .gradleProperty("lenswave.includeX86TestAbi")
        .map(String::toBoolean)
        .orElse(false)
        .get()

require(instrumentationBuildType in setOf("debug", "minified")) {
    "lenswave.instrumentationBuildType must be debug or minified"
}

android {
    namespace = "com.bownee.lenswave"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bownee.lenswave"
        minSdk = 29
        targetSdk = 36
        versionCode = lenswaveVersionCode
        versionName = lenswaveVersionName
        testInstrumentationRunner = "com.bownee.lenswave.LenswaveTestRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            signingConfig = signingConfigs.getByName("debug")
            proguardFile("proguard-instrumentation.pro")
            // Keep this non-debuggable: AGP disables R8 optimizations for debuggable
            // variants, which would make the release-runtime verification misleading.
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    // Only 64-bit ARM ships: minSdk 29 devices are 64-bit and the Proton Drive SDK bundles a
    // Rust native library per ABI, so every extra split adds tens of megabytes.
    splits {
        abi {
            isEnable = true
            reset()
            include(
                *buildList {
                    add("arm64-v8a")
                    if (includeX86TestAbi) add("x86_64")
                }.toTypedArray(),
            )
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        testBuildType = instrumentationBuildType
    }

    lint {
        // lint.xml decides which checks are errors; this block decides what CI does with them.
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        sarifReport = true
        htmlReport = true
        xmlReport = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

configurations.configureEach {
    // Proton's stock binding treats an unavailable user-setting record as telemetry enabled.
    // Lenswave supplies the same data/worker bindings with fail-closed enablement instead.
    exclude(group = "me.proton.core", module = "telemetry-dagger")
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.material)
    implementation(libs.hilt.android)
    implementation(libs.sqlcipher)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.room.compiler)

    implementation(libs.proton.drive.sdk)
    implementation(libs.bundles.proton.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}

dependencyLocking {
    lockAllConfigurations()
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// AGP 9 compiles Kotlin with its built-in compiler and writes the classes to this intermediate
// directory. The coverage tasks assert that it is non-empty so a future AGP change cannot make
// the gate pass vacuously.
val authoredDebugClasses =
    files(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            include("com/bownee/lenswave/**")
            exclude(
                "**/BuildConfig.*",
                "**/R.class",
                "**/R$*.class",
                "**/*_Factory*.*",
                "**/*_Impl*.*",
                "**/Hilt_*.*",
                "**/*HiltModules*.*",
                "dagger/**",
                "hilt_aggregated_deps/**",
            )
        },
    )
val debugCoverageData =
    fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    }

// Reads the task's own inputs rather than script-level values so the configuration cache can
// serialize the action.
fun org.gradle.testing.jacoco.tasks.JacocoReportBase.requireCoverageInputs() {
    doFirst {
        val report = this as org.gradle.testing.jacoco.tasks.JacocoReportBase
        check(!report.classDirectories.asFileTree.isEmpty) {
            "No compiled app classes found for coverage; the AGP intermediate class directory may have moved."
        }
        check(!report.executionData.isEmpty) {
            "No JaCoCo execution data found; unit tests must run with coverage enabled."
        }
    }
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
    requireCoverageInputs()
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    sourceDirectories.setFrom(files("src/main/kotlin"))
    classDirectories.setFrom(authoredDebugClasses)
    executionData.setFrom(debugCoverageData)
}

tasks.register<JacocoCoverageVerification>("jacocoDebugCoverageVerification") {
    dependsOn("jacocoDebugUnitTestReport")
    requireCoverageInputs()
    classDirectories.setFrom(authoredDebugClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(debugCoverageData)
    violationRules {
        // Whole-app ratchet. Raise these when coverage grows; never lower them to make CI pass.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.15".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.16".toBigDecimal()
            }
        }
        // Pure decision objects carry the app's testable logic and must stay close to fully covered.
        rule {
            element = "CLASS"
            includes =
                listOf(
                    "com.bownee.lenswave.*Policy",
                    "com.bownee.lenswave.proton.ProtonSessionGuard",
                    "com.bownee.lenswave.proton.ProtonAccountTransitionCoordinator",
                    "com.bownee.lenswave.proton.ProtonSnapshotCoordinator",
                    "com.bownee.lenswave.proton.ProtonThumbnailQueue",
                    "com.bownee.lenswave.gallery.GalleryUiStateFactory",
                )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
