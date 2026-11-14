package dev.driveform.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * 계기판처럼 바뀌기: 왼쪽에서 다른 주행(또는 다른 동작)을 고르면 화면 틀은 그대로 두고,
 * 숫자·링·막대는 바늘처럼 지난 값에서 새 값으로 움직이고, 글은 살짝 새로 뜨며, 경로는 출발점부터 다시 그려집니다.
 * 움직이는 건 그리기 단계(graphicsLayer, Canvas)와 숫자 한 칸뿐이라 카드 전체를 매 프레임 다시 짜지 않습니다.
 */

private const val ROLL_MS = 650

/** 바늘처럼 움직이는 값. 처음엔 바로 보이고, 값이 바뀌면 지난 값에서 새 값으로 갑니다. 그리기 단계나 작은 칸에서만 읽습니다. */
@Composable
internal fun rolling(value: Float): State<Float> {
    val a = remember { Animatable(value) }
    LaunchedEffect(value) { a.animateTo(value, tween(ROLL_MS, easing = FastOutSlowInEasing)) }
    return a.asState()
}

/** 바늘처럼 오르내리는 숫자 한 칸. 이 칸만 다시 그려집니다. */
@Composable
internal fun RollingNumber(value: Int, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier, lineHeight: TextUnit = TextUnit.Unspecified) {
    val v by rolling(value.toFloat())
    Text("${v.roundToInt()}", color = color, fontSize = fontSize, fontWeight = FontWeight.Bold, lineHeight = lineHeight, modifier = modifier)
}

/**
 * [key]가 바뀌면 0 → 1로 다시 차오르는 진행도(처음 그릴 때는 1). 그리기 단계에서 읽습니다.
 * 바뀐 바로 그 프레임(효과가 돌기 전)에도 0을 돌려, 새 내용이 다 그려진 채 한 번 비치지 않게 합니다.
 */
@Composable
private fun replay(key: Any?, durationMs: Int): () -> Float {
    val a = remember { Animatable(1f) }
    var last by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key == last) return@LaunchedEffect
        last = key
        a.snapTo(0f)
        a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    val pending = key != last
    return { if (pending) 0f else a.value }
}

/** 경로를 차가 지나가듯 출발점부터 다시 그리는 정도. */
@Composable
internal fun drawProgress(key: Any): () -> Float = replay(key, ROLL_MS + 150)

/** [key]가 바뀌면 글이 살짝 새로 뜹니다(숫자처럼 움직일 수 없는 글을 부드럽게 바꿉니다). */
internal fun Modifier.freshen(key: Any?): Modifier = composed {
    val p = replay(key, 320)
    graphicsLayer {
        val v = p()
        alpha = v
        translationY = (1 - v) * 8.dp.toPx()
    }
}
