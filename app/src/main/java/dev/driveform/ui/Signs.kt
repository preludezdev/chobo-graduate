package dev.driveform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.R
import dev.driveform.form.Skill

/*
 * '초보운전 딱지 × 도로 표지판'의 공통 부품.
 * 표지판 모양(색 면 + 흰 안쪽 테두리 + 검정 바깥 테두리)은 진짜 표지(이정표·주의·안내·원형·딱지)에만 쓰고,
 * 나머지는 테두리 없는 [PlainCard]로 둡니다. 문장은 기본 글꼴 굵게, 도현체([SignFont])는 표지 낱말과 숫자에만 씁니다.
 */

/** 도로 표지판 같은 글꼴. 표지 낱말("졸업", "1번")과 숫자에만 씁니다. */
internal val SignFont = FontFamily(Font(R.font.do_hyeon))

private val Ink = Color(0xFF111111)

/** 딱지의 네 귀퉁이. 졸업 과목 하나가 귀퉁이 하나입니다. */
internal enum class Corner { TL, TR, BL, BR }

internal val Skill.corner: Corner get() = when (this) {
    Skill.STEER -> Corner.TL
    Skill.SMOOTH -> Corner.TR
    Skill.ROAD -> Corner.BL
    Skill.SIGNAL -> Corner.BR
    else -> error("심화 과목(${this})에는 딱지 귀퉁이가 없어요")
}

internal val Corner.label: String get() = when (this) {
    Corner.TL -> "왼쪽 위"; Corner.TR -> "오른쪽 위"; Corner.BL -> "왼쪽 아래"; Corner.BR -> "오른쪽 아래"
}

internal val Skill.short: String get() = when (this) {
    Skill.SIGNAL -> "깜빡이"; Skill.STEER -> "핸들"; Skill.SMOOTH -> "정지·출발"; Skill.ROAD -> "방지턱"
    Skill.PARK -> "주차"; Skill.WET -> "빗길"; Skill.NIGHT -> "밤길"
}

/** 심화 과목을 연습할 수 있는 때. 비·밤은 고를 수 없어서 기회가 올 때 쌓입니다. */
internal val Skill.chance: String get() = when (this) {
    Skill.PARK -> "운전 끝날 때마다"
    Skill.WET -> "비·안개 오는 날"
    Skill.NIGHT -> "해 진 뒤"
    else -> "운전할 때마다"
}

/** 과목마다 옆자리 선생님이 건네는 요령 한마디. */
internal val Skill.tip: String get() = when (this) {
    Skill.SIGNAL -> "꺾기 30m 전에 깜빡이 켜 두기"
    Skill.STEER -> "꺾을 때도 풀 때도 한 번에"
    Skill.SMOOTH -> "멈출 곳이 보이면 일찍, 살살"
    Skill.ROAD -> "방지턱 앞에선 미리 천천히"
    Skill.PARK -> "뒤로 한 번에, 걷는 속도로"
    Skill.WET -> "젖은 길에선 한 박자 일찍, 살살"
    Skill.NIGHT -> "밤엔 한 박자 천천히, 멀리 보기"
}

/** 테두리 없는 카드. 낮에는 옅은 그림자, 밤에는 가는 테두리로 바탕과 나눕니다. */
@Composable
internal fun PlainCard(modifier: Modifier = Modifier, highlight: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val base = if (IsNight) modifier.border(1.dp, Line, shape) else modifier.shadow(6.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
    val hl = if (highlight) Modifier.border(4.dp, SignYellow, shape) else Modifier
    Box(base.background(Surface, shape).then(hl), content = content)
}

internal enum class SignKind { GUIDE, INFO, CAUTION }

/**
 * 진짜 표지판. 낮에는 실제 표지처럼 색 면에 흰 안쪽 테두리와 검정 바깥 테두리(주의 표지는 검정 테두리만),
 * 밤에는 눈부시지 않게 어두운 면에 색 테두리만 둡니다.
 */
@Composable
internal fun SignBox(kind: SignKind, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val color = when (kind) { SignKind.GUIDE -> Good; SignKind.INFO -> SignBlue; SignKind.CAUTION -> SignYellow }
    val r = 18.dp
    if (IsNight) {
        Box(modifier.background(Surface, RoundedCornerShape(r)).border(4.dp, color, RoundedCornerShape(r)), content = content)
        return
    }
    val face = if (kind == SignKind.GUIDE) GoodFill else color
    if (kind == SignKind.CAUTION) {
        Box(modifier.shadow(6.dp, RoundedCornerShape(r)).background(face, RoundedCornerShape(r)).border(4.dp, Ink, RoundedCornerShape(r)), content = content)
    } else {
        Box(
            modifier.shadow(6.dp, RoundedCornerShape(r + 3.dp)).background(Ink, RoundedCornerShape(r + 3.dp)).padding(3.dp)
                .background(Color.White, RoundedCornerShape(r)).padding(5.dp).background(face, RoundedCornerShape(r - 4.dp)),
            content = content,
        )
    }
}

/** 표지 위 글자색: 낮의 초록·파랑 표지는 흰 글자, 노랑 표지는 검정, 밤에는 본문색. */
internal fun signInk(kind: SignKind): Color = when {
    IsNight -> TextPrimary
    kind == SignKind.CAUTION -> Ink
    else -> Color.White
}

/** 노란 마름모 주의 표지. 아직 남은 것, 이번 주 과제에 씁니다. */
@Composable
internal fun Diamond(size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.size(size * 1.42f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).rotate(45f)
                .background(if (IsNight) Surface else SignYellow, RoundedCornerShape(size * 0.12f))
                .border(3.dp, if (IsNight) SignYellow else Ink, RoundedCornerShape(size * 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.rotate(-45f), contentAlignment = Alignment.Center, content = content) }
    }
}

