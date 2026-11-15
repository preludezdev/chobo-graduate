package dev.driveform.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.form.DrivePath
import dev.driveform.form.MapPlacement
import dev.driveform.form.MoveForm
import dev.driveform.form.FormScore
import dev.driveform.form.MoveKind
import dev.driveform.form.PathPoint
import dev.driveform.form.Side
import dev.driveform.form.V2
import dev.driveform.vehicle.CarPaint
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 다시보기 시계: 장면의 실제 시간을 [anim](0~1)으로 재생합니다. 한 번 재생하고 멈춥니다. */
internal class ReplayClock(val t0: Long, val dur: Long, val anim: Animatable<Float, AnimationVector1D>) {
    val now: Long get() = t0 + (dur * anim.value).toLong()
    fun fraction(ms: Long) = ((ms - t0).toFloat() / dur).coerceIn(0f, 1f)
}

@Composable
internal fun rememberReplayClock(move: MoveForm, playKey: Int): ReplayClock {
    val path = remember(move) { DrivePath.of(move) }
    val t0 = path.actual.firstOrNull()?.ms ?: move.startMs
    val dur = ((path.actual.lastOrNull()?.ms ?: move.endMs) - t0).coerceAtLeast(1L)
    val anim = remember(move.id) { Animatable(0f) }
    LaunchedEffect(move.id, playKey) {
        anim.snapTo(0f)
        // 실제 걸린 시간대로, 짧으면 3초·길면 12초(주차는 앞뒤로 오가는 핸들을 보도록 20초) 안으로 재생합니다.
        anim.animateTo(1f, tween(dur.coerceIn(3_000L, if (move.kind == MoveKind.PARK) 20_000L else 12_000L).toInt(), easing = LinearEasing))
    }
    return remember(move.id, anim) { ReplayClock(t0, dur, anim) }
}

/**
 * 지도 다시보기. 위에서 50° 내려다보는 카메라로 실제 교차로(도로·건물)를 깔고, 렌더링해 둔 내 차가 실제 시간대로 지나갑니다.
 * 반투명한 모범 차가 같은 진도로 함께 가고, 깜빡이·브레이크는 차 이미지 위에 불빛 층을 켜서 보여 줍니다.
 * 지도가 없는 장면(주차, 차선 변경, 정지·출발 등)은 격자 바닥에 그립니다.
 */
@Composable
internal fun MapReplay(move: MoveForm, clock: ReplayClock, paint: CarPaint, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sprites = remember { CarSprites(context.applicationContext) }
    val measurer = rememberTextMeasurer()
    val scene = remember(move) { ReplayScene(move, DemoMap.junctions(context.applicationContext)) }
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().drawWithCache {
                val cam = scene.camera(Rect(0f, 0f, size.width, size.height).deflate(28.dp.toPx()))
                val ground = scene.ground(cam, this)
                onDrawBehind {
                    drawRect(Coach.Stage)
                    val now = clock.now
                    // 회전 구간·차선 변경은 차를 따라 확대합니다. 확대할수록 차를 화면 가운데 쪽으로 당깁니다.
                    val z = scene.zoomAt(now)
                    val car = scene.carScreen(cam, now)
                    val a = if (z <= 1f) 0f else if (move.kind.isTurn) (z - 1f) / z else 1f // 회전은 반쯤, 긴 장면은 차를 가운데에 두고 따라갑니다
                    val dx = (center.x - car.x) * a; val dy = (center.y - car.y) * a
                    withTransform({ translate(dx, dy); scale(z, z, car) }) {
                        ground.forEach { (path, color, style) -> if (style == null) drawPath(path, color) else drawPath(path, color, style = style) }
                        scene.drawDynamic(this, cam, now, sprites, paint, measurer)
                    }
                    scene.drawHud(this, now, measurer)
                }
            },
        ) {}
    }
}

/** 투영: 경로 좌표(m, +y가 출발 방향)를 화면으로. 먼저 원근으로 투영한 뒤 칸에 꽉 차게 키웁니다. */
private class Cam(cx: Float, cy: Float, extent: Float, keys: List<V2>, area: Rect, val rot: Float = 0f) {
    /** 경로 좌표를 [rot]만큼 거꾸로 돌려 화면 기준(view)으로. 주차는 세운 차가 위를 보게 돌립니다. */
    private val px = cx; private val py = cy
    private val cr = cos(rot); private val sr = sin(rot)
    private fun view(x: Float, y: Float): V2 { val dx = x - px; val dy = y - py; return V2(px + dx * cr + dy * sr, py - dx * sr + dy * cr) }
    private val th = Math.toRadians(50.0)
    private val ct = cos(th).toFloat(); private val st = sin(th).toFloat()
    private val h = 1.25f * extent
    private val yc = cy - 1.35f * extent
    private val x0 = cx
    private var s = 1f
    private var tx = 0f
    private var ty = 0f

    init {
        val raw = keys.mapNotNull { k -> view(k.x, k.y).let { raw(it.x, it.y, 0f) } }
        if (raw.isNotEmpty()) {
            val minX = raw.minOf { it.x }; val maxX = raw.maxOf { it.x }
            val minY = raw.minOf { it.y }; val maxY = raw.maxOf { it.y }
            s = minOf(area.width / (maxX - minX).coerceAtLeast(1e-4f), area.height / (maxY - minY).coerceAtLeast(1e-4f)) * 0.86f
            tx = area.center.x - (minX + maxX) / 2 * s
            ty = area.center.y - (minY + maxY) / 2 * s
        }
    }

    private fun zc(y: Float, z: Float) = (y - yc) * ct - (z - h) * st

    fun raw(x: Float, y: Float, z: Float): Offset? {
        val zc = zc(y, z)
        if (zc < 1f) return null
        val yy = (y - yc) * st + (z - h) * ct
        return Offset((x - x0) / zc, -yy / zc)
    }

    /** 화면 기준 좌표를 투영(격자처럼 화면에 맞춰 까는 것). */
    fun pv(x: Float, y: Float, z: Float = 0f): Offset? = raw(x, y, z)?.let { Offset(it.x * s + tx, it.y * s + ty) }
    fun p(x: Float, y: Float, z: Float = 0f): Offset? = view(x, y).let { pv(it.x, it.y, z) }
    fun p(v: V2, z: Float = 0f) = p(v.x, v.y, z)
    fun p(v: PathPoint) = p(v.x, v.y, 0f)

    /** 땅 위 [v](경로 좌표)에서 1m가 화면에서 몇 px인지. */
    fun ppm(v: V2): Float = s / zc(view(v.x, v.y).y, 0f).coerceAtLeast(1f)
}

