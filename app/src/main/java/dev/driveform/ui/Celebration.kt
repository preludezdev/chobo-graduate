package dev.driveform.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.form.Course
import dev.driveform.form.Graduation
import dev.driveform.form.Skill
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val dateFmt = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

/**
 * 초보 졸업 축하. 기본기 네 칸이 고리를 따라 차례로 차오르고, 다 차면 코치 표시가 나타나며 빛 점이 퍼집니다.
 * 문구는 고생과 축하만 짧게 합니다(새로 생긴 실력을 단정하지 않습니다).
 */
@Composable
internal fun GraduationCelebration(grad: Graduation, trips: Int, hero: ImageBitmap?, onAdvanced: () -> Unit, onClose: () -> Unit) {
    val ring = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val rows = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        ring.animateTo(1f, tween(1_800, easing = FastOutSlowInEasing))
        val a = async { mark.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
        val b = async { burst.animateTo(1f, tween(1_400, easing = LinearOutSlowInEasing)) }
        delay(250)
        text.animateTo(1f, tween(500))
        rows.animateTo(1f, tween(900))
        a.await(); b.await()
    }
    val courses = grad.courses.sortedBy { it.passedAt ?: Long.MAX_VALUE }
    Box(
        Modifier.fillMaxSize().background(Coach.Bg.copy(alpha = 0.94f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 가는 고리가 기본기 네 칸만큼 차오르면, 그 안에서 내 차가 떠오르고 바닥에 빛이 번집니다.
            Box(Modifier.size(460.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    val r = size.minDimension / 2 - 40.dp.toPx()
                    val tl = Offset(center.x - r, center.y - r)
                    val sz = Size(r * 2, r * 2)
                    // 바닥 빛: 차 아래 타원
                    val glow = mark.value.coerceIn(0f, 1f)
                    if (glow > 0f) drawOval(
                        Brush.radialGradient(listOf(Coach.Accent.copy(alpha = 0.55f * glow), Color.Transparent), center = Offset(center.x, center.y + r * 0.35f), radius = r * 0.9f),
                        topLeft = Offset(center.x - r * 0.95f, center.y + r * 0.05f), size = Size(r * 1.9f, r * 0.6f),
                    )
                    drawCircle(Coach.Track, r, center, style = Stroke(stroke))
                    val seg = 360f / courses.size.coerceAtLeast(1)
                    val gap = 6f
                    courses.indices.forEach { i ->
                        val f = ((ring.value * courses.size) - i).coerceIn(0f, 1f)
                        if (f > 0f) drawArc(Coach.Accent, -90f + i * seg + gap / 2, (seg - gap) * f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    // 칸마다 끝에 작은 점(완성 표시)
                    courses.indices.forEach { i ->
                        if (ring.value * courses.size >= i + 1) {
                            val ang = Math.toRadians((-90.0 + (i + 1) * seg - gap / 2))
                            drawCircle(Coach.Text, 5.dp.toPx(), Offset(center.x + (cos(ang) * r).toFloat(), center.y + (sin(ang) * r).toFloat()))
                        }
                    }
                    val b = burst.value
                    if (b > 0f) repeat(18) { k ->
                        val ang = Math.toRadians(k * 20.0 + 10.0)
                        val dist = r + 14.dp.toPx() + b * (30 + (k % 3) * 16).dp.toPx()
                        val o = Offset(center.x + (cos(ang) * dist).toFloat(), center.y + (sin(ang) * dist).toFloat())
                        drawCircle(if (k % 3 == 0) Coach.AmberSoft else Coach.Accent, ((1 - b) * 6 + 1.5f).dp.toPx(), o, alpha = (1 - b).coerceIn(0f, 1f))
                    }
                }
                hero?.let { img ->
                    Image(
                        img, "내 차",
                        Modifier.size(330.dp).graphicsLayer {
                            val m = mark.value
                            alpha = m.coerceIn(0f, 1f); translationY = (1 - m) * 60.dp.toPx(); scaleX = 0.85f + 0.15f * m; scaleY = scaleX
                        },
                    )
                } ?: Box(Modifier.graphicsLayer { scaleX = mark.value; scaleY = mark.value }) { CoachMark(110.dp) }
            }
            Column(Modifier.graphicsLayer { alpha = text.value; translationY = (1 - text.value) * 24.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("초보 졸업", color = Coach.Text, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.size(8.dp))
                Text("그동안 고생 많으셨어요. 축하드려요!", color = Coach.Text2, fontSize = 26.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.size(6.dp))
                Text(listOfNotNull(grad.graduatedAt?.let { day(it) }, "운전 ${trips}번").joinToString(" · "), color = Coach.Muted, fontSize = 19.sp)
            }
            Spacer(Modifier.size(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                courses.forEachIndexed { i, c ->
                    val f = ((rows.value * courses.size) - i).coerceIn(0f, 1f)
                    CourseChip(c, Modifier.graphicsLayer { alpha = f; translationY = (1 - f) * 16.dp.toPx() })
                }
            }
            Spacer(Modifier.size(36.dp))
            Row(Modifier.graphicsLayer { alpha = rows.value }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "심화 과정 보기", color = Coach.AccentInk, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Coach.Accent).press(RoundedCornerShape(16.dp), onClick = onAdvanced).padding(horizontal = 34.dp, vertical = 18.dp),
                )
                Text(
                    "닫기", color = Coach.Text2, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(2.dp, Coach.Line2, RoundedCornerShape(16.dp)).press(RoundedCornerShape(16.dp), onClick = onClose).padding(horizontal = 34.dp, vertical = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun CourseChip(c: Course, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Coach.Panel).border(1.dp, Coach.Line2, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(Coach.AccentDeep, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Coach.Accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(c.skill.label, color = Coach.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        c.passedAt?.let { Text("  ${day(it)}", color = Coach.Muted, fontSize = 16.sp) }
    }
}

/** 심화 과목(주차·빗길·밤길)을 마쳤을 때의 작은 축하. 배지가 튀어 오른 뒤 인사를 띄웁니다. */
@Composable
internal fun BadgeCelebration(skill: Skill, onClose: () -> Unit) {
    val pop = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(skill) {
        delay(200)
        val a = async { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 240f)) }
        burst.animateTo(1f, tween(1_200, easing = LinearOutSlowInEasing))
        a.await()
    }
    Box(
        Modifier.fillMaxSize().background(Coach.Bg.copy(alpha = 0.8f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(620.dp).clip(RoundedCornerShape(28.dp)).background(Coach.Panel).border(1.dp, Coach.Line2, RoundedCornerShape(28.dp)).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = burst.value
                    if (b > 0f) repeat(12) { k ->
                        val ang = Math.toRadians(k * 30.0)
                        val dist = 56.dp.toPx() + b * 28.dp.toPx()
                        drawCircle(Coach.Amber, ((1 - b) * 5 + 2).dp.toPx(), Offset(center.x + (cos(ang) * dist).toFloat(), center.y + (sin(ang) * dist).toFloat()), alpha = 1 - b)
                    }
                }
                Box(
                    Modifier.size(110.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.background(Coach.AmberDeep, CircleShape).border(4.dp, Coach.Amber, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (skill) { Skill.PARK -> Icons.Rounded.LocalParking; Skill.WET -> Icons.Rounded.WaterDrop; Skill.NIGHT -> Icons.Rounded.DarkMode; else -> Icons.Rounded.Check },
                        null, tint = Coach.Amber, modifier = Modifier.size(56.dp),
                    )
                }
            }
            Spacer(Modifier.size(18.dp))
            Text("${skill.label} 배지", color = Coach.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.size(12.dp))
            Text(badgeLine(skill), color = Coach.Text2, fontSize = 23.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.size(28.dp))
            Text(
                "확인", color = Coach.AccentInk, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Coach.Accent).press(RoundedCornerShape(16.dp), onClick = onClose).padding(horizontal = 48.dp, vertical = 16.dp),
            )
        }
    }
}

private fun badgeLine(skill: Skill) = when (skill) {
    Skill.PARK -> "그동안 주차 연습하느라 고생 많으셨어요.\n축하드려요!"
    Skill.WET -> "비 오는 날 운전, 쉽지 않으셨죠.\n고생 많으셨어요. 축하드려요!"
    Skill.NIGHT -> "밤길 운전 연습하느라 고생 많으셨어요.\n축하드려요!"
    else -> "고생 많으셨어요. 축하드려요!"
}
