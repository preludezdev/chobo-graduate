package dev.driveform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.Screen
import dev.driveform.UiState
import dev.driveform.form.Coaching
import dev.driveform.form.Course
import dev.driveform.form.FormScore
import dev.driveform.form.Graduation
import dev.driveform.form.MoveForm
import dev.driveform.form.Skill
import dev.driveform.form.TripSummary
import dev.driveform.vehicle.CarPaint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class CoachTab(val label: String) { TODAY("오늘 코칭"), SKILL("실력"), HISTORY("지난 운전"), CAR("내 차") }

/** 'AI 운전 코치' 화면의 뿌리: 머리글(코치 표시·정차 상태·탭)과 탭 네 개. */
@Composable
fun CoachApp(
    state: UiState,
    onSelect: (Int) -> Unit,
    onShow: (Screen) -> Unit,
    onSelectTrip: (Int) -> Unit,
    onStart: () -> Unit,
    onPaint: (CarPaint) -> Unit,
    speedKmh: State<Int>,
) {
    var tab by rememberSaveable { mutableStateOf(if (state.screen == Screen.GRAD) CoachTab.SKILL else CoachTab.TODAY) }
    // 주차하면 앱이 그 주행의 코칭으로 넘깁니다.
    LaunchedEffect(state.screen) { tab = if (state.screen == Screen.GRAD) CoachTab.SKILL else CoachTab.TODAY }
    val sprites = rememberSprites()
    val context = LocalContext.current
    // 가장 최근 운전으로 졸업했거나 심화 배지를 받았으면 한 번 축하합니다.
    // 본 축하는 그 순간(졸업 시각, 배지 과목·시각)으로 기억해 두고, 기록이 바뀌어 새 순간이 생기면 다시 띄웁니다.
    val gradKey = state.graduation.graduatedAt?.takeIf { !state.graduationBefore.graduated }
    // 본 축하는 앱을 껐다 켜도 다시 뜨지 않게 저장해 둡니다.
    val prefs = remember { context.getSharedPreferences("drive_form", android.content.Context.MODE_PRIVATE) }
    var gradSeen by remember { mutableStateOf<Long?>(prefs.getLong("seen_graduation", 0L).takeIf { it != 0L }) }
    val freshCourse = remember(state.advanced, state.advancedBefore) {
        state.advanced.courses.firstOrNull { c -> c.passed && state.advancedBefore.courses.firstOrNull { it.skill == c.skill }?.passed != true }
    }
    val badgeKey = freshCourse?.let { "${it.skill.name}@${it.passedAt}" }
    var badgeSeen by remember { mutableStateOf(prefs.getString("seen_badge", null)) }
    val celebrate = gradKey != null && gradKey != gradSeen
    val badge = freshCourse?.skill?.takeIf { badgeKey != badgeSeen }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Coach.Bg).padding(horizontal = 44.dp, vertical = 30.dp)) {
        Header(state, speedKmh, tab.takeIf { state.started }) { t ->
            tab = t
            when (t) { CoachTab.TODAY -> onShow(Screen.TRIP); CoachTab.SKILL -> onShow(Screen.GRAD); else -> Unit }
        }
        Gap(24)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!state.started) { StartPane(sprites, state.paint, onStart); return@Box }
            when (tab) {
                CoachTab.TODAY -> TodayPane(state, sprites, onSelect) { tab = CoachTab.SKILL; onShow(Screen.GRAD) }
                CoachTab.SKILL -> SkillPane(state)
                CoachTab.HISTORY -> HistoryPane(state)
                CoachTab.CAR -> MyCarPane(state, sprites, onPaint)
            }
        }
    }
    if (state.started && celebrate) GraduationCelebration(state.graduation, state.tripSummaries.size, sprites.hero(state.paint), onAdvanced = { gradSeen = gradKey; prefs.edit().putLong("seen_graduation", gradKey ?: 0L).apply(); tab = CoachTab.SKILL; onShow(Screen.GRAD) },
        onClose = { gradSeen = gradKey; prefs.edit().putLong("seen_graduation", gradKey ?: 0L).apply() })
    else if (state.started) badge?.let { BadgeCelebration(it) { badgeSeen = badgeKey; prefs.edit().putString("seen_badge", badgeKey).apply() } }
    }
}

