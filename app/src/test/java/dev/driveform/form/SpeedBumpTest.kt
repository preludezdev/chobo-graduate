package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 과속방지턱은 내비 알림이 있어야 해서 녹화 대신 합성 신호로 검증합니다.
 * 0.1초마다 속도를 넣고, 시작할 때 "턱 40m 앞" 알림을 줍니다.
 */
class SpeedBumpTest {

    private class Drive {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        var t = 0L
        var v = 0f

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

        fun bumps() = moves.filter { it.kind == MoveKind.SPEED_BUMP }
    }

    /** 시속 36km로 달리다 턱 40m 앞 알림 → 3초에 걸쳐 시속 14km로 줄이고 브레이크를 뗀 채 넘음. */
    @Test
    fun slowAndOffTheBrake() {
        val r = Drive().apply { v = 10f; d.onSpeed(t, v) }
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 40f)
        r.d.onBrake(r.t, true)
        r.ramp(4f, 3f)          // 약 21m
        r.d.onBrake(r.t, false)
        r.ramp(4f, 8f)          // 32m 더 → 턱을 넘고 1.5초 더 지켜봄
        val b = r.bumps().single()
        assertEquals(14, b.apexKmh)
        assertFalse(b.brakeDuring)
        assertTrue(b.peakAccelMs2 <= FormScore.SMOOTH_MS2)
        assertEquals(100, b.score)
    }

    /** 시속 36km 그대로 가다 턱 바로 앞에서 브레이크를 밟은 채 넘음. */
    @Test
    fun fastWithBrakeOnTheBump() {
        val r = Drive().apply { v = 10f; d.onSpeed(t, v) }
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 40f)
        r.ramp(10f, 3.6f)       // 36m
        r.d.onBrake(r.t, true)
        r.ramp(8.5f, 1f)        // 턱 위에서도 브레이크
        r.d.onBrake(r.t, false)
        r.ramp(8.5f, 3f)
        val b = r.bumps().single()
        assertTrue(b.apexKmh > FormScore.BUMP_FAST_KMH)
        assertTrue(b.brakeDuring)
        assertEquals(100 - 40 - 15, b.score)
    }

    /** 같은 턱 알림이 다시 오면 위치를 고칩니다. 처음 40m라더니 다음엔 '아직 30m'면 더 멀리 있는 것. */
    @Test
    fun repeatedNoticeMovesTheBump() {
        val r = Drive().apply { v = 10f; d.onSpeed(t, v) }
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 40f)
        r.ramp(10f, 2f)         // 20m
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 30f)
        r.ramp(10f, 2.5f)       // 25m: 처음 알림 기준이면 이미 넘었을 거리
        assertTrue(r.bumps().isEmpty())
        r.ramp(10f, 3f)
        assertEquals(1, r.bumps().size)
    }

    /** 알림만 오고 턱에 닿기 전에 멈추면(골목으로 빠지는 등) 기록하지 않습니다. */
    @Test
    fun noticeWithoutCrossingIsIgnored() {
        val r = Drive().apply { v = 10f; d.onSpeed(t, v) }
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 200f)
        r.ramp(0f, 5f)
        r.ramp(0f, 61f)
        assertTrue(r.bumps().isEmpty())
    }

    /** 알림을 받고 턱에 닿기 전에 주차하면, 다음 주행에서 그 턱을 넘은 것으로 치지 않습니다. */
    @Test
    fun parkingDropsPendingBump() {
        val r = Drive().apply { v = 10f; d.onSpeed(t, v) }
        r.d.onRoadPoint(r.t, MoveKind.SPEED_BUMP, "b1", 40f)
        r.ramp(0f, 4f)          // 20m 가서 멈춤
        r.d.onParked()
        r.ramp(10f, 5f)
        r.ramp(10f, 3f)
        assertTrue(r.bumps().isEmpty())
    }
}
