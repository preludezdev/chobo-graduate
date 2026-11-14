package dev.driveform.form

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 시연용 주행 기록. 성남 수정구 집에서 분당 정자동 회사로 2주 동안 출퇴근하며 실력이 느는 초보 운전자를 대본으로 씁니다.
 * 대본대로 만든 속도·조향·브레이크·깜빡이 신호를 실제 [MoveDetector]에 흘려 넣으므로, 동작 판정과 점수, 경로 그림은
 * 실제 주행과 같은 계산을 거칩니다. 디버그 빌드의 시연 준비에만 씁니다.
 *
 * 이야기:
 * - 처음엔 깜빡이를 자주 잊고, 2주 동안 모든 기술이 조금씩 좋아집니다. 지난주까지 가장 약했던 깜빡이가 이번 주 미션이 됩니다.
 * - 마지막 주행은 '능숙'(고수까지 몇 점)이고, 신호등 앞 정지가 조금 셌던 것이 다음 목표입니다.
 * - 마지막 주행은 깜빡이를 한 번 늦게 켠 뒤 4번 연속 미리 켜서, 시연 주행에서 한 번 더 잘하면 미션이 완료됩니다.
 * - 매일 아침 같은 신흥동 사거리에서 좌회전해서, 같은 교차로의 지난 기록과 비교할 수 있습니다.
 * - 졸업 과목은 깜빡이만 남아 있고(최근 열 번 중 8번), 시연 주행에서 깜빡이를 한 번 미리 켜면 초보 졸업입니다.
 * - 출퇴근 끝마다 집·회사 주차장에서 후진 주차를 합니다. 첫 주엔 서너 번 만에 넣고 멈춘 채 핸들을 돌리다가, 둘째 주엔 한두 번 만에 넣습니다.
 *   주차(심화 과목)는 아직 이수 전이고, 마지막 주행은 두 번 만에 넣어서 시연 주행에서 한 번에 넣으면 나아진 게 보입니다.
 */
object DemoHistory {

    data class Result(
        val trips: List<Trip>,
        val moves: List<MoveForm>,
        /** 대본에서 기대한 판정: 동작 번호 → (동작 종류, 기술별 잘했는지). 테스트가 대본과 검출 결과를 맞춰 봅니다. */
        val intended: Map<Int, Pair<MoveKind, Map<Skill, Boolean>>>,
    )

    enum class Q { GOOD, SOSO, BAD }
    enum class Sig { EARLY, LATE, NONE, AFTER, WRONG }

    /** 시연 장소. 교차로 좌표는 실제 OSM 교차로(assets/demo_map.json)에 맞췄습니다. */
    class Spot(val place: String, val lat: Double, val lon: Double)

    sealed interface Step
    data class Place(val name: String) : Step
    data class Drive(val kmh: Int, val seconds: Int) : Step
    data class Start(var q: Q) : Step
    data class Stop(var q: Q, val waitS: Int) : Step
    data class Turn(val side: Side, val spot: Spot, var signal: Sig, var steer: Q, var lateOff: Boolean = false, val uTurn: Boolean = false) : Step
    data class Lane(val side: Side, var signal: Sig, var steer: Q, var lateOff: Boolean = false) : Step
    data class Road(val kind: MoveKind, var q: Q) : Step
    /** 빗길에서 바퀴가 미끄러져 ESC가 잡아 준 순간. */
    data object Slip : Step
    /**
     * 후진 주차. [tries]번 만에 넣고, [fast]면 후진이 조금 빠르고, [dry]면 멈춘 채 핸들을 돌리고,
     * [crooked]면 바퀴가 꺾인 채로 세웁니다.
     */
    data class Park(val spot: Spot, var tries: Int, var fast: Boolean = false, var dry: Boolean = false, var crooked: Boolean = false) : Step {
        val good: Boolean get() = tries == 1 && !fast && !crooked // 멈춘 채 핸들(dry)은 아쉬운 점이 아닙니다
    }

    private val A = Spot("수정구 시흥동", 37.43764, 127.12718)
    private val B = Spot("수정구 신흥동", 37.44176, 127.14651)
    private val C = Spot("분당구 정자동", 37.36663, 127.10805)
    private val D = Spot("수정구 태평동", 37.44407, 127.13420)
    /** 집과 회사 주차장. 교차로와는 따로 묶입니다. */
    private val HOME_LOT = Spot("수정구 시흥동", 37.43862, 127.12701)
    private val WORK_LOT = Spot("분당구 정자동", 37.36571, 127.10872)

