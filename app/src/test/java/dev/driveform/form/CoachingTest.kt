package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CoachingTest {

    private val utc = ZoneOffset.UTC
    /** 2026-11-08은 일요일이라 이번 주는 11-02(월)부터입니다. */
    private val sunday = LocalDate.of(2026, 11, 8).atStartOfDay(utc).toInstant().toEpochMilli()
    private val lastWeek = LocalDate.of(2026, 10, 28).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun move(
        id: Int, kind: MoveKind, tripId: Int, at: Long,
        peak: Float = 1f, signalLeadM: Float? = 40f, corrections: Int = 0,
    ) = MoveForm(
        id = id, side = if (kind.isSpeedOnly) null else Side.LEFT, startMs = 0, endMs = 3000,
        steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = signalLeadM, signalAfterStart = false, brakeDuring = false,
        corrections = corrections, signalOffDelayS = null, entryKmh = 20, apexKmh = 15, exitKmh = 20,
        kind = kind, durationS = 3f, peakAccelMs2 = peak, recordedAt = at, tripId = tripId,
    )

    private fun coach(all: List<MoveForm>, tripId: Int): Coaching {
        val trips = all.groupBy { it.tripId!! }.map { (id, ms) -> Trip(id, ms.minOf { it.recordedAt }, ms.maxOf { it.recordedAt }) }
        val s = TripSummary.all(trips, all).first { it.trip.id == tripId }
        return Coaching.of(s, all, utc)
    }

    @Test
    fun praisesWhatWentWell() {
        val today = listOf(
            move(1, MoveKind.TURN, 1, sunday, signalLeadM = null),   // 핸들은 깔끔, 깜빡이 안 켬
            move(2, MoveKind.LANE_CHANGE, 1, sunday + 1, signalLeadM = null),
            move(3, MoveKind.STOP, 1, sunday + 2, peak = 5f),         // 급제동 -40
        )
        val c = coach(today, 1)
        assertEquals(Skill.STEER.praise(2), c.praise)
        assertNull(c.trend)
    }

    @Test
    fun streakCountsFromTheLatestMove() {
        val moves = listOf(
            move(1, MoveKind.START, 1, sunday),
            move(2, MoveKind.START, 1, sunday + 1),
            move(3, MoveKind.START, 1, sunday + 2),
            move(4, MoveKind.STOP, 1, sunday + 3, peak = 5f),
            move(5, MoveKind.START, 2, sunday + 4),
            move(6, MoveKind.STOP, 2, sunday + 5),
        )
        val smooth = coach(moves, 2).stats.first { it.skill == Skill.SMOOTH }
        assertEquals(2, smooth.streak)
        assertEquals(3, smooth.bestStreak)
        assertEquals(83, smooth.recent)          // 6번 중 5번
        assertEquals(2, smooth.tripOk)
    }

    /** 미션은 지난주까지 가장 약했던 기술이고, 진행은 이번 주 기록만 셉니다. */
    @Test
    fun missionFromBeforeThisWeek() {
        val moves = listOf(
            move(1, MoveKind.TURN, 1, lastWeek, signalLeadM = null),
            move(2, MoveKind.TURN, 1, lastWeek + 1, signalLeadM = 10f),
            move(3, MoveKind.TURN, 1, lastWeek + 2),
            move(4, MoveKind.STOP, 1, lastWeek + 3),
            move(5, MoveKind.STOP, 1, lastWeek + 4),
            move(6, MoveKind.STOP, 1, lastWeek + 5),
            move(7, MoveKind.TURN, 2, sunday),
            move(8, MoveKind.STOP, 2, sunday + 1, peak = 5f),
        )
        val m = coach(moves, 2).mission
        assertEquals(Skill.SIGNAL, m.skill)
        assertEquals(listOf(true), m.attempts)
        assertEquals(1, m.done)
    }

    /** 미션은 연속 성공으로 셉니다. 한 번 놓치면 처음부터, 한 번 해내면 그 주는 완료입니다. */
    @Test
    fun missionCountsARun() {
        val m = Mission(Skill.SIGNAL, listOf(true, true, false, true, true, true, true))
        assertEquals(4, m.run)
        assertEquals(4, m.done)
        assertEquals(false, m.completed)
        val done = Mission(Skill.SIGNAL, listOf(true, true, true, true, true, false))
        assertEquals(0, done.run)
        assertEquals(true, done.completed)
        assertEquals(5, done.done)
    }

    @Test
    fun softPhrasesSayWhatHappened() {
        val noSignal = move(1, MoveKind.LANE_CHANGE, 1, sunday, signalLeadM = null)
        assertEquals("왼쪽 차선 변경에서 깜빡이를 켜지 않았어요", Coaching.soft(noSignal, "깜빡이"))
        val wrong = noSignal.copy(signalWrongSide = true)
        assertEquals("왼쪽 차선 변경에서 반대쪽 깜빡이를 켰어요", Coaching.soft(wrong, "깜빡이"))
    }
}
