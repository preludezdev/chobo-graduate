package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GraduationTest {

    /** 깜빡이를 켰으면(40m 전) 잘함, 안 켰으면 아쉬움인 좌회전. 핸들은 늘 깔끔합니다. */
    private fun turn(id: Int, signal: Boolean, at: Long = id * 1000L) = MoveForm(
        id = id, side = Side.LEFT, startMs = 0, endMs = 3000,
        steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = if (signal) 40f else null, signalAfterStart = false, brakeDuring = false,
        corrections = 0, signalOffDelayS = null, entryKmh = 20, apexKmh = 15, exitKmh = 20,
        durationS = 3f, recordedAt = at, tripId = 1,
    )

    private fun signal(g: Graduation) = g.courses.first { it.skill == Skill.SIGNAL }

    @Test
    fun passesAtNineOfTheLastTen() {
        // 잘함 8번 + 아쉬움 2번: 아직. 다음에 잘하면 맨 앞의 아쉬움이 빠져 최근 열 번 중 아홉 번이라 이수.
        val pattern = listOf(false, true, true, true, false, true, true, true, true, true)
        val moves = pattern.mapIndexed { i, ok -> turn(i + 1, ok) }
        assertFalse(signal(Graduation.of(moves)).passed)
        assertEquals(1, signal(Graduation.of(moves)).toGo)
        val next = moves + turn(11, true)
        assertEquals(11_000L, signal(Graduation.of(next)).passedAt)
    }

    @Test
    fun aPassedCourseStaysPassed() {
        val moves = (1..9).map { turn(it, true) } + (10..20).map { turn(it, false) }
        val c = signal(Graduation.of(moves))
        assertTrue(c.passed)
        assertEquals(0, c.ok)
        assertEquals(1f, c.progress)
    }

    /**
     * 시연 이야기: 깜빡이만 남았고(최근 열 번 중 8번), 시연 주행에서 깜빡이를 한 번 미리 켜면 졸업합니다.
     * 시연 기록은 넣는 시각 기준으로 만들어지므로, 어느 요일·시각에 넣어도 이야기가 같아야 합니다.
     */
    @Test
    fun theDemoGraduatesOnTheLiveDrive() {
        val seoul = ZoneId.of("Asia/Seoul")
        for (day in 9..15) for (hour in listOf(0, 9, 13, 21)) {
            val at = LocalDateTime.of(2026, 11, day, hour, 30)
            val r = DemoHistory.build(at.atZone(seoul).toInstant().toEpochMilli(), seoul)
            val g = Graduation.of(r.moves)
            assertEquals("$at", listOf(Skill.SIGNAL), g.remaining.map { it.skill })
            assertEquals("$at", 1, g.remaining.first().toGo)

            val last = r.moves.maxOf { it.id }
            val time = r.moves.maxOf { it.recordedAt }
            assertTrue("$at", Graduation.of(r.moves + turn(last + 1, true, time + 60_000L)).graduated)
            assertFalse("$at", Graduation.of(r.moves + turn(last + 1, false, time + 60_000L)).graduated)
        }
    }

    /** 시연 주행을 붙인 기록은 그 주행에서 졸업합니다(콘솔 없이 졸업 장면을 보는 용도). */
    @Test
    fun theFinaleGraduatesOnItsLastTrip() {
        val seoul = ZoneId.of("Asia/Seoul")
        val r = DemoHistory.build(LocalDateTime.of(2026, 11, 12, 21, 0).atZone(seoul).toInstant().toEpochMilli(), seoul, finale = true)
        val g = Graduation.of(r.moves)
        assertTrue(g.graduated)
        val lastTrip = r.trips.last()
        assertTrue(g.graduatedAt!! >= lastTrip.startedAt)
        assertFalse(Graduation.of(r.moves.filter { it.tripId != lastTrip.id }).graduated)
    }
}