    private class Plan(
        val date: LocalDate, val time: LocalTime, val steps: List<Step>,
        val weather: Weather = Weather.CLEAR,
        /** 비·안개 속에서 전조등 없이 달린 시간(초). */
        val lightsOffS: Int = 0,
    )

    /**
     * [finale]면 이야기 끝에 '시연 주행'을 하나 더 붙입니다: 10분 전, 집 앞 우회전 하나를 깜빡이를 미리 켜고 한 짧은 주행.
     * 이 주행으로 깜빡이가 이수되어 졸업하므로, 콘솔로 운전하지 않고도 졸업 장면(스티커 떼기)을 보거나 다시 찍을 수 있습니다.
     */
    /**
     * [badge]면(시연 주행과 함께) 그 시연 주행의 주차로 주차 배지까지 받습니다: 앞의 여덟 번을 한 번에 넣은 걸로 두고,
     * 그 앞 두 번은 고쳐 넣은 걸로 둬서 시연 주행에서 딱 아홉 번째가 됩니다. 졸업 뒤 심화 홈의 배지 축하를 보여 줄 때 씁니다.
     */
    fun build(now: Long, zone: ZoneId = ZoneId.systemDefault(), seed: Int = 11, finale: Boolean = false, badge: Boolean = false): Result {
        val rnd = Random(seed)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(DAYS - 1L)

        val plans = mutableListOf<Plan>()
        for (i in 0 until DAYS) {
            val date = first.plusDays(i.toLong())
            val x = i / (DAYS - 1f)
            val q = Quality(rnd, x)
            when (date.dayOfWeek) {
                DayOfWeek.SATURDAY -> plans += Plan(date, LocalTime.of(14, 5 + rnd.nextInt(20)), errand(q))
                DayOfWeek.SUNDAY -> plans += Plan(date, LocalTime.of(10, 10 + rnd.nextInt(25)), toWork(q, school = false))
                else -> {
                    plans += Plan(date, LocalTime.of(7, 30 + rnd.nextInt(20)), toWork(q, school = date.dayOfWeek.value % 2 == 1))
                    plans += Plan(date, LocalTime.of(18, 25 + rnd.nextInt(30)), toHome(q))
                }
            }
        }
        // 시연할 때보다 나중 시각의 주행은 넣지 않습니다.
        plans.removeAll { it.date.atTime(it.time).atZone(zone).toInstant().toEpochMilli() > now - LATEST_GAP_MS }
        shapeStory(plans, today)
        // 시연에서 볼 최근 주차 두 번은 각을 못 맞춰 앞뒤로 세 번 오가며 넣습니다(초보에게 흔한 장면).
        plans.flatMap { it.steps }.filterIsInstance<Park>().takeLast(2).forEach { it.tries = 3; it.dry = false; it.fast = false }
        addWeather(plans, today)
        if (finale) {
            val at = java.time.Instant.ofEpochMilli(now - 10 * 60_000L).atZone(zone)
            plans += Plan(
                at.toLocalDate(), at.toLocalTime(),
                listOf(
                    Place(A.place), Start(Q.GOOD), Drive(30, 30), Turn(Side.RIGHT, A, Sig.EARLY, Q.GOOD), Drive(20, 20), Stop(Q.GOOD, 0),
                    Park(HOME_LOT, 1),
                ),
            )
            if (badge) {
                val parks = plans.flatMap { it.steps }.filterIsInstance<Park>()
                parks.takeLast(Course.PASS).forEach { it.tries = 1; it.fast = false; it.dry = false; it.crooked = false }
                parks.dropLast(Course.PASS).takeLast(2).forEach { it.tries = 3 }
            }
        }
        return simulate(plans, zone)
    }

    /** 실력이 늘어 가는 정도 [x](0~1)에 따라 기술마다 잘할 확률을 높입니다. */
    private class Quality(private val rnd: Random, private val x: Float) {
        private fun q(p: Float): Q {
            val r = rnd.nextFloat()
            return when {
                r < p -> Q.GOOD
                r < p + (1 - p) * 0.65f -> Q.SOSO
                else -> Q.BAD
            }
        }
        fun signal(): Sig {
            val r = rnd.nextFloat()
            val p = 0.3f + 0.55f * x
            return when {
                r < p -> Sig.EARLY
                r < p + (1 - p) * 0.5f -> Sig.LATE
                r < p + (1 - p) * 0.8f -> Sig.NONE
                r < p + (1 - p) * 0.95f -> Sig.AFTER
                else -> Sig.WRONG
            }
        }
        fun steer() = q(0.62f + 0.3f * x)
        fun smooth() = q(0.6f + 0.32f * x)
        fun road() = q(0.55f + 0.35f * x)
        fun lateOff() = rnd.nextFloat() < 0.15f * (1 - x)

