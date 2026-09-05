package com.bownee.lenswave.proton

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.bownee.lenswave.core.R

@Composable
internal fun LenswaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = colorResource(R.color.lenswave_accent),
                onPrimary = colorResource(R.color.lenswave_on_accent),
                primaryContainer = colorResource(R.color.lenswave_accent_container),
                onPrimaryContainer = colorResource(R.color.lenswave_on_accent_container),
                secondary = colorResource(R.color.lenswave_secondary),
                onSecondary = colorResource(R.color.lenswave_on_secondary),
                secondaryContainer = colorResource(R.color.lenswave_secondary_container),
                onSecondaryContainer = colorResource(R.color.lenswave_on_secondary_container),
                background = colorResource(R.color.lenswave_background),
                onBackground = colorResource(R.color.lenswave_text),
                surface = colorResource(R.color.lenswave_surface),
                onSurface = colorResource(R.color.lenswave_text),
                surfaceVariant = colorResource(R.color.lenswave_surface_raised),
                onSurfaceVariant = colorResource(R.color.lenswave_muted),
                outline = colorResource(R.color.lenswave_border),
                error = colorResource(R.color.lenswave_error),
                onError = colorResource(R.color.lenswave_on_error),
            ),
        content = content,
    )
}
