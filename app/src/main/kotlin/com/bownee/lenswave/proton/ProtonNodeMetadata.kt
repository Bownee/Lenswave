package com.bownee.lenswave.proton

import me.proton.drive.sdk.entity.Node

internal fun Node.originalFileName(): String? = name.getOrNull()
    ?.trim()
    ?.takeIf(String::isNotBlank)
