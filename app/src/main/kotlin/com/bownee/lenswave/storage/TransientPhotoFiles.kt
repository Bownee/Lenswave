package com.bownee.lenswave.storage

import android.content.Context
import android.net.Uri
import java.io.File

object TransientPhotoFiles {
    fun deleteIfOwned(context: Context, uri: Uri?) {
        if (uri?.scheme != "file") return
        runCatching {
            val root = File(context.cacheDir, "proton-decrypted").canonicalFile
            val candidate = File(requireNotNull(uri.path)).canonicalFile
            if (candidate.toPath().startsWith(root.toPath())) candidate.delete()
        }
    }
}
