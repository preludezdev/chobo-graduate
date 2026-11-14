package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class DrivePathTest {

    /** 마지막 몇 m의 진행 방향(도). 0 = 처음 방향, 왼쪽이 +. */
    private fun endHeading(path: DrivePath): Double {
        val pts = path.actual
        val b = pts.last()
        val a = pts.last { kotlin.math.hypot(b.x - it.x, b.y - it.y) > 2f }
        return Math.toDegrees(atan2(-(b.x - a.x).toDouble(), (b.y - a.y).toDouble()))
    }

    @Test
    fun goodLeftTurnEndsFacingLeft() {
        val m = Recordings.replay("good_left")
        val p = DrivePath.of(m)
        assertEquals(90.0, endHeading(p), 15.0)
        assertTrue(p.actual.last().x < -5f)
        // 깜빡이를 20m 전에 켰으니 '켰어야 할 곳'(30m 전)을 함께 보여 줍니다.
        assertNotNull(p.signalAt)
        assertNotNull(p.signalTarget)
        assertTrue(p.corrections.isEmpty())
        // 브레이크는 회전 전에만 밟았습니다.
        assertTrue(p.brakeSegments.flatten().all { it.ms < m.startMs })
    }

    @Test
    fun badLeftTurnShowsCorrectionsAndBrakeInTurn() {
        val m = Recordings.replay("bad_left")
        val p = DrivePath.of(m)
        assertEquals(90.0, endHeading(p), 15.0)
        assertTrue(p.corrections.isNotEmpty())
        assertTrue(p.brakeSegments.flatten().any { it.ms in m.startMs..m.endMs })
        assertNotNull(p.signalTarget)
    }

    @Test
    fun goodLaneChangeMovesLeftAndEndsStraight() {
        val p = DrivePath.of(Recordings.replay("good_lane"))
        assertEquals(-DrivePath.LANE_WIDTH_M, p.lateralM, 0.05f)
        assertEquals(0.0, endHeading(p), 3.0)
        assertEquals(p.actual.last().x, p.ideal.last().x, 0.01f)
        assertTrue(p.signalTarget == null)
    }

    @Test
    fun badLaneChangeMovesRight() {
        val p = DrivePath.of(Recordings.replay("bad_lane"))
        assertEquals(DrivePath.LANE_WIDTH_M, p.lateralM, 0.05f)
        assertEquals(0.0, endHeading(p), 3.0)
        assertNotNull(p.signalTarget)
    }

    @Test
    fun uTurnEndsFacingBack() {
        val p = DrivePath.of(Recordings.replay("uturn"))
        assertEquals(180.0, kotlin.math.abs(endHeading(p)), 15.0)
        assertTrue(p.actual.last().x < -2f)
    }

    /** 급제동은 늦게 밟은 것이므로 '여기서부터 천천히' 지점을 보여 줍니다. 정지·출발은 곧은 길입니다. */
    @Test
    fun harshStopShowsWhereToStartBraking() {
        val p = DrivePath.of(Recordings.replayOne("harsh_stop_start", MoveKind.STOP))
        assertNotNull(p.brakeFrom)
        assertNotNull(p.idealBrakeFrom)
        assertTrue(p.idealBrakeFrom!!.y < p.brakeFrom!!.y)
        assertTrue(p.actual.all { kotlin.math.abs(it.x) < 0.01f })
    }
}
