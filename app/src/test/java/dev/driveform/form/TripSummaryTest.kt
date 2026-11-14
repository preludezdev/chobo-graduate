package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripSummaryTest {

    private fun move(
        id: Int, kind: MoveKind, tripId: Int?, at: Long = id * 60_000L,
        peak: Float = 1f, signalLeadM: Float? = 40f, corrections: Int = 0,
    ) = MoveForm(
        id = id, side = if (kind.isSpeedOnly) null else Side.LEFT, startMs = 0, endMs = 3000,
        steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = signalLeadM, signalAfterStart = false, brakeDuring = false,
        corrections = corrections, signalOffDelayS = null, entryKmh = 20, apexKmh = 15, exitKmh = 20,
        kind = kind, durationS = 3f, peakAccelMs2 = peak, recordedAt = at, tripId = tripId,
    )

    @Test
    fun scoreWeighsLossesAndGroupsAreSplit() {
        val moves = listOf(
            move(1, MoveKind.TURN, 1),                         // 100
            move(2, MoveKind.TURN, 1, signalLeadM = null),     // 깜빡이 안 켬 -30 → 70
            move(3, MoveKind.STOP, 1, peak = 5f),              // 급제동 -40 → 60
            move(4, MoveKind.START, 1),                        // 100
        )
        val s = TripSummary.of(Trip(1, 60_000L, 660_000L), moves)
        assertEquals(48, s.score)              // 깎인 점수 평균 (0+30+40+0)/4 = 17.5, 100 - 3×17.5 = 47.5
        assertEquals(Tier.PRACTICE, s.tier)
        assertEquals(2, s.goodCount)
        assertEquals(listOf(MoveGroup.TURN to 85, MoveGroup.STOP_START to 80), s.groups.map { it.group to it.avgScore })
        assertEquals(1, s.best!!.id)
        assertEquals(3, s.worst!!.id)
        assertEquals(10, s.minutes)
    }

    @Test
    fun biggestLossBecomesNextGoal() {
        val moves = listOf(
            move(1, MoveKind.TURN, 1, signalLeadM = null),
            move(2, MoveKind.TURN, 1, signalLeadM = null),
            move(3, MoveKind.TURN, 1, corrections = 1),
            move(4, MoveKind.STOP, 1, peak = 3.5f),
        )
        val w = TripSummary.of(Trip(1, 0L), moves).weaknesses
        assertEquals("회전·유턴 · 깜빡이", w.first().label)
        assertEquals(2, w.first().missed)
        assertEquals(3, w.first().of)
        assertEquals(listOf("회전·유턴 · 깜빡이", "회전·유턴 · 조향", "정지·출발 · 감속"), w.map { it.label })
    }

    @Test
    fun noWorstWhenEverythingIsGood() {
        val s = TripSummary.of(Trip(1, 0L), listOf(move(1, MoveKind.TURN, 1), move(2, MoveKind.STOP, 1)))
        assertNull(s.worst)
        assertEquals(Tier.MASTER, s.tier)
        assertEquals(emptyList<Weakness>(), s.weaknesses)
    }

    @Test
    fun comparesWithPreviousTripAndSkipsEmptyTrips() {
        val trips = listOf(Trip(1, 0L, 1L), Trip(2, 2L, 3L), Trip(3, 4L, 5L))
        val moves = listOf(
            move(1, MoveKind.STOP, 1, peak = 5f),   // 동작 60점 → 주행 100 - 3×40 = 0
            move(2, MoveKind.STOP, 3),              // 100
        )
        val all = TripSummary.all(trips, moves)
        assertEquals(listOf(1, 3), all.map { it.trip.id })
        assertEquals(0, all[1].previous!!.score)
        assertEquals(60, all[1].groups.single().previousAvg)
    }

    /** 주행 번호가 없던 옛 기록은 10분 넘게 비면 다른 주행으로 나눕니다. */
    @Test
    fun backfillSplitsOldRecordsByGap() {
        val moves = listOf(
            move(1, MoveKind.STOP, null, at = 0L),
            move(2, MoveKind.STOP, null, at = 5 * 60_000L),
            move(3, MoveKind.STOP, null, at = 30 * 60_000L),
            move(4, MoveKind.STOP, 7, at = 40 * 60_000L),
        )
        val (trips, filled) = Trips.backfill(listOf(Trip(7, 39 * 60_000L, 41 * 60_000L)), moves)
        assertEquals(listOf(8, 8, 9, 7), filled.map { it.tripId })
        assertEquals(listOf(7, 8, 9), trips.map { it.id })
        assertEquals(Trip(8, 0L, 5 * 60_000L), trips[1])
    }
}
