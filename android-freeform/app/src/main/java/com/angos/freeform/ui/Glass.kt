package com.angos.freeform.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for the frosted-glass look. Every surface in the
 * app is built from this so the material stays consistent.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    tint: Color = MacColors.GlassTint,
    borderColor: Color = MacColors.GlassBorder,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    listOf(
                        tint.copy(alpha = tint.alpha + 0.06f),
                        tint.copy(alpha = (tint.alpha - 0.04f).coerceAtLeast(0f))
                    )
                )
            )
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius)),
        content = content
    )
}

/**
 * Applies a genuine gaussian blur to whatever is drawn *inside* the modifier
 * (API 31+). Used for the wallpaper layer that sits behind the glass panels so
 * the frost is real rather than faked with opacity.
 */
fun Modifier.frostedBackdrop(radius: Float = 48f): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else this
