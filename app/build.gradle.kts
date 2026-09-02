import java.util.Properties
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.room")
    jacoco
}

val lenswaveVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { input ->
        load(input)
    }
}
val lenswaveVersionName = requireNotNull(lenswaveVersionProperties.getProperty("VERSION_NAME")) {
    "VERSION_NAME must be set in version.properties"
}
val lenswaveVersionCode = requireNotNull(lenswaveVersionProperties.getProperty("VERSION_CODE")) {
    "VERSION_CODE must be set in version.properties"
}.toInt()

require(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(lenswaveVersionName)) {
    "VERSION_NAME must use semantic versioning"
}
require(lenswaveVersionCode > 0) {
    "VERSION_CODE must be positive"
}

val releaseStoreFile = providers.environmentVariable("LENSWAVE_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("LENSWAVE_SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LENSWAVE_SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LENSWAVE_SIGNING_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val instrumentationBuildType = providers.gradleProperty("lenswave.instrumentationBuildType")
    .orElse("debug")
    .get()
val includeX86TestAbi = providers.gradleProperty("lenswave.includeX86TestAbi")
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
        managedDevices {
            localDevices {
                create("pixel2Api29") {
                    device = "Pixel 2"
                    apiLevel = 29
                    systemImageSource = "aosp"
                    require64Bit = true
                    testedAbi = "x86_64"
                }
            }
        }
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
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestUtil("androidx.test:orchestrator:1.6.1")

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.fragment:fragment:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("me.proton.drive:sdk:0.24.0-rust")

    val protonCoreVersion = "36.7.0"
    implementation("me.proton.core:account:$protonCoreVersion")
    implementation("me.proton.core:account-manager:$protonCoreVersion")
    implementation("me.proton.core:account-recovery:$protonCoreVersion")
    implementation("me.proton.core:auth:$protonCoreVersion")
    implementation("me.proton.core:auth-fido:$protonCoreVersion")
    implementation("me.proton.core:biometric:$protonCoreVersion")
    implementation("me.proton.core:challenge:$protonCoreVersion")
    implementation("me.proton.core:country:$protonCoreVersion")
    implementation("me.proton.core:crypto:$protonCoreVersion")
    implementation("me.proton.core:crypto-validator:$protonCoreVersion")
    implementation("me.proton.core:data-room:$protonCoreVersion")
    implementation("me.proton.core:domain:$protonCoreVersion")
    implementation("me.proton.core:event-manager:$protonCoreVersion")
    implementation("me.proton.core:feature-flag:$protonCoreVersion")
    implementation("me.proton.core:human-verification:$protonCoreVersion")
    implementation("me.proton.core:key:$protonCoreVersion")
    implementation("me.proton.core:key-transparency:$protonCoreVersion")
    implementation("me.proton.core:network:$protonCoreVersion")
    implementation("me.proton.core:notification:$protonCoreVersion")
    implementation("me.proton.core:observability:$protonCoreVersion")
    implementation("me.proton.core:pass-validator:$protonCoreVersion")
    implementation("me.proton.core:payment:$protonCoreVersion")
    implementation("me.proton.core:plan:$protonCoreVersion")
    implementation("me.proton.core:presentation-compose:$protonCoreVersion")
    implementation("me.proton.core:proguard-rules:$protonCoreVersion")
    implementation("me.proton.core:push:$protonCoreVersion")
    implementation("me.proton.core:telemetry:$protonCoreVersion")
    implementation("me.proton.core:user:$protonCoreVersion")
    implementation("me.proton.core:user-settings:$protonCoreVersion")
    implementation("me.proton.core:util-android-dagger:$protonCoreVersion")
    implementation("me.proton.core:user-recovery:$protonCoreVersion")

    testImplementation("junit:junit:4.13.2")
}

dependencyLocking {
    lockAllConfigurations()
}

jacoco {
    toolVersion = "0.8.13"
}

val authoredDebugClasses = files(
    fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
        include("com/bownee/lenswave/**")
        exclude(
            "**/BuildConfig.*", "**/R.class", "**/R$*.class",
            "**/*_Factory*.*", "**/*_Impl*.*", "**/Hilt_*.*", "**/*HiltModules*.*",
            "dagger/**", "hilt_aggregated_deps/**",
        )
    },
)
val debugCoverageData = fileTree(layout.buildDirectory) {
    include("jacoco/testDebugUnitTest.exec")
    include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
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
    classDirectories.setFrom(authoredDebugClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(debugCoverageData)
    violationRules {
        // Full app-authored Kotlin baseline; generated Room/Proton classes are excluded above.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.11".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.11".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "com.bownee.lenswave.gallery.GalleryOperationPolicy",
                "com.bownee.lenswave.gallery.CombinedPhotoRepository",
                "com.bownee.lenswave.proton.ProtonSessionGuard",
                "com.bownee.lenswave.proton.ProtonAccountTransitionCoordinator",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
