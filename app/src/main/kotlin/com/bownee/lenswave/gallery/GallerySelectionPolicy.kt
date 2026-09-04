package com.bownee.lenswave.gallery

/** Reconciles a photo selection with newly submitted rows. */
internal object GallerySelectionPolicy {
    /**
     * The selected ids that [rows] no longer list. The scan stops as soon as every selected id has
     * been seen, so with the usual small selection near the top of a long page it touches a
     * handful of rows rather than every photo.
     */
    fun missingSelection(
        rows: List<GalleryRow>,
        selectedIds: Collection<String>,
    ): Set<String> {
        if (selectedIds.isEmpty()) return emptySet()
        val missing = HashSet(selectedIds)
        for (row in rows) {
            if (row !is GalleryRow.Photos) continue
            for (item in row.items) {
                if (missing.remove(item.stableId) && missing.isEmpty()) return missing
            }
        }
        return missing
    }
}