        /** 첫 주엔 서너 번 만에, 둘째 주엔 대개 한두 번 만에 넣습니다. */
        fun park(spot: Spot): Park {
            val r = rnd.nextFloat()
            val one = 0.15f + 0.6f * x
            val tries = when {
                r < one -> 1
                r < one + (1 - one) * 0.55f -> 2
                r < one + (1 - one) * 0.85f -> 3
                else -> 4
            }
            return Park(
                spot, tries,
                fast = rnd.nextFloat() < 0.35f * (1 - x),
                dry = rnd.nextFloat() < 0.6f * (1 - x),
                crooked = rnd.nextFloat() < 0.3f * (1 - x),
            )
        }
    }

    /** 집 → 회사(약 20분). 신흥동 사거리 좌회전이 매일 아침 나옵니다. */
    private fun toWork(q: Quality, school: Boolean) = listOfNotNull(
        Place(A.place), Start(q.smooth()), Drive(25, 30), Road(MoveKind.SPEED_BUMP, q.road()), Drive(30, 20),
        Turn(Side.RIGHT, A, q.signal(), q.steer(), q.lateOff()), Drive(40, 150), Stop(q.smooth(), 45), Start(q.smooth()),
        Turn(Side.LEFT, B, q.signal(), q.steer(), q.lateOff()),
        Place("중원구 여수동"), Drive(50, 200), Lane(Side.LEFT, q.signal(), q.steer(), q.lateOff()), Drive(55, 60),
        Road(MoveKind.CAMERA, q.road()), Drive(55, 280), Lane(Side.RIGHT, q.signal(), q.steer(), q.lateOff()),
        Place("분당구 수내동"), if (school) Road(MoveKind.SCHOOL_ZONE, q.road()) else null,
        Drive(45, 150), Stop(q.smooth(), 35), Start(q.smooth()), Drive(45, 180),
        Place(C.place), Turn(Side.RIGHT, C, q.signal(), q.steer(), q.lateOff()), Drive(20, 25), Stop(q.smooth(), 0),
        q.park(WORK_LOT),
    )

    /** 회사 → 집. 아침과 같은 교차로를 반대 방향으로 지납니다. */
    private fun toHome(q: Quality) = listOf(
        Place(C.place), Start(q.smooth()), Drive(20, 25), Turn(Side.LEFT, C, q.signal(), q.steer(), q.lateOff()),
        Place("분당구 수내동"), Drive(45, 200), Stop(q.smooth(), 40), Start(q.smooth()), Drive(50, 120),
        Lane(Side.LEFT, q.signal(), q.steer(), q.lateOff()), Place("중원구 여수동"), Drive(55, 300),
        Road(MoveKind.CAMERA, q.road()), Drive(50, 150), Lane(Side.RIGHT, q.signal(), q.steer(), q.lateOff()), Drive(45, 100),
        Place(B.place), Turn(Side.RIGHT, B, q.signal(), q.steer(), q.lateOff()), Drive(40, 150), Stop(q.smooth(), 30), Start(q.smooth()),
        Place(A.place), Drive(30, 40), Road(MoveKind.SPEED_BUMP, q.road()), Drive(30, 20),
        Turn(Side.LEFT, A, q.signal(), q.steer(), q.lateOff()), Drive(15, 15), Stop(q.smooth(), 0),
        q.park(HOME_LOT),
    )

    /** 주말 장보기. 어린이 보호구역과 유턴이 있습니다. */
    private fun errand(q: Quality) = listOf(
        Place(A.place), Start(q.smooth()), Drive(30, 40), Road(MoveKind.SCHOOL_ZONE, q.road()),
        Turn(Side.LEFT, A, q.signal(), q.steer(), q.lateOff()), Drive(45, 180), Lane(Side.LEFT, q.signal(), q.steer(), q.lateOff()),
        Drive(40, 60), Place(D.place), Turn(Side.LEFT, D, q.signal(), q.steer(), q.lateOff(), uTurn = true), Drive(45, 90),
        Lane(Side.RIGHT, q.signal(), q.steer(), q.lateOff()), Drive(35, 60), Turn(Side.RIGHT, D, q.signal(), q.steer(), q.lateOff()),
        Drive(15, 20), Stop(q.smooth(), 0),
    )

