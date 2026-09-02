package com.bownee.lenswave.proton

import java.time.Instant
import me.proton.drive.sdk.entity.Author
import me.proton.drive.sdk.entity.FileRevision
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.PhotoNode
import me.proton.drive.sdk.entity.RevisionState
import me.proton.drive.sdk.entity.RevisionUid
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtonTrashPhotoMapperTest {
    @Test
    fun mapsPhotoNodeAndUsesTrashDate() {
        val photo = photoNode().toProtonTrashPhoto(hasThumbnail = true)

        assertEquals(
            ProtonTrashPhoto(
                nodeUid = "node-1",
                trashedAtEpochSeconds = 2_000L,
                hasThumbnail = true,
                displayName = "photo.jpg",
            ),
            photo,
        )
    }

    @Test
    fun ignoresNonImagePhotoNode() {
        assertNull(photoNode(mediaType = "application/octet-stream").toProtonTrashPhoto(hasThumbnail = false))
    }

    @Test
    fun originalFileNameIgnoresBlankAndUnavailableNames() {
        assertNull(photoNode(name = Result.success("   ")).originalFileName())
        assertNull(photoNode(name = Result.failure(IllegalStateException("locked"))).originalFileName())
    }

    private fun photoNode(
        mediaType: String = "image/jpeg",
        name: Result<String> = Result.success("photo.jpg"),
    ) = PhotoNode(
        uid = NodeUid("node-1"),
        parentUid = null,
        treeEventScopeId = ScopeId("scope-1"),
        name = name,
        mediaType = mediaType,
        creationTime = Instant.ofEpochSecond(1_000L),
        trashTime = Instant.ofEpochSecond(2_000L),
        nameAuthor = Result.success(Author("owner@example.com")),
        keyAuthor = Result.success(Author("owner@example.com")),
        ownedBy = OwnedBy(email = "owner@example.com", organization = null),
        activeRevision = FileRevision(
            uid = RevisionUid("revision-1"),
            state = RevisionState.ACTIVE,
            creationTime = Instant.ofEpochSecond(1_000L),
            storageSize = 100L,
            claimedSize = null,
            claimedDigests = null,
            claimedModificationTime = null,
            thumbnails = emptyList(),
            claimedAdditionalMetadata = null,
            contentAuthor = null,
        ),
        totalStorageSize = 100L,
        isShared = false,
        isSharedByUrl = false,
        errors = emptyList(),
        captureTime = Instant.ofEpochSecond(900L),
        albumUids = emptyList(),
    )
}
