package dev.driveform.form

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

/**
 * 속도와 조향각으로 차의 방향 변화를 계산합니다(자전거 모델).
 * 조향비와 축거는 가정값입니다. 실차에서는 차종별 값으로 바꿔야 합니다.
 */
object Kinematics {
    const val STEERING_RATIO = 15f
    const val WHEELBASE_M = 3f

    /**
     * 속도 이벤트 사이 구간마다의 방향 변화량(라디안, 왼쪽이 +).
     * 조향 이벤트는 값이 바뀔 때만 오므로, 다음 이벤트 전까지는 직전 값이 유지된다고 봅니다.
     */
    fun yawSteps(steering: List<Point>, speed: List<Point>): FloatArray {
        if (speed.size < 2) return FloatArray(0)
        var si = 0
        var steer = 0f
        return FloatArray(speed.size - 1) { i ->
            while (si < steering.size && steering[si].ms <= speed[i].ms) steer = steering[si++].value
            val dt = (speed[i + 1].ms - speed[i].ms) / 1000f
            val wheel = Math.toRadians((steer / STEERING_RATIO).toDouble())
            (speed[i].value * tan(wheel) / WHEELBASE_M * dt).toFloat()
        }
    }

    /** [from]~[to] 사이에 차 방향이 얼마나 바뀌었는지(도). */
    fun headingChangeDeg(steering: List<Point>, speed: List<Point>, from: Long, to: Long): Float {
        val steps = yawSteps(steering, speed)
        var sum = 0.0
        for (i in steps.indices) if (speed[i].ms >= from && speed[i].ms < to) sum += steps[i]
        return Math.toDegrees(sum).toFloat()
    }

    /**
     * [from]~[to] 동안 시작할 때의 방향을 기준으로 차가 그린 길.
     * 차선 변경은 방향이 잠깐 조금(10° 안팎) 틀어졌다가 돌아오고 옆으로 차로 하나(3m 남짓)만큼 가지만,
     * 굽은 길을 따라간 것은 방향이 크게 틀어지고 옆으로도 훨씬 멀리 갑니다.
     */
    fun sway(steering: List<Point>, speed: List<Point>, from: Long, to: Long): Sway {
        val steps = yawSteps(steering, speed)
        var heading = 0.0
        var side = 0.0
        var widest = 0.0
        for (i in steps.indices) {
            if (speed[i].ms < from || speed[i].ms >= to) continue
            heading += steps[i]
            side += speed[i].value * sin(heading) * (speed[i + 1].ms - speed[i].ms) / 1000.0
            widest = maxOf(widest, abs(heading))
        }
        return Sway(Math.toDegrees(widest).toFloat(), Math.toDegrees(heading).toFloat(), side.toFloat())
    }

    /** [maxHeadingDeg]: 가장 크게 틀어진 방향, [endHeadingDeg]: 끝났을 때 방향, [sideM]: 옆으로 간 거리(왼쪽이 +). */
    data class Sway(val maxHeadingDeg: Float, val endHeadingDeg: Float, val sideM: Float)

    /** 각 시점의 가속도(m/s², 감속은 음수). 속도가 1km/h 단위라 [windowMs] 구간 차이로 부드럽게 봅니다. */
    fun accelerations(speed: List<Point>, windowMs: Long = 500): List<Point> =
        speed.mapIndexedNotNull { i, p ->
            val back = speed.subList(0, i).lastOrNull { p.ms - it.ms >= windowMs } ?: return@mapIndexedNotNull null
            Point(p.ms, (p.value - back.value) / ((p.ms - back.ms) / 1000f))
        }
}