    /**
     * 시연에 쓸 이야기로 다듬습니다.
     * - 마지막 주행: 깜빡이를 처음 한 번 늦게 켜고 나머지는 미리 켬. 정지 두 번은 조금 세게(다음 목표), 출발 하나와 방지턱이 조금 아쉬움.
     * - 이번 주 깜빡이는 마지막 주행 전까지 5번 연속을 넘지 않게 끊어, 미션이 시연 주행에서 완료되게 합니다.
     */
    private fun shapeStory(plans: List<Plan>, today: LocalDate) {
        val last = plans.lastOrNull() ?: return
        var firstSignal = true
        var stops = 0
        var starts = 0
        last.steps.forEach { s ->
            when (s) {
                is Turn -> { s.signal = if (firstSignal) Sig.LATE else Sig.EARLY; s.steer = Q.GOOD; s.lateOff = false; firstSignal = false }
                is Lane -> { s.signal = if (firstSignal) Sig.LATE else Sig.EARLY; s.steer = Q.GOOD; s.lateOff = false; firstSignal = false }
                // 신호등 정지 두 번이 조금 셌던 것이 다음 목표가 됩니다.
                is Stop -> { s.q = if (stops++ < 2) Q.SOSO else Q.GOOD }
                is Start -> { s.q = if (starts++ == 1) Q.SOSO else Q.GOOD }
                is Road -> s.q = if (s.kind == MoveKind.SPEED_BUMP) Q.SOSO else Q.GOOD
                // 마지막 주차는 두 번 만에: 시연 주행에서 한 번에 넣으면 '지난번보다 나아짐'이 보입니다.
                is Park -> { s.tries = 2; s.fast = false; s.dry = false; s.crooked = false }
                else -> {}
            }
        }
        // 주차(심화 과목)는 아직 이수 전으로 둡니다: 열 번 중 아홉 번 잘한 자리가 생기면 하나를 '두 번 만에'로 바꿉니다.
        val parks = plans.flatMap { it.steps }.filterIsInstance<Park>()
        var p = 0
        while (p < parks.size) {
            val from = maxOf(0, p - Course.WINDOW + 1)
            if ((from..p).count { parks[it].good } >= Course.PASS) { parks[(from..p).last { parks[it].good }].tries = 2; continue }
            p++
        }
        // 졸업 이야기: 깜빡이만 남기고 나머지 과목은 둘째 주에 이수해 둡니다. 방지턱·카메라는 드물게 나와서 둘째 주에는 다 잘한 걸로 둡니다.
        plans.filter { it !== last && !it.date.isBefore(today.minusDays(6)) }.flatMap { it.steps }.forEach { if (it is Road) it.q = Q.GOOD }
        // 마지막 주행 바로 앞 깜빡이 다섯 번은 '늦게, 미리×4'로 둡니다. 그러면 시연 직전 최근 열 번이 8번이라,
        // 시연 주행에서 깜빡이를 한 번 미리 켜면 맨 앞의 '늦게'가 빠지며 아홉 번이 되어 졸업합니다(이번 주 과제도 5번 연속으로 완료).
        // 단계는 값이 같으면 같다고 보는 data class라서, 자리(번호)로 구분합니다.
        val signals = plans.flatMap { it.steps }.filter { it is Turn || it is Lane }
        val lastCount = last.steps.count { it is Turn || it is Lane }
        val tailStart = signals.size - lastCount - 5
        for (k in tailStart until signals.size - lastCount) setSignal(signals[k], if (k == tailStart) Sig.LATE else Sig.EARLY)
        // 그 앞의 기록에서 깜빡이가 한 번이라도 열 번 중 아홉 번이 되면 이미 이수해 버리므로, 그런 자리의 '미리'를 '늦게'로 바꿉니다.
        var i = 0
        while (i < signals.size) {
            val from = maxOf(0, i - Course.WINDOW + 1)
            if ((from..i).count { signalOf(signals[it]) == Sig.EARLY } >= Course.PASS) {
                val k = (from..i).last { it < tailStart && signalOf(signals[it]) == Sig.EARLY }
                setSignal(signals[k], Sig.LATE)
                continue
            }
            i++
        }

        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var run = 0
        plans.filter { it !== last && !it.date.isBefore(monday) }.flatMap { it.steps }.forEach { s ->
            val ok = when (s) {
                is Turn -> s.signal == Sig.EARLY
                is Lane -> s.signal == Sig.EARLY
                else -> return@forEach
            }
            run = if (ok) run + 1 else 0
            if (run >= Coaching.MISSION_TARGET) {
                when (s) { is Turn -> s.signal = Sig.LATE; is Lane -> s.signal = Sig.LATE; else -> {} }
                run = 0
            }
        }
    }