/** 원형 표지(초록 테두리). 이수한 과목, 점수에 씁니다. */
@Composable
internal fun RoundSign(size: Dp, modifier: Modifier = Modifier, ring: Dp = size * 0.11f, content: @Composable BoxScope.() -> Unit) {
    val outer = if (IsNight) modifier else modifier.background(Ink, CircleShape).padding(2.dp).background(Color.White, CircleShape).padding(3.dp)
    Box(
        outer.size(size).background(if (IsNight) Surface else Color.White, CircleShape).border(ring, Good, CircleShape),
        contentAlignment = Alignment.Center, content = content,
    )
}

/** 표지 아이콘 색: 낮에는 검정, 밤에는 본문색. */
internal val SignIcon: Color get() = if (IsNight) TextPrimary else Ink

/** 이수 판(초록) / 남은 판(노랑). */
@Composable
internal fun Plate(done: Boolean, text: String = if (done) "이수" else "한 번 남음") {
    Text(
        text, fontFamily = SignFont, fontSize = 26.sp, color = if (done) Color.White else Ink,
        modifier = Modifier.background(if (done) GoodFill else SignYellow, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

/** 표지 글꼴 숫자·낱말. */
@Composable
internal fun SignText(text: String, size: TextUnit, color: Color = TextPrimary, modifier: Modifier = Modifier) {
    Text(text, fontFamily = SignFont, fontSize = size, color = color, lineHeight = size, modifier = modifier)
}

/**
 * 초보운전 딱지. [peeled] 귀퉁이는 들려서 뒷면이 보입니다(과목 하나를 마칠 때마다 한 귀퉁이).
 * [lift](0~1)는 들린 귀퉁이 크기, [textSize]는 딱지 글자 크기.
 */
@Composable
internal fun Ddakji(peeled: Set<Corner>, modifier: Modifier, lift: (Corner) -> Float = { 1f }, textSize: TextUnit = 72.sp) {
    val measurer = rememberTextMeasurer()
    val yellow = SignYellow
    val back = if (IsNight) listOf(Color(0xFFBDB7A8), Color(0xFF8F897C)) else listOf(Color.White, Color(0xFFD3CCBA))
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // 귀퉁이마다 들린 크기(들리지 않았으면 0).
        val dd = Corner.entries.associateWith { c -> if (c in peeled) h * 0.34f * lift(c).coerceIn(0f, 1f) else 0f }
        val tl = dd.getValue(Corner.TL); val tr = dd.getValue(Corner.TR); val br = dd.getValue(Corner.BR); val bl = dd.getValue(Corner.BL)
        val body = Path().apply {
            moveTo(tl, 0f)
            lineTo(w - tr, 0f); lineTo(w, tr)
            lineTo(w, h - br); lineTo(w - br, h)
            lineTo(bl, h); lineTo(0f, h - bl)
            lineTo(0f, tl)
            close()
        }
        // 그림자 → 몸체 → 글자 → 들린 귀퉁이
        translate(0f, 6.dp.toPx()) { drawPath(body, Color(0x40000000)) }
        drawPath(body, yellow)
        clipPath(body) {
            val t = measurer.measure("초보운전", TextStyle(fontFamily = SignFont, fontSize = textSize, color = Ink))
            drawText(t, topLeft = Offset((w - t.size.width) / 2, (h - t.size.height) / 2))
        }
        dd.forEach { (c, d) -> if (d > 0.5f) drawFlap(c, w, h, d, back) }
    }
}

private fun DrawScope.drawFlap(c: Corner, w: Float, h: Float, d: Float, back: List<Color>) {
    val p = Path().apply {
        when (c) {
            Corner.TL -> { moveTo(d, 0f); lineTo(0f, d); lineTo(d, d) }
            Corner.TR -> { moveTo(w - d, 0f); lineTo(w, d); lineTo(w - d, d) }
            Corner.BL -> { moveTo(0f, h - d); lineTo(d, h); lineTo(d, h - d) }
            Corner.BR -> { moveTo(w, h - d); lineTo(w - d, h); lineTo(w - d, h - d) }
        }
        close()
    }
    translate(3.dp.toPx(), 4.dp.toPx()) { drawPath(p, Color(0x4D000000)) }
    drawPath(p, Brush.linearGradient(back))
}

/** 뒷유리. 딱지와 귀퉁이 표시를 올려놓는 어두운 유리입니다. */
@Composable
internal fun Glass(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val edge = if (IsNight) Color(0xFF20252B) else Color(0xFF3A4049)
    val (a, b) = if (IsNight) Color(0xFF0A0C0E) to Color(0xFF1A1F25) else Color(0xFF1E2329) to Color(0xFF2B323B)
    Box(
        modifier.background(Brush.linearGradient(listOf(a, b, a)), RoundedCornerShape(24.dp)).border(10.dp, edge, RoundedCornerShape(24.dp)),
        content = content,
    )
}

/** 초록 이정표: 위쪽 화살표, 큰 제목, 작은 설명, 오른쪽에 남은 것(예: 깜빡이 1번). */
@Composable
internal fun GuideSign(title: String, sub: String, modifier: Modifier = Modifier, right: Triple<String, String, String>? = null, titleSize: TextUnit = 84.sp) {
    val ink = signInk(SignKind.GUIDE)
    SignBox(SignKind.GUIDE, modifier) {
        Row(Modifier.padding(horizontal = 30.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 76.dp, height = 92.dp)) {
                val w = size.width; val hh = size.height
                drawPath(Path().apply {
                    moveTo(w * 0.5f, 0f); lineTo(w, hh * 0.42f); lineTo(w * 0.7f, hh * 0.42f); lineTo(w * 0.7f, hh)
                    lineTo(w * 0.3f, hh); lineTo(w * 0.3f, hh * 0.42f); lineTo(0f, hh * 0.42f); close()
                }, if (IsNight) Good else Color.White)
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                SignText(title, titleSize, ink)
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(sub, color = ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (right != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(right.first, color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        SignText(right.second, 92.sp, ink)
                        SignText(right.third, 40.sp, ink, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}

/** 진도 막대: 아스팔트 위 차선 점선, 초록으로 채운 만큼 노란 마름모가 달려갑니다. 눈금은 과목(귀퉁이) 네 칸. */
@Composable
internal fun RoadBar(fraction: Float, modifier: Modifier = Modifier) {
    val green = Good; val yellow = SignYellow
    Canvas(modifier.fillMaxWidth().height(54.dp)) {
        val r = CornerRadius(12.dp.toPx())
        drawRoundRect(Color(0xFF3A3E44), cornerRadius = r)
        val f = fraction.coerceIn(0f, 1f)
        drawRoundRect(green, size = Size(size.width * f, size.height), cornerRadius = r)
        drawLine(Color.White.copy(alpha = 0.85f), Offset(16.dp.toPx(), size.height / 2), Offset(size.width - 16.dp.toPx(), size.height / 2), 4.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(22.dp.toPx(), 14.dp.toPx())))
        listOf(0.25f, 0.5f, 0.75f).forEach { x -> drawLine(Color.White.copy(alpha = 0.3f), Offset(size.width * x, 0f), Offset(size.width * x, size.height), 3.dp.toPx()) }
        val cx = (size.width * f).coerceIn(24.dp.toPx(), size.width - 24.dp.toPx())
        val s = 19.dp.toPx()
        val diamond = Path().apply { moveTo(cx, size.height / 2 - s); lineTo(cx + s, size.height / 2); lineTo(cx, size.height / 2 + s); lineTo(cx - s, size.height / 2); close() }
        drawPath(diamond, yellow)
        drawPath(diamond, Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
    }
}

/** 칸 다섯 개짜리 연속 막대(이번 주 과제 4/5 같은). */
@Composable
internal fun Segments(done: Int, total: Int, on: Color, off: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(16.dp)) {
        repeat(total) { i ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < done) on else off, RoundedCornerShape(4.dp)))
        }
    }
}

/** 최근 열 번의 결과 점(잘함은 채운 칸, 아쉬움은 빈 칸, 아직 안 해 본 칸은 옅게). */
@Composable
internal fun ResultDots(results: List<Boolean>, on: Color, modifier: Modifier = Modifier, window: Int = 10) {
    Row(modifier.height(16.dp)) {
        repeat(window) { i ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            val r = results.getOrNull(i - (window - results.size))
            val m = Modifier.weight(1f).fillMaxHeight()
            Box(
                when (r) {
                    true -> m.background(on, RoundedCornerShape(4.dp))
                    false -> m.border(3.dp, Dot, RoundedCornerShape(4.dp))
                    null -> m.background(Track, RoundedCornerShape(4.dp))
                },
            )
        }
    }
}
