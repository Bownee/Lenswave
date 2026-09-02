@file:Suppress("UNCHECKED_CAST")

import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("androidx.room") version "2.7.2" apply false
    id("org.cyclonedx.bom") version "3.4.1"
}

val lenswaveProjectVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}.getProperty("VERSION_NAME") ?: error("VERSION_NAME must be set")

allprojects {
    group = "com.bownee.lenswave"
    version = lenswaveProjectVersion
}

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

// These exact published POMs omit license metadata; version changes require a fresh review.
val approvedMissingLicenseMetadata = setOf(
    "com.airbnb.android:lottie:4.1.0",
    "javax.annotation:javax.annotation-api:1.3.2",
    "net.zetetic:sqlcipher-android:4.6.1",
)

tasks.register("verifySbomLicenses") {
    notCompatibleWithConfigurationCache("CycloneDX resolves all variant configurations")
    dependsOn("cyclonedxBom")
    val sbom = layout.buildDirectory.file("reports/cyclonedx/bom.json")
    inputs.file(sbom)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val document = JsonSlurper().parse(sbom.get().asFile) as Map<String, Any?>
        val components = document["components"] as? List<Map<String, Any?>> ?: emptyList()
        val problems = buildList {
            components.forEach { component ->
                val coordinate = listOf("group", "name", "version")
                    .joinToString(":") { key -> component[key]?.toString().orEmpty() }
                if (coordinate.startsWith("com.bownee.lenswave:app:")) return@forEach
                val licenses = (component["licenses"] as? List<Map<String, Any?>>).orEmpty()
                    .mapNotNull { entry ->
                        val value = entry["license"] as? Map<String, Any?> ?: return@mapNotNull null
                        value["id"]?.toString() ?: value["name"]?.toString()
                    }
                    .toSet()
                if (licenses.isEmpty()) {
                    if (coordinate !in approvedMissingLicenseMetadata) {
                        add("missing license metadata: $coordinate")
                    }
                } else {
                    val unknown = licenses - approvedSbomLicenses
                    if (unknown.isNotEmpty()) add("unapproved license for $coordinate: ${unknown.sorted()}")
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