    /**
     * 날씨 이야기: 첫 주 저녁 퇴근길 하나는 비가 와서(전조등을 6분쯤 안 켬, 큰길에서 한 번 미끄러질 뻔함) 첫 빗길이 되고,
     * 둘째 주 초 아침 하나는 안개가 껴서 첫 안갯길이 됩니다. 시연 직전 주행들(마지막 5번)은 맑게 둬서 이야기가 흔들리지 않게 합니다.
     */
    private fun addWeather(plans: MutableList<Plan>, today: LocalDate) {
        val last = plans.lastOrNull() ?: return
        plans.withIndex().filter { (_, p) -> p !== last && p.time.hour >= 18 && p.date.isBefore(today.minusDays(7)) }.getOrNull(1)?.let { (i, p) ->
            // 여수동 큰길(시속 55km)을 달리다 미끄러질 뻔합니다.
            val at = p.steps.indexOfFirst { it is Drive && it.kmh >= 55 } + 1
            val steps = if (at > 0) p.steps.subList(0, at) + Slip + p.steps.subList(at, p.steps.size) else p.steps
            plans[i] = Plan(p.date, p.time, steps, Weather.RAIN, lightsOffS = 380)
        }
        plans.withIndex().filter { (_, p) ->
            p !== last && p.time.hour < 9 && !p.date.isBefore(today.minusDays(6)) && p.date.isBefore(today.minusDays(3))
        }.firstOrNull()?.let { (i, p) -> plans[i] = Plan(p.date, p.time, p.steps, Weather.FOG) }
    }

    private fun signalOf(s: Step) = when (s) { is Turn -> s.signal; is Lane -> s.signal; else -> null }
    private fun setSignal(s: Step, sig: Sig) { when (s) { is Turn -> s.signal = sig; is Lane -> s.signal = sig; else -> {} } }

    private fun simulate(plans: List<Plan>, zone: ZoneId): Result {
        val rnd = Random(7)
        val moves = mutableListOf<MoveForm>()
        val intended = mutableMapOf<Int, Pair<MoveKind, Map<Skill, Boolean>>>()
        val trips = mutableListOf<Trip>()
        lateinit var drv: Driver
        var place = ""
        var spot: Spot? = null
        var step: Step? = null
        /** 느리게 돈 회전(유턴)은 도로 속도에 오른 다음 단계에서 나오므로, 그 회전 단계로 이어 붙입니다. */
        var lastTurn: Turn? = null
        var tripId = 0
        var weather = Weather.CLEAR

        val detector = MoveDetector(
            onMove = { raw ->
                val late = lastTurn?.takeIf { raw.kind.isTurn && step !is Turn }
                val at = late?.spot ?: spot
                val lat = at?.lat?.plus(rnd.nextDouble(-JITTER, JITTER))
                val lon = at?.lon?.plus(rnd.nextDouble(-JITTER, JITTER))
                val m = raw.copy(
                    recordedAt = drv.t, lat = lat, lon = lon, place = at?.place ?: place, tripId = tripId, weather = weather,
                    intersectionId = when {
                        raw.kind.isTurn -> Intersections.assign(lat, lon, moves.filter { it.kind != MoveKind.PARK })
                        raw.kind == MoveKind.PARK -> Intersections.assign(lat, lon, moves.filter { it.kind == MoveKind.PARK })
                        else -> null
                    },
                    night = Firsts.isNightAt(drv.t, zone),
                )
                moves += m
                (late ?: step)?.let { intended[m.id] = intent(it) }
            },
            onMoveUpdated = { u ->
                moves.replaceAll { if (it.id == u.id) u.copy(recordedAt = it.recordedAt, lat = it.lat, lon = it.lon, intersectionId = it.intersectionId, place = it.place, tripId = it.tripId, weather = it.weather, night = it.night) else it }
            },
        )

        plans.forEach { plan ->
            val start = plan.date.atTime(plan.time).atZone(zone).toInstant().toEpochMilli()
            tripId++
            weather = plan.weather
            drv = Driver(detector, start, rnd, wet = plan.weather != Weather.CLEAR)
            detector.onGear(start, Gear.D)
            drv.hold(1f)
            plan.steps.forEach { s ->
                step = s
                if (s is Turn) lastTurn = s
                spot = (s as? Turn)?.spot ?: (s as? Park)?.spot
                when (s) {
                    is Place -> place = s.name
                    is Drive -> drv.drive(s.kmh, s.seconds)
                    is Start -> drv.start(s.q)
                    is Stop -> drv.stop(s.q, s.waitS)
                    is Turn -> drv.turn(s)
                    is Lane -> drv.lane(s)
                    is Road -> drv.road(s)
                    Slip -> drv.slip()
                    is Park -> drv.park(s)
                }
                spot = null
            }
            detector.onGear(drv.t, Gear.P)
            trips += Trip(tripId, start, drv.t, drv.meters, plan.weather, plan.lightsOffS)
        }
        return Result(trips, moves, intended)
    }