@Composable
private fun rememberSprites(): CarSprites {
    val context = LocalContext.current
    return remember { CarSprites(context.applicationContext) }
}

@Composable
private fun Header(state: UiState, speedKmh: State<Int>, tab: CoachTab?, onTab: (CoachTab) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        CoachMark(44.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("초보졸업", color = Coach.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("운전 코치", color = Coach.Muted, fontSize = 15.sp)
        }
        Spacer(Modifier.width(22.dp))
        val kmh by speedKmh
        Text(
            if (state.parked) "P 정차 중" else "주행 중 · 시속 ${kmh}km",
            color = Coach.Muted, fontSize = 17.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Coach.Panel2).padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        if (tab != null) {
            Row(Modifier.panel(16.dp).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoachTab.entries.forEach { t ->
                    val on = t == tab
                    Text(
                        t.label, color = if (on) Coach.AccentInk else Coach.Text2, fontSize = 19.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Coach.Accent else Color.Transparent)
                            .press(RoundedCornerShape(12.dp)) { onTab(t) }.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ---------- 오늘 코칭 ----------

/** 이 주행에서 다시 볼 장면: 아쉬운 점이 있는 장면만, 아쉬운 정도가 큰 순서. */
internal fun mistakesOf(t: TripSummary): List<MoveForm> = t.moves.filter { m -> m.checks.any { !it.ok } }.sortedBy { it.score }

@Composable
private fun TodayPane(state: UiState, sprites: CarSprites, onSelect: (Int) -> Unit, onSkill: () -> Unit) {
    // 오늘 코칭은 언제나 가장 최근 운전입니다. 지난 운전은 '지난 운전' 탭 안에서 따로 엽니다.
    val trip = state.tripSummaries.firstOrNull()
    if (trip == null) {
        Box(Modifier.fillMaxSize().panel(), contentAlignment = Alignment.Center) {
            Text("아직 운전 기록이 없어요. 한 번 다녀오시면 여기서 같이 돌아봐요.", color = Coach.Text2, fontSize = 24.sp)
        }
        return
    }
    val mistakes = remember(trip) { mistakesOf(trip) }
    val move = trip.moves.firstOrNull { it.id == state.selectedId } ?: mistakes.firstOrNull() ?: trip.moves.last()
    DriveReview(state, trip, move, onSelect) { ProgressLink(state.graduation, state.advanced, onSkill) }
}

/** 한 운전 돌아보기: 왼쪽은 고른 장면의 다시보기, 오른쪽 위는 그 장면에 대한 코치 한마디, 아래는 이 운전의 다시 볼 장면. */
@Composable
private fun DriveReview(state: UiState, trip: TripSummary, move: MoveForm, onSelect: (Int) -> Unit, header: (@Composable () -> Unit)? = null, bottom: @Composable () -> Unit) {
    val mistakes = remember(trip) { mistakesOf(trip) }
    var playKey by remember(move.id) { mutableIntStateOf(0) }
    val clock = rememberReplayClock(move, playKey)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        header?.invoke()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${listTitle(move)} 다시보기", color = Coach.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(14.dp))
                    Text(listOfNotNull(placeName(move), whenText(move.recordedAt)).joinToString(" · "), color = Coach.Muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.dp, Coach.Line, RoundedCornerShape(24.dp))) {
                    MapReplay(move, clock, state.paint, Modifier.fillMaxSize())
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(18.dp).clip(RoundedCornerShape(12.dp)).background(Coach.Bg.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Legend(Coach.Accent, "내 차")
                        if (move.kind != dev.driveform.form.MoveKind.PARK) Legend(Coach.Faint, "모범대로 가는 차")
                        if (move.checks.any { !it.ok }) Legend(Coach.Amber, "아쉬운 곳")
                    }
                }
                Timeline(move, clock) { playKey++ }
            }
            Column(Modifier.width(500.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SceneCoach(move)
                SceneList(state, trip, mistakes, move, Modifier.weight(1f), onSelect)
                bottom()
            }
        }
    }
}

@Composable
private fun Timeline(move: MoveForm, clock: ReplayClock, onReplay: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val path = remember(move) { dev.driveform.form.DrivePath.of(move) }
    val marks = remember(move) {
        listOfNotNull(
            path.signalTarget?.let { Triple(clock.fraction(it.ms), "켜야 할 곳", Coach.Text) },
            move.signalOnMs?.let { Triple(clock.fraction(it), "깜빡이 켬", if (path.signalTarget != null) Coach.Amber else Coach.Accent) },
            path.brakeSegments.firstOrNull()?.firstOrNull()?.let { Triple(clock.fraction(it.ms), "브레이크", Coach.Amber) },
            path.switches.firstOrNull()?.let { Triple(clock.fraction(it.ms), "방향 바꿈", Coach.Amber) },
            Triple(clock.fraction(move.startMs), if (move.kind.isTurn) "꺾기 시작" else "시작", Coach.Muted).takeIf { move.kind.isTurn },
        )
    }
    Row(Modifier.fillMaxWidth().panel(20.dp).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Coach.Accent).press(CircleShape, onClick = onReplay), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Replay, "다시 재생", tint = Coach.AccentInk, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(18.dp))
        Canvas(Modifier.weight(1f).height(52.dp)) {
            val y = 12.dp.toPx(); val h = 10.dp.toPx()
            drawRoundRect(Coach.Track, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val f = clock.anim.value
            drawRoundRect(Coach.Accent, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * f, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            var lastLabelEnd = -1f
            marks.sortedBy { it.first }.forEach { (fx, label, color) ->
                val x = size.width * fx
                drawCircle(Coach.Panel, 11.dp.toPx(), Offset(x, y)); drawCircle(color, 8.dp.toPx(), Offset(x, y))
                val layout = measurer.measure(label, TextStyle(color = if (color == Coach.Amber) Coach.AmberSoft else Coach.Muted, fontSize = 15.sp))
                val lx = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                if (lx > lastLabelEnd + 8f) { drawText(layout, topLeft = Offset(lx, y + 14.dp.toPx())); lastLabelEnd = lx + layout.size.width }
            }
            drawLine(Color.White, Offset(size.width * f, y - 14.dp.toPx()), Offset(size.width * f, y + 14.dp.toPx()), 5.dp.toPx(), cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(18.dp))
        Text("%.1f초".format(clock.dur / 1000f), color = Coach.Muted, fontSize = 17.sp)
    }
}

/** 다시보기에 나오는 그 장면에 대한 코치 한마디: 한 문장, 아쉬운 수치, 다음엔 이렇게. */
@Composable
private fun SceneCoach(move: MoveForm) {
    val worst = move.checks.filter { !it.ok }.maxByOrNull { it.penalty }
    Column(Modifier.fillMaxWidth().panel().padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachMark(34.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("코치 한마디", color = Coach.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(listTitle(move), shortPlace(move.place), localDateTime(move.recordedAt).format(timeFormat)).joinToString(" · "), color = Coach.Muted, fontSize = 15.sp)
            }
        }
        val line = if (worst != null) Coaching.soft(move, worst.title) + "." else "깔끔했어요. 이 ${listTitle(move)}, 그대로 하면 돼요."
        Text(keepWords(line), color = Coach.Text, fontSize = 23.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
        if (worst != null) {
            Column(Modifier.fillMaxWidth().panel(16.dp, Coach.Stage).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(worst.title, color = Coach.Muted, fontSize = 17.sp)
                Text(keepWords(worst.detail), color = Coach.AmberSoft, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().border(1.dp, Coach.Line2, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lightbulb, null, tint = Coach.Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(keepWords(FormScore.tip(move)), color = Coach.Text2, fontSize = 19.sp, lineHeight = 28.sp)
        }
    }
}

/** 이 운전의 한 줄 칭찬과 다시 볼 장면. 잘한 장면은 접어 둡니다. */
@Composable
private fun SceneList(state: UiState, trip: TripSummary, mistakes: List<MoveForm>, selected: MoveForm, modifier: Modifier, onSelect: (Int) -> Unit) {
    var showGood by remember(trip) { mutableStateOf(false) }
    val good = trip.moves.filter { it !in mistakes }
    val coaching = remember(trip) { state.coachingFor(trip) }
    Column(modifier.fillMaxWidth().panel().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 14.dp)) {
        Text(listOfNotNull("이번 운전", route(trip), trip.minutes?.let { "${it}분" }).joinToString(" · "), color = Coach.Muted, fontSize = 15.sp)
        Gap(4)
        Text(keepWords(coaching.praise), color = Coach.Text2, fontSize = 18.sp, lineHeight = 26.sp)
        Gap(14)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("다시 볼 장면 ", color = Coach.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("${mistakes.size}", color = Coach.Amber, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("장면 ${trip.moves.size}개 중", color = Coach.Muted, fontSize = 15.sp)
        }
        Gap(12)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(mistakes, key = { it.id }) { m -> SceneRow(m, m.id == selected.id, true) { onSelect(m.id) } }
            if (showGood) items(good, key = { it.id }) { m -> SceneRow(m, m.id == selected.id, false) { onSelect(m.id) } }
        }
        if (good.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).press { showGood = !showGood }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, null, tint = Coach.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showGood) "잘한 장면 접기" else "잘한 장면 ${good.size}개는 접어 뒀어요", color = Coach.Muted, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun SceneRow(m: MoveForm, selected: Boolean, mistake: Boolean, onClick: () -> Unit) {
    val worst = m.checks.filter { !it.ok }.maxByOrNull { it.penalty }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Coach.AccentDeep else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) Coach.Accent else Coach.Track, RoundedCornerShape(16.dp))
            .press(RoundedCornerShape(16.dp), onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoveIcon(m, if (selected) Coach.Accent else Coach.Text2, Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(listTitle(m), shortPlace(m.place)).joinToString(" · "), color = Coach.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (mistake && worst != null) "${worst.title} · ${worst.detail}" else "잘했어요",
                color = if (mistake) Coach.AmberSoft else Coach.Muted, fontSize = 16.sp, maxLines = 1,
            )
        }
        Text(localDateTime(m.recordedAt).format(timeFormat), color = Coach.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun ProgressLink(grad: Graduation, advanced: Graduation, onClick: () -> Unit) {
    val g = if (grad.graduated) advanced else grad
    val left = g.remaining.size
    Row(Modifier.fillMaxWidth().panel(20.dp).press(RoundedCornerShape(20.dp), onClick = onClick).padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(46.dp)) {
            val stroke = 6.dp.toPx()
            drawCircle(Coach.Track, size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            drawArc(Coach.Accent, -90f, 360f * g.courses.count { it.passed } / g.courses.size.coerceAtLeast(1), false, style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when { !grad.graduated && left > 0 -> "초보 졸업까지 기본기 ${left}개"; !grad.graduated -> "초보 졸업"; left > 0 -> "심화 과정 ${left}개 남음"; else -> "심화 과정 모두 마침" },
                color = Coach.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Text("${if (grad.graduated) "심화" else "기본기"} ${g.courses.count { it.passed }} / ${g.courses.size}", color = Coach.Muted, fontSize = 15.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Coach.Muted)
    }
}

// ---------- 실력 ----------

@Composable
private fun SkillPane(state: UiState) {
    val grad = state.graduation
    val courses = if (grad.graduated) state.advanced.courses else grad.courses
    var focusSkill by remember { mutableStateOf<Skill?>(null) }
    val focus = courses.firstOrNull { it.skill == focusSkill } ?: courses.firstOrNull { !it.passed } ?: courses.first()
    val series = remember(state.moves, focus.skill) { rollingRate(state.moves, focus.skill) }
    val coaching = remember(state.selectedTrip) { state.selectedTrip?.let(state::coachingFor) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().panel().padding(horizontal = 36.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionLabel(if (focus.passed) "완성한 기본기" else "지금 연습 중")
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(focus.skill.label, color = Coach.Text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                    Text(focus.skill.mission, color = Coach.Text2, fontSize = 20.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    BigNumber("${focus.ok}", "/${focus.recent.size.coerceAtLeast(10)}", 72)
                    Text("최근 10번 중", color = Coach.Muted, fontSize = 17.sp)
                }
            }
            RateChart(series, focus.passed, Modifier.weight(1f).fillMaxWidth())
            Text(keepWords(skillLine(focus, series)), color = Coach.Text2, fontSize = 21.sp, lineHeight = 31.sp)
        }
        Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().panel().padding(horizontal = 32.dp, vertical = 28.dp)) {
                SectionLabel(if (grad.graduated) "심화 과정" else "초보 졸업까지")
                Gap(10)
                val left = courses.count { !it.passed }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (left > 0) {
                        BigNumber("$left", size = 64)
                        Spacer(Modifier.width(12.dp))
                        Text(if (grad.graduated) "과정 남았어요" else "기본기 남았어요", color = Coach.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 6.dp))
                    } else {
                        Text(if (grad.graduated) "심화 과정도 모두 마쳤어요" else "초보 졸업!", color = Coach.Accent, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (grad.graduated && grad.graduatedAt != null) Text("초보 졸업 ${dayFormat.format(Instant.ofEpochMilli(grad.graduatedAt!!).atZone(ZoneId.systemDefault()))}", color = Coach.AccentSoft, fontSize = 17.sp)
                Gap(8)
                courses.forEach { c ->
                    val on = c.skill == focus.skill
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Coach.Panel2 else Color.Transparent)
                            .press(RoundedCornerShape(12.dp)) { focusSkill = c.skill }.padding(horizontal = 10.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (c.passed) Box(Modifier.size(24.dp).background(Coach.AccentDeep, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Coach.Accent, modifier = Modifier.size(18.dp)) }
                        else Box(Modifier.size(24.dp).border(3.dp, Coach.Amber, CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(c.skill.label, color = Coach.Text, fontSize = 21.sp, fontWeight = if (c.passed) FontWeight.Medium else FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            c.passedAt?.let { "${dayFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))} 완성" } ?: "${c.ok} / 10 · 연습 중",
                            color = if (c.passed) Coach.Muted else Coach.AmberSoft, fontSize = 17.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("최근 10번 중 9번 잘하면 완성이에요.", color = Coach.Muted, fontSize = 16.sp)
            }
            coaching?.mission?.let { mission ->
                val streak = mission.attempts.reversed().takeWhile { it }.size.coerceAtMost(mission.target)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Coach.AccentDeep).border(2.dp, Coach.Accent, RoundedCornerShape(22.dp)).padding(horizontal = 26.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("이번 주 목표 · ${mission.target}번 연속", color = Coach.AccentSoft, fontSize = 16.sp)
                        Text(mission.skill.mission, color = Coach.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    BigNumber("$streak", " / ${mission.target}", 36)
                }
            }
            if (!grad.graduated) Text(
                "졸업하면 심화 과정 · 주차 · 빗길 · 밤길", color = Coach.Muted, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().border(1.dp, Coach.Line2, RoundedCornerShape(22.dp)).padding(horizontal = 26.dp, vertical = 18.dp),
            )
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

/** 날마다 그날까지의 '최근 10번 중 잘한 비율'(%). 최근 14일, 기록이 있는 날부터. */
internal fun rollingRate(moves: List<MoveForm>, skill: Skill, days: Int = 14, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, Int>> {
    val judged = moves.filter { it.recordedAt > 0 }.sortedBy { it.recordedAt }.mapNotNull { m -> skill.judge(m)?.let { m.recordedAt to it } }
    if (judged.isEmpty()) return emptyList()
    val last = Instant.ofEpochMilli(judged.last().first).atZone(zone).toLocalDate()
    return (days - 1 downTo 0).mapNotNull { back ->
        val day = last.minusDays(back.toLong())
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val recent = judged.filter { it.first < end }.takeLast(10)
        if (recent.isEmpty()) null else day to (recent.count { it.second } * 100 / recent.size)
    }
}

private fun skillLine(c: Course, series: List<Pair<LocalDate, Int>>): String {
    val first = series.firstOrNull()?.second
    val last = series.lastOrNull()?.second
    val trend = if (first != null && last != null && last > first) "2주 전 ${first}%에서 지금 ${last}%까지 올라왔어요. " else ""
    if (c.passed) return trend + "이미 몸에 붙은 기본기예요. 이 느낌 그대로 가면 돼요."
    val need = (9 - c.ok).coerceAtLeast(1)
    val times = when (need) { 1 -> "한 번"; 2 -> "두 번"; 3 -> "세 번"; 4 -> "네 번"; else -> "${need}번" }
    return trend + "${times}만 더 잘하면 ${c.skill.label}도 완성이에요."
}

@Composable
private fun RateChart(series: List<Pair<LocalDate, Int>>, done: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val color = if (done) Coach.Accent else Coach.Amber
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val pl = 56.dp.toPx(); val pr = 96.dp.toPx(); val pt = 18.dp.toPx(); val pb = 34.dp.toPx()
        val w = size.width - pl - pr; val h = size.height - pt - pb
        fun x(i: Int) = pl + w * i / (series.size - 1)
        fun y(v: Int) = pt + h * (1 - v / 100f)
        listOf(0, 50, 100).forEach { v ->
            drawLine(Coach.Line, Offset(pl, y(v)), Offset(pl + w, y(v)), 1.dp.toPx())
            val l = measurer.measure("$v%", TextStyle(color = Coach.Muted, fontSize = 15.sp))
            drawText(l, topLeft = Offset(pl - l.size.width - 12.dp.toPx(), y(v) - l.size.height / 2))
        }
        drawLine(Coach.Text, Offset(pl, y(90)), Offset(pl + w, y(90)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)))
        val target = measurer.measure("완성 90%", TextStyle(color = Coach.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        drawText(target, topLeft = Offset(pl + w + 12.dp.toPx(), y(90) - target.size.height / 2))
        val p = androidx.compose.ui.graphics.Path()
        series.forEachIndexed { i, (_, v) -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
        drawPath(p, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val lx = x(series.lastIndex); val ly = y(series.last().second)
        drawCircle(Coach.Panel, 11.dp.toPx(), Offset(lx, ly)); drawCircle(color, 8.dp.toPx(), Offset(lx, ly))
        val now = measurer.measure("${series.last().second}%", TextStyle(color = if (done) Coach.AccentSoft else Coach.AmberSoft, fontSize = 20.sp, fontWeight = FontWeight.Bold))
        drawText(now, topLeft = Offset(lx + 16.dp.toPx(), ly - now.size.height / 2 - (if (kotlin.math.abs(ly - y(90)) < 24.dp.toPx()) 20.dp.toPx() else 0f)))
        val fmt = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
        val a = measurer.measure(series.first().first.format(fmt), TextStyle(color = Coach.Muted, fontSize = 15.sp))
        drawText(a, topLeft = Offset(pl, size.height - a.size.height))
        val b = measurer.measure(series.last().first.format(fmt), TextStyle(color = Coach.Muted, fontSize = 15.sp))
        drawText(b, topLeft = Offset(pl + w - b.size.width, size.height - b.size.height))
    }
}

// ---------- 지난 운전 ----------

@Composable
private fun HistoryPane(state: UiState) {
    val trips = state.tripSummaries
    // 목록에서 고른 운전은 이 탭 안에서 상세로 엽니다(오늘 코칭은 늘 최근 운전).
    var openId by rememberSaveable { mutableStateOf<Int?>(null) }
    // 목록에서 장면을 바로 눌렀으면 그 장면부터 엽니다.
    var openScene by rememberSaveable { mutableStateOf<Int?>(null) }
    val open = trips.firstOrNull { it.trip.id == openId }
    if (open != null) {
        var selectedId by rememberSaveable(open.trip.id, openScene) { mutableStateOf(openScene ?: mistakesOf(open).firstOrNull()?.id ?: open.moves.last().id) }
        val move = open.moves.firstOrNull { it.id == selectedId } ?: open.moves.last()
        DriveReview(state, open, move, { selectedId = it }, header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Coach.Panel2).press(RoundedCornerShape(50)) { openId = null }.padding(start = 16.dp, end = 30.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Coach.Text, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("지난 운전", color = Coach.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(20.dp))
                Text(listOfNotNull(whenText(open.trip.startedAt), route(open)).joinToString(" · "), color = Coach.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }) {}
        return
    }
    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize().panel(), contentAlignment = Alignment.Center) { Text("아직 지난 운전이 없어요.", color = Coach.Text2, fontSize = 24.sp) }
        return
    }
    // 돌아볼 건 최근 운전이라 최근 [HISTORY_TRIPS]번만 보여 줍니다(목록이 길면 화면이 무거워져요).
    val byDay = remember(trips) { trips.take(HISTORY_TRIPS).groupBy { localDateTime(it.trip.startedAt).toLocalDate() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        byDay.forEach { (day, list) ->
            item(key = "d$day") { Text(dayHeader(day), color = Coach.Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            items(list, key = { it.trip.id }) { t ->
                val mistakes = mistakesOf(t)
                val n = mistakes.size
                Column(Modifier.fillMaxWidth().panel(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().press(RoundedCornerShape(20.dp)) { openScene = null; openId = t.trip.id }.padding(horizontal = 26.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(localDateTime(t.trip.startedAt).format(timeFormat), color = Coach.Muted, fontSize = 19.sp, modifier = Modifier.width(130.dp))
                    Column(Modifier.weight(1f)) {
                        Text(route(t) ?: "운전", color = Coach.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(t.minutes?.let { "${it}분" }, "장면 ${t.moves.size}개").joinToString(" · "), color = Coach.Muted, fontSize = 16.sp)
                    }
                    Text(
                        if (n == 0) "다시 볼 장면 없음" else "다시 볼 장면 $n",
                        color = if (n == 0) Coach.AccentSoft else Coach.AmberSoft, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (n == 0) Coach.AccentDeep else Coach.AmberDeep).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Rounded.ChevronRight, null, tint = Coach.Muted)
                }
                // 다시 볼 장면: 눌러서 그 장면부터 바로 엽니다.
                if (mistakes.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(start = 152.dp, end = 16.dp, bottom = 14.dp)) {
                        mistakes.take(HISTORY_SCENES).forEachIndexed { i, m ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(end = 10.dp).height(1.dp).background(Coach.Line))
                            HistorySceneRow(m) { openScene = m.id; openId = t.trip.id }
                        }
                        if (n > HISTORY_SCENES) Text("그 밖에 ${n - HISTORY_SCENES}개는 열어서 볼 수 있어요", color = Coach.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 50.dp, top = 6.dp))
                    }
                }
                }
            }
        }
    }
}

/** 지난 운전 목록에 보이는 최근 운전 수와, 운전마다 미리 보이는 다시 볼 장면 수. */
private const val HISTORY_TRIPS = 5
private const val HISTORY_SCENES = 3

/** 지난 운전 목록 안의 다시 볼 장면 한 줄. */
@Composable
private fun HistorySceneRow(m: MoveForm, onClick: () -> Unit) {
    val worst = m.checks.filter { !it.ok }.maxByOrNull { it.penalty }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).press(RoundedCornerShape(12.dp), onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoveIcon(m, Coach.Text2, Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(listOfNotNull(listTitle(m), shortPlace(m.place)).joinToString(" · "), color = Coach.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(220.dp), maxLines = 1)
        Text(worst?.let { "${it.title} · ${it.detail}" } ?: "", color = Coach.AmberSoft, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(localDateTime(m.recordedAt).format(timeFormat), color = Coach.Muted, fontSize = 15.sp)
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Coach.Faint, modifier = Modifier.size(20.dp))
    }
}

// ---------- 내 차 ----------

@Composable
private fun MyCarPane(state: UiState, sprites: CarSprites, onPaint: (CarPaint) -> Unit) {
    val look = state.car.look
    val fromCar = state.car.lengthM != null
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Coach.Stage).border(1.dp, Coach.Line, RoundedCornerShape(24.dp)).padding(horizontal = 38.dp, vertical = 34.dp)) {
            sprites.hero(state.paint)?.let { Image(it, "${state.paint.label} ${look.displayName}", Modifier.align(Alignment.Center).size(700.dp)) }
            Column {
                SectionLabel("내 차")
                Text(look.displayName, color = Coach.Text, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                Text(listOfNotNull(state.car.year?.let { "${it}년식" }, "외관은 차종에 맞춰 입혀요").joinToString(" · "), color = Coach.Muted, fontSize = 17.sp)
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("길이" to (state.car.lengthM ?: look.lengthM), "폭" to (state.car.widthM ?: look.widthM), "높이" to (state.car.heightM ?: look.heightM), "축간거리" to (state.car.wheelbaseM ?: look.wheelbaseM))
                    .forEach { (k, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Coach.Panel.copy(alpha = 0.92f)).padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Text(k, color = Coach.Muted, fontSize = 15.sp)
                            BigNumber("%.2f".format(v), "m", 28, if (k == "축간거리") Coach.Accent else Coach.Text)
                        }
                    }
            }
        }
        Column(Modifier.width(500.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.fillMaxWidth().panel().padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("차 색상", color = Coach.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(keepWords("다시보기에 나오는 내 차 색이에요. 차가 색상을 알려 주지 않아서 한 번 골라 주세요."), color = Coach.Muted, fontSize = 16.sp, lineHeight = 24.sp)
                CarPaint.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { p ->
                            val on = p == state.paint
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Coach.Stage)
                                    .border(if (on) 3.dp else 1.dp, if (on) Coach.Accent else Coach.Track, RoundedCornerShape(18.dp))
                                    .press(RoundedCornerShape(18.dp)) { onPaint(p) }.padding(top = 6.dp, bottom = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                sprites.hero(p)?.let { Image(it, null, Modifier.size(110.dp)) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(14.dp).background(Color(("FF" + p.hex).toLong(16)), CircleShape).border(1.dp, Coach.Line2, CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(p.label, color = Coach.Text, fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                if (state.paint.dark) Text(keepWords("어두운 색은 지도에서 잘 보이도록 차 둘레에 밝은 테두리를 함께 그려요."), color = Coach.Text2, fontSize = 16.sp, lineHeight = 24.sp)
            }
            Column(Modifier.weight(1f).fillMaxWidth().panel().padding(horizontal = 26.dp, vertical = 24.dp)) {
                Text("차에서 읽어 온 정보", color = Coach.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Gap(8)
                listOf(
                    "제조사" to (state.car.make ?: "-"), "모델" to (state.car.model ?: "-"), "연식" to (state.car.year?.toString() ?: "-"),
                    "차체 치수" to if (fromCar) "경로 계산에 사용 중" else "읽지 못해 제원표 값 사용",
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(k, color = Coach.Muted, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(v, color = Coach.Text, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// ---------- 시작하기 ----------

@Composable
private fun StartPane(sprites: CarSprites, paint: CarPaint, onStart: () -> Unit) {
    Row(Modifier.fillMaxSize().panel().padding(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            CoachMark(64.dp)
            Text("운전할 때마다\n옆자리 코치가 함께해요", color = Coach.Text, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 60.sp)
            Text(keepWords("운전하는 동안엔 소리와 불빛으로만 알려 드리고, 차를 세우면 다시 볼 장면만 골라 같이 돌아봐요. 기본기 네 가지가 몸에 붙으면 초보 졸업이에요."),
                color = Coach.Text2, fontSize = 22.sp, lineHeight = 34.sp)
            Gap(8)
            Text(
                "시작하기", color = Coach.AccentInk, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Coach.Accent).press(RoundedCornerShape(18.dp), onClick = onStart).padding(horizontal = 40.dp, vertical = 20.dp),
            )
        }
        sprites.hero(paint)?.let { Image(it, null, Modifier.size(620.dp)) }
    }
}
