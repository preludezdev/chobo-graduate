package dev.driveform.form

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 평면 위 한 점(m). */
data class V2(val x: Float, val y: Float)

/**
 * 교차로 주변 지도(OpenStreetMap). 좌표는 교차로 노드 기준 미터이고 동쪽이 +x, 북쪽이 +y입니다.
 * [arms]는 교차로에서 뻗어 나간 길의 방위(북쪽에서 시계 방향, 도)입니다.
 */
data class MapJunction(
    val lat: Double,
    val lon: Double,
    val name: String,
    val arms: List<Float>,
    val roads: List<MapRoad>,
    val buildings: List<MapBuilding>,
)

data class MapRoad(val halfWidth: Float, val points: List<V2>)
data class MapBuilding(val height: Float, val points: List<V2>)

/**
 * 회전 장면을 실제 교차로 위에 올립니다.
 * 경로는 속도와 핸들로 계산한 추정이라 방위를 모릅니다. 그래서 회전한 각도와 교차로의 길 방향을 맞춰 들어온 길과 나간 길을 고르고,
 * 들어온 방향이 경로의 출발 방향과 같아지도록 지도를 돌립니다. 위치는 들어온 길과 나간 길(오른쪽 차로)이 만나는 곳에 교차로를 둡니다.
 */
object MapPlacement {
    /** 이 거리 안의 교차로만 씁니다(시연 좌표에는 수 m 흔들림이 있습니다). */
    const val NEAR_M = 150.0
    /** 오른쪽 차로 가운데가 길 가운데에서 떨어진 거리(m). */
    const val LANE_OFFSET_M = 1.6f
    /** 회전으로 보는 최소 방향 변화(°). 이보다 작으면 지도에 올리지 않습니다. */
    const val MIN_TURN_DEG = 45.0

    /** 지도(동·북) 좌표를 경로 좌표로 바꾸는 회전 [c](라디안)와 이동. */
    class Placement(val junction: MapJunction, private val c: Double, private val node: V2) {
        fun toPath(p: V2): V2 {
            val cc = cos(c).toFloat(); val sc = sin(c).toFloat()
            return V2(cc * p.x - sc * p.y + node.x, sc * p.x + cc * p.y + node.y)
        }
    }

    fun place(m: MoveForm, path: List<PathPoint>, junctions: List<MapJunction>): Placement? {
        if (!m.kind.isTurn || path.size < 4) return null
        val lat = m.lat ?: return null
        val lon = m.lon ?: return null
        val j = junctions.minByOrNull { distanceM(lat, lon, it.lat, it.lon) }?.takeIf { distanceM(lat, lon, it.lat, it.lon) <= NEAR_M } ?: return null
        val (ds, ps) = direction(path, fromStart = true) ?: return null
        val (de, pe) = direction(path, fromStart = false) ?: return null
        val thS = angle(ds); val thE = angle(de)
        val turn = Math.toDegrees(norm(thE - thS))
        if (abs(turn) < MIN_TURN_DEG) return null
        // 들어온 길 e(방위 a_e: 교차로에서 그 길 쪽)로 와서 나간 길 x로 나감: 들어올 때 방위 a_e+180, 나갈 때 a_x. 왼쪽으로 돌면 방위가 줄어듭니다.
        val uTurn = abs(turn) > 135
        var best: Triple<Float, Float, Double>? = null
        for (ae in j.arms) for (ax in j.arms) {
            if (ae == ax && !uTurn) continue
            val bin = ae + 180.0
            val err = abs(normDeg(bin - turn - ax))
            if (best == null || err < best.third) best = Triple(ae, ax, err)
        }
        val (ae, _, err) = best ?: return null
        if (err > 50) return null
        val bIn = Math.toRadians(ae + 180.0)
        // 방위 β의 길이 경로에서 각도 φ = (θs + b_in) − β 가 되도록 돌립니다.
        val c = thS + bIn
        val node = if (uTurn) uTurnNode(path, ds, ps) else {
            val i = intersect(ps, ds, pe, de) ?: return null
            val rs = V2(ds.y, -ds.x); val re = V2(de.y, -de.x)
            V2(i.x - LANE_OFFSET_M * (rs.x + re.x), i.y - LANE_OFFSET_M * (rs.y + re.y))
        }
        return Placement(j, c, node)
    }

    /** 처음(또는 끝) 5m 구간의 진행 방향(단위 벡터)과 그 기준점. */
    private fun direction(path: List<PathPoint>, fromStart: Boolean): Pair<V2, V2>? {
        val pts = if (fromStart) path else path.asReversed()
        val a = pts.first()
        val b = pts.firstOrNull { hypot(it.x - a.x, it.y - a.y) > 5f } ?: return null
        val dx = if (fromStart) b.x - a.x else a.x - b.x
        val dy = if (fromStart) b.y - a.y else a.y - b.y
        val l = hypot(dx, dy)
        return V2(dx / l, dy / l) to V2(a.x, a.y)
    }

    /** 경로 각도: +y가 0, 왼쪽(반시계)이 +. */
    private fun angle(d: V2) = atan2(-d.x.toDouble(), d.y.toDouble())
    private fun norm(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }
    private fun normDeg(a: Double): Double { var x = a % 360; if (x > 180) x -= 360; if (x < -180) x += 360; return x }

    private fun intersect(p: V2, d: V2, q: V2, e: V2): V2? {
        val den = d.x * e.y - d.y * e.x
        if (abs(den) < 0.2f) return null
        val t = ((q.x - p.x) * e.y - (q.y - p.y) * e.x) / den
        return V2(p.x + d.x * t, p.y + d.y * t)
    }

    /** 유턴: 두 차로 사이 한가운데, 가장 멀리 나간 곳에서 조금 앞을 교차로로 봅니다. */
    private fun uTurnNode(path: List<PathPoint>, ds: V2, ps: V2): V2 {
        val along = path.maxOf { (it.x - ps.x) * ds.x + (it.y - ps.y) * ds.y }
        val end = path.last()
        val r = V2(ds.y, -ds.x)
        val side = ((end.x - ps.x) * r.x + (end.y - ps.y) * r.y) / 2
        val l = along - 4f
        return V2(ps.x + ds.x * l + r.x * side, ps.y + ds.y * l + r.y * side)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return hypot(x, y) * r
    }
}
