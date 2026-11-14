package dev.driveform.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.Screen
import dev.driveform.UiState
import dev.driveform.form.Coaching
import dev.driveform.form.Course
import dev.driveform.form.Firsts
import dev.driveform.form.Graduation
import dev.driveform.form.Mission
import dev.driveform.form.Skill
import dev.driveform.form.Stage
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * 졸업 진도(홈), 졸업 순간, 시작하기. 앱의 목표는 점수가 아니라 뒷유리의 초보운전 딱지를 떼는 날입니다.
 * 딱지 네 귀퉁이가 졸업 과목 네 개라서, 과목을 하나 마칠 때마다 한 귀퉁이씩 들리고 네 귀퉁이가 다 들리면 딱지를 뗍니다.
 * 문구는 옆자리 선생님이 말해 주듯 씁니다.
 */

private val gradDate = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val InkBlack = Color(0xFF111111)

/** 받침이 있으면 [with], 없으면 [without]("깜빡이를", "핸들을"). */
internal fun josa(word: String, with: String, without: String): String {
    val c = word.lastOrNull { it in '가'..'힣' } ?: return without
    return if ((c - '가') % 28 != 0) with else without
}

/** 마지막 과목을 마치려면 다음에 무엇을 하면 되는지, 말하듯. */
private fun nextAction(skill: Skill) = when (skill) {
    Skill.SIGNAL -> "꺾을 때 깜빡이를 조금 일찍 켜 주시면"
    Skill.STEER -> "꺾을 때 핸들을 한 번에 돌려 주시면"
    Skill.SMOOTH -> "멈출 때 브레이크를 살살 밟아 주시면"
    Skill.ROAD -> "방지턱 앞에서 미리 천천히 가 주시면"
    Skill.PARK -> "뒤로 한 번에 천천히 넣어 주시면"
    Skill.WET -> "젖은 길에서 한 박자 일찍 줄여 주시면"
    Skill.NIGHT -> "밤길에서도 지금처럼 차분히 가 주시면"
}

@Composable
internal fun GraduationScreen(state: UiState, onShow: (Screen) -> Unit, onSelectTrip: (Int) -> Unit, modifier: Modifier) {
    if (!state.graduation.graduated) return GradHome(state, onShow, onSelectTrip, modifier)
    // 방금 졸업했으면 졸업 장면부터, 그 뒤로는 심화 과정이 홈입니다. 심화 화면에서 졸업 증명을 다시 볼 수 있습니다.
    val just = !state.graduationBefore.graduated
    var celebrate by remember(state.graduation.graduatedAt, just) { mutableStateOf(just) }
    if (celebrate) GraduatedScreen(state, onAdvance = { celebrate = false }, modifier)
    else AdvancedHome(state, onShow, onSelectTrip, onCertificate = { celebrate = true }, modifier)
}

