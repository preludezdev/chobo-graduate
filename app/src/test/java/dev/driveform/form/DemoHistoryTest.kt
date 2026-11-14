package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DemoHistoryTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun build(at: LocalDateTime) = DemoHistory.build(at.atZone(seoul).toInstant().toEpochMilli(), seoul)

    /** 대본의 동작마다 검출기가 같은 종류로, 같은 판정(잘함/아쉬움)으로 잡아야 시연 기록을 믿을 수 있습니다. */
    @Test
    fun detectorAgreesWithTheScript() {
        val r = build(LocalDateTime.of(2026, 11, 8, 21, 0))
        assertEquals("대본에 없던 동작이 잡혔습니다", r.moves.size, r.intended.size)
        val wrong = r.moves.mapNotNull { m ->
            val (kind, skills) = r.intended.getValue(m.id)
            val judged = skills.mapValues { (skill, _) -> skill.judge(m) }
            if (m.kind == kind && judged == skills) null
            else "#${m.id} ${m.kind}(대본 $kind) ${m.checks.map { it.detail }} 판정 $judged 대본 $skills"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun storyForTheDemo() {
        val r = build(LocalDateTime.of(2026, 11, 8, 21, 0))
        val summaries = TripSummary.all(r.trips, r.moves)
        val last = summaries.last()
        val c = Coaching.of(last, r.moves, seoul)

        // 지난주까지 가장 약했던 깜빡이가 미션이고, 4번 연속 성공 중이라 시연 주행에서 완료할 수 있습니다.
        assertEquals(Skill.SIGNAL, c.mission.skill)
        assertEquals(4, c.mission.run)
        assertFalse(c.mission.completed)

        // 마지막 주행은 '능숙'으로 고수가 눈앞이고, 다음 목표는 정지 감속입니다. 첫 주보다 점수가 크게 올랐습니다.
        assertEquals("${last.score}", Tier.SKILLED, last.tier)
        assertEquals("정지·출발 · 감속", last.weaknesses.first().label)
        val firstWeek = summaries.take(5).map { it.score }.average()
        val lastWeek = summaries.takeLast(5).map { it.score }.average()
        assertTrue("$firstWeek → $lastWeek", lastWeek - firstWeek >= 10)

        // 실제 출퇴근처럼 보이는 길이(15분 넘게, 수 km).
        val commute = summaries.first { it.trip.endedAt != null && it.moves.size > 10 }
        assertTrue("${commute.minutes}", (commute.minutes ?: 0) >= 15)
        assertTrue("${commute.trip.distanceM}", (commute.trip.distanceM ?: 0.0) > 6_000)

        // 매일 아침 같은 신흥동 사거리 좌회전을 지나 비교할 기록이 쌓입니다.
        val sinheung = r.moves.filter { it.kind == MoveKind.TURN && it.side == Side.LEFT && it.place == "수정구 신흥동" }
        assertTrue(sinheung.size >= 8)
        assertEquals(1, sinheung.mapNotNull { it.intersectionId }.distinct().size)
    }

    /** 월요일 아침에 준비해도 이번 주 기록이 이야기와 맞습니다. */
    @Test
    fun storyHoldsOnAMonday() {
        val r = build(LocalDateTime.of(2026, 11, 9, 9, 30))
        val last = TripSummary.all(r.trips, r.moves).last()
        val c = Coaching.of(last, r.moves, seoul)
        assertEquals(Skill.SIGNAL, c.mission.skill)
        assertEquals(4, c.mission.run)
    }
}
