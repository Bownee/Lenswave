package com.bownee.lenswave

import android.util.Log

/** Emits operation-level diagnostics without exception messages or user/content identifiers. */
internal object LenswaveDiagnostics {
    fun reportFailure(operation: String, error: Throwable) {
        Log.w(TAG, "operation=$operation failure=${error::class.java.name}")
    }

    private const val TAG = "Lenswave"
}
