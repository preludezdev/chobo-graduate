package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntersectionsTest {

    private fun turn(id: Int, lat: Double, lon: Double, intersectionId: Int) = MoveForm(
        id = id, side = Side.LEFT, startMs = 0, endMs = 0, steering = emptyList(), speed = emptyList(),
        brakeSpans = emptyList(), signalOnMs = null, signalLeadM = null, signalAfterStart = false,
        brakeDuring = false, corrections = 0, signalOffDelayS = null, entryKmh = 0, apexKmh = 0, exitKmh = 0,
        lat = lat, lon = lon, intersectionId = intersectionId,
    )

    // 강남역 사거리 근처
    private val gangnam = turn(1, 37.49790, 127.02760, 1)

    @Test
    fun nearbyTurnJoinsSameIntersection() {
        // 약 22m 떨어진 지점
        assertEquals(1, Intersections.assign(37.49800, 127.02780, listOf(gangnam)))
    }

    @Test
    fun farTurnGetsNewIntersection() {
        // 약 110m 떨어진 지점
        assertEquals(2, Intersections.assign(37.49890, 127.02760, listOf(gangnam)))
    }

    @Test
    fun firstTurnStartsAtOne() {
        assertEquals(1, Intersections.assign(37.5, 127.0, emptyList()))
    }

    @Test
    fun noLocationMeansNoIntersection() {
        assertNull(Intersections.assign(null, null, listOf(gangnam)))
    }
}
