plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    namespace = "com.bownee.lenswave.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    buildFeatures {
        androidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.exifinterface)
    implementation(libs.hilt.android)
    implementation(libs.proton.drive.sdk)
    api(libs.kotlinx.coroutines.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
