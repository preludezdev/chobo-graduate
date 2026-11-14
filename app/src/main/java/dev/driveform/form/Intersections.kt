package dev.driveform.form

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 위치가 가까운 회전끼리 같은 교차로로 묶습니다. 주소 조회 없이 번호만 붙입니다. */
object Intersections {
    const val SAME_RADIUS_M = 40.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** 반경 안에 이미 기록된 교차로가 있으면 그 번호를, 없으면 새 번호를 줍니다. 위치가 없으면 null. */
    fun assign(lat: Double?, lon: Double?, history: List<MoveForm>): Int? {
        if (lat == null || lon == null) return null
        val nearest = history
            .filter { it.intersectionId != null && it.lat != null && it.lon != null }
            .map { it to distanceM(lat, lon, it.lat!!, it.lon!!) }
            .minByOrNull { it.second }
        if (nearest != null && nearest.second <= SAME_RADIUS_M) return nearest.first.intersectionId
        return (history.mapNotNull { it.intersectionId }.maxOrNull() ?: 0) + 1
    }
}
