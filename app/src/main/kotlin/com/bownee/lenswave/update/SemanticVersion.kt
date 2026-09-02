package com.bownee.lenswave.update

internal data class SemanticVersion(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }

        prerelease.zip(other.prerelease).forEach { (left, right) ->
            comparePrereleaseIdentifiers(left, right).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            """^[vV]?([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )

        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val major = match.groupValues[1].toVersionNumber() ?: return null
            val minor = match.groupValues[2].toVersionNumber() ?: return null
            val patch = match.groupValues[3].toVersionNumber() ?: return null
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                ?.map { identifier ->
                    val numeric = identifier.all(Char::isDigit)
                    if (numeric && identifier.length > 1 && identifier.startsWith('0')) return null
                    identifier
                }
                .orEmpty()
            return SemanticVersion(major, minor, patch, prerelease)
        }

        private fun String.toVersionNumber(): Int? {
            if (length > 1 && startsWith('0')) return null
            return toIntOrNull()
        }

        private fun compareNumericStrings(left: String, right: String): Int =
            compareValues(left.length, right.length).takeIf { it != 0 }
                ?: left.compareTo(right)

        private fun comparePrereleaseIdentifiers(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && !rightNumeric -> -1
                !leftNumeric && rightNumeric -> 1
                leftNumeric -> compareNumericStrings(left, right)
                else -> left.compareTo(right)
            }
        }
    }
}
