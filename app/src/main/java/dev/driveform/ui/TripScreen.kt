package dev.driveform.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.UiState
import dev.driveform.form.MoveGroup
import dev.driveform.form.Stage
import dev.driveform.form.FormScore
import dev.driveform.form.Tier
import dev.driveform.form.First
import dev.driveform.form.Firsts
import dev.driveform.form.Weather
import dev.driveform.form.WeatherTracker
import dev.driveform.form.TripSummary
import dev.driveform.form.Coaching
import dev.driveform.form.SkillStat

/**
 * 주행 요약: P에 두면 바로 뜨는 화면입니다. 초보와 습관을 고치려는 운전자가 대상이라
 * 평가표가 아니라 코치처럼 말합니다. 잘한 것을 먼저 말하고, 고칠 것은 딱 하나만 내세우며,
 * 과목(기술)별 실력과 이번 주 연수 과제, 졸업 진도로 다음 운전을 기대하게 합니다.
 */
@Composable
internal fun TripSummaryPane(state: UiState, onOpenMove: (Int) -> Unit, onOpenGrad: () -> Unit, modifier: Modifier) {
    val summary = state.selectedTrip ?: return
    SummaryCard(summary, state.coachingFor(summary), state.firsts[summary.trip.id].orEmpty(), onOpenMove, onOpenGrad, modifier)
}

/** 날짜별 주행 목록. 왼쪽에 늘 떠 있고, 오른쪽(요약 ↔ 동작 기록)은 여기서 고른 주행을 보여 줍니다. */
@Composable
internal fun TripListPane(state: UiState, onSelectTrip: (Int) -> Unit, modifier: Modifier) {
    val summary = state.selectedTrip ?: return
    TripList(state.tripSummaries, summary.trip.id, onSelectTrip, modifier)
}

@Composable
private fun TripList(trips: List<TripSummary>, selectedId: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val groups = trips.groupBy { localDateTime(it.trip.startedAt).toLocalDate() }
    val listState = rememberLazyListState()
    // 고른 주행이 화면 밖이면 그리로 옮깁니다. 새 주행이 생기거나 기록이 통째로 바뀌면 맨 위의 새 주행이 골라집니다.
    // 기록이 바뀐 직후에는 목록이 예전 자리를 붙들고 있으므로, 한 번 그린 뒤에 확인합니다.
    LaunchedEffect(selectedId, trips.firstOrNull()?.trip?.id) {
        withFrameNanos { }
        var index = 0
        var target = 0
        var header = 0
        groups.forEach { (_, list) ->
            val h = index++
            list.forEach { if (it.trip.id == selectedId) { target = index; header = h }; index++ }
        }
        val end = listState.layoutInfo.viewportEndOffset
        val shown = listState.layoutInfo.visibleItemsInfo.any { it.index == target && it.offset >= 0 && it.offset + it.size <= end }
        if (!shown) listState.scrollToItem(if (target - header <= 2) header else target - 1)
    }
    PlainCard(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 12.dp), state = listState) {
            groups.forEach { (day, list) ->
                item(key = "day-$day") {
                    Text(
                        dayHeader(day), color = TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 26.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                items(list, key = { it.trip.id }) { t -> TripRow(t, t.trip.id == selectedId) { onSelect(t.trip.id) } }
            }
        }
    }
}

