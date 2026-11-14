package dev.driveform.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.Ghost
import dev.driveform.Screen
import dev.driveform.UiState
import dev.driveform.form.Coaching
import dev.driveform.form.DrivePath
import dev.driveform.form.FormScore
import dev.driveform.form.MoveForm
import dev.driveform.form.Weather
import dev.driveform.form.MoveKind
import dev.driveform.form.PathPoint
import dev.driveform.form.Side
import java.time.Instant
import kotlin.math.abs
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


/**
 * 졸업 진도(홈)와, 왼쪽 날짜별 목록 + 연수 일지·장면 복습 두 쪽으로 된 기록 화면입니다.
 * 연수 일지(주행 요약)와 장면 복습(동작 기록)은 좌우로 넘기는 두 쪽입니다. 탭을 눌러도, 화면을 밀어도 넘어갑니다.
 * 요약에서 넘기면 그 주행의 동작이, 동작 기록에서 넘기면 고른 동작이 있는 주행의 요약이 나옵니다(앱의 [onShow]가 맞춥니다).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FormScreen(
    state: UiState,
    onSelect: (Int) -> Unit,
    onShow: (Screen) -> Unit,
    onSelectTrip: (Int) -> Unit,
    onOpenMove: (Int) -> Unit,
    onStart: () -> Unit = {},
    /** 주행 속도. 머리글에서만 읽어서, 속도가 바뀔 때 화면 전체를 다시 그리지 않습니다. */
    speedKmh: State<Int> = mutableIntStateOf(0),
) {
    // 동작이 있는 주행이 없으면 요약 쪽은 비어 있으므로 동작 기록 한 쪽만 둡니다.
    val pages = if (state.tripSummaries.isNotEmpty()) listOf(Screen.TRIP, Screen.MOVES) else listOf(Screen.MOVES)
    val pager = rememberPagerState(initialPage = pages.indexOf(state.screen).coerceAtLeast(0)) { pages.size }
    // 탭, '그 장면 다시 보기', 주차 같은 앱 쪽 화면 바꿈을 넘김 애니메이션으로 보여 줍니다.
    LaunchedEffect(state.screen, pages.size) {
        val target = pages.indexOf(state.screen)
        if (target >= 0 && target != pager.currentPage) pager.animateScrollToPage(target)
    }
    // 손으로 넘겨 멈춘 쪽을 앱에 알립니다.
    LaunchedEffect(pager, pages) {
        snapshotFlow { pager.settledPage }.drop(1).collect { pages.getOrNull(it)?.let(onShow) }
    }
    // 경로 카드의 '다시보기'는 좁은 카드 대신 화면 전체를 덮는 창으로 엽니다.
    var replayMove by remember { mutableStateOf<MoveForm?>(null) }
    Box(Modifier.fillMaxSize()) {
    run {
    Column(
        Modifier
            .fillMaxSize()
            .background(Page)
            .padding(vertical = 32.dp),
    ) {
        // 탭: 졸업 진도(홈) · 연수 일지 · 장면 복습. 선택 표시는 홈 ↔ 일지는 미끄러지고, 일지 ↔ 복습은 넘기는 손가락을 따라갑니다.
        val home = state.screen == Screen.GRAD
        val base by animateFloatAsState(if (home) 0f else 1f, tween(320), label = "tab-base")
        Header(state, speedKmh, if (state.started) listOf(Screen.GRAD) + pages else emptyList(), { base * (1 + pager.currentPage + pager.currentPageOffsetFraction) }, onShow, Modifier.padding(horizontal = PAGE_GUTTER))
        Spacer(Modifier.height(24.dp))
        // 처음 켜면 초보운전 딱지를 붙이는 시작하기 화면부터 보여 줍니다.
        if (!state.started) {
            StartScreen(onStart, Modifier.weight(1f).fillMaxWidth().padding(horizontal = PAGE_GUTTER))
            return@Column
        }
        Crossfade(home, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(260), label = "home") { isHome ->
        when {
            isHome -> GraduationScreen(state, onShow, onSelectTrip, Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER))
            // 주행이 아직 없으면 예전처럼 동작 기록 한 화면만 둡니다.
            pages.size == 1 -> MovesPage(state, onSelect, Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER))
            else ->
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(PAGE_GUTTER))
            TripListPane(state, onSelectTrip, Modifier.width(TRIP_LIST_WIDTH).fillMaxHeight())
            // 오른쪽만 넘깁니다. 넘어가는 화면이 왼쪽 목록 위로 올라오지 않게 이 칸 밖은 잘라 냅니다.
            Box(Modifier.weight(1f).fillMaxHeight()) {
            HorizontalPager(pager, Modifier.fillMaxSize().clipToBounds(), beyondBoundsPageCount = 1) { page ->
                val screen = pages[page]
                // 이 쪽이 지금 화면에서 얼마나 오른쪽에 있는지(0이면 제자리, 1이면 한 화면 오른쪽).
                val away = { (page - pager.currentPage) - pager.currentPageOffsetFraction }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 요약을 보고 있을 때 동작 기록의 왼쪽 끝이 오른쪽에 살짝 보이도록 당겨 둡니다.
                            // 밀어서 넘어오는 동안 당긴 만큼이 서서히 풀려 제자리에 섭니다.
                            if (screen == Screen.MOVES) {
                                val a = away().coerceIn(0f, 1f)
                                translationX = -PEEK.toPx() * a
                                // 비치는 카드는 옅게 둬서, 위에 걸친 손잡이와 섞이지 않게 합니다.
                                alpha = 1f - 0.6f * a
                            }
                        },
                ) {
                    // 요약은 오른쪽을 비워 둬서, 그 자리에 손잡이와 다음 쪽이 놓입니다.
                    // 동작 기록은 왼쪽에 손잡이 자리를 비워 둡니다(돌아가기).
                    val start = if (screen == Screen.TRIP) PANE_GAP else HANDLE_LANE
                    val end = if (screen == Screen.TRIP) PEEK else PAGE_GUTTER
                    // 왼쪽에서 다른 주행을 고르면 숫자·링은 바늘처럼 움직이고 글과 경로는 새로 그려집니다(Reveal.kt).
                    Box(Modifier.fillMaxSize().padding(start = start, end = end)) {
                        when (screen) {
                            Screen.TRIP -> TripSummaryPane(state, onOpenMove, { onShow(Screen.GRAD) }, Modifier.fillMaxSize())
                            Screen.MOVES -> TripMovesPage(state, onSelect, Modifier.fillMaxSize())
                            Screen.GRAD -> Unit
                        }
                    }
                    // 비치는 동안 누르면 그 쪽으로 넘어갑니다(목록의 줄이 대신 눌리지 않게 덮어 둡니다).
                    if (screen == Screen.MOVES && pager.currentPage != page) {
                        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onShow(Screen.MOVES) })
                    }
                }
            }
            // 넘길 수 있다는 둥근 손잡이. 카드 사이에 비워 둔 손잡이 자리(HANDLE_LANE) 한가운데에 놓아 어떤 카드와도 겹치지 않습니다.
            // 요약에서는 오른쪽(동작 기록으로), 동작 기록에서는 왼쪽(요약으로 돌아가기)에 있고, 밀기 시작하면 옅어집니다.
            val moved = { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
            SwipeHint(
                pointsLeft = false,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = PEEK - HANDLE_LANE / 2 - HANDLE / 2)
                    .graphicsLayer { alpha = (1f - moved() * 4f).coerceIn(0f, 1f) }
                    .press(CircleShape, scale = 0.85f) { onShow(Screen.MOVES) },
            )
            SwipeHint(
                pointsLeft = true,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = HANDLE_LANE / 2 - HANDLE / 2)
                    .graphicsLayer { alpha = ((moved() - 0.75f) * 4f).coerceIn(0f, 1f) }
                    .press(CircleShape, scale = 0.85f) { onShow(Screen.TRIP) },
            )
            }
        }
        }
        }
    }
    }
    }
}

/**
 * 왼쪽에서 고른 주행의 동작들. 주행 하나를 처음부터 끝까지 되짚도록 일어난 순서대로 놓고,
 * 고른 동작의 폼 카드와 경로를 옆에 둡니다.
 */