@Composable
private fun GradHome(state: UiState, onShow: (Screen) -> Unit, onSelectTrip: (Int) -> Unit, modifier: Modifier) {
    val g = state.graduation
    val before = state.graduationBefore
    val latest = state.tripSummaries.firstOrNull()
    val coaching = latest?.let { state.coachingFor(it) }
    val passed = g.courses.filter { it.passed }.map { it.skill.corner }.toSet()
    val fresh = g.courses.filter { it.passed && before.courses.firstOrNull { b -> b.skill == it.skill }?.passed != true }.map { it.skill.corner }.toSet()
    // 이번 주행으로 새로 들린 귀퉁이만 천천히 들어 올립니다.
    val lift = remember { Animatable(0f) }
    LaunchedEffect(fresh) { lift.snapTo(0f); lift.animateTo(1f, tween(1200, delayMillis = 500, easing = FastOutSlowInEasing)) }
    val remaining = g.remaining
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(680.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            GuideSign(
                "졸업", "초보운전 딱지 떼는 곳", Modifier.fillMaxWidth(),
                right = if (remaining.size == 1) Triple(remaining.first().skill.short, "${remaining.first().toGo}", "번")
                else Triple("남은 과목", "${remaining.size}", "개"),
            )
            Glass(Modifier.fillMaxWidth().height(400.dp)) {
                Ddakji(
                    passed, Modifier.align(Alignment.Center).size(380.dp, 176.dp).graphicsLayer { rotationZ = -2f },
                    lift = { c -> if (c in fresh) lift.value else 1f }, textSize = 74.sp,
                )
                g.courses.forEach { c -> CornerTag(c, Modifier.align(c.skill.corner.alignment).padding(24.dp)) }
            }
            Text(homeMessage(g), color = TextPrimary, fontSize = 26.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium)
            NextStage(Modifier)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                RollingNumber((g.progress * 100).roundToInt(), TextPrimary, 56.sp, lineHeight = 56.sp)
                SignText("%", 30.sp, TextSecondary, Modifier.padding(start = 4.dp, bottom = 6.dp))
                Spacer(Modifier.width(18.dp))
                Text("귀퉁이 ${passed.size} / 4", color = TextSecondary, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            }
            RoadBar(g.progress)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("졸업 과목", color = TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(16.dp))
                Text("열 번 중 아홉 번 잘하면 귀퉁이 하나가 들려요", color = TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            val mission = coaching?.mission?.skill?.takeIf { s -> g.courses.any { it.skill == s && !it.passed } }
            g.courses.chunked(2).forEach { pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    pair.forEach { CourseCard(it, it.skill == mission, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            MissionAndLatest(state, onShow, onSelectTrip)
        }
    }
}

/** 홈 맨 아래: 이번 주 과제(노란 주의 표지)와 가장 최근 운전 돌아보기(파란 안내 표지). */
@Composable
private fun MissionAndLatest(state: UiState, onShow: (Screen) -> Unit, onSelectTrip: (Int) -> Unit) {
    val latest = state.tripSummaries.firstOrNull()
    val coaching = latest?.let { state.coachingFor(it) }
    Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        if (coaching != null) {
            val m = coaching.mission
            val ink = signInk(SignKind.CAUTION)
            SignBox(SignKind.CAUTION, Modifier.weight(1.2f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Diamond(30.dp) { Icon(Icons.Rounded.Flag, null, tint = SignIcon, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("이번 주엔 ${m.skill.short}${josa(m.skill.short, "을", "를")} 연습해요", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        SignText(if (m.completed) "${m.target}번 연속 성공!" else "${m.done} / ${m.target}번 연속", 36.sp, ink)
                    }
                    Spacer(Modifier.width(18.dp))
                    Segments(m.done, m.target, if (IsNight) SignYellow else InkBlack, if (IsNight) Line else Color(0x2E111111), Modifier.weight(1f))
                }
            }
        }
        if (latest != null) {
            val ink = signInk(SignKind.INFO)
            SignBox(SignKind.INFO, Modifier.weight(1f).fillMaxHeight().press(RoundedCornerShape(21.dp)) { onSelectTrip(latest.trip.id); onShow(Screen.TRIP) }) {
                Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoStories, null, tint = if (IsNight) SignBlue else Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${whenText(latest.trip.startedAt)} 운전 돌아보기", color = ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${latest.score}점 · ${latest.tier.label}", color = ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

/** 졸업 전 홈 왼쪽 아래: 딱지를 떼면 이어지는 심화 과정을 미리 보여 줍니다. */
@Composable
private fun NextStage(modifier: Modifier) {
    Row(modifier.background(Line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("졸업하면", color = TextSecondary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextSecondary, modifier = Modifier.size(26.dp))
        Text("심화 과정", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(14.dp))
        Skill.ADVANCED.forEachIndexed { i, s ->
            if (i > 0) Text(" · ", color = TextMuted, fontSize = 21.sp)
            SkillIcon(s, TextSecondary, Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(s.short, color = TextSecondary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val Corner.alignment: Alignment get() = when (this) {
    Corner.TL -> Alignment.TopStart; Corner.TR -> Alignment.TopEnd; Corner.BL -> Alignment.BottomStart; Corner.BR -> Alignment.BottomEnd
}

/** 귀퉁이 옆의 과목 표시. 마친 과목은 초록 체크, 남은 과목은 노랑. */
@Composable
private fun CornerTag(c: Course, modifier: Modifier) {
    val done = c.passed
    Row(
        modifier.background(if (done) GoodFill else SignYellow, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
        else SkillIcon(c.skill, InkBlack, Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (done) c.skill.short else "${c.skill.short} · ${if (c.toGo == 1) "한 번만 더" else "${c.toGo}번 더"}",
            color = if (done) Color.White else InkBlack, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** 홈 아래 문장. 지금 어디까지 왔는지 짚고, 다음에 할 일을 권합니다. */
private fun homeMessage(g: Graduation): String {
    val n = g.courses.count { it.passed }
    val left = g.remaining
    val count = listOf("", "한", "두", "세", "네")[n]
    return when {
        left.size == 1 -> {
            val c = left.first()
            "벌써 귀퉁이 ${count} 개가 들렸어요. 이제 ${c.skill.short}만 남았네요.\n" +
                if (c.toGo == 1) "다음에 ${nextAction(c.skill)} 딱지 떼셔도 돼요." else "${c.toGo}번만 더 잘하면 딱지 떼셔도 돼요."
        }
        n == 0 -> "아직 귀퉁이가 그대로예요. 걱정 마세요,\n과목 하나를 마칠 때마다 한 귀퉁이씩 들려요."
        else -> "귀퉁이 ${count} 개가 들렸어요. 잘하고 계세요.\n과목 하나를 마칠 때마다 한 귀퉁이씩 더 들려요."
    }
}

/**
 * 주차가 얼마나 늘었는지: 처음 다섯 번과 최근 다섯 번의 '몇 번 만에 넣었나' 평균.
 * 점수보다 횟수가 와닿아서 주차 카드 오른쪽에 크게 둡니다.
 */
@Composable
private fun ParkTrend(state: UiState, modifier: Modifier) {
    val parks = state.moves.filter { it.kind == dev.driveform.form.MoveKind.PARK }
    if (parks.size < 6) return
    fun avg(list: List<dev.driveform.form.MoveForm>) = "%.1f".format(list.map { it.tries }.average()).removeSuffix(".0")
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text("몇 번 만에 넣었나", color = TextSecondary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            SignText("${avg(parks.take(5))}번", 40.sp, TextSecondary)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextSecondary, modifier = Modifier.size(34.dp).padding(bottom = 4.dp))
            SignText("${avg(parks.takeLast(5))}번", 56.sp, Good)
        }
        Text("처음 다섯 번 → 최근 다섯 번", color = TextMuted, fontSize = 19.sp)
    }
}

/** 과목 카드. 마친 과목은 원형 표지, 남은 과목은 노란 마름모. 이번 주 과제면 노란 테두리. [extra]는 카드 위에 겹쳐 그립니다. */
@Composable
private fun CourseCard(c: Course, isMission: Boolean, modifier: Modifier, extra: @Composable BoxScope.() -> Unit = {}) {
    PlainCard(modifier, highlight = isMission) {
        extra()
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.passed) RoundSign(64.dp) { SkillIcon(c.skill, SignIcon, Modifier.size(30.dp)) }
                else Diamond(46.dp) { SkillIcon(c.skill, SignIcon, Modifier.size(28.dp)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // 긴 과목 이름은 낱말 사이에서 두 줄로 나눕니다("부드러운 / 정지·출발").
                    Text(if (c.skill.label.length > 8) c.skill.label.replaceFirst(' ', '\n') else c.skill.label, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
                    Text(if (c.skill.stage == Stage.BASIC) "딱지 ${c.skill.corner.label}" else c.skill.chance, color = TextSecondary, fontSize = 21.sp)
                }
                Plate(c.passed, if (c.passed) "이수" else if (c.toGo == 1) "한 번 남음" else "${c.toGo}번 남음")
            }
            Spacer(Modifier.weight(1f))
            Text(c.skill.tip, color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            ResultDots(c.recent, if (c.passed) Good else TextPrimary, Modifier.fillMaxWidth())
            Text(
                when {
                    c.passed -> "${localDateTime(c.passedAt!!).format(gradDate)}에 마쳤어요"
                    isMission -> "이번 주에 같이 연습해요"
                    c.skill.stage == Stage.BASIC -> "${c.toGo}번 더 잘하면 귀퉁이가 들려요"
                    c.recent.isEmpty() && c.skill.situational -> "${c.skill.chance} 운전하면 쌓여요"
                    else -> "${c.toGo}번 더 잘하면 이수해요"
                },
                color = TextSecondary, fontSize = 22.sp,
            )
        }
    }
}

// ───────────── 심화 과정 ─────────────

/**
 * 졸업 뒤의 홈. 딱지는 뗐지만 초보가 오래 어려워하는 순간(주차, 빗길·안갯길, 밤길)을 이어서 연습합니다.
 * 뒷유리엔 딱지 대신 졸업 도장이 있고, 심화 과목을 마칠 때마다 배지가 하나씩 채워집니다.
 */
@Composable
private fun AdvancedHome(state: UiState, onShow: (Screen) -> Unit, onSelectTrip: (Int) -> Unit, onCertificate: () -> Unit, modifier: Modifier) {
    val a = state.advanced
    val before = state.advancedBefore
    val fresh = a.courses.filter { it.passed && before.courses.firstOrNull { b -> b.skill == it.skill }?.passed != true }.map { it.skill }.toSet()
    val pop = remember { Animatable(0f) }
    LaunchedEffect(fresh) { pop.snapTo(0f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 260f)) }
    val left = a.remaining
    val latest = state.tripSummaries.firstOrNull()
    val mission = latest?.let { state.coachingFor(it) }?.mission?.skill?.takeIf { s -> left.any { it.skill == s } }
    // 이번 운전으로 배지를 받았으면 배지가 통 튀어 오른 뒤 축하 인사를 띄웁니다.
    var badge by remember(fresh) { mutableStateOf<Skill?>(null) }
    LaunchedEffect(fresh) { if (fresh.isNotEmpty()) { delay(900); badge = fresh.first() } }
    Box(modifier) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(680.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            GuideSign(
                "심화", "졸업 다음 단계", Modifier.fillMaxWidth(),
                right = when {
                    left.isEmpty() -> Triple("심화", "수료", "")
                    left.size == 1 -> Triple(left.first().skill.short, "${left.first().toGo}", "번")
                    else -> Triple("남은 과목", "${left.size}", "개")
                },
            )
            Glass(Modifier.fillMaxWidth().height(400.dp).press(RoundedCornerShape(24.dp), scale = 0.98f, onClick = onCertificate)) {
                // 딱지를 뗀 자리에 졸업 도장, 그 아래로 심화 배지 세 개.
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(96.dp).graphicsLayer { rotationZ = -10f }.border(5.dp, SignYellow, CircleShape).padding(6.dp).border(2.dp, SignYellow, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { SignText("졸업", 32.sp, SignYellow) }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("${localDateTime(state.graduation.graduatedAt!!).format(gradDate)} 초보 졸업", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text("눌러서 졸업 증명 다시 보기", color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.height(34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        a.courses.forEach { c -> Badge(c, if (c.skill in fresh) pop.value else 1f) }
                    }
                }
            }
            Text(advancedMessage(a), color = TextPrimary, fontSize = 26.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                RollingNumber((a.progress * 100).roundToInt(), TextPrimary, 56.sp, lineHeight = 56.sp)
                SignText("%", 30.sp, TextSecondary, Modifier.padding(start = 4.dp, bottom = 6.dp))
                Spacer(Modifier.width(18.dp))
                Text("배지 ${a.courses.count { it.passed }} / ${a.courses.size}", color = TextSecondary, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            }
            RoadBar(a.progress)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("심화 과목", color = TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(16.dp))
                Text("비·밤은 기회가 올 때마다 쌓여요", color = TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            val park = a.courses.first { it.skill == Skill.PARK }
            CourseCard(park, park.skill == mission, Modifier.weight(1f).fillMaxWidth()) { ParkTrend(state, Modifier.align(Alignment.CenterEnd).padding(end = 28.dp)) }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                a.courses.filter { it.skill != Skill.PARK }.forEach { CourseCard(it, it.skill == mission, Modifier.weight(1f).fillMaxHeight()) }
            }
            MissionAndLatest(state, onShow, onSelectTrip)
        }
    }
    badge?.let { BadgeCongrats(it, onClose = { badge = null }) }
    }
}

/** 심화 배지를 받은 순간의 축하. 졸업 축하보다 작게, 같은 모양으로. */
@Composable
private fun BadgeCongrats(skill: Skill, onClose: () -> Unit) {
    val show = remember { Animatable(0f) }
    val party = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { party.animateTo(1f, tween(PARTY_MS, easing = LinearEasing)) }
        launch { delay(200); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
        show.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f))
    }
    var center by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { center = Offset(it.size.width / 2f, it.size.height / 2f) }
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.5f * show.value.coerceIn(0f, 1f))) }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Confetti({ party.value }, { center }, Modifier.fillMaxSize())
        PlainCard(
            Modifier.width(760.dp).graphicsLayer {
                val v = show.value
                val s = 0.6f + 0.4f * v
                scaleX = s; scaleY = s; alpha = v.coerceIn(0f, 1f); translationY = 80.dp.toPx() * (1f - v)
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 52.dp, vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(128.dp).graphicsLayer { val b = pop.value; scaleX = b; scaleY = b; rotationZ = -30f * (1f - b) }
                        .background(GoodFill, CircleShape).border(5.dp, SignYellow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SkillIcon(skill, Color.White, Modifier.size(64.dp)) }
                Spacer(Modifier.height(22.dp))
                SignText("${skill.short} 배지를 받으셨어요!", 54.sp, Good)
                Spacer(Modifier.height(18.dp))
                Text(badgeMessage(skill), color = TextPrimary, fontSize = 27.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Box(
                    Modifier.fillMaxWidth().height(92.dp).press(RoundedCornerShape(18.dp), onClick = onClose).background(GoodFill, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("고마워요!", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

private fun badgeMessage(skill: Skill) = when (skill) {
    Skill.PARK -> "그동안 주차 연습하느라 고생 많으셨어요.\n축하드려요!"
    Skill.WET -> "비 오는 날 운전, 쉽지 않으셨죠.\n고생 많으셨어요. 축하드려요!"
    Skill.NIGHT -> "밤길 운전 연습하느라 고생 많으셨어요.\n축하드려요!"
    else -> "고생 많으셨어요. 축하드려요!"
}

/** 심화 배지. 마친 과목은 초록 원에 체크, 남은 과목은 점선 원에 아이콘과 남은 횟수. */
@Composable
private fun Badge(c: Course, pop: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(104.dp).graphicsLayer { scaleX = pop; scaleY = pop }.then(
                if (c.passed) Modifier.background(GoodFill, CircleShape).border(4.dp, SignYellow, CircleShape)
                else Modifier.drawBehind {
                    drawCircle(Color.White.copy(alpha = 0.55f), style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
                },
            ),
            contentAlignment = Alignment.Center,
        ) { SkillIcon(c.skill, if (c.passed) Color.White else Color.White.copy(alpha = 0.8f), Modifier.size(48.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(c.skill.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            when {
                c.passed -> "마침"
                c.recent.isEmpty() -> "아직 기회 없음"
                else -> "${c.ok} / ${Course.PASS}"
            },
            color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp,
        )
    }
}

/** 심화 홈 문장. 졸업을 한 번 더 축하하고, 다음에 할 일을 권합니다. */
private fun advancedMessage(a: Graduation): String {
    val left = a.remaining
    val park = a.courses.first { it.skill == Skill.PARK }
    return when {
        left.isEmpty() -> "심화 과정까지 다 마치셨어요.\n이제 어디든 편하게 다녀오셔도 돼요."
        !park.passed -> "딱지는 뗐어도 주차는 아직 어렵죠.\n운전 끝날 때마다 한 번씩, 같이 연습해요."
        left.all { it.skill.situational } -> "주차까지 마치셨어요. 남은 건 날씨와 시간이\n허락할 때 하나씩 쌓으면 돼요."
        else -> "한 과목씩 잘 쌓아 가고 계세요.\n졸업한 뒤에도 운전은 계속 늘어요."
    }
}

// ───────────── 졸업 순간 ─────────────

/** 살짝 지나쳤다 돌아오는 곡선: 카드·줄·버튼이 통 하고 자리를 잡습니다. */
private val Bouncy = CubicBezierEasing(0.34f, 1.7f, 0.64f, 1f)

@Composable
private fun GraduatedScreen(state: UiState, onAdvance: () -> Unit, modifier: Modifier) {
    val g = state.graduation
    val at = g.graduatedAt!!
    // 방금 졸업했으면: 마지막 귀퉁이를 몇 번 잡아당기다 → 딱지가 톡 튀어 빙글 날아가고 → 졸업 도장이 쾅
    // (유리가 흔들리고 종이꽃이 터짐) → 증명 카드의 과목 줄이 하나씩 들어오고 → 버튼이 통 튀어나옵니다. 이미 지난 일이면 끝난 모습으로.
    val just = !state.graduationBefore.graduated
    val lastSkill = g.courses.maxByOrNull { it.passedAt ?: 0L }!!.skill
    val last = lastSkill.corner
    val start = if (just) 0f else 1f
    val peel = remember(at, just) { Animatable(start) }
    val fly = remember(at, just) { Animatable(start) }
    val stamp = remember(at, just) { Animatable(start) }
    val party = remember(at, just) { Animatable(start) } // 도장 뒤 3초: 종이꽃, 충격파, 흔들림, 표지 들썩임
    val card = remember(at, just) { Animatable(start) }
    val rows = remember(at, just) { Animatable(if (just) 0f else 4f) }
    val buttons = remember(at, just) { Animatable(start) }
    var congrats by remember(at, just) { mutableStateOf(false) }
    LaunchedEffect(at, just) {
        if (!just) return@LaunchedEffect
        delay(500)
        peel.animateTo(1f, keyframes { durationMillis = 1400; 0.32f at 260; 0.16f at 460; 0.52f at 760; 0.38f at 920; 1f at 1400 })
        launch { delay(450); card.animateTo(1f, tween(650, easing = Bouncy)) }
        fly.animateTo(1f, tween(1150, easing = LinearEasing))
        launch { stamp.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
        delay(130)
        launch { party.animateTo(1f, tween(PARTY_MS, easing = LinearEasing)) }
        delay(250)
        rows.animateTo(4f, tween(4 * 280, easing = LinearEasing))
        buttons.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
        // 다 자리를 잡고 종이꽃도 내려앉은 뒤에 축하 인사를 띄웁니다.
        party.animateTo(1f)
        delay(400)
        congrats = true
    }
    val trips = state.tripSummaries.filter { it.trip.startedAt <= at }
    val first = trips.minOfOrNull { it.trip.startedAt } ?: at
    val days = ((at - first) / 86_400_000L).toInt() + 1
    val km = state.trips.filter { it.startedAt <= at }.sumOf { it.distanceM ?: 0.0 } / 1000
    val period = if (days >= 7) "${(days + 3) / 7}주" else "${days}일"
    // 도장 직후 0.45초: 표지가 들썩이고 유리가 흔들립니다.
    val hit = { (party.value * PARTY_MS / 450f).coerceIn(0f, 1f).let { if (it in 0.001f..0.999f) it else 0f } }
    var glassCenter by remember { mutableStateOf(Offset.Zero) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { rootAt = it.positionInRoot() }) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(740.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            GuideSign(
                "졸업", "이제 딱지 떼셔도 돼요",
                Modifier.fillMaxWidth().graphicsLayer {
                    val s = 1f + 0.07f * sin(PI.toFloat() * hit())
                    scaleX = s; scaleY = s
                },
                titleSize = 88.sp,
            )
            Glass(
                Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { glassCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                    .graphicsLayer {
                        val h = hit()
                        val wob = sin(h * PI.toFloat() * 7f) * (1f - h)
                        translationX = 12.dp.toPx() * wob; translationY = 6.dp.toPx() * wob
                    },
            ) {
                // 딱지가 있던 자리.
                Canvas(Modifier.align(Alignment.Center).size(380.dp, 176.dp)) {
                    drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                }
                Ddakji(
                    Corner.entries.toSet(),
                    Modifier.align(Alignment.Center).size(380.dp, 176.dp).graphicsLayer {
                        // 처음 20%: 톡 떨어지며 살짝 커짐. 나머지: 오른쪽 위로 빙글 돌며 작아져 사라짐.
                        val f = fly.value
                        val hop = (f / 0.2f).coerceIn(0f, 1f)
                        val p = ((f - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        val e = 1f - (1f - p) * (1f - p)
                        translationX = 300.dp.toPx() * e
                        translationY = -26.dp.toPx() * sin(PI.toFloat() * hop) - 380.dp.toPx() * e + 90.dp.toPx() * p * p
                        rotationZ = -2f - 6f * hop + 420f * p * p
                        val s = 1f + 0.14f * hop - 0.7f * p
                        scaleX = s; scaleY = s
                        alpha = 1f - ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    },
                    lift = { c -> if (c == last) peel.value else 1f }, textSize = 74.sp,
                )
                Box(
                    Modifier.align(Alignment.Center).size(210.dp).graphicsLayer {
                        val s = stamp.value
                        scaleX = 2.6f - 1.6f * s; scaleY = 2.6f - 1.6f * s; alpha = (s * 2.5f).coerceIn(0f, 1f); rotationZ = -24f + 16f * s
                    }.border(8.dp, SignYellow, CircleShape).padding(10.dp).border(3.dp, SignYellow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { SignText("졸업", 66.sp, SignYellow) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            PlainCard(
                Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                    val c = card.value
                    translationY = 90.dp.toPx() * (1f - c); alpha = c.coerceIn(0f, 1f)
                },
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.School, null, tint = Good, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("초보 졸업 증명", color = Good, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        "${period} 동안 ${trips.size}번 운전하면서\n네 과목을 다 마치셨어요", color = TextPrimary,
                        fontSize = 44.sp, lineHeight = 58.sp, fontWeight = FontWeight.Black,
                    )
                    Text("${localDateTime(at).format(gradDate)} · 그동안 ${km.roundToInt()}km를 달렸어요", color = TextSecondary, fontSize = 23.sp)
                    // 빗길·안갯길·밤 운전도 해냈다면 함께 남깁니다.
                    val did = Firsts.counts(trips.map { it.trip }).filterValues { it > 0 }
                    if (did.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        did.entries.forEachIndexed { i, (f, n) ->
                            if (i > 0) Spacer(Modifier.width(18.dp))
                            IconText(firstIcon(f), "${f.label.removePrefix("첫 ")} ${n}번", Good, 23.sp, iconSize = 24.dp, gap = 6.dp, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("도 해내셨어요", color = TextSecondary, fontSize = 23.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    g.courses.forEachIndexed { i, c ->
                        // 줄이 오른쪽에서 미끄러져 들어오고, 조금 뒤 체크가 통 튀어나옵니다.
                        val inOf = { (rows.value - i).coerceIn(0f, 1f) }
                        val checkOf = { ((rows.value - i - 0.35f) / 0.65f).coerceIn(0f, 1f) }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Line))
                        Row(
                            Modifier.padding(vertical = 12.dp).graphicsLayer {
                                val v = inOf()
                                translationX = 60.dp.toPx() * (1f - Bouncy.transform(v)); alpha = v
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundSign(54.dp, ring = 6.dp) { SkillIcon(c.skill, SignIcon, Modifier.size(24.dp)) }
                            Spacer(Modifier.width(16.dp))
                            Text(c.skill.label, color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Text("${localDateTime(c.passedAt!!).format(gradDate)}에 마침", color = TextSecondary, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.Rounded.Check, null, tint = Good,
                                modifier = Modifier.size(30.dp).graphicsLayer {
                                    val s = Bouncy.transform(checkOf()) * 1f
                                    scaleX = s; scaleY = s; rotationZ = -30f * (1f - checkOf())
                                },
                            )
                        }
                    }
                }
            }
            val context = LocalContext.current
            // 공유 버튼은 버튼이 자리 잡은 뒤 세 번만 숨 쉬듯 커졌다 작아집니다(계속 움직이면 화면을 쉬지 않고 다시 그립니다).
            val breathe = remember(at, just) { Animatable(1f) }
            LaunchedEffect(at, just) {
                if (just) delay(5_500)
                repeat(3) { breathe.animateTo(1.035f, tween(900, easing = FastOutSlowInEasing)); breathe.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            }
            val pulse = breathe.value
            Row(
                Modifier.fillMaxWidth().height(96.dp).graphicsLayer {
                    val b = buttons.value
                    val s = 0.6f + 0.4f * b
                    scaleX = s; scaleY = s; alpha = b.coerceIn(0f, 1f); translationY = 40.dp.toPx() * (1f - b)
                },
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .graphicsLayer { val s = if (buttons.value >= 0.99f) pulse else 1f; scaleX = s; scaleY = s }
                        .press(RoundedCornerShape(18.dp)) {
                            val text = "초보졸업: ${period} 동안 ${trips.size}번 운전하고 초보운전 딱지를 뗐어요!"
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        .background(GoodFill, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("졸업장 공유하기", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
                PlainCard(Modifier.weight(1f).fillMaxHeight().press(RoundedCornerShape(22.dp), onClick = onAdvance)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Text("심화 과정 시작하기", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextPrimary, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }
    Confetti({ party.value }, { glassCenter - rootAt }, Modifier.fillMaxSize())
    if (congrats) Congrats(lastSkill, onClose = { congrats = false })
    }
}

/** 졸업 축하 인사. 카드가 통 튀어 오르고 뒤에서 종이꽃이 한 번 더 터집니다. 버튼이나 바깥을 누르면 닫힙니다. */
@Composable
private fun Congrats(lastSkill: Skill, onClose: () -> Unit) {
    val show = remember { Animatable(0f) }
    val party = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { party.animateTo(1f, tween(PARTY_MS, easing = LinearEasing)) }
        launch { delay(250); badge.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
        show.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f))
    }
    var center by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { center = Offset(it.size.width / 2f, it.size.height / 2f) }
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.55f * show.value.coerceIn(0f, 1f))) }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Confetti({ party.value }, { center }, Modifier.fillMaxSize())
        PlainCard(
            Modifier.width(880.dp).graphicsLayer {
                val v = show.value
                val s = 0.6f + 0.4f * v
                scaleX = s; scaleY = s; alpha = v.coerceIn(0f, 1f); translationY = 80.dp.toPx() * (1f - v)
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(120.dp).graphicsLayer {
                        val b = badge.value
                        scaleX = b; scaleY = b; rotationZ = -40f * (1f - b)
                    }.background(GoodFill, CircleShape).border(5.dp, SignYellow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.School, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                Spacer(Modifier.height(24.dp))
                SignText("졸업 축하드려요!", 64.sp, Good)
                Spacer(Modifier.height(20.dp))
                Text(
                    "마지막까지 애먹이던 ${lastSkill.short}도 이젠 몸에 익으셨네요.\n" +
                        "처음 딱지 붙이던 날보다 정말 많이 느셨어요.\n" +
                        "이제 주차, 빗길, 밤길도 하나씩 같이 연습해 봐요.",
                    color = TextPrimary, fontSize = 28.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                Box(
                    Modifier.fillMaxWidth().height(96.dp).press(RoundedCornerShape(18.dp), onClick = onClose).background(GoodFill, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("고마워요!", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

private const val PARTY_MS = 3200

private class Bit(val vx: Float, val vy: Float, val spin: Float, val w: Float, val h: Float, val color: Color, val phase: Float, val round: Boolean)

/** 도장이 찍힌 곳에서 터지는 종이꽃과 노란 충격파. 공기 저항을 받아 느려지고 살랑이며 떨어집니다. [t]는 0→1(3.2초). */
@Composable
private fun Confetti(t: () -> Float, origin: () -> Offset, modifier: Modifier) {
    val bits = remember {
        val r = Random(11)
        val colors = listOf(Color(0xFFFFD43B), Color(0xFF2FB873), Color(0xFF4C7FE0), Color.White, Color(0xFFFFE58A), Color(0xFF7FD6A8))
        List(110) {
            val a = Math.toRadians(-90.0 + (r.nextFloat() - 0.5) * 170.0)
            val speed = 600f + r.nextFloat() * 1300f
            Bit(
                (cos(a) * speed).toFloat(), (sin(a) * speed).toFloat(), (r.nextFloat() - 0.5f) * 1080f,
                8f + r.nextFloat() * 10f, 12f + r.nextFloat() * 14f, colors[it % colors.size], r.nextFloat() * 6.28f, r.nextFloat() < 0.25f,
            )
        }
    }
    Canvas(modifier) {
        val v = t()
        if (v <= 0f || v >= 1f) return@Canvas
        val sec = v * PARTY_MS / 1000f
        val o = origin()
        // 충격파 두 겹.
        for ((delay, width) in listOf(0f to 10f, 0.12f to 5f)) {
            val w = ((sec - delay) / 0.6f).coerceIn(0f, 1f)
            if (w in 0.001f..0.999f) {
                drawCircle(Color(0xFFFFD43B).copy(alpha = 0.85f * (1f - w)), radius = (110f + 260f * w).dp.toPx(), center = o, style = Stroke(width.dp.toPx() * (1f - w * 0.6f)))
            }
        }
        val k = 1.9f
        val gravity = 1000f
        val decay = 1f - exp(-k * sec)
        val fade = 1f - ((v - 0.75f) / 0.25f).coerceIn(0f, 1f)
        bits.forEach { b ->
            val x = b.vx / k * decay + sin(sec * 5f + b.phase) * 14f * (sec / 1.2f).coerceAtMost(1f)
            val y = b.vy / k * decay + gravity / k * sec - gravity / (k * k) * decay
            val p = o + Offset(x.dp.toPx(), y.dp.toPx())
            val c = b.color.copy(alpha = fade)
            if (b.round) {
                drawCircle(c, radius = b.w.dp.toPx() / 2, center = p)
            } else {
                // 납작해졌다 펴지는 걸로 뒤집히는 느낌을 냅니다.
                val flip = abs(cos(sec * 7f + b.phase))
                rotate(b.spin * sec, p) {
                    drawRect(c, topLeft = p - Offset(b.w.dp.toPx() / 2, b.h.dp.toPx() * flip / 2), size = Size(b.w.dp.toPx(), b.h.dp.toPx() * flip))
                }
            }
        }
    }
}

// ───────────── 시작하기 ─────────────

@Composable
internal fun StartScreen(onStart: () -> Unit, modifier: Modifier) {
    // 버튼을 누르면 딱지가 점선 자리로 붙고, 그다음 홈으로 갑니다.
    val stick = remember { Animatable(0f) }
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(go) {
        if (!go) return@LaunchedEffect
        stick.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f))
        delay(500)
        onStart()
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(740.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("초보운전 딱지 붙이고\n같이 연습해 봐요", color = TextPrimary, fontSize = 56.sp, lineHeight = 70.sp, fontWeight = FontWeight.Black)
            Text(
                "운전 끝나고 P에 두시면 오늘 운전을 정리해 드릴게요.\n운전하는 동안엔 화면 대신 소리랑 불빛으로만 알려 드려요.",
                color = TextSecondary, fontSize = 25.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium,
            )
            Glass(Modifier.fillMaxWidth().weight(1f).press(RoundedCornerShape(24.dp), scale = 0.98f) { go = true }) {
                Canvas(Modifier.align(Alignment.Center).size(380.dp, 176.dp)) {
                    drawRoundRect(Color.White.copy(alpha = 0.55f), cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
                }
                Ddakji(
                    emptySet(),
                    Modifier.align(Alignment.Center).size(380.dp, 176.dp).graphicsLayer {
                        val s = stick.value
                        translationX = 18.dp.toPx() * (1 - s); translationY = -40.dp.toPx() * (1 - s); rotationZ = -6f + 4f * s
                        scaleX = 1.06f - 0.06f * s; scaleY = 1.06f - 0.06f * s
                    },
                    textSize = 74.sp,
                )
                Text(
                    "눌러서 뒷유리에 붙여 보세요", color = Color.White.copy(alpha = 0.9f), fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                )
            }
            Box(
                Modifier.fillMaxWidth().height(100.dp).press(RoundedCornerShape(18.dp)) { go = true }.background(InkBlack, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("딱지 붙이고 시작하기", color = SignYellow, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SignYellow, modifier = Modifier.size(38.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GuideSign("과목 넷, 귀퉁이 넷", "과목 하나를 마칠 때마다 귀퉁이가 하나씩 들려요", Modifier.fillMaxWidth(), titleSize = 52.sp)
            listOf(Skill.STEER, Skill.SMOOTH, Skill.ROAD, Skill.SIGNAL).forEach { s ->
                PlainCard(Modifier.fillMaxWidth().weight(1f)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundSign(72.dp) { SkillIcon(s, SignIcon, Modifier.size(34.dp)) }
                        Spacer(Modifier.width(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                            Text(s.tip, color = TextSecondary, fontSize = 23.sp)
                        }
                        Text("딱지\n${s.corner.label}", color = TextSecondary, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Diamond(28.dp) { Icon(Icons.Rounded.Flag, null, tint = SignIcon, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Text("매주 제일 어려워하는 과목 하나를 골라 같이 연습해요", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            NextStage(Modifier)
        }
    }
}

/** 연수 일지에 넣는 이정표: 졸업 전엔 미니 딱지와 남은 귀퉁이, 졸업 뒤엔 심화 배지. 이번 주 과제와 함께. 누르면 홈으로 갑니다. */
@Composable
internal fun GradGuideCard(c: Coaching, onClick: () -> Unit, modifier: Modifier) {
    val g = c.graduation
    val mission = c.mission
    val ink = signInk(SignKind.GUIDE)
    SignBox(SignKind.GUIDE, modifier.press(RoundedCornerShape(21.dp), onClick = onClick)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val left = g.remaining
            val advLeft = c.advanced.remaining
            SignText(
                when {
                    g.graduated && advLeft.isEmpty() -> "심화까지 마쳤어요!"
                    g.graduated -> "심화 배지 ${c.advanced.courses.count { it.passed }} / ${c.advanced.courses.size}"
                    left.size == 1 -> "귀퉁이 하나 남았어요"
                    else -> "귀퉁이 ${left.size}개 남았어요"
                },
                36.sp, ink,
            )
            Text(
                when {
                    g.graduated -> "졸업 뒤엔 주차·빗길·밤길을 연습해요"
                    left.size == 1 -> "${left.first().skill.short}만 잘하면 졸업이에요"
                    else -> "하나씩 들려 볼까요"
                },
                color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            // 남은 공간에 지금 모습: 졸업 전엔 딱지(들린 귀퉁이), 뒤엔 심화 배지.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!g.graduated) Ddakji(g.courses.filter { it.passed }.map { it.skill.corner }.toSet(), Modifier.size(200.dp, 92.dp).graphicsLayer { rotationZ = -3f }, textSize = 40.sp)
                else Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    c.advanced.courses.forEach { course ->
                        Box(
                            Modifier.size(64.dp).then(
                                if (course.passed) Modifier.background(GoodFill, CircleShape).border(3.dp, SignYellow, CircleShape)
                                else Modifier.border(2.dp, ink.copy(alpha = 0.5f), CircleShape),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { SkillIcon(course.skill, if (course.passed) Color.White else ink, Modifier.size(32.dp)) }
                    }
                }
            }
            Text("이번 주엔 ${mission.skill.mission}${josa(mission.skill.mission, "을", "를")} 연습해요", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignText("${mission.done}", 46.sp, ink)
                Text(" / ${mission.target}번 연속", color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Segments(mission.done, mission.target, SignYellow, if (IsNight) Line else Color.White.copy(alpha = 0.3f), Modifier.weight(1f))
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(if (g.graduated) "심화 진도 보러 가기" else "졸업 진도 보러 가기", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink, modifier = Modifier.size(28.dp))
            }
        }
    }
}
