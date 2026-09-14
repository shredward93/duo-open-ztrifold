package com.duoopen.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.duoopen.fold.TriFoldLine
import com.duoopen.fold.TriShader
import com.duoopen.settings.DuoConfig

/**
 * Applies the three-pane tri-fold to this layout subtree. [tiltLeft] and
 * [tiltRight] are read inside the layer block, so a moving hinge only re-runs
 * the layer, not composition. The center pane is rendered crisp by the shader.
 *
 * @param fold Hinge placement in layer px; null = thirds from the config.
 */
fun Modifier.triFoldEffect(
    shader: RuntimeShader,
    tiltLeft: () -> Float,
    tiltRight: () -> Float,
    config: DuoConfig,
    pxPerMm: Float,
    fold: TriFoldLine?,
): Modifier = graphicsLayer {
    val tl = tiltLeft()
    val tr = tiltRight()
    val w = size.width
    val h = size.height
    if ((tl < TriShader.FLAT_EPSILON && tr < TriShader.FLAT_EPSILON) || w <= 1f || h <= 1f) {
        renderEffect = null
        return@graphicsLayer
    }
    val line = fold ?: TriShader.triFold(w, h, config.foldSplitsLong)
    TriShader.setUniforms(shader, w, h, tl, tr, config, pxPerMm, line)
    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    clip = true
}
