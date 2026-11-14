package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 에뮬레이터 Virtual Console에서 녹화한 주행(signal-probe)을 그대로 재생해 검증합니다. */
class MoveDetectorTest {

    @Test
    fun goodLeftTurn() {
        val t = Recordings.replay("good_left")
        assertEquals(MoveKind.TURN, t.kind)
        assertEquals(Side.LEFT, t.side)
        assertFalse(t.signalAfterStart)
        assertEquals(19.7f, t.signalLeadM!!, 0.5f)
        assertFalse(t.brakeDuring)
        assertEquals(0, t.corrections)
        assertTrue(t.score >= FormScore.GOOD_SCORE)
    }

    @Test
    fun badLeftTurn() {
        val t = Recordings.replay("bad_left")
        // 핸들을 400°까지 꺾었지만 유턴(450° 이상)은 아닙니다.
        assertEquals(MoveKind.TURN, t.kind)
        assertEquals(Side.LEFT, t.side)
        assertTrue(t.signalAfterStart)
        assertNull(t.signalLeadM)
        assertTrue(t.brakeDuring)
        assertEquals(5, t.corrections)
        assertEquals(2.7f, t.signalOffDelayS!!, 0.1f)
        assertTrue(t.score < 30)
    }

    /** 약 57km/h에서 깜빡이를 켜고 2.7초 뒤, 3초에 걸쳐 S자로 왼쪽 차로로 옮김. */
    @Test
    fun goodLeftLaneChange() {
        val m = Recordings.replay("good_lane")
        assertEquals(MoveKind.LANE_CHANGE, m.kind)
        assertEquals(Side.LEFT, m.side)
        assertFalse(m.signalWrongSide)
        assertTrue(m.signalLeadM!! >= FormScore.SIGNAL_TARGET_M)
        assertFalse(m.brakeDuring)
        assertEquals(0, m.corrections)
        assertEquals(3.0f, m.durationS, 0.2f)
        assertEquals(0.4f, m.signalOffDelayS!!, 0.1f)
        assertEquals(100, m.score)
    }

    /**
     * 에뮬레이터에서 실제로 나온 오탐: 회전을 끝내며 핸들을 푸는 동안 시속 30km를 넘자
     * 마지막 풀림(36° → 18° → 0°)이 차선 변경으로 잡혔습니다. 회전 하나만 나와야 합니다.
     */
    @Test
    fun turnExitAboveLaneSpeedIsNotLaneChange() {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        var t = 0L
        fun tick(mps: Float, steer: Float? = null) {
            t += 100
            d.onSpeed(t, mps)
            steer?.let { d.onSteering(t, it) }
        }
        d.onSteering(t, 0f)
        repeat(20) { tick(4f) }
        d.onTurnSignal(t, Side.LEFT)
        repeat(20) { tick(4f) }
        (1..20).forEach { tick(4f, 18f * it) }
        repeat(10) { tick(4f) }
        // 풀면서 가속해 시속 30km(8.3m/s)를 넘깁니다.
        (19 downTo 0).forEach { tick(4f + (19 - it) * 0.4f, 18f * it) }
        repeat(30) { tick(12f) }
        assertEquals(listOf(MoveKind.TURN), moves.map { it.kind })
    }

    /** 깜빡이 없이 1.5초 만에 오른쪽으로 옮기면서 브레이크. */
    @Test
    fun badRightLaneChange() {
        val m = Recordings.replay("bad_lane")
        assertEquals(MoveKind.LANE_CHANGE, m.kind)
        assertEquals(Side.RIGHT, m.side)
        assertNull(m.signalOnMs)
        assertTrue(m.brakeDuring)
        assertTrue(m.durationS < FormScore.LANE_MIN_DURATION_S)
        assertEquals(35, m.score)
    }

