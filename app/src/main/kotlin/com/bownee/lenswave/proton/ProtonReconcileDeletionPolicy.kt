package com.bownee.lenswave.proton

/**
 * Which photos a timeline reconcile may delete the renditions of.
 *
 * A photo that left the timeline keeps its thumbnail, preview and original while an album
 * still shows it: an album-only photo is not a deleted one. The set of photos the other listings
 * name is read from disk at reconcile time, and a read that fails for a moment (a Keystore that
 * refuses to unwrap the data key, an I/O error) must not read as "no album references anything":
 * that reconcile then rewrites the listings but deletes nothing, and the next one sweeps.
 */
internal object ProtonReconcileDeletionPolicy {
    /**
     * The node uids in [removedNodeUids] whose renditions may go: those [referencedElsewhere]
     * does not name, or none at all when the references could not be read (null).
     */
    fun deletable(
        removedNodeUids: Collection<String>,
        referencedElsewhere: Set<String>?,
    ): List<String> =
        referencedElsewhere
            ?.let { referenced -> removedNodeUids.filterNot(referenced::contains) }
            .orEmpty()
}

/** A reconcile skipped its rendition deletes because a listing that names photos could not be read. */
internal class ProtonRenditionSweepSkippedException :
    IllegalStateException("A listing could not be read; renditions are kept until the next reconcile")