    private fun intent(s: Step): Pair<MoveKind, Map<Skill, Boolean>> = when (s) {
        is Turn -> (if (s.uTurn) MoveKind.U_TURN else MoveKind.TURN) to mapOf(Skill.SIGNAL to (s.signal == Sig.EARLY), Skill.STEER to (s.steer == Q.GOOD))
        is Lane -> MoveKind.LANE_CHANGE to mapOf(Skill.SIGNAL to (s.signal == Sig.EARLY), Skill.STEER to (s.steer == Q.GOOD))
        is Start -> MoveKind.START to mapOf(Skill.SMOOTH to (s.q == Q.GOOD))
        is Stop -> MoveKind.STOP to mapOf(Skill.SMOOTH to (s.q == Q.GOOD))
        is Road -> s.kind to mapOf(Skill.ROAD to (s.q == Q.GOOD))
        Slip -> MoveKind.SLIP to emptyMap()
        is Park -> MoveKind.PARK to mapOf(Skill.PARK to s.good)
        else -> error("동작이 아닌 단계에서 동작이 나왔습니다: $s")
    }

    /** 대본을 0.1초 단위 차량 신호로 바꿉니다. 속도는 실제 차처럼 1km/h 단위로 보냅니다. */
    /** [wet]이면(비·안개) 잘하는 운전자는 카메라·보호구역에서 날씨에 맞춰 20% 더 줄입니다. */
    private class Driver(private val d: MoveDetector, var t: Long, private val rnd: Random, private val wet: Boolean = false) {
        var v = 0f
        var meters = 0.0
        private var steer = 0f
        private var braking = false
        private var signal: Side? = null

        fun tick(ms: Long = TICK_MS) {
            t += ms
            meters += v * ms / 1000.0
            d.onSpeed(t, (v * 3.6f).roundToInt() / 3.6f)
        }

        private fun steer(deg: Float) { if (deg != steer) { steer = deg; d.onSteering(t, deg) } }
        private fun brake(on: Boolean) { if (on != braking) { braking = on; d.onBrake(t, on) } }
        private fun signal(s: Side?) { if (s != signal) { signal = s; d.onTurnSignal(t, s) } }

        fun hold(seconds: Float, each: () -> Unit = {}) = repeat((seconds * 10).roundToInt()) { tick(); each() }

        /** 일정한 가속도로 목표 속도까지. */
        fun speedTo(kmh: Float, accel: Float, each: () -> Unit = {}) {
            val target = kmh / 3.6f
            val step = accel * TICK_MS / 1000f
            while (v != target) {
                v = if (v < target) minOf(target, v + step) else maxOf(target, v - step)
                tick(); each()
            }
        }

        /** [m]미터를 지금 속도로. [each]에는 남은 거리를 줍니다. */
        fun driveFor(m: Double, each: (Double) -> Unit = {}) {
            val end = meters + m
            while (meters < end) { each(end - meters); tick() }
        }

        private fun steerTo(deg: Float, ticks: Int, each: (Int) -> Unit = {}) {
            val from = steer
            for (i in 1..ticks) { steer(from + (deg - from) * i / ticks); tick(); each(i) }
        }

        /** 긴 직선 구간. 1초마다 한 번씩만 보내 기록을 가볍게 합니다. */
        fun drive(kmh: Int, seconds: Int) {
            speedTo(kmh.toFloat(), 1.2f)
            repeat(seconds) { tick(1_000) }
        }

        /** 달리다 바퀴가 미끄러져 ESC가 잡아 줌: 놀라서 속도를 조금 줄이고, 장면이 만들어질 때까지 달립니다. */
        fun slip() {
            d.onSlip(t)
            speedTo(v * 3.6f - 8f, 2.8f)
            hold(3.5f)
        }