    /** 가속 페달을 2.5초 꾹 밟아 출발하고, 시속 45km에서 브레이크를 꾹 밟아 멈춤. */
    @Test
    fun harshStartAndStop() {
        val start = Recordings.replayOne("harsh_stop_start", MoveKind.START)
        assertTrue(start.peakAccelMs2 > FormScore.HARSH_START_MS2)
        assertEquals(70, start.score)
        val stop = Recordings.replayOne("harsh_stop_start", MoveKind.STOP)
        assertNull(stop.side)
        assertTrue(stop.peakAccelMs2 > FormScore.HARSH_STOP_MS2)
        assertTrue(stop.finalDecelMs2 > FormScore.JERKY_FINAL_MS2)
        assertEquals(45, stop.score)
    }

    /** 속도를 1km/h 단위로 넣어 정지 하나를 만듭니다. [kmh]는 0.1초마다의 속도입니다. */
    private fun stopFrom(kmh: List<Float>): MoveForm {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        kmh.forEachIndexed { i, v -> d.onSpeed(i * 100L, kotlin.math.round(v) / 3.6f) }
        return moves.single { it.kind == MoveKind.STOP }
    }

    private fun ramp(from: Float, to: Float, seconds: Float) =
        (0 until (seconds * 10).toInt()).map { from + (to - from) * it / (seconds * 10) }

    /** 39km/h에서 29km/h로 줄인 뒤 9초 달리다 멈추면, 감속은 29km/h부터 멈출 때까지입니다. */
    @Test
    fun stopCountsOnlyTheLastSlowdown() {
        val m = stopFrom(List(10) { 39f } + ramp(39f, 29f, 4f) + List(90) { 29f } + ramp(29f, 0f, 2f) + List(5) { 0f })
        assertEquals(29, m.entryKmh)
        assertTrue("${m.durationS}", m.durationS in 1.5f..2.5f)
    }

    /** 27~28km/h를 오가며 15초 달린 뒤 멈춰도, 오락가락한 구간은 감속에 넣지 않습니다. */
    @Test
    fun stopIgnoresCruiseWobble() {
        val wobble = List(150) { if (it / 7 % 2 == 0) 27f else 28f }
        val m = stopFrom(List(10) { 50f } + ramp(50f, 28f, 5f) + wobble + ramp(28f, 0f, 5f) + List(5) { 0f })
        assertTrue("${m.durationS}", m.durationS in 4.5f..6f)
    }

    /** 처음부터 끝까지 천천히 줄이면 전부 감속입니다. */
    @Test
    fun steadySlowdownIsAllDeceleration() {
        val m = stopFrom(List(10) { 40f } + ramp(40f, 0f, 20f) + List(5) { 0f })
        assertTrue("${m.entryKmh}", m.entryKmh >= 39)
        assertTrue("${m.durationS}", m.durationS in 18f..21f)
    }

    /** 가속 페달과 브레이크를 짧게 나눠 밟아 출발하고 멈춤. */
    @Test
    fun smoothStartAndStop() {
        val start = Recordings.replayOne("smooth_stop_start", MoveKind.START)
        assertTrue(start.peakAccelMs2 <= FormScore.SMOOTH_MS2)
        assertEquals(100, start.score)
        val stop = Recordings.replayOne("smooth_stop_start", MoveKind.STOP)
        assertTrue(stop.peakAccelMs2 <= FormScore.SMOOTH_MS2)
        assertTrue(stop.finalDecelMs2 <= FormScore.JERKY_FINAL_MS2)
        assertEquals(100, stop.score)
    }

    /** 시속 10km 안팎에서 깜빡이를 켜고 핸들을 왼쪽 끝(540°)까지 돌려 반 바퀴. */
    @Test
    fun uTurn() {
        val m = Recordings.replay("uturn")
        assertEquals(MoveKind.U_TURN, m.kind)
        assertEquals(Side.LEFT, m.side)
        assertFalse(m.signalAfterStart)
        assertEquals(0, m.corrections)
    }
}