@Composable
private fun TripMovesPage(state: UiState, onSelect: (Int) -> Unit, modifier: Modifier) {
    val trip = state.selectedTrip ?: return MovesPage(state, onSelect, modifier)
    val moves = trip.moves.sortedBy { it.id }
    val move = state.selected?.takeIf { m -> moves.any { it.id == m.id } } ?: moves.lastOrNull()
    if (move == null) {
        EmptyState(modifier)
        return
    }
    val ghost = state.ghostFor(move)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PANE_GAP)) {
        TripMoveList(moves, move.id, onSelect, Modifier.width(320.dp).fillMaxHeight().freshen(trip.trip.id))
        ScoreCard(move, ghost, state.visits(move), Modifier.width(420.dp).fillMaxHeight())
        PathCard(move, ghost, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TripMoveList(moves: List<MoveForm>, selectedId: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (moves.indexOfFirst { it.id == selectedId }).coerceAtLeast(0))
    // 고른 동작이 화면 밖이면 그리로 옮깁니다(머리글 한 줄이 앞에 있어 자리는 +1).
    LaunchedEffect(selectedId, moves.size) {
        val index = moves.indexOfFirst { it.id == selectedId } + 1
        val end = listState.layoutInfo.viewportEndOffset
        val shown = listState.layoutInfo.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= end }
        if (!shown) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    PlainCard(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 12.dp), state = listState) {
            item(key = "head") {
                Text(
                    "이번 운전의 장면 ${moves.size}개", color = TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(moves, key = { it.id }) { m -> TripMoveRow(m, m.id == selectedId) { onSelect(m.id) } }
        }
    }
}

/** 장면 목록 한 줄: 아이콘, 이름, 큰 점수(90점 아래는 주황). 시각·동네는 오른쪽 카드가 말합니다. */
@Composable
private fun TripMoveRow(m: MoveForm, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .press(RectangleShape, scale = 0.98f, onClick = onClick)
            .background(if (selected) Select else Color.Transparent)
            .padding(start = 14.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(40.dp).background(if (selected) TextPrimary else Color.Transparent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        MoveIcon(m, if (selected) TextPrimary else TextSecondary, Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(listTitle(m), color = TextPrimary, fontSize = 25.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SignText("${m.score}", 32.sp, if (m.score < 90) Bad else TextPrimary)
    }
}

@Composable
private fun MovesPage(state: UiState, onSelect: (Int) -> Unit, modifier: Modifier) {
    val move = state.selected
    if (move == null) {
        EmptyState(modifier)
        return
    }
    val ghost = state.ghostFor(move)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        HistoryList(state, move.id, onSelect, Modifier.width(380.dp).fillMaxHeight())
        ScoreCard(move, ghost, state.visits(move), Modifier.width(480.dp).fillMaxHeight())
        PathCard(move, ghost, Modifier.weight(1f).fillMaxHeight())
    }
}

private val PAGE_GUTTER = 48.dp
private val PANE_GAP = 24.dp
private val TRIP_LIST_WIDTH = 380.dp
/** 요약 오른쪽에 비워 두는 폭. 손잡이 자리(HANDLE_LANE)를 빼고 남는 64dp만큼 동작 기록 카드가 비칩니다. */
private val PEEK = 160.dp
private val TAB_WIDTH = 184.dp

/** 넘김 손잡이의 지름과, 카드 사이에 손잡이만을 위해 비워 두는 폭(양옆 16dp 여백). */
private val HANDLE = 64.dp
private val HANDLE_LANE = HANDLE + 32.dp

/**
 * 넘길 수 있다는 둥근 손잡이([pointsLeft]면 왼쪽을 가리킵니다). 카드 색 원에 그림자를 둬 뒤에 비치는 카드와 떨어져 보이게 하고,
 * 안의 두 겹 화살표는 두 번 밀고 잠깐 쉬기를 세 번만 하고 멈춥니다. 계속 움직이면 눈에 거슬리고,
 * 화면이 가만히 있어도 계속 다시 그려야 해서 전력을 씁니다.
 */
@Composable
private fun SwipeHint(pointsLeft: Boolean, modifier: Modifier) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pointsLeft) {
        repeat(3) {
            anim.snapTo(0f)
            anim.animateTo(1f, keyframes { durationMillis = 2600; 0f at 0; 1f at 700; 0f at 701; 1f at 1400; 1f at 2600 })
        }
        anim.snapTo(0f)
    }
    val phase = anim.value
    Box(
        modifier
            .size(HANDLE)
            .shadow(10.dp, CircleShape)
            .background(Surface, CircleShape)
            .border(1.dp, Line, CircleShape),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { if (pointsLeft) scaleX = -1f }) {
            val w = 9.dp.toPx()
            val h = 16.dp.toPx()
            val travel = 6.dp.toPx()
            val stroke = 3.5.dp.toPx()
            // 앞 화살표가 조금 먼저 나가고, 나갈수록 흐려집니다.
            listOf(0f, 0.25f).forEachIndexed { i, lag ->
                val p = ((phase - lag) / (1 - lag)).coerceIn(0f, 1f)
                val x = size.width / 2 - 8.dp.toPx() + i * 9.dp.toPx() + travel * p
                val alpha = (1f - p * 0.6f) * (if (i == 0) 0.45f else 1f)
                val path = Path().apply {
                    moveTo(x - w / 2, size.height / 2 - h / 2)
                    lineTo(x + w / 2, size.height / 2)
                    lineTo(x - w / 2, size.height / 2 + h / 2)
                }
                drawPath(path, TextPrimary.copy(alpha = alpha), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun Header(state: UiState, speedKmh: State<Int>, pages: List<Screen>, position: () -> Float, onShow: (Screen) -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 앱 표시: 노란 마름모 주의 표지 안의 학사모.
        Diamond(38.dp) { Icon(Icons.Rounded.School, null, tint = SignIcon, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        SignText("초보졸업", 44.sp)
        Spacer(Modifier.width(28.dp))
        if (state.parked) IconText(Icons.Rounded.LocalParking, "정차 중", TextSecondary, 24.sp, iconTint = TextSecondary, fontWeight = FontWeight.SemiBold)
        else IconText(Icons.Rounded.DirectionsCar, "주행 중 · ${speedKmh.value}km/h", TextSecondary, 24.sp, iconTint = SeriesNow, fontWeight = FontWeight.SemiBold)
        // 와이퍼나 안개등이 켜지면 '빗길 연수 중'을 노란 딱지로 붙입니다.
        weatherIcon(state.weather)?.let { icon ->
            Spacer(Modifier.width(16.dp))
            Box(Modifier.background(SignYellow, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                IconText(icon, if (state.parked) state.weather.label else "${state.weather.label} 연수 중", Color(0xFF111111), 22.sp, iconSize = 22.dp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.weight(1f))
        if (pages.size > 1) {
            // 선택 표시(낮엔 초록 판)는 넘기는 손가락을 따라 미끄러집니다.
            PlainCard {
            Box(Modifier.padding(6.dp)) {
                Box(
                    Modifier
                        .offset { IntOffset((position() * TAB_WIDTH.toPx()).roundToInt(), 0) }
                        .width(TAB_WIDTH).height(58.dp)
                        .background(if (IsNight) Select else GoodFill, RoundedCornerShape(14.dp)),
                )
                Row {
                    pages.forEachIndexed { i, screen ->
                        val on = state.screen == screen
                        Box(
                            Modifier.width(TAB_WIDTH).height(58.dp).press(RoundedCornerShape(14.dp), scale = 0.9f) { onShow(screen) },
                            contentAlignment = Alignment.Center,
                        ) {
                            IconText(
                                when (screen) { Screen.GRAD -> Icons.Rounded.School; Screen.TRIP -> Icons.Rounded.AutoStories; Screen.MOVES -> Icons.Rounded.Replay },
                                when (screen) { Screen.GRAD -> if (state.graduation.graduated) "심화 진도" else "졸업 진도"; Screen.TRIP -> "연수 일지"; Screen.MOVES -> "장면 복습" },
                                color = if (on) (if (IsNight) TextPrimary else Color.White) else TextSecondary, fontSize = 24.sp, iconSize = 26.dp,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                                iconTint = if (on) SignYellow else TextSecondary,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Surface, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.VideoLibrary, null, tint = TextMuted, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("회전이나 차선 변경을 하면 복습할 장면이 여기에 쌓여요", color = TextPrimary, fontSize = 34.sp)
            Spacer(Modifier.height(12.dp))
            Text("주행 중에는 화면 대신 소리와 조명으로만 알려드려요", color = TextSecondary, fontSize = 24.sp)
        }
    }
}

@Composable
private fun ScoreCard(move: MoveForm, ghost: Ghost?, visits: List<MoveForm>, modifier: Modifier) {
    PlainCard(modifier) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 어디서·언제 한 동작인지가 제목입니다.
        Row(Modifier.freshen(move.id), verticalAlignment = Alignment.CenterVertically) {
            MoveIcon(move, TextPrimary, Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(listOfNotNull(shortPlace(move.place), move.title).joinToString(" "), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                move.recordedAt.takeIf { it > 0 }?.let {
                    val wx = if (move.weather == Weather.CLEAR) "" else " · ${move.weather.label} 기준"
                    Text(whenText(it) + wx, color = TextSecondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // 점수보다 코치의 한마디가 먼저입니다.
        Text(keepWords(coachLine(move)), color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp, modifier = Modifier.freshen(move.id))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundSign(104.dp, ring = 10.dp) {
                val v by rolling(move.score.toFloat())
                SignText("${v.roundToInt()}", 48.sp, if (IsNight) TextPrimary else Color(0xFF111111))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.freshen(move.id)) {
                val prevPark = if (move.kind == MoveKind.PARK) visits.getOrNull(visits.size - 2) else null
                if (move.kind == MoveKind.SLIP) {
                    // 미끄러질 뻔한 순간은 다른 순간과 점수를 견주지 않습니다.
                    Text("놀라셨죠? 다치지 않고 잘 넘기셨어요.\n어떤 상황이었는지 같이 돌아봐요", color = TextSecondary, fontSize = 22.sp, lineHeight = 30.sp)
                } else if (prevPark != null) {
                    // 주차는 점수보다 '몇 번 만에'가 와닿습니다. 같은 자리의 지난번과 견줍니다.
                    val d = prevPark.tries - move.tries
                    SignText("${prevPark.tries}번 → ${move.tries}번", 38.sp, when { d > 0 -> Good; d < 0 -> Bad; else -> TextSecondary })
                    Text(
                        when {
                            d > 0 -> "이 자리, 지난번보다 ${d}번 덜 고쳐 넣었어요"
                            d < 0 -> "이 자리, 지난번엔 ${prevPark.tries}번 만에 넣으셨어요"
                            else -> "이 자리, 지난번이랑 같아요"
                        },
                        color = TextSecondary, fontSize = 22.sp,
                    )
                } else if (ghost == null) {
                    Text("첫 기록이에요.\n다음부터 이 기록과 비교해 드릴게요", color = TextSecondary, fontSize = 22.sp, lineHeight = 30.sp)
                } else {
                    val diff = move.score - ghost.move.score
                    SignText(when { diff > 0 -> "▲ ${diff}"; diff < 0 -> "▼ ${-diff}"; else -> "= 같아요" }, 38.sp, when { diff > 0 -> Good; diff < 0 -> Bad; else -> TextSecondary })
                    Text("${if (ghost.sameIntersection) "여기서" else "지난번에"} 제일 잘했을 땐 ${ghost.move.score}점", color = TextSecondary, fontSize = 22.sp)
                }
            }
        }
        // 아쉬운 것을 먼저, 잘한 것은 아래에. 상태는 색만이 아니라 모양(마름모 = 아쉬움, 체크 = 잘함)으로도 구분합니다.
        Column(Modifier.freshen(move.id), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            move.checks.sortedBy { it.ok }.forEach { check ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                        if (check.ok) Icon(Icons.Rounded.Check, null, tint = Good, modifier = Modifier.size(30.dp)) else Diamond(18.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(check.title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(118.dp), maxLines = 1)
                    Text(check.detail, color = TextSecondary, fontSize = 22.sp, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (visits.size >= 2) VisitHistory(move, visits)
        val perfect = move.checks.all { it.ok }
        val kind = if (perfect) SignKind.GUIDE else SignKind.CAUTION
        SignBox(kind, Modifier.fillMaxWidth().freshen(move.id)) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconText(
                    if (perfect) Icons.Rounded.ThumbUp else Icons.Rounded.TipsAndUpdates, if (perfect) "이 느낌 그대로" else "다음엔 이렇게 해 봐요",
                    signInk(kind), 22.sp, fontWeight = FontWeight.ExtraBold, iconSize = 24.dp,
                )
                Text(keepWords(FormScore.tip(move)), color = signInk(kind), fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            }
        }
    }
    }
}

/** 폼 카드 맨 위의 한 문장. 다 잘했으면 칭찬, 아니면 감점이 가장 큰 항목을 말하듯 풀어 씁니다. */
private fun coachLine(move: MoveForm): String {
    val worst = move.checks.filter { !it.ok }.maxByOrNull { it.penalty } ?: return "깔끔했어요. 이 ${listTitle(move)}, 그대로 하면 돼요."
    return Coaching.soft(move, worst.title) + "."
}

/**
 * 지난 최고 기록과의 차이. 숫자(+17점)가 주인공이고, 비교 대상이 언제의 나인지 옆에 적습니다.
 * 차이는 색만으로 구분하지 않고 ▲▼ 기호를 함께 씁니다.
 */
@Composable
private fun Compare(move: MoveForm, ghost: Ghost?) {
    Row(
        Modifier.fillMaxWidth().background(Line, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ghost == null) {
            Text("첫 기록이에요. 다음부터 이 기록과 비교해 드릴게요", color = TextSecondary, fontSize = 22.sp)
            return@Row
        }
        val diff = move.score - ghost.move.score
        val (text, color) = when {
            diff > 0 -> "▲ ${diff}점" to Good
            diff < 0 -> "▼ ${-diff}점" to Bad
            else -> "= 같음" to TextSecondary
        }
        Text(text, color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
        Column {
            Text(
                "${if (ghost.sameIntersection) "이 교차로 최고" else "지난 최고"} ${ghost.move.score}점 대비",
                color = TextPrimary, fontSize = 22.sp,
            )
            ghost.move.recordedAt.takeIf { it > 0 }?.let {
                Text("${whenText(it)} 기록", color = TextMuted, fontSize = 20.sp)
            }
        }
    }
}

/**
 * 날짜별로 묶은 기록 목록. 한 줄에 시각, 동작 모양, 장소, 점수를 보여 줍니다.
 * 언제·어디서 했는지로 기록을 찾게 하려는 목록이라 시각과 장소를 먼저 둡니다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(state: UiState, selectedId: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val groups = state.moves.asReversed().groupBy { if (it.recordedAt > 0) localDateTime(it.recordedAt).toLocalDate() else null }
    // 목록에서 고른 동작의 자리(날짜 머리글 포함). 날짜 머리글이 위에 붙어 있으므로 그 아래로 두 줄 앞부터 보여 줍니다.
    var index = 0
    var selectedIndex = 0
    var headerIndex = 0
    groups.forEach { (_, moves) ->
        val header = index++
        moves.forEach { if (it.id == selectedId) { selectedIndex = index; headerIndex = header }; index++ }
    }
    // 그날 앞쪽이면 그날 머리글부터 시작해 앞날의 줄이 머리글 밑에 반쯤 걸리지 않게 합니다.
    val target = if (selectedIndex - headerIndex <= 2) headerIndex else selectedIndex - 1
    // 요약 화면에서 '그 장면 다시 보기'로 오면 목록이 새로 만들어지므로, 처음부터 고른 동작 자리에서 시작합니다.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = target)
    // 고른 동작이 화면 밖이면 그리로 옮깁니다. 새 기록은 맨 위에 붙으며 골라지므로 맨 위로 올라갑니다.
    // 목록에서 직접 누른 줄은 이미 보이므로 움직이지 않습니다.
    LaunchedEffect(selectedId, state.moves.lastOrNull()?.id) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val shown = visible.any { it.index == selectedIndex && it.offset >= 0 && it.offset + it.size <= viewportEnd }
        if (!shown) listState.animateScrollToItem(target)
    }
    LazyColumn(modifier.background(Surface, RoundedCornerShape(36.dp)).padding(vertical = 20.dp), state = listState) {
        groups.forEach { (day, moves) ->
            // 날짜는 스크롤해도 위에 붙어 있어, 오래된 기록을 볼 때도 어느 날인지 보입니다.
            stickyHeader(key = "day-$day") {
                Text(
                    day?.let { dayHeader(it) } ?: "날짜 없음",
                    color = TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().background(Surface).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            items(moves, key = { it.id }) { m -> HistoryRow(m, m.id == selectedId) { onSelect(m.id) } }
        }
    }
}

@Composable
private fun HistoryRow(m: MoveForm, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .press(RectangleShape, scale = 0.98f, onClick = onClick)
            .background(if (selected) Line else Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(48.dp).background(if (selected) SeriesNow else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Text(
            if (m.recordedAt > 0) localDateTime(m.recordedAt).format(timeFormat) else "",
            color = TextSecondary, fontSize = 18.sp, maxLines = 1, modifier = Modifier.width(96.dp),
        )
        MoveIcon(m, if (selected) SeriesNow else TextSecondary, Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(listTitle(m), color = TextPrimary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            placeName(m)?.let { Text(it, color = TextMuted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "${m.score}", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * 동작 모양 아이콘. 회전은 위로 가다 꺾이는 화살표, 유턴은 U자, 차선 변경은 옆 차로로 옮겨 가는 S자,
 * 정지는 정지선에서 멈추는 화살표, 출발은 출발선에서 올라가는 화살표입니다.
 * 주행 경로 그림과 같은 모양이라 목록에서도 무슨 동작인지 바로 보입니다.
 */
@Composable
internal fun MoveIcon(m: MoveForm, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val dir = if (m.side == Side.LEFT) -1f else 1f
        val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()
        val tip: Offset
        val back: Offset
        if (m.kind == MoveKind.U_TURN) {
            val x0 = w / 2 + w * 0.2f
            val x1 = w / 2 - w * 0.2f
            path.moveTo(x0, h * 0.92f)
            path.lineTo(x0, h * 0.4f)
            path.cubicTo(x0, h * 0.02f, x1, h * 0.02f, x1, h * 0.4f)
            tip = Offset(x1, h * 0.75f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, -1f)
        } else if (m.kind == MoveKind.SPEED_BUMP) {
            // 길을 가로지르는 볼록한 턱 위로 지나가는 화살표
            drawPath(Path().apply {
                moveTo(w * 0.1f, h * 0.62f)
                quadraticBezierTo(w / 2, h * 0.3f, w * 0.9f, h * 0.62f)
            }, color, style = stroke)
            path.moveTo(w / 2, h * 0.95f)
            tip = Offset(w / 2, h * 0.08f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.CAMERA) {
            // 길가의 카메라(몸통과 렌즈) 옆을 지나가는 화살표
            val thin = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(color, Offset(w * 0.5f, h * 0.3f), Size(w * 0.42f, h * 0.3f), CornerRadius(w * 0.06f), style = thin)
            drawCircle(color, w * 0.07f, Offset(w * 0.71f, h * 0.45f), style = thin)
            path.moveTo(w * 0.24f, h * 0.92f)
            tip = Offset(w * 0.24f, h * 0.08f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.RAILROAD) {
            // 철길 건널목 표지(X) 아래로 지나가는 화살표
            drawLine(color, Offset(w * 0.2f, h * 0.12f), Offset(w * 0.8f, h * 0.48f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.8f, h * 0.12f), Offset(w * 0.2f, h * 0.48f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            path.moveTo(w / 2, h * 0.95f)
            tip = Offset(w / 2, h * 0.58f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.SCHOOL_ZONE) {
            // 주의 표지(세모) 안을 지나가는 화살표
            drawPath(Path().apply {
                moveTo(w / 2, h * 0.08f); lineTo(w * 0.92f, h * 0.82f); lineTo(w * 0.08f, h * 0.82f); close()
            }, color, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
            path.moveTo(w / 2, h * 0.98f)
            tip = Offset(w / 2, h * 0.36f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.SLIP) {
            // 미끄러지는 바퀴 자국(물결 두 줄) 사이로 지나가는 화살표
            val thin = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
            listOf(0.18f, 0.82f).forEach { x ->
                drawPath(Path().apply {
                    moveTo(w * x, h * 0.9f)
                    cubicTo(w * (x - 0.12f), h * 0.7f, w * (x + 0.12f), h * 0.5f, w * x, h * 0.3f)
                    cubicTo(w * (x - 0.12f), h * 0.2f, w * x, h * 0.12f, w * x, h * 0.08f)
                }, color, style = thin)
            }
            path.moveTo(w / 2, h * 0.95f)
            path.cubicTo(w * 0.36f, h * 0.7f, w * 0.64f, h * 0.45f, w / 2, h * 0.1f)
            tip = Offset(w / 2, h * 0.1f)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.PARK) {
            // 주차칸(ㄷ자) 안으로 뒤로 들어가는 화살표
            val thin = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(Path().apply {
                moveTo(w * 0.14f, h * 0.72f); lineTo(w * 0.14f, h * 0.08f); lineTo(w * 0.86f, h * 0.08f); lineTo(w * 0.86f, h * 0.72f)
            }, color, style = thin)
            path.moveTo(w / 2, h * 0.95f)
            tip = Offset(w / 2, h * 0.3f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind.isSpeedOnly) {
            // 정지는 위쪽 정지선 앞에서 멈추고, 출발은 아래쪽 출발선에서 올라갑니다.
            val lineY = if (m.kind == MoveKind.STOP) h * 0.1f else h * 0.92f
            drawLine(color, Offset(w * 0.18f, lineY), Offset(w * 0.82f, lineY), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
            val from = if (m.kind == MoveKind.STOP) h * 0.92f else h * 0.78f
            path.moveTo(w / 2, from)
            tip = Offset(w / 2, if (m.kind == MoveKind.STOP) h * 0.28f else h * 0.1f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        } else if (m.kind == MoveKind.TURN) {
            val x0 = w / 2 - dir * w * 0.15f
            path.moveTo(x0, h * 0.92f)
            path.lineTo(x0, h * 0.5f)
            path.quadraticBezierTo(x0, h * 0.25f, x0 + dir * w * 0.25f, h * 0.25f)
            tip = Offset(x0 + dir * w * 0.42f, h * 0.25f)
            path.lineTo(tip.x, tip.y)
            back = Offset(-dir, 0f)
        } else {
            // 가운데 차선(점선)을 넘어 옆 차로로 옮겨 가는 모양
            drawLine(
                color.copy(alpha = 0.45f), Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = w * 0.06f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(h * 0.14f, h * 0.1f)),
            )
            val x0 = w / 2 - dir * w * 0.27f
            val x1 = w / 2 + dir * w * 0.27f
            path.moveTo(x0, h * 0.92f)
            path.lineTo(x0, h * 0.65f)
            path.cubicTo(x0, h * 0.45f, x1, h * 0.5f, x1, h * 0.3f)
            tip = Offset(x1, h * 0.08f)
            path.lineTo(tip.x, tip.y)
            back = Offset(0f, 1f)
        }
        drawPath(path, color, style = stroke)
        val n = Offset(-back.y, back.x)
        val len = w * 0.22f
        drawPath(Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x + back.x * len + n.x * len * 0.7f, tip.y + back.y * len + n.y * len * 0.7f)
            moveTo(tip.x, tip.y)
            lineTo(tip.x + back.x * len - n.x * len * 0.7f, tip.y + back.y * len - n.y * len * 0.7f)
        }, color, style = stroke)
    }
}

/** 목록은 좁아서 짧게 씁니다. 차선 변경의 방향은 아이콘이 보여 줍니다. */
internal fun listTitle(m: MoveForm) = when (m.kind) {
    MoveKind.LANE_CHANGE -> "차선 변경"
    MoveKind.SCHOOL_ZONE -> "보호구역"
    else -> m.title
}

/** 목록의 날짜 묶음 제목: "오늘 · 11월 6일 (목)" */
internal fun dayHeader(day: LocalDate): String {
    val today = LocalDate.now()
    val date = day.format(dateFormat)
    return when (day) {
        today -> "오늘 · $date"
        today.minusDays(1) -> "어제 · $date"
        else -> date
    }
}

private fun ghostLegend(ghost: Ghost) = if (ghost.sameIntersection) "이 교차로 최고 기록" else "${ghost.move.title} 최고 기록"

/** 사용자가 알아볼 수 있는 장소: 내비가 알려 준 주소, 없으면 교차로 번호. */
internal fun placeName(m: MoveForm): String? = m.place ?: m.intersectionId?.let { if (m.kind == MoveKind.PARK) "주차 자리 $it" else "교차로 $it" }

/** 내비 주소의 마지막 낱말(동네). "수정구 시흥동" → "시흥동". */
internal fun shortPlace(place: String?): String? = place?.trim()?.split(' ')?.lastOrNull()?.takeIf { it.isNotEmpty() }

internal val timeFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
internal val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

internal fun localDateTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** "오늘 오전 8:12", "어제 오후 6:30", "11월 4일 (화) 오전 8:12" */
internal fun whenText(ms: Long): String {
    val t = localDateTime(ms)
    return "${dayText(ms, dateFormat)} ${t.format(timeFormat)}"
}

internal fun dayText(ms: Long, otherDays: DateTimeFormatter): String {
    val day = localDateTime(ms).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> day.format(otherDays)
    }
}

/**
 * 같은 교차로를 지날 때마다의 점수 막대. 막대 하나가 방문 한 번입니다.
 * 지금 보고 있는 방문만 계열 색으로 칠하고 값을 붙입니다(나머지는 회색, 값 생략).
 */
@Composable
private fun VisitHistory(move: MoveForm, visits: List<MoveForm>) {
    val shown = visits.takeLast(MAX_VISITS)
    val measurer = rememberTextMeasurer()
    Column {
        Text(if (move.kind == MoveKind.PARK) "이 자리 주차 ${visits.size}번째" else "이 교차로 ${move.title} ${visits.size}번째", color = TextSecondary, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val labelH = 28.dp.toPx()
            val baseline = size.height - 26.dp.toPx()
            val dayStyle = TextStyle(color = TextMuted, fontSize = 18.sp)
            // 막대 아래 날짜는 날이 바뀔 때만 적어 같은 글자가 반복되지 않게 합니다.
            // 매일 지나면 날짜가 막대마다 붙어 겹치므로, 최근(오른쪽)부터 놓고 앞 글자와 닿는 날짜는 건너뜁니다.
            val dayFormat = DateTimeFormatter.ofPattern("M/d", Locale.KOREAN)
            val gap = 10.dp.toPx()
            var leftEdge = Float.MAX_VALUE
            for (i in shown.indices.reversed()) {
                val v = shown[i]
                if (v.recordedAt <= 0) continue
                val day = dayText(v.recordedAt, dayFormat)
                val prev = shown.getOrNull(i - 1)?.takeIf { it.recordedAt > 0 }?.let { dayText(it.recordedAt, dayFormat) }
                if (day == prev) continue
                val cx = size.width / MAX_VISITS * (i + 0.5f)
                val layout = measurer.measure(day, dayStyle)
                val x = (cx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                if (x + layout.size.width + gap > leftEdge) continue
                drawText(layout, topLeft = Offset(x, baseline + 6.dp.toPx()))
                leftEdge = x
            }
            val slot = size.width / MAX_VISITS
            val barW = minOf(slot * 0.5f, 28.dp.toPx())
            shown.forEachIndexed { i, v ->
                val h = (baseline - labelH) * v.score / 100f
                val cx = slot * i + slot / 2
                val current = v.id == move.id
                drawRoundRect(
                    if (current) SeriesNow else SeriesGhost.copy(alpha = 0.6f),
                    Offset(cx - barW / 2, baseline - h), Size(barW, h.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                if (current) {
                    val text = "${v.score}"
                    val w = measurer.measure(text, TextStyle(fontSize = 20.sp)).size.width
                    label(measurer, text, Offset(cx - w / 2f, baseline - h - labelH), TextPrimary)
                }
            }
            drawLine(Line, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        }
    }
}

private const val MAX_VISITS = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PathCard(move: MoveForm, ghost: Ghost?, modifier: Modifier) {
    val canReplay = !move.kind.isSpeedOnly && move.speed.size >= 2
    Column(modifier.shadow(6.dp, RoundedCornerShape(22.dp)).background(Surface, RoundedCornerShape(22.dp)).padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconText(Icons.Rounded.Route, "주행 경로", TextPrimary, 30.sp, fontWeight = FontWeight.ExtraBold, iconSize = 32.dp, gap = 10.dp, iconTint = SeriesNow)
        }
        Spacer(Modifier.height(12.dp))
        // 기록을 가볍게 하려고 오래된 장면은 경로 곡선을 지웁니다(점수와 점검 결과는 그대로).
        if (move.speed.size < 2) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(keepWords("오래된 장면이라 경로 그림은 남겨 두지 않았어요. 점수와 점검 결과는 그대로예요."), color = TextSecondary, fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 32.sp)
            }
            return@Column
        }
        // 범례는 제목 아래에 두고, 카드가 좁으면 항목 단위로 다음 줄로 넘깁니다.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (move.kind == MoveKind.SCHOOL_ZONE) {
                // 보호구역은 제한속도를 넘은 구간을 칠합니다.
                LegendItem(SchoolZone, LegendStyle.BAND, "보호구역")
                LegendItem(SeriesNow, LegendStyle.LINE, "제한속도 이하")
                LegendItem(Bad, LegendStyle.LINE, "초과")
                return@FlowRow
            }
            if (move.kind == MoveKind.PARK) {
                LegendItem(Good, LegendStyle.LINE, "앞으로")
                LegendItem(SeriesNow, LegendStyle.LINE, "뒤로")
                LegendItem(Warn, LegendStyle.LINE, "방향 바꾼 곳")
                return@FlowRow
            }
            if (move.kind.isSpeedOnly) {
                // 속도만 보는 동작은 길을 세기별 색으로 칠하므로 범례도 세기로 보여 줍니다.
                LegendItem(SeriesNow, LegendStyle.LINE, "부드러움")
                LegendItem(Signal, LegendStyle.LINE, "조금 셈")
                LegendItem(Bad, LegendStyle.LINE, harshLabel(move.kind))
                return@FlowRow
            }
            LegendItem(SeriesNow, LegendStyle.LINE, "이번 주행")
            LegendItem(IdealBand, LegendStyle.BAND, "모범 경로")
            if (ghost != null) {
                LegendItem(SeriesGhost, LegendStyle.DASHED, ghostLegend(ghost))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PathChart(move, ghost?.move, Modifier.fillMaxSize())
            // 다시보기: 경로를 비스듬히 눕혀 차가 실제 시간대로 지나가게 합니다(Replay.kt). 좁은 카드라 그림 위 귀퉁이에 둡니다.
        }
        Spacer(Modifier.height(16.dp))
        // 카드가 좁아도 낱말 중간에서 줄이 바뀌지 않게 합니다.
        Text(
            keepWords(when (move.kind) {
                MoveKind.TURN, MoveKind.U_TURN ->
                    "진입 ${move.entryKmh}km/h → 가장 많이 꺾을 때 ${move.apexKmh}km/h → 탈출 ${move.exitKmh}km/h"
                MoveKind.LANE_CHANGE ->
                    "시작 ${move.entryKmh}km/h → 끝 ${move.exitKmh}km/h · ${"%.1f".format(move.durationS)}초 동안 옮김"
                MoveKind.STOP -> "${move.entryKmh}km/h → 정지 · ${"%.1f".format(move.durationS)}초 동안 감속"
                MoveKind.START -> "정지 → ${move.exitKmh}km/h · ${"%.1f".format(move.durationS)}초"
                MoveKind.SPEED_BUMP -> "접근 ${move.entryKmh}km/h → 넘을 때 ${move.apexKmh}km/h → 이후 ${move.exitKmh}km/h"
                MoveKind.CAMERA ->
                    "접근 ${move.entryKmh}km/h → 통과 ${move.apexKmh}km/h${move.limitKmh?.let { " (제한 $it)" } ?: ""} → 이후 ${move.exitKmh}km/h"
                MoveKind.RAILROAD ->
                    "접근 ${move.entryKmh}km/h → ${if (move.stoppedBefore) "멈춤 → " else ""}건널 때 ${move.apexKmh}km/h"
                MoveKind.SLIP -> "시속 ${move.apexKmh}km에서 차체 자세 제어(ESC)가 작동 · 앞뒤 ${move.entryKmh} → ${move.exitKmh}km/h"
                MoveKind.SCHOOL_ZONE -> "보호구역 안 최고 ${move.apexKmh}km/h (제한 ${move.limitKmh ?: FormScore.SCHOOL_DEFAULT_LIMIT_KMH}) · " +
                    "${"%.0f".format(move.durationS)}초 동안 지남"
                MoveKind.PARK -> "${if (move.tries == 1) "한 번에" else "${move.tries}번 만에"} 넣음 · 후진 최고 ${move.apexKmh}km/h · " +
                    "${"%.0f".format(move.durationS)}초 걸림"
            }),
            color = TextSecondary, fontSize = 20.sp,
        )
        Text(keepWords("경로는 속도와 핸들 각도로 계산한 추정이에요"), color = TextMuted, fontSize = 18.sp)
    }
}

@Composable
internal fun ChipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.background(Surface, RoundedCornerShape(50)).border(2.dp, color, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

private enum class LegendStyle { LINE, DASHED, BAND }

@Composable
private fun LegendItem(color: Color, style: LegendStyle, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 36.dp, height = 16.dp)) {
            val width = if (style == LegendStyle.BAND) size.height else 3.dp.toPx()
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = width,
                cap = StrokeCap.Round, pathEffect = if (style == LegendStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextSecondary, fontSize = 22.sp)
    }
}

/**
 * 위에서 내려다본 주행 경로. 모범 경로를 도로처럼 넓게 깔고, 그 위에 실제로 지나간 선을 그립니다.
 * 내비처럼 차는 아래에서 위로 진행합니다. 회전은 가로세로 축척이 같은 평면도이고,
 * 차선 변경은 길이에 비해 옆 이동(3.5m)이 너무 작아서 옆 방향만 늘리고 차선과 함께 그립니다.
 */
@Composable
internal fun PathChart(move: MoveForm, ghost: MoveForm?, modifier: Modifier) {
    // 바뀔 때 차가 지나가듯 다시 그리는 정도(그리기 단계에서만 읽습니다).
    val progress = drawProgress(move.id)
    val measurer = rememberTextMeasurer()
    val path = remember(move) { DrivePath.of(move) }
    val ghostPath = remember(ghost) { ghost?.let { DrivePath.of(it) } }
    val lane = move.kind == MoveKind.LANE_CHANGE

    Canvas(modifier) {
        // 주차는 좁은 카드에서 크게 보이도록 가장자리 여백을 줄입니다(이름표는 짧아서 들어갑니다).
        val pad = (if (move.kind == MoveKind.PARK) 14.dp else 40.dp).toPx()
        val area = Rect(pad, pad, size.width - pad, size.height - pad)
        val start = path.actual.lastOrNull { it.ms <= move.startMs } ?: path.actual.first()
        // 고스트는 시작점을 이번 주행의 시작점에 맞춰 옮깁니다.
        val ghostLine = ghostPath?.let { g ->
            val gs = g.actual.lastOrNull { it.ms <= ghost!!.startMs } ?: g.actual.first()
            g.actual.map { PathPoint(it.ms, it.x - gs.x + start.x, it.y - gs.y + start.y) }
        }
        val park = move.kind == MoveKind.PARK
        val parked = if (park) parkedOutline(path) else null
        // 주차는 방향이 정해진 길이 없으므로, 좁은 카드에 가장 크게 들어가는 쪽으로 그림을 돌리고 세운 차가 위를 보게 합니다.
        val rot: (PathPoint) -> PathPoint = if (parked == null) { p -> p } else bestTurn(path.actual + parked.car + parked.slot, parked, area.width, area.height)
        val all = (path.actual + path.ideal + listOfNotNull(path.signalTarget) + (ghostLine ?: emptyList()) +
            (parked?.let { it.car + it.slot } ?: emptyList())).map(rot)
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }

        val toScreen: (PathPoint) -> Offset = if (!lane) {
            // 주차는 몇 m 안에서 움직이므로 여백을 줄여 크게 봅니다.
            val margin = if (park) 1f else 8f
            val s = minOf(area.width / (maxX - minX + margin), area.height / (maxY - minY + margin))
            val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
            { p0 -> val p = rot(p0); Offset(area.center.x + (p.x - cx) * s, area.center.y - (p.y - cy) * s) }
        } else {
            val sy = area.height / (maxY - minY).coerceAtLeast(1f)
            // 두 차선(7m)이 폭의 60%를 차지하도록 옆 방향을 늘립니다.
            val sx = area.width * 0.6f / (2 * DrivePath.LANE_WIDTH_M)
            val mid = start.x + path.lateralM / 2
            { p -> Offset(area.center.x + (p.x - mid) * sx, area.bottom - (p.y - minY) * sy) }
        }

        if (lane) drawLanes(path, start, toScreen, measurer, area)

        // 모범 경로: 도로처럼 넓은 띠 + 가운데 점선
        val bandWidth = if (lane) 26.dp.toPx() else run {
            val a = toScreen(PathPoint(0, 0f, 0f)); val b = toScreen(PathPoint(0, DrivePath.LANE_WIDTH_M, 0f))
            (b.x - a.x).coerceIn(18.dp.toPx(), 80.dp.toPx())
        }
        drawPolyline(path.ideal.map(toScreen), IdealBand, bandWidth)
        drawPolyline(path.ideal.map(toScreen), IdealCenter, 2.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))

        if (!move.kind.isSpeedOnly && !park) {
            ghostLine?.let { drawPolyline(it.map(toScreen), SeriesGhost, 3.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(18f, 12f))) }
        }

        val drawn = progress()
        // 드러나는 중이면 차가 지나가듯 출발점부터 [drawn]만큼만 그리고, 표시 지점은 다 그린 뒤에 붙입니다.
        val actual = path.actual.map(toScreen)
        if (park) {
            drawPark(move, path, parked!!, actual, drawn, toScreen, measurer)
            return@Canvas
        }
        if (move.kind.isSpeedOnly) {
            // 속도만 보는 동작은 곧은 길을 아래에서 위로 달리므로 아래부터 드러냅니다.
            clipRect(top = size.height * (1 - drawn)) { drawSpeedMove(move, path, actual, toScreen, measurer) }
        } else {
            val shown = if (drawn >= 1f) actual else trimPath(actual, drawn)
            drawPolyline(shown, SeriesNow, 5.dp.toPx())
            drawArrowHead(shown, SeriesNow)
        }
        if (drawn < 1f) return@Canvas

        // 표시할 지점들은 선 위에 직접 이름을 붙입니다.
        path.brakeSegments.forEachIndexed { i, seg ->
            val pts = seg.map(toScreen)
            drawPolyline(pts, Bad, 9.dp.toPx())
            if (i == 0) callout(measurer, pts[pts.size / 2], "브레이크", Bad)
        }
        path.corrections.forEachIndexed { i, p ->
            val o = toScreen(p)
            drawCircle(Surface, 10.dp.toPx(), o)
            drawCircle(Warn, 7.dp.toPx(), o)
            if (i == 0) callout(measurer, o, "고쳐 꺾음 ${move.corrections}번", Warn)
        }
        path.signalTarget?.let { p ->
            val o = toScreen(p)
            drawCircle(TextPrimary, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
            callout(measurer, o, "여기서 켜기 (30m 전)", TextPrimary)
        }
        path.signalAt?.let { p ->
            val o = toScreen(p)
            drawCircle(Surface, 11.dp.toPx(), o)
            drawCircle(Signal, 8.dp.toPx(), o)
            callout(measurer, o, if (move.signalWrongSide) "반대쪽 깜빡이" else "깜빡이", Signal, opposite = path.signalTarget != null)
        }
    }
}

/**
 * 속도만 보는 동작: 곧은 길을 가속·감속 세기별 색으로 칠합니다(부드러움 / 조금 셈 / 급함).
 * 정지는 정지선과 '브레이크 시작', 늦게 밟았으면 '여기서부터 천천히'를 함께 표시합니다.
 * 과속방지턱은 턱 위치와 넘을 때 속도, 빠르게 넘었으면 '여기까지 시속 20km로'를 표시합니다.
 * 단속 카메라는 카메라 앞 감속과 지난 뒤 가속을 모두 칠하고, 철길 건널목은 멈춘 곳과 멈췄어야 할 곳,
 * 어린이 보호구역은 구간을 노랗게 깔고 제한속도를 넘은 곳을 빨갛게 칠합니다.
 */
private fun DrawScope.drawSpeedMove(
    move: MoveForm,
    path: DrivePath,
    actual: List<Offset>,
    toScreen: (PathPoint) -> Offset,
    measurer: TextMeasurer,
) {
    val school = move.kind == MoveKind.SCHOOL_ZONE
    val limit = move.limitKmh ?: FormScore.SCHOOL_DEFAULT_LIMIT_KMH
    // 출발은 가속, 단속 카메라는 감속·가속 모두, 나머지는 감속의 세기를 봅니다.
    fun strength(a: Float) = when (move.kind) {
        MoveKind.START -> a
        MoveKind.CAMERA, MoveKind.SLIP -> abs(a)
        else -> -a
    }
    val harsh = if (move.kind == MoveKind.START) FormScore.HARSH_START_MS2 else FormScore.HARSH_STOP_MS2
    fun colorAt(i: Int): Color = when {
        school && path.speedKmh[i] > limit -> Bad
        !school && strength(path.accel[i]) > harsh -> Bad
        // '조금 셈'은 레몬, '급함'은 주황입니다(이 그림에는 깜빡이 표시가 없어 레몬이 겹치지 않음).
        strength(path.accel[i]) > FormScore.SMOOTH_MS2 -> Signal
        else -> SeriesNow
    }

    if (school) {
        // 보호구역 구간을 길 밑에 노랗게 깝니다.
        val from = path.markAt?.let(toScreen)
        val to = path.zoneEnd?.let(toScreen)
        if (from != null && to != null) {
            drawLine(SchoolZone, from, to, strokeWidth = 60.dp.toPx())
            drawLine(Signal, Offset(from.x - 34.dp.toPx(), from.y), Offset(from.x + 34.dp.toPx(), from.y), strokeWidth = 3.dp.toPx())
            drawLine(Signal, Offset(to.x - 34.dp.toPx(), to.y), Offset(to.x + 34.dp.toPx(), to.y), strokeWidth = 3.dp.toPx())
            callout(measurer, from, "어린이 보호구역 · 제한 ${limit}km", TextPrimary, opposite = true)
            callout(measurer, to, "보호구역 끝", TextSecondary, opposite = true)
        }
    }

    val inMove = path.actual.map { it.ms in move.startMs..move.endMs }
    for (i in 1 until actual.size) {
        val c = if (inMove[i]) colorAt(i) else SeriesNow
        drawLine(c, actual[i - 1], actual[i], strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
    }
    drawArrowHead(actual, if (inMove.last()) colorAt(actual.lastIndex) else SeriesNow)

    if (move.kind == MoveKind.CAMERA) {
        path.markAt?.let { p ->
            val o = toScreen(p)
            drawCameraMark(o, TextPrimary)
            callout(
                measurer, o, "단속 카메라 · 통과 ${move.apexKmh}km" + (move.limitKmh?.let { " / 제한 $it" } ?: ""),
                if (move.limitKmh != null && move.apexKmh > move.limitKmh) Bad else TextPrimary, opposite = true,
            )
        }
        path.slowTarget?.let { p ->
            val o = toScreen(p)
            drawCircle(TextPrimary, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
            callout(measurer, o, "여기서부터 천천히 맞추기", TextPrimary)
        }
    } else if (move.kind == MoveKind.RAILROAD) {
        path.markAt?.let { p ->
            val o = toScreen(p)
            // 길을 가로지르는 두 줄 철로
            val half = 46.dp.toPx(); val gap = 7.dp.toPx()
            drawLine(TextPrimary, Offset(o.x - half, o.y - gap), Offset(o.x + half, o.y - gap), strokeWidth = 3.dp.toPx())
            drawLine(TextPrimary, Offset(o.x - half, o.y + gap), Offset(o.x + half, o.y + gap), strokeWidth = 3.dp.toPx())
            callout(measurer, o, "철길 건널목 · 시속 ${move.apexKmh}km", TextPrimary, opposite = true)
        }
        path.stopAt?.let { p ->
            val o = toScreen(p)
            drawCircle(Surface, 11.dp.toPx(), o)
            drawCircle(Good, 8.dp.toPx(), o)
            callout(measurer, o, "일시정지", Good)
        }
        path.slowTarget?.let { p ->
            val o = toScreen(p)
            drawCircle(TextPrimary, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
            callout(measurer, o, "여기서 멈추기", TextPrimary)
        }
    } else if (school) {
        // 구간 표시는 길을 그리기 전에 했습니다.
    } else if (move.kind == MoveKind.SPEED_BUMP) {
        path.markAt?.let { p ->
            val o = toScreen(p)
            // 길을 가로지르는 노란 턱
            drawRoundRect(
                Signal, Offset(o.x - 44.dp.toPx(), o.y - 9.dp.toPx()), Size(88.dp.toPx(), 18.dp.toPx()),
                cornerRadius = CornerRadius(9.dp.toPx()),
            )
            callout(measurer, o, "과속방지턱 · 시속 ${move.apexKmh}km", TextPrimary, opposite = true)
            if (move.brakeDuring) callout(measurer, Offset(o.x + 30.dp.toPx(), o.y), "턱 위에서 브레이크", Bad)
        }
        path.slowTarget?.let { p ->
            val o = toScreen(p)
            drawCircle(TextPrimary, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
            callout(measurer, o, "여기까지 시속 ${FormScore.BUMP_GOOD_KMH}km로", TextPrimary)
        }
    } else if (move.kind == MoveKind.STOP) {
        val end = actual.last()
        drawLine(TextPrimary, Offset(end.x - 40.dp.toPx(), end.y - 14.dp.toPx()), Offset(end.x + 40.dp.toPx(), end.y - 14.dp.toPx()), strokeWidth = 4.dp.toPx())
        callout(measurer, Offset(end.x, end.y - 14.dp.toPx()), "정지", TextPrimary, opposite = true)
        path.brakeFrom?.let { p ->
            val o = toScreen(p)
            drawCircle(Surface, 11.dp.toPx(), o)
            drawCircle(TextPrimary, 8.dp.toPx(), o)
            callout(measurer, o, "브레이크 시작", TextPrimary, opposite = true)
        }
        path.idealBrakeFrom?.let { p ->
            val o = toScreen(p)
            drawCircle(TextPrimary, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
            callout(measurer, o, "여기서부터 천천히", TextPrimary)
        }
    } else if (move.kind == MoveKind.SLIP) {
        // ESC가 작동한 곳에 점을 찍습니다.
        val at = move.markMs?.let { mark -> path.actual.lastOrNull { it.ms <= mark } } ?: path.actual.last()
        val o = toScreen(at)
        drawCircle(Surface, 13.dp.toPx(), o)
        drawCircle(Bad, 10.dp.toPx(), o)
        callout(measurer, o, "미끄러진 곳", TextPrimary, opposite = true)
    } else {
        val begin = toScreen(path.actual.lastOrNull { it.ms <= move.startMs } ?: path.actual.first())
        drawLine(TextPrimary, Offset(begin.x - 40.dp.toPx(), begin.y), Offset(begin.x + 40.dp.toPx(), begin.y), strokeWidth = 4.dp.toPx())
        callout(measurer, begin, "출발", TextPrimary, opposite = true)
    }

    // 가장 센(보호구역은 가장 빨랐던) 곳에 이름표 하나. 다른 표시 위에 오도록 마지막에 그립니다.
    val moving = actual.indices.filter { inMove[it] }
    if (school) {
        moving.maxByOrNull { path.speedKmh[it] }?.let { i ->
            if (path.speedKmh[i] > limit) callout(measurer, actual[i], "최고 시속 ${move.apexKmh}km", Bad)
        }
    } else {
        val worst = moving.maxByOrNull { strength(path.accel[it]) }
        if (worst != null && colorAt(worst) != SeriesNow) {
            val label = when {
                colorAt(worst) != Bad -> "조금 셈"
                move.kind == MoveKind.CAMERA && (move.markMs ?: 0) < path.actual[worst].ms -> "지나자마자 급가속"
                else -> harshLabel(move.kind)
            }
            // 길 오른쪽 옆에 붙이고, 정지는 정지선 아래로 내려 선과 겹치지 않게 합니다.
            val below = if (move.kind == MoveKind.STOP) actual.last().y - 14.dp.toPx() + 10.dp.toPx() else 0f
            callout(measurer, actual[worst], label, colorAt(worst), beside = true, minTop = below)
        }
    }
}

/** 점들을 원점 기준으로 [a]만큼 돌린 것. */
internal fun turned(p: PathPoint, a: Float): PathPoint {
    val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
    return PathPoint(p.ms, p.x * c - p.y * s, p.x * s + p.y * c)
}

/** 15°씩 돌려 보며 [w]×[h] 칸에 가장 크게 들어가는 각도를 고르고, 세운 차의 앞이 위를 보도록 필요하면 반 바퀴 더 돌립니다. */
internal fun bestTurn(pts: List<PathPoint>, parked: Parked, w: Float, h: Float): (PathPoint) -> PathPoint {
    fun fit(a: Float): Float {
        val r = pts.map { turned(it, a) }
        val bw = r.maxOf { it.x } - r.minOf { it.x } + 2f
        val bh = r.maxOf { it.y } - r.minOf { it.y } + 2f
        return minOf(w / bw, h / bh)
    }
    var a = (0 until 12).map { it * kotlin.math.PI.toFloat() / 12 }.maxBy(::fit)
    val front = turned(PathPoint(0, (parked.car[1].x + parked.car[2].x) / 2, (parked.car[1].y + parked.car[2].y) / 2), a)
    val rear = turned(PathPoint(0, (parked.car[0].x + parked.car[3].x) / 2, (parked.car[0].y + parked.car[3].y) / 2), a)
    if (front.y < rear.y) a += kotlin.math.PI.toFloat()
    return { p -> turned(p, a) }
}

/** 세운 차의 윤곽과 주차칸(ㄷ자, 앞이 열림). 위치(m)입니다. */
internal class Parked(val car: List<PathPoint>, val slot: List<PathPoint>)

/**
 * 마지막 위치를 뒷바퀴 축으로 보고, 차 앞이 향한 쪽으로 길이 4.6m·폭 1.9m 차를 놓습니다.
 * 주차칸은 차를 둘러싼 2.5m × 5.2m 칸입니다(주차장법의 일반형 칸 크기). 실제 칸 위치가 아니라 이해를 돕는 그림입니다.
 */
internal fun parkedOutline(path: DrivePath): Parked {
    val end = path.actual.last()
    val fx = -kotlin.math.sin(path.parkedHeading); val fy = kotlin.math.cos(path.parkedHeading)
    val rx = fy; val ry = -fx
    fun at(along: Float, side: Float) = PathPoint(0, end.x + fx * along + rx * side, end.y + fy * along + ry * side)
    val car = listOf(at(-1.0f, -0.95f), at(3.6f, -0.95f), at(3.6f, 0.95f), at(-1.0f, 0.95f), at(-1.0f, -0.95f))
    val slot = listOf(at(4.0f, -1.25f), at(-1.2f, -1.25f), at(-1.2f, 1.25f), at(4.0f, 1.25f))
    return Parked(car, slot)
}

/** 주차: 앞으로 간 길(초록)과 뒤로 간 길(파랑)을 나눠 그리고, 방향 바꾼 곳·멈춘 채 핸들 돌린 곳·세운 차를 표시합니다. */
private fun DrawScope.drawPark(
    move: MoveForm, path: DrivePath, parked: Parked, actual: List<Offset>, drawn: Float,
    toScreen: (PathPoint) -> Offset, measurer: TextMeasurer,
) {
    drawPolyline(parked.slot.map(toScreen), TextMuted, 3.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
    val shown = if (drawn >= 1f) actual else trimPath(actual, drawn)
    var i = 0
    while (i < shown.size - 1) {
        val back = path.reverse.getOrElse(i + 1) { false }
        var j = i + 1
        while (j < shown.size - 1 && path.reverse.getOrElse(j + 1) { false } == back) j++
        drawPolyline(shown.subList(i, j + 1), if (back) SeriesNow else Good, 5.dp.toPx())
        i = j
    }
    if (drawn < 1f) return
    val car = parked.car.map(toScreen)
    drawPath(Path().apply { moveTo(car[0].x, car[0].y); car.drop(1).forEach { lineTo(it.x, it.y) }; close() }, SeriesNow.copy(alpha = 0.16f))
    drawPolyline(car, SeriesNow, 3.dp.toPx())
    // 차 앞쪽 표시(앞 유리 자리).
    val front = (car[1] + car[2]) / 2f
    drawCircle(SeriesNow, 5.dp.toPx(), front)
    actual.firstOrNull()?.let { callout(measurer, it, "출발", TextSecondary) }
    path.switches.forEachIndexed { k, p ->
        val o = toScreen(p)
        drawCircle(Surface, 10.dp.toPx(), o)
        drawCircle(Warn, 7.dp.toPx(), o)
        if (k == 0) callout(measurer, o, "앞뒤로 ${path.switches.size}번 바꿈", Warn)
    }
    path.dryAt?.let { p ->
        val o = toScreen(p)
        drawCircle(Bad, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
        callout(measurer, o, "멈춘 채 핸들", Bad, opposite = true)
    }
}

private fun harshLabel(kind: MoveKind) = when (kind) {
    MoveKind.START -> "급출발"
    MoveKind.SPEED_BUMP, MoveKind.CAMERA -> "급하게 줄임"
    MoveKind.SLIP -> "급하게 바뀜"
    else -> "급제동"
}

/** 단속 카메라 표시: 몸통과 렌즈. */
private fun DrawScope.drawCameraMark(at: Offset, color: Color) {
    val w = 30.dp.toPx(); val h = 20.dp.toPx()
    val left = at.x + 14.dp.toPx()
    drawRoundRect(Surface, Offset(left, at.y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()))
    drawRoundRect(color, Offset(left, at.y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()), style = Stroke(2.5f.dp.toPx()))
    drawCircle(color, h * 0.28f, Offset(left + w / 2, at.y), style = Stroke(2.5f.dp.toPx()))
    drawLine(color, Offset(at.x - 6.dp.toPx(), at.y), Offset(left, at.y), strokeWidth = 2.dp.toPx())
}

private fun DrawScope.drawLanes(
    path: DrivePath,
    start: PathPoint,
    toScreen: (PathPoint) -> Offset,
    measurer: TextMeasurer,
    area: Rect,
) {
    val w = DrivePath.LANE_WIDTH_M
    val dir = if (path.lateralM < 0) -1f else 1f
    fun xAt(x: Float) = toScreen(PathPoint(0, x, 0f)).x
    val outerFrom = xAt(start.x - dir * w / 2)
    val middle = xAt(start.x + dir * w / 2)
    val outerTo = xAt(start.x + dir * w * 1.5f)
    drawLine(LaneLine, Offset(outerFrom, area.top), Offset(outerFrom, area.bottom), 2.dp.toPx())
    drawLine(LaneLine, Offset(outerTo, area.top), Offset(outerTo, area.bottom), 2.dp.toPx())
    drawLine(LaneLine, Offset(middle, area.top), Offset(middle, area.bottom), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 18f)))
    // 차선 이름은 경로와 겹치지 않게 그림 밖에 붙입니다: 출발한 차선은 아래, 옮겨 간 차선은 위.
    val style = TextStyle(color = TextMuted, fontSize = 20.sp)
    val from = measurer.measure("원래 차선", style)
    val to = measurer.measure("옮겨 간 차선", style)
    drawText(from, topLeft = Offset((outerFrom + middle) / 2 - from.size.width / 2f, area.bottom + 8.dp.toPx()))
    drawText(to, topLeft = Offset((middle + outerTo) / 2 - to.size.width / 2f, area.top - to.size.height - 8.dp.toPx()))
}

internal fun DrawScope.drawPolyline(pts: List<Offset>, color: Color, width: Float, effect: PathEffect? = null) {
    if (pts.size < 2) return
    val p = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

/** 선을 길이 기준으로 앞에서부터 [fraction]만큼만 남깁니다. */
private fun trimPath(pts: List<Offset>, fraction: Float): List<Offset> {
    if (pts.size < 2) return pts
    val lengths = pts.zipWithNext { a, b -> (b - a).getDistance() }
    var left = lengths.sum() * fraction.coerceIn(0f, 1f)
    val out = mutableListOf(pts[0])
    lengths.forEachIndexed { i, len ->
        if (left <= 0f) return out
        if (len <= left) { out += pts[i + 1]; left -= len }
        else { out += pts[i] + (pts[i + 1] - pts[i]) * (left / len); left = 0f }
    }
    return out
}

/** 진행 방향을 알 수 있게 끝에 화살촉을 그립니다. */
private fun DrawScope.drawArrowHead(pts: List<Offset>, color: Color) {
    if (pts.size < 2) return
    val tip = pts.last()
    val from = pts.lastOrNull { (it - tip).getDistance() > 12f } ?: return
    val d = (tip - from) / (tip - from).getDistance()
    val n = Offset(-d.y, d.x)
    val len = 22.dp.toPx(); val half = 11.dp.toPx()
    val head = Path().apply {
        moveTo(tip.x + d.x * 6f, tip.y + d.y * 6f)
        lineTo(tip.x - d.x * len + n.x * half, tip.y - d.y * len + n.y * half)
        lineTo(tip.x - d.x * len - n.x * half, tip.y - d.y * len - n.y * half)
        close()
    }
    drawPath(head, color)
}

/**
 * 지점 옆에 이름표. 기본은 오른쪽 위, [opposite]면 왼쪽 아래에 붙여 가까운 두 지점의 이름표가 겹치지 않게 합니다.
 * 글자는 배경색 바탕 위에 써서 선과 겹쳐도 읽히게 합니다.
 */
internal fun DrawScope.callout(
    measurer: TextMeasurer, at: Offset, text: String, color: Color,
    opposite: Boolean = false,
    /** 지점 바로 오른쪽, 같은 높이에 붙입니다. 굵은 길 선 위로 올라가지 않습니다. */
    beside: Boolean = false,
    /** 이름표 위쪽이 이보다 위로 올라가지 않게 합니다(정지선 같은 가로선 피하기). */
    minTop: Float = 0f,
) {
    val style = TextStyle(color = color, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    val layout = measurer.measure(text, style)
    val x = when {
        opposite -> (at.x - layout.size.width - 16.dp.toPx()).coerceAtLeast(4f)
        beside -> (at.x + 22.dp.toPx()).coerceAtMost(size.width - layout.size.width - 4f)
        else -> (at.x + 16.dp.toPx()).coerceAtMost(size.width - layout.size.width - 4f)
    }
    val y = when {
        opposite -> at.y + 10.dp.toPx()
        beside -> at.y - layout.size.height / 2f
        else -> at.y - layout.size.height - 10.dp.toPx()
    }.coerceAtLeast(minTop)
    drawRoundRect(Surface.copy(alpha = 0.85f), Offset(x - 6f, y - 2f), Size(layout.size.width + 12f, layout.size.height + 4f), CornerRadius(6f))
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, color: Color) {
    drawText(measurer, text, at, TextStyle(color = color, fontSize = 20.sp))
}
