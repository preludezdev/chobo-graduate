package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimTest {
    private fun turn(id: Int, score: Int, place: Int) = MoveForm(
        id = id, side = Side.LEFT, startMs = 0, endMs = 3_000,
        steering = listOf(Point(0, 0f), Point(1_000, 200f)), speed = listOf(Point(0, 5f), Point(1_000, 5f)), brakeSpans = listOf(0L to 500L),
        signalOnMs = null, signalLeadM = 40f, signalAfterStart = false, brakeDuring = false,
        corrections = (100 - score) / 10, signalOffDelayS = null, entryKmh = 20, apexKmh = 15, exitKmh = 20, intersectionId = place,
    )

    /** 오래된 동작은 곡선만 지우고 점수는 그대로, 최근 것과 교차로별 최고 기록은 곡선을 남깁니다. */
    @Test
    fun oldCurvesAreDroppedButScoresStay() {
        val moves = (1..Slim.KEEP_DETAILED + 50).map { turn(it, if (it == 3) 100 else 80, it % 2) }
        val slim = Slim.of(moves)
        assertEquals(moves.map { it.score }, slim.map { it.score })
        assertTrue(slim.first { it.id == 3 }.speed.isNotEmpty()) // 교차로 최고 기록
        assertTrue(slim.first { it.id == 5 }.speed.isEmpty())
        assertTrue(slim.takeLast(Slim.KEEP_DETAILED).all { it.speed.isNotEmpty() })
        // 이미 가벼우면 같은 목록을 그대로 돌려줘 화면 계산 캐시가 깨지지 않습니다.
        assertSame(slim, Slim.of(slim))
    }
}
