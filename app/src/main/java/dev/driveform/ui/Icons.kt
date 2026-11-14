package dev.driveform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.driveform.form.First
import dev.driveform.form.Skill
import dev.driveform.form.Weather

/*
 * 아이콘. 대부분 Material Rounded를 쓰고, 계기판 기호처럼 차에서 익숙한 것(깜빡이, 운전대)은 직접 그립니다.
 */

/** 아이콘 + 글. 제목·라벨에 씁니다. */
@Composable
internal fun IconText(
    icon: ImageVector, text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp, gap: Dp = 8.dp, fontWeight: FontWeight? = null, iconTint: Color = color,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(gap))
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 둥근 바탕 위의 아이콘. 카드 머리에 씁니다. */
@Composable
internal fun IconBadge(icon: ImageVector, tint: Color, background: Color, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}

/** 졸업 과목(기술)의 아이콘: 깜빡이는 계기판의 좌우 화살표, 핸들은 운전대, 정지·출발은 신호등, 방지턱·카메라는 속도계. */
@Composable
internal fun SkillIcon(skill: Skill, tint: Color, modifier: Modifier = Modifier.size(26.dp)) {
    when (skill) {
        Skill.SIGNAL -> Canvas(modifier) {
            val w = size.width
            // 계기판의 방향지시등처럼 마주 보는 두 화살표.
            fun arrow(dir: Float) = Path().apply {
                val cx = w / 2 + dir * w * 0.06f
                moveTo(cx + dir * w * 0.42f, w * 0.5f)
                lineTo(cx + dir * w * 0.12f, w * 0.22f)
                lineTo(cx + dir * w * 0.12f, w * 0.38f)
                lineTo(cx, w * 0.38f)
                lineTo(cx, w * 0.62f)
                lineTo(cx + dir * w * 0.12f, w * 0.62f)
                lineTo(cx + dir * w * 0.12f, w * 0.78f)
                close()
            }
            drawPath(arrow(-1f), tint)
            drawPath(arrow(1f), tint)
        }
        Skill.STEER -> WheelIcon(tint, modifier)
        Skill.SMOOTH -> Icon(Icons.Rounded.Traffic, null, tint = tint, modifier = modifier)
        Skill.ROAD -> Icon(Icons.Rounded.Speed, null, tint = tint, modifier = modifier)
        Skill.PARK -> Icon(Icons.Rounded.LocalParking, null, tint = tint, modifier = modifier)
        Skill.WET -> Icon(Icons.Rounded.WaterDrop, null, tint = tint, modifier = modifier)
        Skill.NIGHT -> Icon(Icons.Rounded.DarkMode, null, tint = tint, modifier = modifier)
    }
}

/** 운전대. 코치 얼굴과 핸들 과목에 씁니다. */
@Composable
internal fun WheelIcon(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.4f, style = stroke)
        drawCircle(tint, w * 0.1f, style = stroke)
        drawLine(tint, Offset(w * 0.5f, w * 0.6f), Offset(w * 0.5f, w * 0.9f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.4f, w * 0.47f), Offset(w * 0.12f, w * 0.4f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.6f, w * 0.47f), Offset(w * 0.88f, w * 0.4f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

/** 날씨 아이콘: 빗길은 물방울, 안갯길은 가로 줄. 맑으면 null. */
internal fun weatherIcon(w: Weather): ImageVector? = when (w) {
    Weather.RAIN -> Icons.Rounded.WaterDrop
    Weather.FOG -> Icons.Rounded.Dehaze
    Weather.CLEAR -> null
}

internal val NightIcon: ImageVector get() = Icons.Rounded.DarkMode

internal fun firstIcon(f: First): ImageVector = when (f) {
    First.RAIN -> Icons.Rounded.WaterDrop
    First.FOG -> Icons.Rounded.Dehaze
    First.NIGHT -> Icons.Rounded.DarkMode
}

/** 전조등: 계기판의 전조등 표시처럼 둥근 등과 앞으로 뻗는 빛줄기 셋. */
@Composable
internal fun HeadlightIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(w * 0.5f, h * 0.2f)
            cubicTo(w * 0.02f, h * 0.2f, w * 0.02f, h * 0.8f, w * 0.5f, h * 0.8f)
            close()
        }, color, style = stroke)
        listOf(0.3f, 0.5f, 0.7f).forEach { y ->
            drawLine(color, Offset(w * 0.64f, h * y), Offset(w * 0.95f, h * (y + 0.06f)), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}
