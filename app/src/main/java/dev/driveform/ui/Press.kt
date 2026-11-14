package dev.driveform.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 누르면 살짝 눌려 들어가고(어두워지고), 떼면 통 하고 튀어 돌아옵니다.
 * 짧게 톡 쳐도 눌린 모습이 보이도록 눌림을 끝까지 보여 준 뒤 돌아오고, 그 뒤에 [onClick]을 부릅니다(최대 0.12초).
 * 바탕(background)보다 앞에 두어야 바탕까지 같이 줄어듭니다. [shape]를 주면 그 모양으로 살짝 어둡게 덮습니다.
 */
internal fun Modifier.press(
    shape: Shape? = null,
    scale: Float = 0.95f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val down = remember { Animatable(0f) } // 0 = 놓음, 1 = 다 눌림. 놓을 때 0 아래로 살짝 넘어가며 튑니다.
    val scope = rememberCoroutineScope()
    val dim = if (IsNight) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    LaunchedEffect(source) {
        source.interactions.collectLatest { i ->
            when (i) {
                is PressInteraction.Press -> down.animateTo(1f, tween(90))
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (down.value < 0.9f) down.animateTo(1f, tween(70))
                    down.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 520f))
                }
            }
        }
    }
    Modifier
        .graphicsLayer {
            val s = 1f - (1f - scale) * down.value
            scaleX = s; scaleY = s
        }
        .drawWithContent {
            drawContent()
            val v = down.value.coerceIn(0f, 1f)
            if (shape != null && v > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), dim.copy(alpha = dim.alpha * v))
        }
        .clickable(source, indication = null) {
            scope.launch {
                withTimeoutOrNull(120) { snapshotFlow { down.value }.first { it >= 0.95f } }
                onClick()
            }
        }
}
