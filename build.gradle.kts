import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.cyclonedx)
}

// version.properties is the single source of truth for the user-facing version and the
// Android version code. It is parsed and validated exactly once, here, and every module reads
// the validated values through the root project's extra properties.
val lenswaveVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val lenswaveVersionName = requireNotNull(lenswaveVersion.getProperty("VERSION_NAME")) {
    "VERSION_NAME must be set in version.properties"
}.trim()
val lenswaveVersionCode = requireNotNull(lenswaveVersion.getProperty("VERSION_CODE")) {
    "VERSION_CODE must be set in version.properties"
}.trim().toInt()

require(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(lenswaveVersionName)) {
    "VERSION_NAME must use semantic versioning, got '$lenswaveVersionName'"
}
require(lenswaveVersionCode > 0) { "VERSION_CODE must be positive, got $lenswaveVersionCode" }

extra["lenswaveVersionName"] = lenswaveVersionName
extra["lenswaveVersionCode"] = lenswaveVersionCode

allprojects {
    group = "com.bownee.lenswave"
    version = lenswaveVersionName
}

// SPDX identifiers and the exact license names that published POMs in the dependency tree use.
// Everything here is compatible with distributing Lenswave under GPL-3.0-only.
val approvedSbomLicenses = setOf(
    "Android Software Development Kit License",
    "Android Software Development Kit License Agreement",
    "Apache License 2",
    "Apache-1.0",
    "Apache-2.0",
    "Bouncy Castle Licence",
    "BSD style",
    "BSD-3-Clause",
    "EPL-1.0",
    "EPL-2.0",
    "GNU General Public License, version 2 (GPL2), with the classpath exception",
    "GNU GENERAL PUBLIC LICENSE, Version 3.0",
    "LGPL-2.1-only",
    "MIT",
    "Public Domain",
)

// These artifacts publish no license metadata in their POM. They are reviewed by coordinate
// without a version so that routine version bumps do not fail the gate; a review is still
// required when one of them changes license, which Dependabot release notes surface.
val approvedMissingLicenseMetadata = setOf(
    "com.airbnb.android:lottie",
    "javax.annotation:javax.annotation-api",
    "net.zetetic:sqlcipher-android",
)

tasks.register("verifySbomLicenses") {
    description = "Fails when a dependency in the SBOM uses a license outside the approved list."
    group = "verification"
    notCompatibleWithConfigurationCache("CycloneDX resolves all variant configurations")
    dependsOn("cyclonedxBom")
    val sbom = layout.buildDirectory.file("reports/cyclonedx/bom.json")
    inputs.file(sbom)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val document = JsonSlurper().parse(sbom.get().asFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val components = document["components"] as? List<Map<String, Any?>> ?: emptyList()
        val problems = buildList {
            components.forEach { component ->
                val group = component["group"]?.toString().orEmpty()
                val name = component["name"]?.toString().orEmpty()
                val version = component["version"]?.toString().orEmpty()
                val module = "$group:$name"
                // Lenswave's own modules carry no POM license metadata; the policy is about third parties.
                if (group == "com.bownee.lenswave") return@forEach

                @Suppress("UNCHECKED_CAST")
                val licenses = (component["licenses"] as? List<Map<String, Any?>>).orEmpty()
                    .mapNotNull { entry ->
                        @Suppress("UNCHECKED_CAST")
                        val value = entry["license"] as? Map<String, Any?> ?: return@mapNotNull null
                        value["id"]?.toString() ?: value["name"]?.toString()
                    }
                    .toSet()
                if (licenses.isEmpty()) {
                    if (module !in approvedMissingLicenseMetadata) {
                        add("missing license metadata: $module:$version")
                    }
                } else {
                    val unknown = licenses - approvedSbomLicenses
                    if (unknown.isNotEmpty()) add("unapproved license for $module:$version: ${unknown.sorted()}")
                }
            }
        }
        check(problems.isEmpty()) { "SBOM license policy failed:\n${problems.joinToString("\n")}" }
        logger.lifecycle("Verified license metadata for ${components.size} SBOM components.")
    }
}

tasks.named("cyclonedxBom") {
    notCompatibleWithConfigurationCache("CycloneDX resolves all variant configurations")
}