        /**
         * 후진 주차: 칸을 지나 앞으로 조금 빼며 핸들을 돌리고(전진), 뒤로 돌려 넣습니다. 고쳐 넣을 때마다 앞으로 조금 뺐다가 다시 뒤로 넣습니다.
         * 마지막엔 핸들을 풀고 P에 둡니다.
         */
        /**
         * 후진 주차(직각): 통로를 따라 칸 앞을 곧게 지나간 뒤, 앞을 비스듬히 틀어 서고(꽁무니가 칸을 향함),
         * 핸들을 반대로 끝까지 감고 후진하며 90°를 돌아 칸에 넣습니다. 고쳐 넣는 회차는 첫 후진이 덜 돌아서
         * 앞으로 조금 빼며 각도를 바로잡고 다시 넣습니다(초보의 앞뒤 왔다 갔다).
         */
        fun park(p: Park) {
            brake(false)
            speedTo(6f, 1.0f)
            steerTo(0f, 6)
            hold(2.0f) // 칸 앞을 곧게 지나갑니다
            steerTo(430f, 8) // 앞을 비스듬히(칸 반대쪽으로) 짧게 틉니다
            hold(1.3f)
            steerTo(40f, 8)
            brake(true); speedTo(0f, 1.6f); hold(0.5f)
            val backKmh = if (p.fast) 9f else 4f
            /** 핸들을 끝까지 감고 [sec]초 후진. 마지막이면 핸들을 풀며 칸 끝까지 들어갑니다. */
            fun back(sec: Float, first: Boolean, last: Boolean) {
                d.onGear(t, Gear.R)
                hold(0.4f)
                if (p.dry && first) { steerTo(-450f, 12); hold(0.4f) }
                brake(false)
                speedTo(backKmh, 1.0f)
                if (steer > -400f) steerTo(-450f, 10)
                hold(sec)
                if (last) { steerTo(if (p.crooked) -200f else 0f, 10); hold(1.4f) }
                brake(true); speedTo(0f, 1.5f); hold(0.4f)
            }
            /** 각이 안 맞아 앞으로 조금 빼며 핸들을 반대로 틀어 각을 바로잡습니다(초보의 앞뒤 왔다 갔다). */
            fun pullForward(sec: Float) {
                d.onGear(t, Gear.D)
                hold(0.4f)
                brake(false)
                speedTo(3f, 1.0f)
                steerTo(-250f, 8); hold(sec); steerTo(0f, 8)
                brake(true); speedTo(0f, 1.5f); hold(0.4f)
            }
            // 마지막 후진 시간은 몇 번 만에 넣든 차가 칸과 나란히(90°) 서도록 맞춘 값입니다.
            val lastBack = when (p.tries) { 1 -> 3.6f; 2 -> 2.4f; 3 -> 1.8f; else -> 1.0f }
            for (k in 1 until p.tries) {
                back(if (k == 1) 1.8f else 1.2f, first = k == 1, last = false)
                pullForward(if (k == 1) 1.6f else 1.0f)
            }
            back(lastBack, first = p.tries == 1, last = true)
            d.onGear(t, Gear.P)
            hold(0.5f)
        }

        fun start(q: Q) {
            brake(false)
            hold(0.6f)
            speedTo(30f, when (q) { Q.GOOD -> 1.8f; Q.SOSO -> 3.2f; Q.BAD -> 4.2f })
        }

        fun stop(q: Q, waitS: Int) {
            brake(true)
            when (q) {
                // 멈추기 직전엔 브레이크를 살짝 풀어 울컥하지 않게 합니다.
                Q.GOOD -> { speedTo(6f, 1.8f); speedTo(0f, 1.2f) }
                // 조금 세게 줄이다가 멈추기 직전엔 살살: '조금 세게 멈춤'만 걸립니다.
                Q.SOSO -> { speedTo(9f, 3.7f); speedTo(0f, 1.2f) }
                Q.BAD -> speedTo(0f, 5.3f)
            }
            hold(1f)
            repeat(waitS) { tick(1_000) }
        }

        private fun lead(sig: Sig) = when (sig) {
            Sig.EARLY, Sig.WRONG -> 33f + rnd.nextInt(12)
            Sig.LATE -> 8f + rnd.nextInt(12)
            else -> null
        }

        private fun other(side: Side) = if (side == Side.LEFT) Side.RIGHT else Side.LEFT

        fun turn(s: Turn) {
            val dir = if (s.side == Side.LEFT) 1f else -1f
            val sigSide = if (s.signal == Sig.WRONG) other(s.side) else s.side
            // 꺾기 전에 감속을 끝냅니다.
            brake(true); speedTo(if (s.uTurn) 10f else 15f, 2f); brake(false)
            val lead = lead(s.signal)
            driveFor((lead ?: 0f) + 4.0) { rem -> if (lead != null && rem <= lead) signal(sigSide) }

            val brakeDuring = s.steer == Q.BAD && rnd.nextBoolean()
            val corrections = when (s.steer) { Q.GOOD -> 0; Q.SOSO -> 1; Q.BAD -> if (brakeDuring) 0 else 2 }
            val peak = dir * (if (s.uTurn) 540f else 280f + rnd.nextInt(80))
            steerTo(peak, 15) { i ->
                if (s.signal == Sig.AFTER && i == 4) signal(s.side)
                if (brakeDuring && i == 6) brake(true)
            }
            hold(if (s.uTurn) 3f else 1f)
            repeat(corrections) { steerTo(peak - dir * 70f, 4); steerTo(peak, 4) }
            brake(false)
            steerTo(0f, 15) { v = minOf(v + 0.15f, 25 / 3.6f) }
            hold(if (s.lateOff) 4f else 0.5f)
            signal(null)
        }

