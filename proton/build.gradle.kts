plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Compose exists only because Proton Core's presentation artifact needs an AppTheme binding
    // (see LenswaveTheme); Lenswave's own UI is built from Views.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    jacoco
}

ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    filter { exclude { entry -> entry.file.path.contains("/build/") || entry.file.path.contains("\build\\") } }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "com.bownee.lenswave.proton"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VERSION_NAME", "\"${rootProject.extra["lenswaveVersionName"]}\"")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        androidResources = true
        buildConfig = true
        compose = true
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    api(project(":core"))
    implementation(project(":storage"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.work.runtime)
    api(libs.proton.drive.sdk)
    api(libs.bundles.proton.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.exifinterface)
    implementation(libs.hilt.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.sqlcipher)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

room {
    schemaDirectory("$projectDir/schemas")
}

configurations.configureEach {
    // Proton's stock binding treats an unavailable user-setting record as telemetry enabled.
    // Lenswave supplies the same data/worker bindings with fail-closed enablement instead.
    exclude(group = "me.proton.core", module = "telemetry-dagger")
}
