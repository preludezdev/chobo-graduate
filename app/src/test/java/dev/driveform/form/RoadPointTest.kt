package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 단속 카메라·철길 건널목·어린이 보호구역. 과속방지턱처럼 내비 알림이 있어야 해서 합성 신호로 검증합니다.
 * 0.1초마다 속도를 넣습니다.
 */
class RoadPointTest {

    private class Drive(startMps: Float) {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        var t = 0L
        var v = startMps

        init { d.onSpeed(t, v) }

        /** [seconds]초 동안 속도를 [to]m/s까지 고르게 바꿉니다. */
        fun ramp(to: Float, seconds: Float) {
            val steps = (seconds * 10).toInt()
            val from = v
            repeat(steps) { i ->
                t += 100
                v = from + (to - from) * (i + 1) / steps
                d.onSpeed(t, v)
            }
        }

        fun notice(kind: MoveKind, remainM: Float, limitKmh: Int? = null) = d.onRoadPoint(t, kind, "p1", remainM, limitKmh)

        fun only(kind: MoveKind) = moves.filter { it.kind == kind }.single()
    }

    /** 시속 50km로 고르게 달려 제한 60 카메라를 지남. */
    @Test
    fun cameraSteadyUnderLimit() {
        val r = Drive(13.9f)
        r.notice(MoveKind.CAMERA, 150f, 60)
        r.ramp(13.9f, 15f)
        val m = r.only(MoveKind.CAMERA)
        assertEquals(50, m.apexKmh)
        assertEquals(60, m.limitKmh)
        assertEquals(100, m.score)
    }

    /** 시속 70km로 오다 카메라 앞에서 55km로 급하게 줄이고, 지나자마자 다시 급가속('캥거루 운전'). */
    @Test
    fun cameraKangaroo() {
        val r = Drive(19.4f)
        r.notice(MoveKind.CAMERA, 100f, 60)
        r.ramp(19.4f, 3.2f)     // 62m
        r.ramp(15.3f, 1.0f)     // 약 0.42g로 줄임, 17m
        r.ramp(15.3f, 1.5f)     // 23m → 카메라 통과
        r.ramp(19.4f, 1.2f)     // 지나자마자 약 0.35g로 가속
        r.ramp(19.4f, 3f)
        val m = r.only(MoveKind.CAMERA)
        assertTrue(m.apexKmh <= 60)
        assertTrue(m.peakAccelMs2 > FormScore.SMOOTH_MS2)
        assertTrue(m.afterAccelMs2 > FormScore.CAMERA_RUSH_MS2)
        assertEquals(100 - 25 - 15, m.score)
    }

    @Test
    fun cameraOverLimit() {
        val r = Drive(20f)
        r.notice(MoveKind.CAMERA, 100f, 60)
        r.ramp(20f, 9f)
        assertEquals(100 - 40, r.only(MoveKind.CAMERA).score)
    }

    /** 건널목 20m 앞에서 멈췄다가 천천히 건넘. */
    @Test
    fun railroadStopThenCross() {
        val r = Drive(8f)
        r.notice(MoveKind.RAILROAD, 60f)
        r.ramp(8f, 3f)          // 24m
        r.ramp(0f, 4f)          // 16m → 40m 지점에서 멈춤(건널목 20m 앞)
        r.ramp(0f, 2f)
        r.ramp(4f, 3f)          // 6m
        r.ramp(4f, 6f)          // 24m → 건널목 통과
        val m = r.only(MoveKind.RAILROAD)
        assertTrue(m.stoppedBefore)
        assertEquals(14, m.apexKmh)
        assertEquals(100, m.score)
    }

    @Test
    fun railroadWithoutStopping() {
        val r = Drive(8f)
        r.notice(MoveKind.RAILROAD, 60f)
        r.ramp(8f, 10f)
        val m = r.only(MoveKind.RAILROAD)
        assertFalse(m.stoppedBefore)
        assertEquals(100 - 50 - 10, m.score)
    }

    /** 보호구역(300m)을 시속 29km로 고르게 지남. */
    @Test
    fun schoolZoneUnderLimit() {
        val r = Drive(8f)
        r.notice(MoveKind.SCHOOL_ZONE, 50f)
        r.ramp(8f, 45f)
        val m = r.only(MoveKind.SCHOOL_ZONE)
        assertEquals(29, m.apexKmh)
        assertEquals(FormScore.SCHOOL_DEFAULT_LIMIT_KMH, m.limitKmh ?: FormScore.SCHOOL_DEFAULT_LIMIT_KMH)
        assertEquals(100, m.score)
    }

    @Test
    fun schoolZoneSpeeding() {
        val r = Drive(11.1f)
        r.notice(MoveKind.SCHOOL_ZONE, 50f)
        r.ramp(11.1f, 35f)
        assertEquals(100 - 40, r.only(MoveKind.SCHOOL_ZONE).score)
    }

    /** 보호구역 안에서 아이를 내려 주려고 주차하면, 그때까지를 한 번으로 기록합니다. */
    @Test
    fun parkingInsideSchoolZoneRecordsIt() {
        val r = Drive(8f)
        r.notice(MoveKind.SCHOOL_ZONE, 20f)
        r.ramp(8f, 10f)
        r.ramp(0f, 3f)
        assertTrue(r.moves.none { it.kind == MoveKind.SCHOOL_ZONE })
        r.d.onParked()
        assertEquals(29, r.only(MoveKind.SCHOOL_ZONE).apexKmh)
    }
}
