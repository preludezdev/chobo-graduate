package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MapPlacementTest {
    /** 남북·동서로 곧게 만나는 사거리(길 방위 0·90·180·270°). */
    private val cross = MapJunction(
        lat = 37.0, lon = 127.0, name = "시험 사거리", arms = listOf(0f, 90f, 180f, 270f),
        roads = listOf(MapRoad(3.6f, listOf(V2(0f, -80f), V2(0f, 80f))), MapRoad(3.6f, listOf(V2(-80f, 0f), V2(80f, 0f)))),
        buildings = emptyList(),
    )

    /** +y로 30m 오다가 반지름 [r]m로 왼쪽(또는 오른쪽)으로 90° 돌고 20m 더 갑니다. */
    private fun turnPath(left: Boolean, r: Float = 8f): List<PathPoint> {
        val pts = mutableListOf<PathPoint>()
        var t = 0L
        for (i in 0..30) pts += PathPoint(t++, 0f, i.toFloat())
        val sgn = if (left) -1f else 1f
        for (k in 1..18) {
            val a = Math.toRadians(90.0 * k / 18)
            pts += PathPoint(t++, sgn * (r - r * cos(a)).toFloat(), 30f + r * sin(a).toFloat())
        }
        val end = pts.last()
        for (i in 1..20) pts += PathPoint(t++, end.x + sgn * i, end.y)
        return pts
    }

    private fun move(left: Boolean, lat: Double = 37.0) = MoveForm(
        id = 1, side = if (left) Side.LEFT else Side.RIGHT, startMs = 0, endMs = 60, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = null, signalAfterStart = false, brakeDuring = false, corrections = 0, signalOffDelayS = null,
        entryKmh = 15, apexKmh = 12, exitKmh = 18, lat = lat, lon = 127.0,
    )

    /** 좌회전: 남쪽 길로 들어와 서쪽 길로 나가게 놓이고, 출발점은 교차로 남쪽 길 위(오른쪽 차로)에 있습니다. */
    @Test
    fun leftTurnUsesSouthArmInAndWestArmOut() {
        val path = turnPath(left = true)
        val p = MapPlacement.place(move(true), path, listOf(cross))!!
        // 남쪽 길의 한 점(교차로에서 20m 남쪽)은 출발 방향 선 위, 차로만큼 왼쪽에 와야 합니다.
        val south = p.toPath(V2(0f, -20f))
        assertEquals(-MapPlacement.LANE_OFFSET_M, south.x - path.first().x, 1.5f)
        // 서쪽 길은 나가는 길(경로 끝의 가로선)과 차로만큼만 떨어져 나란히 놓여야 합니다.
        val end = path.last()
        val west = p.toPath(V2(-30f, 0f))
        assertEquals(MapPlacement.LANE_OFFSET_M, end.y - west.y, 1.5f)
    }

    @Test
    fun rightTurnIsPlacedToo() {
        assertNotNull(MapPlacement.place(move(false), turnPath(left = false), listOf(cross)))
    }

    /** 교차로에서 너무 멀거나(150m 넘게) 회전이 아니면 지도에 올리지 않습니다. */
    @Test
    fun farOrStraightIsNotPlaced() {
        assertNull(MapPlacement.place(move(true, lat = 37.01), turnPath(left = true), listOf(cross)))
        val straight = (0..40).map { PathPoint(it.toLong(), 0f, it.toFloat()) }
        assertNull(MapPlacement.place(move(true), straight, listOf(cross)))
    }
}