/** 날짜별 목록 한 줄: 어디서 어디로, 출발 시각, 큰 점수. 단계 이름은 요약에서 말하므로 뺍니다. */
@Composable
private fun TripRow(t: TripSummary, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .press(RectangleShape, scale = 0.98f, onClick = onClick)
            .background(if (selected) Select else Color.Transparent)
            .padding(start = 18.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(56.dp).background(if (selected) TextPrimary else Color.Transparent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val time = localDateTime(t.trip.startedAt).format(timeFormat)
            val route = route(t)
            Text(route ?: "$time 출발", color = TextPrimary, fontSize = 25.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (t.trip.endedAt == null) "$time · 주행 중" else time, color = TextSecondary, fontSize = 22.sp, maxLines = 1)
                listOfNotNull(weatherIcon(t.trip.weather), NightIcon.takeIf { Firsts.isNight(t.trip) }).forEach {
                    Spacer(Modifier.width(8.dp))
                    Icon(it, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        SignText("${t.score}", 38.sp, TextPrimary)
    }
}

/** 출발한 동네 → 도착한 동네("시흥동 → 정자동"). 같은 동네를 돌았으면 하나만, 주소가 없으면 null. */
internal fun route(t: TripSummary): String? {
    val places = t.moves.sortedBy { it.id }.mapNotNull { shortPlace(it.place) }
    val from = places.firstOrNull() ?: return null
    val to = places.last()
    return if (from == to) from else "$from → $to"
}

/** "28분 · 12.4km · 동작 14번". 달리는 중이면 "주행 중"을 앞에 붙입니다. */
private fun tripFacts(t: TripSummary, withMoves: Boolean) = listOfNotNull(
    "주행 중".takeIf { t.trip.endedAt == null },
    t.minutes?.takeIf { t.trip.endedAt != null }?.let { if (it < 1) "1분 미만" else "${it}분" },
    t.trip.distanceM?.let { "%.1fkm".format(it / 1000) },
    "동작 ${t.moves.size}번".takeIf { withMoves },
).joinToString(" · ")

/**
 * 한글은 글자 단위로 줄이 바뀌어 "모/두"처럼 낱말이 잘립니다.
 * 낱말 안의 글자 사이에 줄바꿈 금지 문자(U+2060)를 넣어 띄어쓰기에서만 줄이 바뀌게 합니다.
 */
internal fun keepWords(text: String) = text.split(' ').joinToString(" ") { it.toList().joinToString("\u2060") }

@Composable
private fun SummaryCard(s: TripSummary, c: Coaching, firsts: List<First>, onOpenMove: (Int) -> Unit, onOpenGrad: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            CoachSign(s, c, firsts, Modifier.weight(1f).fillMaxHeight())
            ScoreSign(s, c, Modifier.width(480.dp).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            c.current.forEach { st -> SkillRing(st, st.skill == c.mission.skill && !c.mission.completed, Modifier.weight(1f).fillMaxHeight()) }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            OneThing(s, onOpenMove, Modifier.weight(1.4f).fillMaxHeight(), preferPark = c.stage == Stage.ADVANCED)
            // 이 주행까지의 졸업 진도와 이번 주 과제. 누르면 홈으로 갑니다.
            GradGuideCard(c, onOpenGrad, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** 코치의 한마디(파란 안내 표지): 잘한 것 한 문장. 다듬을 것은 아래 노란 주의 표지가 말합니다. */
@Composable
private fun CoachSign(s: TripSummary, c: Coaching, firsts: List<First>, modifier: Modifier) {
    val ink = signInk(SignKind.INFO)
    SignBox(SignKind.INFO, modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            // 다른 주행으로 바뀌면 운전대를 한 번 꺾었다가 출렁이며 똑바로 돌아옵니다.
            val turn = remember { Animatable(0f) }
            var lastTrip by remember { mutableStateOf(s.trip.id) }
            LaunchedEffect(s.trip.id) {
                if (s.trip.id == lastTrip) return@LaunchedEffect
                lastTrip = s.trip.id
                turn.snapTo(-70f)
                turn.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 140f))
            }
            Box(Modifier.size(92.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                WheelIcon(if (IsNight) Color(0xFF111111) else SignBlue, Modifier.size(54.dp).graphicsLayer { rotationZ = turn.value })
            }
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f).freshen(s.trip.id), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 빗길·안갯길·밤 운전이면 맨 앞에 아이콘과 이름을 붙입니다.
                    val tags = listOfNotNull(
                        weatherIcon(s.trip.weather)?.let { it to s.trip.weather.label },
                        (NightIcon to "밤").takeIf { Firsts.isNight(s.trip) },
                    )
                    tags.forEach { (icon, label) ->
                        IconText(icon, label, ink, 22.sp, iconSize = 24.dp, gap = 4.dp, fontWeight = FontWeight.ExtraBold)
                        Text("  ·  ", color = ink, fontSize = 22.sp)
                    }
                    Text(
                        route(s)?.let { r -> listOfNotNull(whenText(s.trip.startedAt), r, s.minutes?.takeIf { s.trip.endedAt != null }?.let { "${it}분" }).joinToString(" · ") }
                            ?: "${whenText(s.trip.startedAt)} 출발 · ${tripFacts(s, withMoves = false)}",
                        color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 처음 해 본 것(첫 빗길·안갯길·밤 운전)이면 그걸 먼저 축하합니다.
                Text(keepWords(firsts.firstOrNull()?.praise ?: c.praise), color = ink, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 41.sp, maxLines = 3)
            }
        }
    }
}

/** 점수: 원형 표지 안의 숫자(바늘처럼 움직임), 단계, 고수까지, 지난번과 비교. */
@Composable
private fun ScoreSign(s: TripSummary, c: Coaching, modifier: Modifier) {
    PlainCard(modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundSign(140.dp, ring = 14.dp) {
                val v by rolling(s.score.toFloat())
                SignText("${v.roundToInt()}", 74.sp, if (IsNight) TextPrimary else Color(0xFF111111))
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.freshen(s.trip.id), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignText(s.tier.label, 46.sp)
                val gap = Tier.MASTER.min - s.score
                Text(if (gap > 0) "고수까지 ${gap}점 남았어요" else "고수 폼이에요", color = TextSecondary, fontSize = 22.sp)
                val up = c.trend?.contains("올랐") == true
                val down = c.trend?.contains("내려") == true
                IconText(
                    if (up) Icons.AutoMirrored.Rounded.TrendingUp else if (down) Icons.AutoMirrored.Rounded.TrendingDown else Icons.AutoMirrored.Rounded.TrendingFlat,
                    c.trend ?: "첫 주행이에요", TextSecondary, 22.sp, iconSize = 24.dp,
                    iconTint = if (up) Good else if (down) Bad else TextMuted,
                )
            }
        }
    }
}

/** 과목 링. 최근 잘한 비율을 채우고, 이번 주 과제인 과목은 노란 테두리와 검정(밤엔 노랑) 링으로 표시합니다. */
@Composable
private fun SkillRing(st: SkillStat, isMission: Boolean, modifier: Modifier) {
    val fill by rolling((st.recent ?: 0).toFloat())
    val color = if (isMission) (if (IsNight) SignYellow else Color(0xFF111111)) else Good
    val track = Track
    PlainCard(modifier, highlight = isMission) {
        Column(Modifier.fillMaxSize().padding(vertical = 20.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 14.dp.toPx()
                    val inset = w / 2
                    val arcSize = Size(size.width - w, size.height - w)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    if (fill > 0) drawArc(color, -90f, 360f * fill / 100f, false, Offset(inset, inset), arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SkillIcon(st.skill, TextPrimary, Modifier.size(24.dp))
                    if (st.recent != null) {
                        val v by rolling(st.recent.toFloat())
                        SignText("${v.roundToInt()}", 40.sp)
                    } else SignText("–", 40.sp)
                }
            }
            Text(st.skill.short, color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            val today = when {
                st.recent == null -> "아직 기록이 없어요"
                st.tripTotal == 0 -> "이번엔 없었어요"
                else -> "이번 주행 ${st.tripOk}/${st.tripTotal}"
            }
            Text(today, color = if (isMission) TextPrimary else TextSecondary, fontSize = 22.sp, fontWeight = if (isMission) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.freshen(today))
        }
    }
}

/** 다음 운전에서 신경 쓸 것 하나(노란 주의 표지). 감점이 가장 컸던 항목과 그 장면의 경로 그림입니다. */
@Composable
private fun OneThing(s: TripSummary, onOpenMove: (Int) -> Unit, modifier: Modifier, preferPark: Boolean = false) {
    // 졸업 뒤(심화 과정)엔 주차 습관을 먼저 짚습니다. 다른 것이 훨씬 컸으면(두 배 넘게) 그것부터.
    val first = s.weaknesses.firstOrNull()
    val park = if (preferPark) s.weaknesses.firstOrNull { it.group == MoveGroup.PARK } else null
    val top = if (park != null && first != null && park.penalty * 2 >= first.penalty) park else first
    val ink = signInk(SignKind.CAUTION)
    SignBox(SignKind.CAUTION, modifier) {
        // 비·안개 속에서 전조등을 안 켠 건 어떤 동작보다 먼저 말합니다(법으로 정해진 일이고 상대에게 내가 안 보입니다).
        if (s.trip.lightsOffWetS >= WeatherTracker.LIGHTS_OFF_NOTICE_MS / 1000) {
            val what = if (s.trip.weather == Weather.FOG) "안개 낀 날" else "비 올 때"
            val min = (s.trip.lightsOffWetS + 30) / 60
            Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 22.dp, top = 24.dp, bottom = 24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().freshen(s.trip.id), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Diamond(28.dp) { Icon(Icons.Rounded.TrackChanges, null, tint = SignIcon, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Text("다음엔 이것만 신경 써 봐요", color = ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Text(keepWords("${what}엔 전조등도 꼭 켜 주세요"), color = ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp)
                    Text(
                        keepWords("${s.trip.weather.label.ifEmpty { "빗길" }}에서 ${if (min < 1) "잠깐" else "${min}분쯤"} 전조등이 꺼져 있었어요. 내가 잘 보여야 다른 차도 조심할 수 있어요."),
                        color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.width(220.dp).fillMaxHeight().background(Color(0xFF111111), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    HeadlightIcon(SignYellow, Modifier.size(120.dp))
                }
            }
            return@SignBox
        }
        if (top == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("다음엔 이것만 신경 써 봐요", color = ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(keepWords("이번엔 다듬을 곳이 없었어요. 정말 잘하셨어요!"), color = ink, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 48.sp)
                Text(keepWords("이 느낌 그대로 다음에도 달려 봐요"), color = ink, fontSize = 24.sp)
            }
            return@SignBox
        }
        val example = s.moves.first { it.id == top.exampleId }
        Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 22.dp, top = 24.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().freshen(example.id), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Diamond(28.dp) { Icon(Icons.Rounded.TrackChanges, null, tint = SignIcon, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Text("다음엔 이것만 신경 써 봐요", color = ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                Text(keepWords(top.tip), color = ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp)
                Text(
                    keepWords("${top.of}번 중 ${top.missed}번이 조금 아쉬웠어요" + (shortPlace(example.place)?.let { " · $it ${example.title}" } ?: "")),
                    color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.fillMaxWidth().press(RoundedCornerShape(14.dp)) { onOpenMove(top.exampleId) }
                        .background(Color(0xFF111111), RoundedCornerShape(14.dp)).padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconText(Icons.Rounded.Replay, "그 장면 다시 보기", SignYellow, 26.sp, fontWeight = FontWeight.ExtraBold, iconSize = 28.dp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(220.dp).fillMaxHeight().press(RoundedCornerShape(14.dp)) { onOpenMove(top.exampleId) }.background(Surface, RoundedCornerShape(14.dp))) {
                PathChart(example, null, Modifier.fillMaxSize())
            }
        }
    }
}
