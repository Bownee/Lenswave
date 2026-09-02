package com.bownee.lenswave

import java.util.Locale

/** Shared numerical contract for CPU export and GPU preview rendering. */
internal object PhotoAdjustmentSpec {
    const val LUMA_RED = 0.2126f
    const val LUMA_GREEN = 0.7152f
    const val LUMA_BLUE = 0.0722f
    const val SHADOW_EDGE = 0.55f
    const val HIGHLIGHT_EDGE = 0.45f
    const val LIGHT_STRENGTH = 0.35f
    const val WARMTH_STRENGTH = 0.12f
    const val TINT_RED_STRENGTH = 0.04f
    const val TINT_GREEN_STRENGTH = 0.07f
    const val TINT_BLUE_STRENGTH = 0.02f
    const val VIGNETTE_START = 0.35f
    const val VIGNETTE_STRENGTH = 0.65f
    const val CORNER_DISTANCE = 0.70710678f

    val FRAGMENT_SHADER: String = String.format(
        Locale.ROOT,
        """
        precision mediump float;
        uniform sampler2D u_texture;
        uniform float u_brightness;
        uniform float u_contrast;
        uniform float u_highlights;
        uniform float u_shadows;
        uniform float u_saturation;
        uniform float u_warmth;
        uniform float u_tint;
        uniform float u_vignette;
        uniform int u_rotation;
        varying vec2 v_texCoord;

        vec2 rotatedUv(vec2 uv) {
            if (u_rotation == 1) return vec2(uv.y, 1.0 - uv.x);
            if (u_rotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
            if (u_rotation == 3) return vec2(1.0 - uv.y, uv.x);
            return uv;
        }

        void main() {
            vec2 uv = rotatedUv(v_texCoord);
            vec4 color = texture2D(u_texture, uv);
            float luminance = dot(color.rgb, vec3(%1${'$'}f, %2${'$'}f, %3${'$'}f));
            float shadowMask = 1.0 - smoothstep(0.0, %4${'$'}f, luminance);
            float highlightMask = smoothstep(%5${'$'}f, 1.0, luminance);
            float lightShift = u_brightness
                    + u_shadows * shadowMask * %6${'$'}f
                    + u_highlights * highlightMask * %6${'$'}f;
            color.rgb += lightShift;
            color.rgb = (color.rgb - 0.5) * (1.0 + u_contrast) + 0.5;
            luminance = dot(color.rgb, vec3(%1${'$'}f, %2${'$'}f, %3${'$'}f));
            color.rgb = mix(vec3(luminance), color.rgb, 1.0 + u_saturation);
            color.r += u_warmth * %7${'$'}f + u_tint * %8${'$'}f;
            color.g -= u_tint * %9${'$'}f;
            color.b -= u_warmth * %7${'$'}f + u_tint * %10${'$'}f;
            float distanceFromCenter = length(v_texCoord - 0.5) / %11${'$'}f;
            float vignetteMask = smoothstep(%12${'$'}f, 1.0, distanceFromCenter);
            color.rgb *= 1.0 - u_vignette * vignetteMask * %13${'$'}f;
            gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
        }
        """.trimIndent(),
        LUMA_RED,
        LUMA_GREEN,
        LUMA_BLUE,
        SHADOW_EDGE,
        HIGHLIGHT_EDGE,
        LIGHT_STRENGTH,
        WARMTH_STRENGTH,
        TINT_RED_STRENGTH,
        TINT_GREEN_STRENGTH,
        TINT_BLUE_STRENGTH,
        CORNER_DISTANCE,
        VIGNETTE_START,
        VIGNETTE_STRENGTH,
    )
}