private data class Layer(val path: Path, val color: Color, val style: Stroke?)

/** 한 장면의 정적인 재료: 경로, 모범 경로, 지도(경로 좌표로 옮긴 것). */
private class ReplayScene(val move: MoveForm, junctions: List<dev.driveform.form.MapJunction>) {
    val path: DrivePath = DrivePath.of(move)
    val park = move.kind == MoveKind.PARK
    private val placement = MapPlacement.place(move, path.actual, junctions)
    val onMap get() = placement != null
    private val parked = if (park && path.actual.size >= 2) parkedOutline(path) else null

    private val keys: List<V2> = buildList {
        if (park) {
            // 주차는 칸 근처에서 앞뒤로 오간 곳만 화면에 담습니다(통로를 길게 온 길은 화면 밖으로).
            val end = path.actual.last()
            path.actual.filter { hypot(it.x - end.x, it.y - end.y) < 11f }.forEach { listOf(-2.4f, 2.4f).forEach { d -> add(V2(it.x + d, it.y)); add(V2(it.x, it.y + d)) } } // 차 몸통만큼 여유
            parked?.let { (it.car + it.slot).forEach { p -> add(V2(p.x, p.y)) } }
        } else {
            path.actual.forEach { add(V2(it.x, it.y)) }
            path.ideal.forEach { add(V2(it.x, it.y)) }
            path.signalTarget?.let { add(V2(it.x, it.y)) }
            // 끝에서 멈춘 차 몸통과 그 앞(정지선·횡단보도)까지 화면에 담습니다.
            path.actual.lastOrNull()?.let { e ->
                val ahead = if (move.kind == MoveKind.STOP) 10f else 5f
                val h = headingNear(path.actual, path.actual.lastIndex)
                add(V2(e.x - sin(h).toFloat() * ahead, e.y + cos(h).toFloat() * ahead))
            }
        }
    }
    private val minX = keys.minOfOrNull { it.x } ?: 0f
    private val maxX = keys.maxOfOrNull { it.x } ?: 1f
    private val minY = keys.minOfOrNull { it.y } ?: 0f
    private val maxY = keys.maxOfOrNull { it.y } ?: 1f
    private val cx = (minX + maxX) / 2
    private val cy = (minY + maxY) / 2
    private val extent = maxOf(maxX - minX, maxY - minY, if (park) 12f else 22f)

    /** 주차는 세운 차의 앞이 화면 위를 보도록 장면을 돌립니다. */
    fun camera(area: Rect) = Cam(cx, cy, extent, if (park) keys else keys + listOf(V2(cx - extent * 0.35f, cy), V2(cx + extent * 0.35f, cy)), area, rot = if (park) path.parkedHeading else 0f)

