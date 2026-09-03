package com.bownee.lenswave.gallery

/** Keeps a requested favorite state visible until the latest Proton-backed gallery state confirms it. */
internal class OptimisticFavoriteState {
    private val updates = mutableMapOf<String, Update>()

    fun reconcile(serverStates: Map<String, Boolean>) {
        updates.keys.retainAll(serverStates.keys)
        updates.entries.removeAll { (stableId, update) ->
            !update.inProgress && serverStates[stableId] == update.favorite
        }
    }

    fun begin(stableId: String, favorite: Boolean) {
        updates[stableId] = Update(favorite, inProgress = true)
    }

    fun finish(stableId: String, succeeded: Boolean, serverState: Boolean?) {
        val update = updates[stableId] ?: return
        if (!succeeded || serverState == update.favorite) {
            updates.remove(stableId)
        } else {
            updates[stableId] = update.copy(inProgress = false)
        }
    }

    fun displayedValue(stableId: String, serverState: Boolean): Boolean =
        updates[stableId]?.favorite ?: serverState

    fun isUpdating(stableId: String): Boolean = updates[stableId]?.inProgress == true

    fun clear() = updates.clear()

    private data class Update(
        val favorite: Boolean,
        val inProgress: Boolean,
    )
}
