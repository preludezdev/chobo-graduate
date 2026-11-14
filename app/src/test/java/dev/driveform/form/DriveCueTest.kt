package dev.driveform.form

import dev.driveform.vehicle.AmbientLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DriveCueTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli()

    private fun turn(id: Int, early: Boolean, at: Long) = MoveForm(
        id = id, side = Side.RIGHT, startMs = 0, endMs = 3_000, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = 0, signalLeadM = if (early) 35f else 10f, signalAfterStart = false, brakeDuring = false, corrections = 0,
        signalOffDelayS = null, entryKmh = 20, apexKmh = 15, exitKmh = 20, kind = MoveKind.TURN, recordedAt = at,
    )

    private fun stop(id: Int, at: Long) = MoveForm(
        id = id, side = null, startMs = 0, endMs = 5_000, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = null, signalAfterStart = false, brakeDuring = false, corrections = 0, signalOffDelayS = null,
        entryKmh = 40, apexKmh = 0, exitKmh = 0, kind = MoveKind.STOP, peakAccelMs2 = 1.5f, recordedAt = at,
    )

    /** 시연 이야기: 깜빡이 한 번을 미리 켜면 과제 완료와 졸업이 동시에 오고, 졸업이 먼저입니다. */
    @Test
    fun theLiveDemoSignalGraduates() {
        val r = DemoHistory.build(now, seoul)
        val move = turn(r.moves.maxOf { it.id } + 1, early = true, at = now)
        assertEquals(DriveCue.Graduated, DriveCue.of(move, r.moves, r.moves + move, seoul))
    }

    /** 과제(깜빡이)는 잘할 때마다 연속 횟수만큼 깜빡이고, 다섯 번째에 완료합니다. 과제가 아닌 동작에는 조용합니다. */
    @Test
    fun onlyTheMissionSpeaks() {
        // 지난주: 깜빡이가 가장 약함(그래서 이번 주 과제), 정지는 잘함.
        val week = 7 * 86_400_000L
        val history = (1..6).map { turn(it, early = it % 2 == 0, at = now - week) } + (7..9).map { stop(it, now - week) }
        var moves = history
        var id = 10
        val cues = (1..5).map {
            val m = turn(id++, early = true, at = now + it)
            DriveCue.of(m, moves, moves + m, seoul).also { moves = moves + m }
        }
        assertEquals(listOf(DriveCue.Success(1), DriveCue.Success(2), DriveCue.Success(3), DriveCue.Success(4), DriveCue.MissionDone), cues)
        val s = stop(id++, now + 10)
        assertNull("과제가 아닌 정지는 조용히", DriveCue.of(s, moves, moves + s, seoul))
        val late = turn(id, early = false, at = now + 11)
        assertNull("아쉬운 동작도 조용히", DriveCue.of(late, moves, moves + late, seoul))
    }

    @Test
    fun colorsMixFromStickerYellowToGreen() {
        assertEquals(0xFFD43B, AmbientLight.mix(0xFFD43B, 0x00FF66, 0f))
        assertEquals(0x00FF66, AmbientLight.mix(0xFFD43B, 0x00FF66, 1f))
        assertEquals(0x7FE950, AmbientLight.mix(0xFFD43B, 0x00FF66, 0.5f))
    }
}
