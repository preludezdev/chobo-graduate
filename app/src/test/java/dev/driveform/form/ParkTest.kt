package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

class ParkTest {

    private val seoul = ZoneId.of("Asia/Seoul")

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

        /** 시속 30km로 와서 멈춘 뒤 주차를 시작합니다. */
        fun arrive() { gear(Gear.D); speed(30f); tick(30); speed(0f); tick(5) }
        fun forward(deg: Float) { speed(5f); steerTo(deg); tick(15); steerTo(0f); speed(0f) }
        fun backward(kmh: Float = 4f, endDeg: Float = 0f) { speed(kmh); steerTo(-450f); tick(25); steerTo(endDeg); tick(5); speed(0f) }
        fun park(): MoveForm? { gear(Gear.P); return moves.lastOrNull { it.kind == MoveKind.PARK } }
    }

    @Test
    fun oneSmoothReverseIsOneTry() {
        val c = Car()
        c.arrive()
        c.forward(200f)
        c.gear(Gear.R)
        c.backward()
        val p = c.park()!!
        assertEquals(1, p.tries)
        assertEquals(4, p.apexKmh)
        assertTrue(p.checks.all { it.ok })
        assertEquals(true, Skill.PARK.judge(p))
        // 주차하려고 돌린 핸들은 회전으로 남지 않습니다.
        assertTrue(c.moves.none { it.kind.isTurn })
    }

    @Test
    fun backAndForthCountsTriesAndHabits() {
        val c = Car()
        c.arrive()
        c.forward(200f)
        c.gear(Gear.R)
        c.steerTo(-450f, 12) // 멈춘 채 핸들
        c.backward(kmh = 9f)
        c.gear(Gear.D)
        c.forward(150f)
        c.gear(Gear.R)
        c.backward(endDeg = -200f) // 바퀴가 꺾인 채
        val p = c.park()!!
        assertEquals(2, p.tries)
        val failed = p.checks.filter { !it.ok }.map { it.title }.toSet()
        assertEquals(setOf("넣기", "후진 속도", "바퀴 정렬"), failed) // 제자리 핸들은 초보에게 허용
        assertTrue(p.dryTurnDeg >= 450)
        assertEquals(-200, p.finalSteerDeg)
        assertEquals(2, p.reverseSpans.size)
        assertEquals("주차를 2번 만에 넣었어요", Coaching.soft(p, "넣기"))
        // 경로: 뒤로 간 점이 있고, 방향을 바꾼 곳이 표시됩니다.
        val path = DrivePath.of(p)
        assertTrue(path.reverse.any { it } && path.reverse.any { !it })
        assertTrue(path.switches.size >= 2)
        assertTrue(path.dryAt != null)
    }

    /** 아주 느리게 꺾고 그대로 세우면(앞으로 넣는 주차, 골목 끝) 주차는 아니지만, 주차장 안에서 돈 것으로 보고 깜빡이를 채점하지 않습니다. */
    @Test
    fun slowTurnThenParkIsNotGraded() {
        val c = Car()
        c.arrive()
        c.forward(200f)
        assertNull(c.park())
        assertTrue(c.moves.none { it.kind.isTurn })
    }

    /** 도로를 달리다 느리게 꺾은 회전은 다시 도로 속도(시속 30km)에 오를 때 나옵니다. */
    @Test
    fun slowTurnIsReleasedOnceTheCarIsBackAtRoadSpeed() {
        val c = Car()
        c.gear(Gear.D)
        c.forward(200f)
        assertTrue(c.moves.none { it.kind.isTurn })
        c.speed(15f)
        assertTrue(c.moves.none { it.kind.isTurn })
        c.speed(35f)
        assertEquals(1, c.moves.count { it.kind == MoveKind.TURN })
    }

    @Test
    fun wetAndNightUseTheBasicsInHarderConditions() {
        val stop = MoveForm(
            id = 1, side = null, startMs = 0, endMs = 5_000, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
            signalOnMs = null, signalLeadM = null, signalAfterStart = false, brakeDuring = false, corrections = 0, signalOffDelayS = null,
            entryKmh = 40, apexKmh = 0, exitKmh = 0, kind = MoveKind.STOP, peakAccelMs2 = 2.0f,
        )
        assertNull(Skill.WET.judge(stop))
        assertEquals(true, Skill.WET.judge(stop.copy(weather = Weather.RAIN)))
        // 맑은 날엔 부드러운 감속도 빗길에선 세게 멈춘 것: 빗길 과목에선 아쉬움입니다.
        assertEquals(false, Skill.WET.judge(stop.copy(weather = Weather.RAIN, peakAccelMs2 = 2.7f)))
        assertNull(Skill.NIGHT.judge(stop))
        assertEquals(true, Skill.NIGHT.judge(stop.copy(night = true)))
        assertEquals(false, Skill.WET.judge(stop.copy(kind = MoveKind.SLIP, weather = Weather.RAIN)))
    }

    /** 시연 기록: 출퇴근마다 주차하고, 둘째 주에 나아지며, 아직 이수 전이고, 마지막은 두 번 만에. */
    @Test
    fun demoParksEveryCommuteAndImproves() {
        val now = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli()
        val r = DemoHistory.build(now, seoul)
        val parks = r.moves.filter { it.kind == MoveKind.PARK }
        // 출퇴근(주말 장보기 빼고)마다 한 번씩.
        assertTrue("${parks.size}", parks.size >= 18)
        assertTrue(r.trips.filter { t -> parks.any { it.tripId == t.id } }.all { t -> parks.count { it.tripId == t.id } == 1 })
        val half = parks.size / 2
        assertTrue(parks.take(half).map { it.tries }.average() > parks.drop(half).map { it.tries }.average())
        assertEquals(3, parks.last().tries) // 시연용: 최근 주차는 앞뒤로 세 번
        val adv = Graduation.of(r.moves, Skill.ADVANCED)
        assertFalse(adv.courses.first { it.skill == Skill.PARK }.passed)
        // 집 주차장과 회사 주차장, 두 자리로 묶입니다.
        assertEquals(2, parks.mapNotNull { it.intersectionId }.distinct().size)
        // 졸업 전이라 이번 주 과제는 여전히 초보 과목입니다.
        val last = TripSummary.all(r.trips, r.moves).last()
        assertEquals(Skill.SIGNAL, Coaching.of(last, r.moves, seoul).mission.skill)
    }

    /** 졸업한 다음 주엔 심화 과목 중 고를 수 있는 주차가 과제가 됩니다. */
    @Test
    fun afterGraduationTheMissionIsParking() {
        val now = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli()
        val r = DemoHistory.build(now, seoul, finale = true)
        assertTrue(Graduation.of(r.moves).graduated)
        val nextWeek = LocalDateTime.of(2026, 11, 17, 8, 0).atZone(seoul).toInstant().toEpochMilli()
        assertEquals(Skill.PARK, Coaching.missionAt(nextWeek, r.moves, seoul).skill)
    }

    private fun goodPark(id: Int, at: Long) = MoveForm(
        id = id, side = null, startMs = 0, endMs = 20_000, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = null, signalAfterStart = false, brakeDuring = false, corrections = 0, signalOffDelayS = null,
        entryKmh = 5, apexKmh = 4, exitKmh = 0, kind = MoveKind.PARK, recordedAt = at,
    )

    /** 한 번에 깔끔하게 넣으면 과제(깜빡이)와 상관없이 P에 둔 순간 알려 줍니다. */
    @Test
    fun aCleanParkIsCheered() {
        val now = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli()
        val history = DemoHistory.build(now, seoul).moves
        val park = goodPark(history.maxOf { it.id } + 1, now)
        assertEquals(DriveCue.Parked, DriveCue.of(park, history, history + park, seoul))
        val sloppy = park.copy(corrections = 2)
        assertNull(DriveCue.of(sloppy, history, history + sloppy, seoul))
    }

    /** 주차를 열 번 중 아홉 번 잘하는 순간 배지를 받습니다. */
    @Test
    fun ninthCleanParkEarnsTheBadge() {
        val base = LocalDateTime.of(2026, 11, 2, 9, 0).atZone(seoul).toInstant().toEpochMilli()
        val before = (1..8).map { goodPark(it, base + it * 3_600_000L) }
        val ninth = goodPark(9, base + 9 * 3_600_000L)
        assertEquals(DriveCue.Badge(Skill.PARK), DriveCue.of(ninth, before, before + ninth, seoul))
    }

    /** 배지 시연: 시연 주행의 주차가 딱 아홉 번째라 그 주행에서 졸업과 주차 배지를 함께 받습니다. */
    @Test
    fun badgeDemoEarnsParkingOnTheFinale() {
        val now = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli()
        val r = DemoHistory.build(now, seoul, finale = true, badge = true)
        val lastTrip = r.trips.last().id
        val before = r.moves.filter { it.tripId != lastTrip }
        assertFalse(Graduation.of(before, Skill.ADVANCED).courses.first { it.skill == Skill.PARK }.passed)
        val park = Graduation.of(r.moves, Skill.ADVANCED).courses.first { it.skill == Skill.PARK }
        assertEquals(r.moves.last { it.kind == MoveKind.PARK }.recordedAt, park.passedAt)
        assertTrue(Graduation.of(r.moves).graduated)
    }
}