        fun lane(s: Lane) {
            if (v * 3.6f < 40) speedTo(45f, 1.5f)
            val dir = if (s.side == Side.LEFT) 1f else -1f
            val sigSide = if (s.signal == Sig.WRONG) other(s.side) else s.side
            val lead = lead(s.signal)
            driveFor((lead ?: 0f) + 3.0) { rem -> if (lead != null && rem <= lead) signal(sigSide) }

            val rushed = s.steer == Q.BAD && rnd.nextBoolean()
            val brakeDuring = s.steer == Q.BAD && !rushed
            // 조향 센서처럼 몇 도씩 끊어서 S자로 옮깁니다. 고쳐 꺾으면 한 번 더 흔듭니다.
            val path = buildList {
                addAll(listOf(6f, 12f))
                if (s.steer == Q.SOSO) addAll(listOf(6f, 12f))
                addAll(listOf(6f, 0f, -6f, 0f))
            }
            val total = if (rushed) 13 else 30
            val each = total / path.size
            path.forEachIndexed { i, deg ->
                steer(dir * deg)
                if (i == 1 && s.signal == Sig.AFTER) signal(s.side)
                if (i == 1 && brakeDuring) brake(true)
                repeat(each) { tick() }
            }
            brake(false)
            hold(if (s.lateOff) 4f else 0.5f)
            signal(null)
            hold(1.2f)
        }

        fun road(s: Road) {
            val id = "demo-$t"
            when (s.kind) {
                MoveKind.SPEED_BUMP -> {
                    speedTo(30f, 1.2f)
                    d.onRoadPoint(t, s.kind, id, BUMP_NOTICE_M.toFloat())
                    val at = meters + BUMP_NOTICE_M
                    when (s.q) {
                        Q.GOOD -> { driveFor(40.0); brake(true); speedTo(17f, 1.2f); brake(false) }
                        Q.SOSO -> { driveFor(40.0); speedTo(26f, 0.8f) }
                        // 턱 바로 앞에서 세게 밟고, 밟은 채 넘습니다.
                        Q.BAD -> { driveFor(at - meters - 14); brake(true); speedTo(18f, 3.8f) }
                    }
                    driveFor(at - meters)
                    hold(1f)
                    brake(false)
                    hold(1.5f)
                    speedTo(30f, 1.2f)
                }
                MoveKind.CAMERA -> {
                    speedTo(if (s.q == Q.GOOD) (if (wet) 38f else 48f) else 57f, 1.2f)
                    d.onRoadPoint(t, s.kind, id, CAMERA_NOTICE_M.toFloat(), CAMERA_LIMIT)
                    val at = meters + CAMERA_NOTICE_M
                    // 조금 아쉬움: 카메라 바로 앞에서 급하게 줄임. 아쉬움: 제한속도를 넘은 채 지남.
                    if (s.q == Q.SOSO) { driveFor(at - meters - 45); brake(true); speedTo(48f, 3.7f); brake(false) }
                    driveFor(at - meters)
                    hold(3.5f)
                }
                MoveKind.SCHOOL_ZONE -> {
                    speedTo(when (s.q) { Q.GOOD -> if (wet) 22f else 27f; Q.SOSO -> 33f; Q.BAD -> 38f }, 1.2f)
                    d.onRoadPoint(t, s.kind, id, SCHOOL_NOTICE_M.toFloat(), SCHOOL_LIMIT)
                    driveFor(SCHOOL_NOTICE_M + MoveDetector.SCHOOL_ZONE_LENGTH_M + 15)
                }
                else -> error("시연 기록에 없는 지점: ${s.kind}")
            }
        }
    }

    const val DAYS = 14
    private const val TICK_MS = 100L
    private const val LATEST_GAP_MS = 40 * 60_000L
    private const val JITTER = 0.00004
    private const val BUMP_NOTICE_M = 80.0
    private const val CAMERA_NOTICE_M = 250.0
    private const val CAMERA_LIMIT = 50
    private const val SCHOOL_NOTICE_M = 100.0
    private const val SCHOOL_LIMIT = 30
}