    /** 바닥: 지도(도로·건물) 또는 격자와 모범 경로 띠. 화면 크기가 바뀔 때만 다시 만듭니다. */
    fun ground(cam: Cam, d: androidx.compose.ui.unit.Density): List<Layer> = with(d) {
        val out = mutableListOf<Layer>()
        val radius = extent * 2.4f
        fun near(v: V2) = hypot(v.x - cx, v.y - cy) < radius
        val pl = placement
        if (pl == null) {
            // 격자(5m, 주차는 2m)
            val step = if (park) 2f else 5f
            val g = Path()
            var x = ((cx - radius) / step).toInt() * step
            while (x <= cx + radius) { line(g, cam, V2(x, cy - radius * 0.6f), V2(x, cy + radius)); x += step }
            var y = ((cy - radius * 0.6f) / step).toInt() * step
            while (y <= cy + radius) { line(g, cam, V2(cx - radius, y), V2(cx + radius, y)); y += step }
            out += Layer(g, Coach.Grid, Stroke((1.dp.toPx() / baseZoom)))
            when {
                park -> parkingLot(cam, out, this)
                move.kind == MoveKind.LANE_CHANGE -> street(cam, out, this, lanesFor())
                else -> street(cam, out, this, listOf(path.actual.first().x - 3.5f, path.actual.first().x), stop = move.kind == MoveKind.STOP, start = move.kind == MoveKind.START)
            }
        } else {
            val j = pl.junction
            for (r in j.roads) {
                val pts = densify(r.points.map(pl::toPath), 3f)
                for (run in runs(pts) { near(it) && cam.p(it) != null }) band(cam, run, r.halfWidth)?.let { out += Layer(it, Coach.Road, null) }
            }
            for (r in j.roads) if (r.halfWidth >= 3.5f) {
                val pts = densify(r.points.map(pl::toPath), 3f)
                for (run in runs(pts) { near(it) && cam.p(it) != null }) {
                    val p = Path(); var first = true
                    run.forEach { v -> cam.p(v)?.let { o -> if (first) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y); first = false } }
                    out += Layer(p, Coach.RoadLine, Stroke((1.6.dp.toPx() / baseZoom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 16f))))
                }
            }
            // 건물: 먼 것부터 벽, 지붕
            val blds = j.buildings.map { b -> b.height to b.points.map(pl::toPath) }
                .filter { (_, pts) -> pts.all { near(it) } && pts.all { cam.p(it, 0f) != null } }
                .sortedByDescending { (_, pts) -> pts.sumOf { it.y.toDouble() } / pts.size }
            for ((h, pts) in blds) {
                val ring = if (pts.first() == pts.last()) pts.dropLast(1) else pts
                for (i in ring.indices) {
                    val a = ring[i]; val b = ring[(i + 1) % ring.size]
                    val q = listOf(cam.p(a, 0f), cam.p(b, 0f), cam.p(b, h), cam.p(a, h))
                    if (q.any { it == null }) continue
                    val w = Path().apply { moveTo(q[0]!!.x, q[0]!!.y); q.drop(1).forEach { lineTo(it!!.x, it.y) }; close() }
                    out += Layer(w, Coach.Wall, null)
                }
                val roof = Path(); var ok = true
                ring.forEachIndexed { i, v -> val o = cam.p(v, h); if (o == null) ok = false else if (i == 0) roof.moveTo(o.x, o.y) else roof.lineTo(o.x, o.y) }
                if (ok) { roof.close(); out += Layer(roof, Coach.Roof, null); out += Layer(roof, Coach.RoofEdge, Stroke((1.dp.toPx() / baseZoom))) }
            }
        }
        // 모범 경로 가운데 점선
        if (!park && path.ideal.size >= 2) {
            val p = Path(); var first = true
            path.ideal.forEach { v -> cam.p(v)?.let { o -> if (first) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y); first = false } }
            out += Layer(p, Color(0xFF56677B), Stroke((2.dp.toPx() / baseZoom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))))
        }
        // 내가 넣을 칸(점선)
        parked?.let { pk ->
            val p = Path(); var first = true
            pk.slot.forEach { v -> cam.p(v)?.let { o -> if (first) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y); first = false } }
            out += Layer(p, Coach.Muted, Stroke((3.dp.toPx() / baseZoom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
        }
        out
    }

    /** 차선 변경 도로의 차로 가운데들: 출발 차로, 옮겨 간 차로, 그 반대쪽 한 차로. */
    private fun lanesFor(): List<Float> {
        val x0 = path.actual.first().x
        val moved = path.actual.last().x - x0
        val dir = if (moved < 0f) -1f else 1f
        return listOf(x0 - dir * 3.5f, x0, x0 + dir * 3.5f).sorted()
    }

    /** 곧은 길: 차로들, 가장자리 실선·차로 점선, 인도, 길가 건물. 정지는 정지선·횡단보도·신호등, 출발은 뒤쪽 정지선. */
    private fun street(cam: Cam, out: MutableList<Layer>, d: androidx.compose.ui.unit.Density, lanes: List<Float>, stop: Boolean = false, start: Boolean = false) = with(d) {
        val y0 = minY - 30f; val y1 = maxY + 40f
        val left = lanes.first() - 1.75f; val right = lanes.last() + 1.75f
        fun rect(x0: Float, x1: Float, ya: Float, yb: Float, color: Color) {
            val q = listOf(cam.p(x0, ya), cam.p(x1, ya), cam.p(x1, yb), cam.p(x0, yb))
            if (q.any { it == null }) return
            out += Layer(Path().apply { moveTo(q[0]!!.x, q[0]!!.y); q.drop(1).forEach { lineTo(it!!.x, it.y) }; close() }, color, null)
        }
        rect(left - 3.5f, left, y0, y1, Coach.Panel2) // 인도
        rect(right, right + 3.5f, y0, y1, Coach.Panel2)
        rect(left, right, y0, y1, Coach.Road)
        fun ln(x: Float, color: Color, w: Float, dash: Boolean) {
            val a = cam.p(x, y0); val b = cam.p(x, y1)
            if (a != null && b != null) out += Layer(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }, color, Stroke(w, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(26f, 22f)) else null))
        }
        ln(left, Color(0xFF8A97A6), (2.5.dp.toPx() / baseZoom), false); ln(right, Color(0xFF8A97A6), (2.5.dp.toPx() / baseZoom), false)
        for (i in 0 until lanes.size - 1) ln((lanes[i] + lanes[i + 1]) / 2, Color(0xFFB8C3CF), (2.dp.toPx() / baseZoom), true)
        // 길가 건물(일정한 간격, 높이는 번갈아)
        var y = y0; var k = 0
        while (y < y1) {
            val h = 6f + (k * 37 % 5) * 2f
            extrude(cam, listOf(V2(left - 13f, y), V2(left - 5f, y), V2(left - 5f, y + 11f), V2(left - 13f, y + 11f)), h, out, d, baseZoom)
            extrude(cam, listOf(V2(right + 5f, y + 4f), V2(right + 13f, y + 4f), V2(right + 13f, y + 14f), V2(right + 5f, y + 14f)), h + 2f, out, d, baseZoom)
            y += 16f; k++
        }
        val end = path.actual.last()
        if (stop || start) {
            val ys = if (stop) end.y + 4.4f else path.actual.first().y - 0.5f
            rect(left, right, ys, ys + 0.5f, Color(0xFFE8EEF5)) // 정지선
            if (stop) {
                var x = left + 0.3f
                while (x < right - 0.3f) { rect(x, x + 0.5f, ys + 2f, ys + 6f, Color(0xFFC8D0DA)); x += 1.0f } // 횡단보도
                extrude(cam, listOf(V2(right + 0.8f, ys + 1.0f), V2(right + 1.2f, ys + 1.0f), V2(right + 1.2f, ys + 1.4f), V2(right + 0.8f, ys + 1.4f)), 4.5f, out, d, baseZoom) // 신호등 기둥
            }
        }
    }

    /** 지하 주차장: 통로, 한 줄로 늘어선 칸(실선), 옆 칸에 서 있는 차, 뒤쪽 벽과 기둥. */
    private fun parkingLot(cam: Cam, out: MutableList<Layer>, d: androidx.compose.ui.unit.Density) = with(d) {
        val end = path.actual.last(); val h = path.parkedHeading
        val fx = -sin(h); val fy = cos(h); val rx = fy; val ry = -fx
        fun at(along: Float, side: Float) = V2(end.x + fx * along + rx * side, end.y + fy * along + ry * side)
        fun quad(a: V2, b: V2, c: V2, e: V2, color: Color, style: Stroke? = null) {
            val q = listOf(cam.p(a), cam.p(b), cam.p(c), cam.p(e))
            if (q.any { it == null }) return
            out += Layer(Path().apply { moveTo(q[0]!!.x, q[0]!!.y); q.drop(1).forEach { lineTo(it!!.x, it.y) }; if (style == null) close() }, color, style)
        }
        quad(at(4.0f, -16f), at(4.0f, 16f), at(11f, 16f), at(11f, -16f), Coach.Road) // 통로
        quad(at(-1.2f, -16f), at(-1.2f, 16f), at(4.0f, 16f), at(4.0f, -16f), Color(0xFF151D27)) // 칸 줄 바닥
        val line = Stroke((2.5.dp.toPx() / baseZoom))
        for (k in -6..6) {
            val sd = -1.25f + k * 2.5f
            val a = cam.p(at(-1.2f, sd)); val b = cam.p(at(4.0f, sd))
            if (a != null && b != null) out += Layer(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }, Color(0xFFC8D0DA), line)
        }
        val ba = cam.p(at(-1.2f, -16f)); val bb = cam.p(at(-1.2f, 16f))
        if (ba != null && bb != null) out += Layer(Path().apply { moveTo(ba.x, ba.y); lineTo(bb.x, bb.y) }, Color(0xFFC8D0DA), line)
        // 칸 뒤 연석과 칸마다 바퀴 멈춤턱(바닥에 납작하게: 카메라 쪽이라 세우면 칸을 가립니다)
        quad(at(-1.2f, -16f), at(-1.2f, 16f), at(-1.7f, 16f), at(-1.7f, -16f), Coach.Panel2)
        for (k in -6..6) { val sd = k * 2.5f; listOf(-0.75f, 0.75f).forEach { o -> quad(at(-0.55f, sd + o - 0.32f), at(-0.55f, sd + o + 0.32f), at(-0.8f, sd + o + 0.32f), at(-0.8f, sd + o - 0.32f), Color(0xFF6B5428)) } }
        // 통로 건너편: 벽과 기둥(멀리 있어 칸을 가리지 않습니다)
        quad(at(11f, -16f), at(11f, 16f), at(14f, 16f), at(14f, -16f), Color(0xFF151D27))
        extrude(cam, listOf(at(14f, -16f), at(14f, 16f), at(14.5f, 16f), at(14.5f, -16f)), 2.6f, out, d, baseZoom)
        for (k in listOf(-5, -2, 1, 4)) { val sd = k * 2.5f; extrude(cam, listOf(at(11.4f, sd - 0.4f), at(11.4f, sd + 0.4f), at(12.2f, sd + 0.4f), at(12.2f, sd - 0.4f)), 2.6f, out, d, baseZoom) }
    }

    /** 옆 칸에 서 있는 차들(칸 번호, 색, 앞뒤). 바로 옆 칸은 비워 둡니다. */
    private val neighbours = listOf(Triple(-3, CarPaint.WHITE, true), Triple(-2, CarPaint.NAVY, false), Triple(2, CarPaint.GRAY, true), Triple(3, CarPaint.BLACK, true), Triple(4, CarPaint.WHITE, false))

    /** 차선 변경 장면에서 옆 차로를 달리는 차들(차로, 앞뒤 간격 m, 내 차보다 빠른 m/s, 색). */
    private val traffic = listOf(Triple(0, 22f to 0.6f, CarPaint.WHITE), Triple(2, -14f to 1.2f, CarPaint.GRAY), Triple(2, 30f to 0.4f, CarPaint.NAVY))

    /** 장면 내내 쓰는 확대 배율. 길게 달린 장면(정지·차선 변경·속도)은 약 38m가 화면에 들어오게 차를 따라갑니다. */
    val baseZoom: Float = when {
        move.kind.isTurn || park -> 1f
        move.kind == MoveKind.LANE_CHANGE -> maxOf(1.9f, extent / 38f).coerceAtMost(4f)
        else -> (extent / 38f).coerceIn(1f, 5f)
    }

    /** 지금 확대 배율. 이름표는 이만큼 줄여 그려서 확대해도 글씨 크기가 그대로입니다. */
    private var zoomNow = baseZoom

    private fun DrawScope.tag(measurer: TextMeasurer, at: Offset, text: String, color: Color, opposite: Boolean = false, beside: Boolean = false) {
        val z = zoomNow
        if (z == 1f) callout(measurer, at, text, color, opposite = opposite, beside = beside)
        else withTransform({ scale(1f / z, 1f / z, at) }) { callout(measurer, at, text, color, opposite = opposite, beside = beside) }
    }

    /** 따라가기 확대 배율. 회전은 꺾기 전후로 확대했다가 돌아오고, 차선 변경은 처음부터 확대해 따라갑니다. */
    fun zoomAt(now: Long): Float = zoomRaw(now).also { zoomNow = it }

    private fun zoomRaw(now: Long): Float {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        return when {
            move.kind.isTurn -> 1f + 0.7f * minOf(smooth((now - (move.startMs - 1_600)) / 900f), smooth(((move.endMs + 1_200) - now) / 900f))
            else -> baseZoom
        }
    }

    /** 지금 내 차 가운데의 화면 위치(확대 기준점). */
    fun carScreen(cam: Cam, now: Long): Offset {
        val head = headAt(path.actual, now)
        return cam.p(head.point) ?: Offset.Zero
    }

    /** 회전 중 옆으로 쏠린 정도(g): 속도 × 방향이 바뀌는 빠르기. 점마다. */
    /** 점마다 도는 방향(+1 왼쪽, -1 오른쪽). */
    private val latDir = IntArray(path.actual.size) { 1 }

    private val latG: FloatArray = FloatArray(path.actual.size).also { g ->
        val pts = path.actual
        for (i in 1 until pts.size - 1) {
            val dt = (pts[i + 1].ms - pts[i - 1].ms) / 1000f
            if (dt <= 0f) continue
            val h1 = headingNear(pts, i - 1); val h2 = headingNear(pts, i + 1)
            var dh = h2 - h1; while (dh > Math.PI) dh -= 2 * Math.PI; while (dh < -Math.PI) dh += 2 * Math.PI
            val v = path.speedKmh.getOrElse(i) { 0f } / 3.6f
            g[i] = (abs(dh / dt) * v / 9.81).toFloat()
            latDir[i] = if (dh >= 0) 1 else -1
        }
    }

    /** 점 [i]에서 다음 점으로 가는 동안 후진인지. */
    private fun backAt(i: Int): Boolean = path.reverse.getOrElse(i + 1) { path.reverse.getOrElse(i) { false } }

    /**
     * 주차: 점마다 차 앞이 향한 방향. 움직인 방향에 후진이면 180°를 더하고,
     * 방향을 바꾸는 순간이나 짧은 흔들림으로 한 번에 120° 넘게 뒤집히면 앞 방향을 그대로 둡니다(차는 제자리에서 뒤돌지 않으니까요).
     */
    private val facings: DoubleArray = DoubleArray(path.actual.size).also { out ->
        val pts = path.actual
        var prev: Double? = null
        for (i in pts.indices) {
            val back = backAt(i)
            // 같은 방향(앞/뒤)으로 30cm 넘게 간 점을 찾아 방향을 잡습니다.
            var j = i + 1
            var target: PathPoint? = null
            while (j < pts.size && backAt(j - 1) == back) {
                if (hypot(pts[j].x - pts[i].x, pts[j].y - pts[i].y) > 0.3f) { target = pts[j]; break }
                j++
            }
            var f = target?.let { atan2(-(it.x - pts[i].x).toDouble(), (it.y - pts[i].y).toDouble()) + if (back) Math.PI else 0.0 }
            val p = prev
            if (f == null) f = p ?: path.parkedHeading.toDouble()
            if (p != null) {
                var d = f - p
                while (d > Math.PI) d -= 2 * Math.PI
                while (d < -Math.PI) d += 2 * Math.PI
                f = if (abs(d) > Math.toRadians(120.0)) p else p + d
            }
            out[i] = f; prev = f
        }
    }

    /** 주차: 두 점 사이에서 이어 준 지금 방향. */
    private fun parkFacing(i: Int, now: Long): Double {
        val pts = path.actual
        val a = pts[i]; val b = pts.getOrElse(i + 1) { a }
        val t = if (b.ms > a.ms) ((now - a.ms).toFloat() / (b.ms - a.ms)).coerceIn(0f, 1f) else 0f
        val f0 = facings[i]; val f1 = facings.getOrElse(i + 1) { f0 }
        return f0 + (f1 - f0) * t
    }

    /** 확대와 상관없이 그리는 것: 왼쪽 위 속도·시간, 회전 중엔 왼쪽 아래 핸들·쏠림 카드, 지도 출처. */
    fun drawHud(d: DrawScope, now: Long, measurer: TextMeasurer) = with(d) {
        val pts = path.actual
        if (pts.size < 2) return@with
        val head = headAt(pts, now)
        val reverse = park && backAt(head.index)
        val kmh = path.speedKmh.getOrElse(head.index) { 0f }.roundToInt()
        val text = "${if (reverse) "후진 " else ""}시속 ${kmh}km · ${"%.1f".format((now - pts.first().ms) / 1000f)}초"
        val layout = measurer.measure(text, TextStyle(color = Coach.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold))
        drawRoundRect(Coach.Bg.copy(alpha = 0.85f), Offset(20.dp.toPx(), 18.dp.toPx()), Size(layout.size.width + 28.dp.toPx(), layout.size.height + 18.dp.toPx()), CornerRadius(12.dp.toPx()))
        drawText(layout, topLeft = Offset(34.dp.toPx(), 27.dp.toPx()))
        sceneCard(this, now, head.index, measurer)
        if (onMap) {
            val credit = measurer.measure(DemoMap.CREDIT, TextStyle(color = Coach.Faint, fontSize = 13.sp))
            drawText(credit, topLeft = Offset(size.width - credit.size.width - 16.dp.toPx(), size.height - credit.size.height - 10.dp.toPx()))
        }
    }

    /** 앞뒤로 쏠린 정도(g, +는 가속·-는 감속): 0.1초마다, 앞뒤 0.3초 속도 차이로. */
    private val lonStepMs = 100L
    private val lonT0 = path.actual.firstOrNull()?.ms ?: move.startMs
    private val lonG: FloatArray = run {
        val sp = move.speed
        val end = path.actual.lastOrNull()?.ms ?: move.endMs
        fun v(ms: Long): Float {
            if (sp.isEmpty()) return 0f
            val i = sp.indexOfLast { it.ms <= ms }
            if (i < 0) return sp.first().value
            val a = sp[i]; val b = sp.getOrElse(i + 1) { a }
            return if (b.ms > a.ms) a.value + (b.value - a.value) * ((ms - a.ms).toFloat() / (b.ms - a.ms)).coerceIn(0f, 1f) else a.value
        }
        FloatArray((((end - lonT0) / lonStepMs) + 1).toInt().coerceAtLeast(1)) { k ->
            val t = lonT0 + k * lonStepMs
            (v(t + 300) - v(t - 300)) / 0.6f / 9.81f
        }
    }

    /**
     * 지금 핸들 각도. 기록은 바뀔 때만 오므로 두 기록 사이를 이어 줍니다(손으로 돌리듯 부드럽게).
     * 오래 그대로였던 값은 다음 기록 0.3초 전까지 그대로 두고, 그때부터 다음 값으로 넘어갑니다.
     */
    private fun steerAt(now: Long): Float {
        val st = move.steering
        val i = st.indexOfLast { it.ms <= now }
        if (i < 0) return st.firstOrNull()?.value ?: 0f
        val a = st[i]; val b = st.getOrNull(i + 1) ?: return a.value
        val from = maxOf(a.ms, b.ms - 300)
        if (now <= from) return a.value
        return a.value + (b.value - a.value) * ((now - from).toFloat() / (b.ms - from).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** 지금 쏠림(g, 부호 있음). 옆 쏠림은 + = 몸이 오른쪽으로, 앞뒤 쏠림은 + = 몸이 앞으로(브레이크). */
    private fun pushAt(now: Long, idx: Int, side: Boolean): Float {
        if (side) {
            val pts = path.actual
            val a = pts.getOrNull(idx) ?: return 0f; val b = pts.getOrNull(idx + 1) ?: a
            val f = if (b.ms > a.ms) ((now - a.ms).toFloat() / (b.ms - a.ms)).coerceIn(0f, 1f) else 0f
            val ga = latG.getOrElse(idx) { 0f } * latDir.getOrElse(idx) { 1 }
            val gb = latG.getOrElse(idx + 1) { 0f } * latDir.getOrElse(idx + 1) { 1 }
            return ga + (gb - ga) * f
        }
        val x = ((now - lonT0).toFloat() / lonStepMs).coerceIn(0f, (lonG.size - 1).toFloat())
        val k = x.toInt(); val f = x - k
        val g = lonG[k] + (lonG.getOrElse(k + 1) { lonG[k] } - lonG[k]) * f
        return -g // 감속(-)이면 몸은 앞으로(+)
    }

    /**
     * 장면 카드(왼쪽 아래): 돌아가는 핸들과 각도, 그리고 쏠림을 '물컵'으로 보여 줍니다.
     * 숫자(g) 대신 컵 속 물이 얼마나 기울고 출렁이는지로 몸이 느끼는 정도를 보여 줍니다.
     * 0.25g 아래는 잔잔, 0.35g 아래는 출렁, 그 위는 넘침. 주차는 핸들을 몇 바퀴 감았는지 보여 줍니다.
     */
    private fun sceneCard(d: DrawScope, now: Long, idx: Int, measurer: TextMeasurer) = with(d) {
        val steer = steerAt(now)
        val side = move.kind.isTurn || move.kind == MoveKind.LANE_CHANGE
        val w = 640.dp.toPx(); val h = 250.dp.toPx()
        val o = Offset(20.dp.toPx(), size.height - h - 20.dp.toPx())
        drawRoundRect(Coach.Bg.copy(alpha = 0.92f), o, Size(w, h), CornerRadius(24.dp.toPx()))
        // 핸들: 왼쪽으로 돌리면 반시계로 돕니다.
        val c = Offset(o.x + 104.dp.toPx(), o.y + h / 2); val r = 70.dp.toPx()
        drawCircle(Coach.Text2, r, c, style = Stroke(11.dp.toPx()))
        val a = Math.toRadians(-steer.toDouble())
        for (t in listOf(0.0, 2.2, -2.2)) {
            val ang = a + t + Math.PI / 2
            drawLine(Coach.Text2, c, Offset(c.x + (cos(ang) * r).toFloat(), c.y + (sin(ang) * r).toFloat()), 10.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(Coach.Text2, 13.dp.toPx(), c)
        drawCircle(Coach.Accent, 13.dp.toPx(), Offset(c.x + (cos(a - Math.PI / 2) * r).toFloat(), c.y + (sin(a - Math.PI / 2) * r).toFloat()))
        val dir = when { steer > 5f -> "왼쪽 "; steer < -5f -> "오른쪽 "; else -> "" }
        val wheelText = "핸들 $dir${abs(steer).roundToInt()}°"
        if (park) {
            val x = o.x + 210.dp.toPx()
            drawText(measurer.measure(wheelText, TextStyle(color = Coach.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(x, o.y + 28.dp.toPx()))
            val turns = abs(steer) / 360f
            drawText(measurer.measure(if (turns < 0.1f) "핸들 풀음" else "${"%.1f".format(turns)}바퀴 감음", TextStyle(color = Coach.AccentSoft, fontSize = 30.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(x, o.y + 86.dp.toPx()))
            val bx = x; val by = o.y + 156.dp.toPx(); val bw = w - (x - o.x) - 32.dp.toPx(); val bh = 14.dp.toPx()
            drawRoundRect(Coach.Track, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
            drawRoundRect(Coach.Accent, Offset(bx, by), Size(bw * (abs(steer) / 540f).coerceIn(0f, 1f), bh), CornerRadius(bh / 2))
            drawText(measurer.measure("끝까지 감으면 1.5바퀴", TextStyle(color = Coach.Muted, fontSize = 22.sp)), topLeft = Offset(bx, by + 24.dp.toPx()))
            return@with
        }
        // 물컵
        val g = pushAt(now, idx, side)
        val mag = abs(g)
        fun level(v: Float) = when { v < 0.25f -> 0; v < 0.35f -> 1; else -> 2 }
        val lv = level(mag)
        val peak = if (side) (0..idx.coerceAtLeast(0)).maxOfOrNull { latG.getOrElse(it) { 0f } } ?: 0f
        else { val k = ((now - lonT0) / lonStepMs).toInt().coerceIn(0, lonG.size - 1); (0..k).maxOf { abs(lonG[it]) } }
        val water = if (lv == 0) Coach.Accent else Coach.Amber
        val cx = o.x + 262.dp.toPx(); val cupW = 78.dp.toPx(); val cupH = 104.dp.toPx()
        val top = o.y + (h - cupH) / 2 + 6.dp.toPx(); val bottom = top + cupH
        val inset = 9.dp.toPx() // 아래가 조금 좁은 컵
        val cup = Path().apply { moveTo(cx - cupW / 2, top); lineTo(cx - cupW / 2 + inset, bottom); lineTo(cx + cupW / 2 - inset, bottom); lineTo(cx + cupW / 2, top) }
        // 물: 기울기 = 쏠림을 보기 좋게 키운 각도. +면 오른쪽이 높아집니다.
        val tilt = (atan(g * 1.8f)).coerceIn(-0.7f, 0.7f)
        val fill = top + cupH * 0.38f
        val halfW = cupW / 2 - inset / 2
        val yl = fill + kotlin.math.tan(tilt) * halfW; val yr = fill - kotlin.math.tan(tilt) * halfW
        clipPath(cup) {
            val waterPath = Path().apply {
                moveTo(cx - cupW, yl + kotlin.math.tan(tilt) * cupW / 2); lineTo(cx + cupW, yr - kotlin.math.tan(tilt) * cupW / 2)
                lineTo(cx + cupW, bottom + 4f); lineTo(cx - cupW, bottom + 4f); close()
            }
            drawPath(waterPath, water.copy(alpha = 0.85f))
        }
        drawPath(cup, Coach.Text2, style = Stroke(5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = StrokeCap.Round))
        if (lv == 2) { // 넘침: 높은 쪽 가장자리로 물방울
            val sx = if (tilt > 0) cx + cupW / 2 else cx - cupW / 2
            val sgn = if (tilt > 0) 1f else -1f
            listOf(Triple(6f, -10f, 6f), Triple(16f, -2f, 4.5f), Triple(10f, 10f, 3.5f)).forEach { (dx, dy, rr) ->
                drawCircle(Coach.Amber, rr.dp.toPx(), Offset(sx + sgn * dx.dp.toPx(), top + dy.dp.toPx()))
            }
        }
        // 글
        val x = o.x + 330.dp.toPx()
        drawText(measurer.measure(wheelText, TextStyle(color = Coach.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(x, o.y + 26.dp.toPx()))
        val what = when {
            side -> "옆으로 쏠려요"
            g > 0.03f -> "브레이크"
            g < -0.03f -> "가속"
            else -> "그대로 가는 중"
        }
        val state = arrayOf("물컵이 잔잔해요", "물이 출렁여요", "물이 넘쳐요")[lv]
        drawText(measurer.measure(what, TextStyle(color = Coach.Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)), topLeft = Offset(x, o.y + 88.dp.toPx()))
        drawText(measurer.measure(state, TextStyle(color = if (lv == 0) Coach.AccentSoft else Coach.AmberSoft, fontSize = 30.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(x, o.y + 120.dp.toPx()))
        val pl = level(peak)
        val peakText = arrayOf("가장 셀 때도 잔잔했어요", "가장 셀 때 출렁였어요", "가장 셀 때 넘쳤어요")[pl]
        drawText(measurer.measure(peakText, TextStyle(color = if (pl == 0) Coach.Muted else Coach.AmberSoft, fontSize = 22.sp)), topLeft = Offset(x, o.y + 178.dp.toPx()))
    }

    /** 움직이는 것: 지나온 길, 표시, 모범 차, 내 차, 왼쪽 위 속도. */
    fun drawDynamic(d: DrawScope, cam: Cam, now: Long, sprites: CarSprites, paint: CarPaint, measurer: TextMeasurer) = with(d) {
        val pts = path.actual
        if (pts.size < 2) return@with
        val head = headAt(pts, now)
        val passed = pts.filter { it.ms <= now } + head.point
        val w = (6.dp.toPx() / zoomNow)
        val lateFrom = path.signalTarget?.ms
        val lateTo = move.signalOnMs ?: move.endMs
        for (i in 0 until passed.size - 1) {
            val a = cam.p(passed[i]) ?: continue; val b = cam.p(passed[i + 1]) ?: continue
            val t = passed[i + 1].ms
            val color = when {
                park && path.reverse.getOrElse(minOf(i + 1, path.reverse.size - 1)) { false } -> Color(0xFF8FB4FF)
                lateFrom != null && t in lateFrom..lateTo -> Coach.Amber
                else -> Coach.Accent
            }
            drawLine(color, a, b, w, cap = StrokeCap.Round)
        }
        path.brakeSegments.forEachIndexed { i, seg ->
            val reached = seg.filter { it.ms <= now }
            if (reached.size >= 2) {
                polyline(cam, reached, Coach.Amber, (9.dp.toPx() / zoomNow))
                if (i == 0) cam.p(reached.last())?.let { tag(measurer, it, "브레이크", Coach.AmberSoft) }
            }
        }
        path.corrections.filter { it.ms <= now }.forEachIndexed { i, p ->
            cam.p(p)?.let { o -> drawCircle(Coach.Stage, (10.dp.toPx() / zoomNow), o); drawCircle(Coach.Amber, (7.dp.toPx() / zoomNow), o); if (i == 0) tag(measurer, o, "고쳐 꺾음", Coach.AmberSoft) }
        }
        path.switches.filter { it.ms <= now }.forEach { p -> cam.p(p)?.let { drawCircle(Coach.Amber, (7.dp.toPx() / zoomNow), it) } }
        path.signalTarget?.let { p ->
            cam.p(p)?.let { o ->
                drawCircle(Coach.Stage, (12.dp.toPx() / zoomNow), o); drawCircle(Coach.Text, (11.dp.toPx() / zoomNow), o, style = Stroke((3.dp.toPx() / zoomNow)))
                tag(measurer, o, "여기서 켰으면 좋아요 · 30m 전", Coach.Text)
            }
        }
        path.signalAt?.takeIf { it.ms <= now }?.let { p ->
            cam.p(p)?.let { o ->
                val late = path.signalTarget != null
                drawCircle(Coach.Stage, (12.dp.toPx() / zoomNow), o); drawCircle(if (late) Coach.Amber else Coach.Accent, (9.dp.toPx() / zoomNow), o)
                val lead = move.signalLeadM?.roundToInt()
                val text = when { move.signalWrongSide -> "반대쪽 깜빡이"; move.signalAfterStart -> "꺾기 시작한 뒤에 켬"; lead != null -> "깜빡이 켬 · 꺾기 ${lead}m 전"; else -> "깜빡이 켬" }
                tag(measurer, o, text, if (late) Coach.AmberSoft else Coach.AccentSoft, opposite = true)
            }
        }
        listOfNotNull(path.brakeFrom?.let { it to "브레이크 시작" }, path.idealBrakeFrom?.let { it to "여기서부터 천천히" }, path.markAt?.let { it to markName() }, path.slowTarget?.let { it to "여기까지 미리 줄이기" })
            .forEach { (p, label) -> cam.p(p)?.let { o -> drawCircle(Coach.Text, (9.dp.toPx() / zoomNow), o, style = Stroke((3.dp.toPx() / zoomNow))); tag(measurer, o, label, Coach.Text, beside = true) } }

        // 주차장 옆 칸에 서 있는 차들
        if (park) {
            val end = pts.last(); val h = path.parkedHeading
            val fx = -sin(h); val fy = cos(h); val rx = fy; val ry = -fx
            for ((k, p, forward) in neighbours) {
                val sd = k * 2.5f; val along = 1.4f
                val at = V2(end.x + fx * along + rx * sd, end.y + fy * along + ry * sd)
                val face = if (forward) h.toDouble() else h + Math.PI
                drawCar(cam, sprites.body(p, Math.toDegrees(face - cam.rot)), at, face)
            }
        }
        // 차선 변경: 옆 차로를 달리는 차들
        if (move.kind == MoveKind.LANE_CHANGE) {
            val lanes = lanesFor()
            val headNow = headAt(pts, now)
            val t = (now - pts.first().ms) / 1000f
            for ((lane, gap, p) in traffic) {
                val (ahead, faster) = gap
                val at = V2(lanes[lane], headNow.point.y + ahead + faster * t)
                drawCar(cam, sprites.body(p, 0.0), at, 0.0)
            }
        }
        // 모범 차(반투명): 모범 경로를 같은 진도로
        if (!park && path.ideal.size >= 2) {
            val f = ((now - pts.first().ms).toFloat() / (pts.last().ms - pts.first().ms).coerceAtLeast(1)).coerceIn(0f, 1f)
            val (gp, gh) = along(path.ideal, f)
            Math.toDegrees(gh - cam.rot).let { gd -> drawCar(cam, sprites.body(paint, gd), gp, gh, alpha = 0.32f, deg = gd) }
        }
        // 내 차
        val reverse = park && backAt(head.index)
        val facing = if (park) parkFacing(head.index, now) else head.heading
        val deg = Math.toDegrees(facing - cam.rot)
        val carCenter = V2(head.point.x + (-sin(facing)).toFloat() * 1.4f, head.point.y + cos(facing).toFloat() * 1.4f)
        if (paint.dark) sprites.body(paint, deg)?.let { drawCar(cam, it, carCenter, facing, outline = true, deg = deg) }
        drawCar(cam, sprites.body(paint, deg), carCenter, facing, deg = deg)
        val on = move.signalOnMs
        if (on != null && now >= on && now <= move.endMs + 2_000 && ((now - on) / 400) % 2 == 0L) {
            val side = move.side?.let { if (move.signalWrongSide) (if (it == Side.LEFT) Side.RIGHT else Side.LEFT) else it }
            side?.let { drawCar(cam, sprites.light(if (it == Side.LEFT) "blinkL" else "blinkR", deg), carCenter, facing, deg = deg) }
        }
        if (move.brakeSpans.any { now in it.first..it.second }) drawCar(cam, sprites.light("brake", deg), carCenter, facing, deg = deg)
        if (reverse) drawCar(cam, sprites.light("reverse", deg), carCenter, facing, deg = deg)
        // (차에 가리지 않게 차 다음에 그립니다)
        // 여러 번 만에 넣은 주차: 첫 후진을 멈춘 곳에 핸들을 얼마나 감았고 차가 얼마나 돌았는지
        if (park && move.corrections > 0) {
            val end = move.reverseSpans.firstOrNull()?.second
            val first = FormScore.firstReverse(move)
            if (end != null && first != null && now >= end) {
                pts.lastOrNull { it.ms <= end }?.let { p -> cam.p(p)?.let { o ->
                    tag(measurer, o, "첫 후진 ${"%.1f".format(first.first / 360f)}바퀴 · ${first.second.roundToInt()}° 돌고 멈춤", Coach.AmberSoft, opposite = true)
                } }
            }
        }
    }

    private fun markName() = when (move.kind) {
        MoveKind.SPEED_BUMP -> "과속방지턱"; MoveKind.CAMERA -> "단속 카메라"; MoveKind.RAILROAD -> "철길 건널목"; MoveKind.SCHOOL_ZONE -> "보호구역 시작"; else -> ""
    }

    /** 차 이미지 한 장을 지도 축척에 맞춰 놓습니다. 이미지 가운데가 차 가운데입니다. */
    private fun DrawScope.drawCar(cam: Cam, img: ImageBitmap?, at: V2, heading: Double, alpha: Float = 1f, outline: Boolean = false, deg: Double? = null) {
        img ?: return
        val o = cam.p(at) ?: return
        val px = CarSprites.RES * cam.ppm(at) / CarSprites.PX_PER_M * (if (outline) 1.04f else 1f)
        val k = px / img.width
        // 이미지는 10°마다 한 장이라, 남은 각도만큼 화면에서 살짝 더 돌려 부드럽게 돌아가게 합니다(왼쪽 = 반시계).
        val rest = deg?.let { (it - Math.round(it / 10.0) * 10.0).toFloat() } ?: 0f
        // 정수 픽셀로 반올림하지 않고 소수 위치에 그려 미끄러지듯 움직이게 합니다.
        withTransform({ translate(o.x, o.y); rotate(-rest, Offset.Zero); scale(k, k, Offset.Zero); translate(-img.width / 2f, -img.height / 2f) }) {
            drawImage(
                img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(img.width, img.height),
                alpha = if (outline) 0.4f else alpha, filterQuality = FilterQuality.Medium,
                colorFilter = if (outline) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null,
            )
        }
    }

    private fun DrawScope.polyline(cam: Cam, pts: List<PathPoint>, color: Color, width: Float) {
        val p = Path(); var first = true
        pts.forEach { v -> cam.p(v)?.let { o -> if (first) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y); first = false } }
        drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private class Head(val point: PathPoint, val heading: Double, val index: Int)

private fun headAt(pts: List<PathPoint>, now: Long): Head {
    val i = pts.indexOfLast { it.ms <= now }.coerceIn(0, pts.size - 1)
    val a = pts[i]; val b = pts.getOrElse(i + 1) { a }
    val f = if (b.ms > a.ms) ((now - a.ms).toFloat() / (b.ms - a.ms)).coerceIn(0f, 1f) else 0f
    // 방향도 두 점 사이에서 이어 줍니다(점마다 툭툭 꺾이지 않게).
    val h0 = headingNear(pts, i)
    val h1 = if (i + 1 < pts.size) headingNear(pts, i + 1) else h0
    var dh = h1 - h0
    while (dh > Math.PI) dh -= 2 * Math.PI
    while (dh < -Math.PI) dh += 2 * Math.PI
    return Head(PathPoint(now, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f), h0 + dh * f, i)
}

/** 흔들리지 않게 30cm 넘게 떨어진 앞(없으면 뒤) 점으로 방향을 잡습니다. 0 = +y, 왼쪽이 +. */
private fun headingNear(pts: List<PathPoint>, i: Int): Double {
    val a = pts[i]
    val ahead = (i + 1 until pts.size).map { pts[it] }.firstOrNull { hypot(it.x - a.x, it.y - a.y) > 0.3f }
    val (from, to) = if (ahead != null) a to ahead else ((0 until i).reversed().map { pts[it] }.firstOrNull { hypot(it.x - a.x, it.y - a.y) > 0.3f } ?: a) to a
    if (from === to) return 0.0
    return atan2(-(to.x - from.x).toDouble(), (to.y - from.y).toDouble())
}

private fun along(pts: List<PathPoint>, f: Float): Pair<V2, Double> {
    val seg = (0 until pts.size - 1).map { hypot(pts[it + 1].x - pts[it].x, pts[it + 1].y - pts[it].y) }
    var left = seg.sum() * f
    for (i in seg.indices) {
        if (left <= seg[i] || i == seg.lastIndex) {
            val t = if (seg[i] > 0) (left / seg[i]).coerceIn(0f, 1f) else 0f
            val a = pts[i]; val b = pts[i + 1]
            return V2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) to headingNear(pts, i)
        }
        left -= seg[i]
    }
    return V2(pts.last().x, pts.last().y) to 0.0
}

private fun densify(pts: List<V2>, step: Float): List<V2> {
    if (pts.size < 2) return pts
    val out = mutableListOf<V2>()
    for (k in 0 until pts.size - 1) {
        val a = pts[k]; val b = pts[k + 1]
        val n = (hypot(b.x - a.x, b.y - a.y) / step).toInt().coerceAtLeast(1)
        for (i in 0 until n) out += V2(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
    }
    out += pts.last()
    return out
}

private fun runs(pts: List<V2>, keep: (V2) -> Boolean): List<List<V2>> {
    val res = mutableListOf<List<V2>>(); var cur = mutableListOf<V2>()
    for (p in pts) if (keep(p)) cur += p else { if (cur.size >= 2) res += cur; cur = mutableListOf() }
    if (cur.size >= 2) res += cur
    return res
}

/** 가운데 선 양옆으로 [half]m 벌린 띠(실제 폭)를 원근으로 바꾼 도형. */
private fun band(cam: Cam, center: List<V2>, half: Float): Path? {
    if (center.size < 2) return null
    val left = mutableListOf<Offset>(); val right = mutableListOf<Offset>()
    for (i in center.indices) {
        val a = center[maxOf(i - 1, 0)]; val b = center[minOf(i + 1, center.size - 1)]
        val len = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: 1f
        val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
        val l = cam.p(center[i].x + nx * half, center[i].y + ny * half) ?: return null
        val r = cam.p(center[i].x - nx * half, center[i].y - ny * half) ?: return null
        left += l; right += r
    }
    return Path().apply {
        moveTo(left[0].x, left[0].y); left.drop(1).forEach { lineTo(it.x, it.y) }
        right.asReversed().forEach { lineTo(it.x, it.y) }; close()
    }
}

/** 바닥 도형을 높이 [h]m로 세운 상자(벽 + 지붕). */
private fun extrude(cam: Cam, ring: List<V2>, h: Float, out: MutableList<Layer>, d: androidx.compose.ui.unit.Density, k: Float = 1f) = with(d) {
    if (ring.any { cam.p(it, 0f) == null || cam.p(it, h) == null }) return@with
    for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        val q = listOf(cam.p(a, 0f)!!, cam.p(b, 0f)!!, cam.p(b, h)!!, cam.p(a, h)!!)
        out += Layer(Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Coach.Wall, null)
    }
    val roof = Path(); ring.forEachIndexed { i, v -> val o = cam.p(v, h)!!; if (i == 0) roof.moveTo(o.x, o.y) else roof.lineTo(o.x, o.y) }
    roof.close(); out += Layer(roof, Coach.Roof, null); out += Layer(roof, Coach.RoofEdge, Stroke((1.dp.toPx() / k)))
}

private fun line(p: Path, cam: Cam, a: V2, b: V2) {
    val pa = cam.pv(a.x, a.y); val pb = cam.pv(b.x, b.y)
    if (pa != null && pb != null) { p.moveTo(pa.x, pa.y); p.lineTo(pb.x, pb.y) }
}
