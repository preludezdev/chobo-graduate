package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 실제 도로에서 만나는 애매한 상황들. 원칙은 "애매하면 채점하지 않는다"입니다.
 * 칭찬을 한 번 놓치는 건 괜찮지만, 잘한 운전을 혼내면 초보는 앱을 지웁니다.
 */
class RoadVariantsTest {

    /** 0.1초마다 신호를 보내는 작은 운전자. */
    private class Car {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        var t = 0L
        var kmh = 0f
        var steer = 0f

        fun tick(n: Int = 1) = repeat(n) { t += 100; d.onSpeed(t, kmh.roundToInt() / 3.6f) }
        fun gear(g: Gear) { d.onGear(t, g); tick(3) }
        fun speed(to: Float) { while (kmh != to) { kmh = if (kmh < to) minOf(to, kmh + 0.5f) else maxOf(to, kmh - 0.5f); tick() } }
        fun steerTo(deg: Float, ticks: Int = 10) {
            val from = steer
            for (i in 1..ticks) { steer = from + (deg - from) * i / ticks; d.onSteering(t, steer); tick() }
        }
        val turns get() = moves.filter { it.kind.isTurn }
        val lanes get() = moves.filter { it.kind == MoveKind.LANE_CHANGE }
    }

    /** 지하 주차장에서 시속 12km로 통로를 두 번 꺾어 나와 도로에 오름: 주차장 안 회전은 깜빡이 채점에서 뺍니다. */
    @Test
    fun turnsWhileLeavingTheGarageAreNotGraded() {
        val c = Car()
        c.gear(Gear.P)
        c.gear(Gear.D)
        repeat(2) {
            c.speed(12f); c.tick(20)
            c.steerTo(300f, 12); c.tick(15); c.steerTo(0f, 12)
            c.tick(20)
        }
        c.speed(45f); c.tick(50)
        assertTrue(c.turns.isEmpty())
    }

    /** 도로를 달리다 지하 4층까지 나선 램프로 내려가 주차: 램프와 통로 회전 모두 채점하지 않고 주차만 남습니다. */
    @Test
    fun spiralDownIntoTheGarageLeavesOnlyThePark() {
        val c = Car()
        c.gear(Gear.D)
        c.speed(40f); c.tick(50)
        c.speed(15f)
        repeat(4) { // 한 층에 한 바퀴 가까이
            c.steerTo(320f, 15); c.tick(110); c.steerTo(0f, 15); c.tick(20)
        }
        c.steerTo(-280f, 12); c.tick(15); c.steerTo(0f, 12) // 통로에서 꺾기
        c.speed(5f); c.steerTo(200f); c.tick(15); c.steerTo(0f); c.speed(0f)
        c.gear(Gear.R)
        c.speed(4f); c.steerTo(-450f); c.tick(25); c.steerTo(0f); c.tick(5); c.speed(0f)
        c.gear(Gear.P)
        assertTrue(c.turns.isEmpty())
        assertEquals(listOf(MoveKind.PARK), c.moves.filter { !it.kind.isSpeedOnly || it.kind == MoveKind.PARK }.map { it.kind })
    }

    /** 시속 22km로 한 방향으로 계속 돌아 방향이 270° 넘게 바뀜(나선 램프, 회전교차로): 회전으로 보지 않습니다. */
    @Test
    fun aLoopIsNotATurn() {
        val c = Car()
        c.gear(Gear.D)
        c.speed(22f)
        c.steerTo(200f, 15); c.tick(130); c.steerTo(0f, 15)
        c.speed(40f); c.tick(30)
        assertTrue(c.turns.isEmpty())
    }

    /** 같은 속도로 한 번만 꺾으면(약 90°) 여전히 회전입니다. */
    @Test
    fun anOrdinaryTurnAtTheSameSpeedStillCounts() {
        val c = Car()
        c.gear(Gear.D)
        c.speed(22f)
        c.steerTo(200f, 15); c.tick(25); c.steerTo(0f, 15)
        c.speed(40f); c.tick(30)
        assertEquals(1, c.turns.size)
    }

    /** 시속 50km로 S자 커브를 따라감: 핸들 모양은 차선 변경과 같지만 차가 크게 돌았다 돌아왔으니 차선 변경이 아닙니다. */
    @Test
    fun anSBendIsNotALaneChange() {
        val c = Car()
        c.gear(Gear.D)
        c.speed(50f); c.tick(30)
        c.steerTo(40f, 10); c.tick(25)
        c.steerTo(-40f, 20); c.tick(25)
        c.steerTo(0f, 10); c.tick(30)
        assertTrue(c.lanes.isEmpty())
        assertTrue(c.turns.isEmpty())
    }

    /** 같은 속도에서 짧게 차로 하나만 옮기면 여전히 차선 변경입니다. */
    @Test
    fun aRealLaneChangeStillCounts() {
        val c = Car()
        c.gear(Gear.D)
        c.speed(50f); c.tick(30)
        c.steerTo(10f, 8); c.tick(4)
        c.steerTo(-10f, 10); c.tick(4)
        c.steerTo(0f, 6); c.tick(30)
        assertEquals(1, c.lanes.size)
    }
}
