package com.bownee.lenswave.update

/** Where Lenswave releases are published; every release URL derives from [REPOSITORY]. */
internal object LenswaveReleases {
    const val REPOSITORY = "Bownee/Lenswave"

    /** GitHub REST endpoint describing the latest published release. */
    val latestReleaseApiUrl: String = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    /** Human-readable page for the latest release, opened from the update prompt. */
    val latestReleasePageUrl: String = "https://github.com/$REPOSITORY/releases/latest"
}
