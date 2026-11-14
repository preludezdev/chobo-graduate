package dev.driveform.form

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 차량 신호를 시간순으로 받아 회전·유턴·차선 변경·정지·출발과, 내비가 알려 준 지점(과속방지턱·단속 카메라·
 * 철길 건널목·어린이 보호구역)을 지나는 동작, 그리고 후진이 들어간 주차를 잘라내고 [MoveForm]을 만듭니다.
 * 한 스레드(메인)에서만 호출해야 합니다. 시각은 SystemClock.elapsedRealtime() 기준 ms입니다.
 * 원형은 signal-probe/tools/form_metrics.py 입니다.
 */
class MoveDetector(
    private val onMove: (MoveForm) -> Unit,
    private val onMoveUpdated: (MoveForm) -> Unit,
) {
    private val speed = ArrayDeque<Point>()
    private val steering = ArrayDeque<Point>()
    private val brake = ArrayDeque<Pair<Long, Boolean>>()
    /** 깜빡이 상태 변화. 값이 null이면 꺼짐입니다. */
    private val signal = ArrayDeque<Pair<Long, Side?>>()
    /** 기어 변화. 기어 신호를 받기 전에는 주행(D)으로 봅니다. */
    private val gears = ArrayDeque<Pair<Long, Gear>>()
    /**
     * 느리게(시속 [GARAGE_TURN_KMH] 미만) 한 회전. 주차장 안 통로나 램프에서 돈 것일 수 있어서 바로 내보내지 않습니다.
     * 도로 속도([ROAD_KMH])에 오르면 도로에서 한 회전으로 내보내고, 그 전에 P에 두면 주차장 안에서 돈 것으로 보고 버립니다.
     * 주차장 안은 도로가 아니라서 깜빡이를 채점하지 않습니다(애매하면 채점하지 않는다).
     */
    private val slowTurns = mutableListOf<MoveForm>()
    /**
     * 이번 주행(마지막 P 이후)에서 도로 속도에 오른 적이 있는지. 출발해서 한 번도 도로 속도에 오르지 않았을 때 돈 회전은
     * 주차장을 빠져나오며 돈 것이라 도로 속도에 올라도 버립니다. 기어를 모르고 시작하면(주행 중에 켜짐) 도로로 봅니다.
     */
    private var onRoad = true

    private var turnStartMs: Long? = null
    private var laneStartMs: Long? = null
    private var laneCalmSinceMs: Long? = null
    private var lastTurnEndMs: Long? = null
    /** 멈췄다가 다시 움직이기 시작한 시각. 출발을 한 번 기록하면 null로 돌립니다. */
    private var launchMs: Long? = null
    /** 직전에 멈춰 있던 마지막 시각. 정지 구간을 거슬러 찾을 때 이보다 앞은 보지 않습니다. */
    private var lastStoppedMs: Long? = null

    /** 지금까지 달린 거리(m). 내비가 알려 준 지점까지 남은 거리를 셀 때 씁니다. */
    private var traveledM = 0.0

    /** 내비가 알려 준 길 위 지점. [atM]은 [traveledM] 기준 위치입니다. */
    private class RoadPoint(val kind: MoveKind, val id: String, var atM: Double, val noticeMs: Long, var limitKmh: Int?) {
        /** 지점 [RAIL_APPROACH_M] 앞을 지난 시각. 철길 건널목에서 '멈췄나'를 이때부터 봅니다. */
        var approachMs: Long? = null
        var crossedMs: Long? = null
    }
    private val roadPoints = mutableListOf<RoadPoint>()
    private var nextId = 1
    private var lastMove: MoveForm? = null

    /** 저장된 기록을 불러온 뒤 번호가 겹치지 않게 이어 붙입니다. */
    fun resumeAfter(lastId: Int) {
        nextId = maxOf(nextId, lastId + 1)
    }

    /** ESC가 미끄러지는 차를 잡아 준 순간. 앞뒤 속도를 함께 남기려고 [SLIP_AFTER_MS] 뒤에 장면을 만듭니다. */
    private var slipAt: Long? = null
    private var lastSlipAt: Long? = null

    fun onSlip(ms: Long) {
        // 서 있을 때 켜진 경고등이나, 한 번 미끄러지며 여러 번 깜빡인 것은 한 장면으로 봅니다.
        if (speedAt(ms) * 3.6f < SLIP_MIN_KMH || slipAt != null || lastSlipAt?.let { ms - it < SLIP_GAP_MS } == true) return
        slipAt = ms
        lastSlipAt = ms
    }

    private fun trackSlip(ms: Long) {
        val at = slipAt ?: return
        if (ms - at < SLIP_AFTER_MS) return
        slipAt = null
        val start = at - SLIP_BEFORE_MS
        val window = speed.filter { it.ms in start..ms }
        val accel = Kinematics.accelerations(window).maxOfOrNull { abs(it.value) } ?: 0f
        emit(
            speedMove(MoveKind.SLIP, start, ms, window, peakAccel = accel, finalDecel = 0f)
                .copy(apexKmh = (speedAt(at) * 3.6f).roundToInt(), markMs = at, brakeDuring = brake.any { it.second && it.first in start..at }),
        )
    }

    fun onSpeed(ms: Long, mps: Float) {
        speed.lastOrNull()?.let { prev -> traveledM += prev.value * (ms - prev.ms) / 1000.0 }
        speed.addAndTrim(Point(ms, mps), ms) { it.ms }
        if ((mps * 3.6f).roundToInt() >= ROAD_KMH && gearAt(ms) != Gear.R) {
            val held = slowTurns.toList()
            slowTurns.clear()
            if (onRoad) held.forEach(::emit)
            onRoad = true
        }
        trackSlip(ms)
        // 조향 이벤트는 값이 바뀔 때만 오므로, 차선 변경이 끝났는지는 속도 이벤트(약 10Hz)를 시계로 삼아 확인합니다.
        checkLaneEnd(ms)
        trackStopStart(ms, mps)
        trackRoadPoints(ms)
    }

    /**
     * 내비가 "○○ [remainM]m 앞"을 알려 줄 때 부릅니다. 같은 지점은 [id]가 같아서, 다시 오면 위치만 고칩니다.
     * 그 뒤로는 속도를 적분해 그 지점을 지난 순간을 직접 찾습니다(내비 알림은 띄엄띄엄 오기 때문).
     */
    fun onRoadPoint(ms: Long, kind: MoveKind, id: String, remainM: Float, limitKmh: Int? = null) {
        val at = traveledM + remainM
        val p = roadPoints.firstOrNull { it.kind == kind && it.id == id }
        if (p == null) {
            roadPoints += RoadPoint(kind, id, at, ms, limitKmh)
            return
        }
        if (p.crossedMs == null) p.atM = at
        if (limitKmh != null) p.limitKmh = limitKmh
    }

    /**
     * 기어가 바뀔 때. 후진에 들어가면 회전·차선 변경을 보지 않고(후진하며 돌린 핸들은 회전이 아님),
     * P에 두면 주차 장면을 만들고 [onParked]로 주행을 마무리합니다.
     */
    fun onGear(ms: Long, gear: Gear) {
        val was = gearAt(ms)
        if (gear == was && gears.isNotEmpty()) return
        gears.addAndTrim(ms to gear, ms) { it.first }
        // 후진하거나 P에 두면 하던 회전·차선 변경 추적을 버립니다(꺾인 채 세운 핸들이 다음 주행의 회전으로 이어지지 않게).
        if (gear == Gear.R || gear == Gear.P) {
            turnStartMs = null
            laneStartMs = null
            laneCalmSinceMs = null
        }
        if (gear == Gear.P) {
            val park = buildPark(ms)
            // 도로 속도에 오르지 않은 채 P에 뒀으면, 그 사이 느린 회전은 주차장 안에서 돈 것(또는 주차하려고 돌린 핸들)입니다.
            slowTurns.clear()
            onRoad = false
            park?.let(::emit)
            onParked()
        }
    }

    private fun gearAt(ms: Long) = gears.lastOrNull { it.first <= ms }?.second ?: Gear.D

    /**
     * P에 둔 순간 [end]에서 거슬러, 마지막으로 시속 [PARK_SLOW_KMH] 넘게 달린 때(또는 직전에 P였던 때)부터를 주차 구간으로 봅니다.
     * 그 구간에 후진으로 [PARK_MIN_REVERSE_M] 넘게 움직였을 때만 주차 장면을 만듭니다(그냥 서서 P에 둔 건 주차 연습이 아님).
     */
    private fun buildPark(end: Long): MoveForm? {
        val lastPark = gears.lastOrNull { it.second == Gear.P && it.first < end }?.first
        val fast = speed.lastOrNull { it.ms < end && it.value * 3.6f >= PARK_SLOW_KMH }?.ms
        val start = listOfNotNull(lastPark, fast, speed.firstOrNull()?.ms).maxOrNull() ?: return null
        val run = speed.filter { it.ms in start..end }
        if (run.size < 2) return null

        // 움직인 방향(앞 +1, 뒤 -1)이 바뀔 때마다 한 토막. 같은 방향으로 멈췄다 다시 가는 건 같은 토막입니다.
        val segments = mutableListOf<Pair<Int, Float>>()
        var reverseM = 0f
        var reverseTop = 0f
        run.zipWithNext { a, b ->
            if (a.value <= MoveDetector.STOPPED_MPS) return@zipWithNext
            val dir = if (gearAt(a.ms) == Gear.R) -1 else 1
            val d = a.value * (b.ms - a.ms) / 1000f
            if (dir < 0) { reverseM += d; reverseTop = maxOf(reverseTop, a.value) }
            if (segments.lastOrNull()?.first == dir) segments[segments.lastIndex] = dir to segments.last().second + d
            else segments += dir to d
        }
        if (reverseM < PARK_MIN_REVERSE_M) return null
        // 조금 굴러간 건 한 번으로 세지 않습니다. 뒤로 넣은 횟수가 '몇 번 만에'이고, 다 넣은 뒤 앞으로 다시 맞췄으면 한 번 더 셉니다.
        val moves = segments.filter { it.second >= PARK_MIN_SEGMENT_M }.map { it.first }
            .fold(mutableListOf<Int>()) { acc, d -> if (acc.lastOrNull() != d) acc += d; acc }
        val afterFirstBack = moves.dropWhile { it > 0 }
        val tries = (afterFirstBack.count { it < 0 } + if (afterFirstBack.lastOrNull() == 1) 1 else 0).coerceAtLeast(1)

        // 멈춰 있는 동안 돌린 핸들의 합.
        val steer = steering.filter { it.ms in start..end }
        val before = steering.lastOrNull { it.ms < start }
        var dry = 0f
        (listOfNotNull(before) + steer).zipWithNext { a, b ->
            if (speedAt(b.ms) <= MoveDetector.STOPPED_MPS) dry += abs(b.value - a.value)
        }

        val window = speed.filter { it.ms in (start - WINDOW_PAD_MS)..end }
        val reverseSpans = mutableListOf<Pair<Long, Long>>()
        var from: Long? = if (gearAt(start) == Gear.R) start else null
        gears.filter { it.first in start..end }.forEach { (ms, g) ->
            if (g == Gear.R && from == null) from = ms
            if (g != Gear.R && from != null) { reverseSpans += from!! to ms; from = null }
        }
        from?.let { reverseSpans += it to end }

        return speedMove(MoveKind.PARK, start, end, window, peakAccel = 0f, finalDecel = 0f).copy(
            apexKmh = (reverseTop * 3.6f).roundToInt(),
            corrections = tries - 1,
            reverseSpans = reverseSpans,
            dryTurnDeg = dry.roundToInt(),
            finalSteerDeg = (steering.lastOrNull { it.ms <= end }?.value ?: 0f).roundToInt(),
        )
    }

    /**
     * 주차하면 경로가 끝난 것이므로 아직 지나지 않은 지점은 버립니다.
     * 어린이 보호구역 안에서 주차했으면(아이를 내려 주는 등) 거기까지를 한 번으로 기록합니다.
     */
    fun onParked() {
        val now = speed.lastOrNull()?.ms ?: return
        // 미끄러진 직후에 바로 P에 두면 기다리지 않고 장면을 만듭니다.
        slipAt?.let { trackSlip(it + SLIP_AFTER_MS) }
        roadPoints.toList().forEach { p ->
            if (p.crossedMs == null) roadPoints.remove(p)
            else if (p.kind == MoveKind.SCHOOL_ZONE) {
                roadPoints.remove(p)
                emit(buildRoadPoint(p, now))
            }
        }
    }

    private fun trackRoadPoints(ms: Long) {
        roadPoints.toList().forEach { p ->
            if (p.approachMs == null && traveledM >= p.atM - RAIL_APPROACH_M) p.approachMs = ms
            val crossed = p.crossedMs
            if (crossed == null) {
                if (traveledM >= p.atM) p.crossedMs = ms
                else if (ms - p.noticeMs > ROAD_POINT_GIVE_UP_MS) roadPoints.remove(p)
                return@forEach
            }
            // 지난 뒤 잠깐 더 지켜봐야 지점 위 브레이크, 지난 뒤 가속을 알 수 있습니다. 보호구역은 구간이 끝날 때까지 봅니다.
            val done = when (p.kind) {
                MoveKind.SCHOOL_ZONE -> traveledM >= p.atM + SCHOOL_ZONE_LENGTH_M || ms - crossed > SCHOOL_ZONE_MAX_MS
                MoveKind.CAMERA -> ms - crossed >= CAMERA_AFTER_MS
                else -> ms - crossed >= BUMP_AFTER_MS
            }
            if (done) {
                roadPoints.remove(p)
                emit(buildRoadPoint(p, ms))
            }
        }
    }

    private fun buildRoadPoint(p: RoadPoint, end: Long): MoveForm {
        val crossed = p.crossedMs!!
        val start = when (p.kind) {
            MoveKind.SCHOOL_ZONE -> crossed
            MoveKind.RAILROAD -> maxOf(p.noticeMs, (p.approachMs ?: crossed) - RAIL_LEAD_MS)
            else -> maxOf(p.noticeMs, crossed - BUMP_APPROACH_MS)
        }
        val window = speed.filter { it.ms in (start - WINDOW_PAD_MS)..end }
        val accels = Kinematics.accelerations(window)
        val crossKmh = (speedAt(crossed) * 3.6f).roundToInt()
        val base = speedMove(p.kind, start, end, window, peakAccel = 0f, finalDecel = 0f)
            .copy(apexKmh = crossKmh, markMs = crossed, limitKmh = p.limitKmh)
        return when (p.kind) {
            MoveKind.SPEED_BUMP -> {
                val around = (crossed - BUMP_BRAKE_WINDOW_MS)..(crossed + BUMP_BRAKE_WINDOW_MS)
                base.copy(
                    peakAccelMs2 = maxDecel(accels, crossed - BUMP_LAST_APPROACH_MS, crossed),
                    brakeDuring = brake.lastOrNull { it.first <= around.first }?.second == true ||
                        brake.any { it.second && it.first in around },
                )
            }
            // 카메라는 다가오는 동안(최대 8초) 어디서든 급하게 줄였으면 '카메라 앞 급감속'으로 봅니다.
            MoveKind.CAMERA -> base.copy(
                peakAccelMs2 = maxDecel(accels, start, crossed),
                afterAccelMs2 = accels.filter { it.ms in crossed..end }.maxOfOrNull { it.value }?.coerceAtLeast(0f) ?: 0f,
            )
            MoveKind.RAILROAD -> base.copy(
                stoppedBefore = window.any { it.ms in (p.approachMs ?: p.noticeMs)..crossed && it.value <= STOPPED_MPS },
            )
            MoveKind.SCHOOL_ZONE -> base.copy(
                apexKmh = ((window.filter { it.ms in crossed..end }.maxOfOrNull { it.value } ?: 0f) * 3.6f).roundToInt(),
                peakAccelMs2 = accels.filter { it.ms in crossed..end }.maxOfOrNull { abs(it.value) } ?: 0f,
                zoneEndMs = end,
            )
            else -> base
        }
    }

    private fun maxDecel(accels: List<Point>, from: Long, to: Long) =
        accels.filter { it.ms in from..to }.maxOfOrNull { -it.value }?.coerceAtLeast(0f) ?: 0f

    fun onBrake(ms: Long, pressed: Boolean) = brake.addAndTrim(ms to pressed, ms) { it.first }

    fun onTurnSignal(ms: Long, side: Side?) {
        signal.addAndTrim(ms to side, ms) { it.first }
        // 동작이 끝난 뒤에 깜빡이를 끄면 직전 동작에 '끄기 지연'을 채웁니다.
        val last = lastMove
        if (side == null && last != null && last.signalOffDelayS == null && last.signalOnMs != null && ms - last.endMs < OFF_WINDOW_MS) {
            lastMove = last.copy(signalOffDelayS = (ms - last.endMs) / 1000f).also(onMoveUpdated)
        }
    }

    fun onSteering(ms: Long, deg: Float) {
        steering.addAndTrim(Point(ms, deg), ms) { it.ms }
        trackTurn(ms, deg)
        trackLane(ms, deg)
    }

    private fun trackTurn(ms: Long, deg: Float) {
        val start = turnStartMs
        if (start == null && abs(deg) >= TURN_START_DEG) {
            turnStartMs = ms
        } else if (start != null && abs(deg) < TURN_START_DEG) {
            turnStartMs = null
            val seg = steering.filter { it.ms in start..ms }
            if (seg.maxOf { abs(it.value) } >= TURN_MIN_PEAK_DEG) {
                lastTurnEndMs = ms
                // 후진하며 돌린 핸들은 회전이 아닙니다.
                if (gears.any { it.second == Gear.R && it.first in start..ms } || gearAt(start) == Gear.R) return
                val heading = Kinematics.headingChangeDeg(steering.toList(), speed.toList(), start, ms)
                // 한 방향으로 270° 넘게 계속 돌았으면 교차로 회전이 아니라 주차장 나선 램프(또는 회전교차로)입니다.
                if (abs(heading) >= LOOP_HEADING_DEG) return
                val turn = buildTurn(start, ms, seg, heading)
                val top = speed.filter { it.ms in start..ms }.maxOfOrNull { it.value } ?: 0f
                if (top * 3.6f < GARAGE_TURN_KMH) slowTurns += turn else emit(turn)
            }
        }
    }

    /**
     * 차선 변경은 핸들이 곧게 펴진 상태에서 시작할 때만 후보로 잡습니다.
     * 회전을 끝내며 핸들을 푸는 동안 속도가 오르면, 그 마지막 풀림이 차선 변경처럼 보이기 때문입니다.
     */
    private fun trackLane(ms: Long, deg: Float) {
        if (laneStartMs == null) {
            val prev = steering.getOrNull(steering.size - 2)?.value ?: 0f
            val afterTurn = turnStartMs != null || ms - (lastTurnEndMs ?: Long.MIN_VALUE / 2) < LANE_AFTER_TURN_MS
            if (!afterTurn && abs(prev) < LANE_START_DEG && abs(deg) >= LANE_START_DEG && speedAt(ms) * 3.6f >= LANE_MIN_KMH) {
                laneStartMs = ms
                laneCalmSinceMs = null
            }
            return
        }
        if (abs(deg) < LANE_CALM_DEG) {
            if (laneCalmSinceMs == null) laneCalmSinceMs = ms
        } else {
            laneCalmSinceMs = null
        }
        checkLaneEnd(ms)
    }

    private fun checkLaneEnd(now: Long) {
        val start = laneStartMs ?: return
        val calm = laneCalmSinceMs
        when {
            calm != null && now - calm >= LANE_SETTLE_MS -> {
                laneStartMs = null
                laneCalmSinceMs = null
                buildLaneChange(start, calm)?.let(::emit)
            }
            now - start > LANE_MAX_MS -> {
                laneStartMs = null
                laneCalmSinceMs = null
            }
        }
    }

    private fun emit(move: MoveForm) {
        // 깜빡이 끄기 지연은 회전·차선 변경에만 붙이므로, 정지·출발은 '직전 동작'으로 잡지 않습니다.
        if (!move.kind.isSpeedOnly && move.kind != MoveKind.PARK) lastMove = move
        onMove(move)
    }

    /**
     * 핸들을 거의 끝까지 돌렸고 차 방향이 135° 넘게 바뀌었으면 유턴, 아니면 회전입니다.
     * 방향 변화는 조향비 가정값으로 계산하므로, 핸들 각도 조건을 함께 둬서 크게 꺾은 좌회전을 유턴으로 잘못 보지 않게 합니다.
     */
    private fun buildTurn(start: Long, end: Long, seg: List<Point>, heading: Float): MoveForm {
        val peak = seg.maxBy { abs(it.value) }
        val side = if (peak.value > 0) Side.LEFT else Side.RIGHT
        val uTurn = abs(heading) >= UTURN_MIN_HEADING_DEG && abs(peak.value) >= UTURN_MIN_PEAK_DEG
        return build(if (uTurn) MoveKind.U_TURN else MoveKind.TURN, side, start, end, peak, turnCorrections(seg))
    }

    /**
     * 속도로만 보는 정지와 출발.
     * 정지: 시속 [STOP_MIN_KMH] 이상에서 0이 되면 한 번. 멈추려고 마지막으로 줄이기 시작한 때([decelStart])부터 봅니다.
     * 출발: 멈췄다가 시속 [START_TARGET_KMH]에 이를 때까지 한 번.
     */
    private fun trackStopStart(ms: Long, mps: Float) {
        val prev = speed.getOrNull(speed.size - 2)
        if (mps <= STOPPED_MPS) {
            if (prev != null && prev.value > STOPPED_MPS) buildStop(ms)?.let(::emit)
            lastStoppedMs = ms
            launchMs = null
            return
        }
        if (prev != null && prev.value <= STOPPED_MPS) launchMs = prev.ms
        val launch = launchMs ?: return
        if (mps * 3.6f >= START_TARGET_KMH) {
            launchMs = null
            emit(buildStart(launch, ms))
        }
    }

    private fun buildStop(end: Long): MoveForm? {
        val run = speed.filter { it.ms > (lastStoppedMs ?: Long.MIN_VALUE) && it.ms <= end }
        val peak = run.maxByOrNull { it.value } ?: return null
        if (peak.value * 3.6f < STOP_MIN_KMH) return null
        val start = decelStart(run)
        val window = speed.filter { it.ms in (start - WINDOW_PAD_MS)..end }
        val decel = Kinematics.accelerations(window).filter { it.ms in start..end }.maxOfOrNull { -it.value } ?: 0f
        val back = window.lastOrNull { end - it.ms >= FINAL_WINDOW_MS }
        val final = back?.let { it.value / ((end - it.ms) / 1000f) } ?: 0f
        // 다시보기에서 멈추러 다가오는 길이 보이도록 앞쪽 [STOP_PREROLL_MS]를 더 담아 둡니다(판정은 위의 window로 끝났습니다).
        val shown = speed.filter { it.ms in maxOf(start - STOP_PREROLL_MS, end - STOP_SHOWN_MAX_MS)..end }
        return speedMove(MoveKind.STOP, start, end, shown, peakAccel = decel.coerceAtLeast(0f), finalDecel = final)
    }

    /**
     * 멈추려고 마지막으로 속도를 줄이기 시작한 때. 멈춘 곳에서 거꾸로 보며,
     * 그 전에 속도가 더 낮았던 곳(줄였다가 다시 밟음)이나 같은 속도로 [CRUISE_MS] 넘게 달린 곳에서 멈춥니다.
     * 일찍 한 번 줄이고 한참 달리다 멈춘 경우에 그 사이를 감속으로 세지 않기 위해서입니다.
     */
    private fun decelStart(run: List<Point>): Long {
        var top = run.last().value
        // 지금 보고 있는 속도 단계에 처음(시간상 가장 늦게) 들어선 점
        var level = run.last()
        var from = run.lastIndex
        for (i in run.indices.reversed()) {
            val p = run[i]
            if (p.value < top - KMH_STEP_MPS) break
            if (p.value > level.value + KMH_STEP_MPS) level = p
            else if (level.ms - p.ms > CRUISE_MS) break
            top = maxOf(top, p.value)
            from = i
        }
        // 그 구간에서 가장 빨랐던 마지막 순간이 줄이기 시작한 때입니다.
        return run.subList(from, run.size).last { it.value >= top }.ms
    }

    private fun buildStart(launch: Long, end: Long): MoveForm {
        val window = speed.filter { it.ms in (launch - WINDOW_PAD_MS)..end }
        val accel = Kinematics.accelerations(window).filter { it.ms in launch..end }.maxOfOrNull { it.value } ?: 0f
        return speedMove(MoveKind.START, launch, end, window, peakAccel = accel.coerceAtLeast(0f), finalDecel = 0f)
    }

    private fun speedMove(kind: MoveKind, start: Long, end: Long, window: List<Point>, peakAccel: Float, finalDecel: Float) = MoveForm(
        id = nextId++,
        kind = kind,
        side = null,
        startMs = start,
        endMs = end,
        steering = steering.filter { it.ms in (start - WINDOW_PAD_MS)..end },
        speed = window,
        brakeSpans = brakeSpans(start - WINDOW_PAD_MS, end),
        signalOnMs = null,
        signalLeadM = null,
        signalAfterStart = false,
        brakeDuring = false,
        corrections = 0,
        signalOffDelayS = null,
        entryKmh = (speedAt(start) * 3.6f).roundToInt(),
        apexKmh = 0,
        exitKmh = (speedAt(end) * 3.6f).roundToInt(),
        peakAccelMs2 = peakAccel,
        finalDecelMs2 = finalDecel,
    )

    /**
     * 일정 속도 이상에서 조향이 작게 한쪽으로 갔다가 돌아오면 차선 변경으로 봅니다.
     * 깜빡이 없이 한쪽으로만 꺾였다 돌아온 경우는 굽은 길일 수 있어서, 반대로 펴는 조향(S자)이 있을 때만 인정합니다.
     */
    private fun buildLaneChange(start: Long, calm: Long): MoveForm? {
        val seg = steering.filter { it.ms in start..calm }
        if (seg.isEmpty()) return null
        val peak = seg.maxBy { abs(it.value) }
        if (abs(peak.value) !in LANE_MIN_PEAK_DEG..LANE_MAX_PEAK_DEG) return null
        val dir = seg.first { abs(it.value) >= LANE_START_DEG }.value.sign
        val counter = seg.maxOf { -dir * it.value }
        val side = if (dir > 0) Side.LEFT else Side.RIGHT
        if (signalFor(start, calm) == null && counter < abs(peak.value) * LANE_MIN_COUNTER_RATIO) return null
        // 굽은 길(S자 커브)을 따라갈 때도 핸들은 차선 변경처럼 돌았다 돌아옵니다. 차가 실제로 그린 길로 가립니다.
        val sway = Kinematics.sway(steering.toList(), speed.toList(), start, calm)
        if (sway.maxHeadingDeg > LANE_MAX_HEADING_DEG || abs(sway.sideM) > LANE_MAX_SIDE_M) return null
        // 끝은 핸들을 곧게 편 순간(calm)입니다. 그 뒤 안정 구간은 넣지 않습니다.
        return build(MoveKind.LANE_CHANGE, side, start, calm, peak, laneCorrections(seg))
    }

    private fun build(kind: MoveKind, side: Side, start: Long, end: Long, peak: Point, corrections: Int): MoveForm {
        val sig = signalFor(start, end)
        val signalOn = sig?.first
        val signalAfterStart = signalOn != null && signalOn > start
        val windowStart = minOf(signalOn ?: start, start) - WINDOW_PAD_MS
        val brakeBefore = brake.lastOrNull { it.first <= start }?.second == true
        // 차선 변경은 안정 구간(1초)이 지나야 확정되므로, 그 사이에 끈 깜빡이도 여기서 잡습니다.
        val offAfterStart = signal.firstOrNull { it.second == null && it.first >= start }?.first

        return MoveForm(
            id = nextId++,
            kind = kind,
            side = side,
            startMs = start,
            endMs = end,
            steering = steering.filter { it.ms in windowStart..end },
            speed = speed.filter { it.ms in windowStart..end },
            brakeSpans = brakeSpans(windowStart, end),
            signalOnMs = signalOn,
            signalLeadM = if (signalOn != null && !signalAfterStart) distance(signalOn, start) else null,
            signalAfterStart = signalAfterStart,
            signalWrongSide = sig != null && sig.second != side,
            brakeDuring = brakeBefore || brake.any { it.second && it.first in start..end },
            corrections = corrections,
            signalOffDelayS = offAfterStart?.takeIf { signalOn != null }?.let { (it - end) / 1000f },
            entryKmh = (speedAt(start) * 3.6f).roundToInt(),
            apexKmh = (speedAt(peak.ms) * 3.6f).roundToInt(),
            exitKmh = (speedAt(end) * 3.6f).roundToInt(),
        )
    }

    /**
     * 이 동작에 해당하는 깜빡이: [end] 전에 켰고, [start] 시점까지 꺼지지 않은 마지막 켜짐.
     * 예전 동작에서 켰다가 끈 깜빡이를 다음 동작에 잘못 붙이지 않기 위한 조건입니다.
     */
    private fun signalFor(start: Long, end: Long): Pair<Long, Side>? {
        val on = signal.lastOrNull { it.second != null && it.first <= end } ?: return null
        if (on.first < start && signal.any { it.second == null && it.first in on.first..start }) return null
        return on.first to on.second!!
    }

    private fun speedAt(ms: Long) = speed.lastOrNull { it.ms <= ms }?.value ?: 0f

    /** t0~t1 사이 주행 거리(m). 속도를 계단식으로 적분합니다. */
    private fun distance(t0: Long, t1: Long): Float {
        var d = 0f
        speed.zipWithNext { a, b ->
            val from = maxOf(a.ms, t0)
            val to = minOf(b.ms, t1)
            if (to > from) d += a.value * (to - from) / 1000f
        }
        return d
    }

    private fun brakeSpans(from: Long, to: Long): List<Pair<Long, Long>> {
        val spans = mutableListOf<Pair<Long, Long>>()
        var downAt: Long? = if (brake.lastOrNull { it.first <= from }?.second == true) from else null
        brake.filter { it.first in from..to }.forEach { (ms, pressed) ->
            if (pressed && downAt == null) downAt = ms
            if (!pressed && downAt != null) { spans += downAt!! to ms; downAt = null }
        }
        downAt?.let { spans += it to to }
        return spans
    }

    /** 조향 방향이 바뀐 횟수(작은 흔들림은 무시). */
    private fun directionChanges(seg: List<Point>, noise: Float): Int {
        val dirs = mutableListOf<Int>()
        seg.zipWithNext { a, b ->
            if (abs(b.value - a.value) >= noise) {
                val d = if (b.value > a.value) 1 else -1
                if (dirs.lastOrNull() != d) dirs += d
            }
        }
        return dirs.size
    }

    /** 회전: 꺾고(1) 푸는(2) 것이 기본. 꺾었다 풀었다를 한 번 더 할 때마다 1. */
    private fun turnCorrections(seg: List<Point>) = maxOf(directionChanges(seg, TURN_NOISE_DEG) - 1, 0) / 2

    /** 차선 변경: 옮기고(1) 반대로 펴고(2) 가운데로(3)가 기본 S자. 그 이상 흔들면 1씩. */
    private fun laneCorrections(seg: List<Point>) = maxOf(directionChanges(seg, LANE_NOISE_DEG) - 3, 0) / 2

    private inline fun <T> ArrayDeque<T>.addAndTrim(item: T, now: Long, time: (T) -> Long) {
        addLast(item)
        // 마지막 하나는 남겨 둬야 '그 시점의 상태'를 알 수 있습니다.
        while (size > 1 && now - time(first()) > HISTORY_MS) removeFirst()
    }

    companion object {
        const val TURN_START_DEG = 30f
        const val TURN_MIN_PEAK_DEG = 90f
        const val TURN_NOISE_DEG = 15f

        const val LANE_MIN_KMH = 30f
        const val LANE_START_DEG = 5f
        const val LANE_CALM_DEG = 3f
        const val LANE_MIN_PEAK_DEG = 8f
        const val LANE_MAX_PEAK_DEG = 60f
        const val LANE_NOISE_DEG = 4f
        const val LANE_MIN_COUNTER_RATIO = 0.3f
        const val LANE_SETTLE_MS = 1_000L
        const val LANE_MAX_MS = 15_000L
        const val LANE_AFTER_TURN_MS = 2_000L
        /** 차선 변경은 차 방향이 이보다 덜 틀어집니다(녹화한 차선 변경은 5~7°). */
        const val LANE_MAX_HEADING_DEG = 15f
        /** 차선 변경은 옆으로 차로 하나(3~3.5m)만큼 갑니다. 이보다 멀리 가면 굽은 길을 따라간 것입니다. */
        const val LANE_MAX_SIDE_M = 5f

        const val UTURN_MIN_HEADING_DEG = 135f
        const val UTURN_MIN_PEAK_DEG = 450f

        const val STOPPED_MPS = 0.3f
        const val STOP_MIN_KMH = 15f
        const val START_TARGET_KMH = 20f
        /** 속도가 1km/h 단위로 오므로 '최고 속도에서 떨어지기 시작함'을 이만큼으로 봅니다. */
        const val KMH_STEP_MPS = 0.3f
        const val FINAL_WINDOW_MS = 500L
        /** 속도를 이만큼 넘게 유지했으면 감속이 아니라 달리는 중으로 봅니다. */
        const val CRUISE_MS = 2_500L

        /** 알림 뒤 이만큼 지나도 지점에 닿지 않으면(경로를 벗어나는 등) 잊습니다. */
        const val ROAD_POINT_GIVE_UP_MS = 60_000L
        const val BUMP_AFTER_MS = 1_500L
        const val BUMP_APPROACH_MS = 8_000L
        const val BUMP_LAST_APPROACH_MS = 3_000L
        const val BUMP_BRAKE_WINDOW_MS = 700L
        const val CAMERA_AFTER_MS = 3_000L
        /** 철길 건널목은 이 거리 앞부터 멈췄는지 봅니다. */
        const val RAIL_APPROACH_M = 30.0
        const val RAIL_LEAD_MS = 3_000L
        /**
         * 어린이 보호구역 길이. 내비는 시작 지점만 알려 주므로, 보호구역 지정 범위(주 출입문 반경 300m, 법규 확인 필요)를
         * 기준으로 들어간 뒤 이만큼을 구간으로 봅니다.
         */
        const val SCHOOL_ZONE_LENGTH_M = 300.0
        const val SCHOOL_ZONE_MAX_MS = 150_000L

        /** 어린이 보호구역(300m)을 천천히 지나도 처음부터 볼 수 있게 넉넉히 들고 있습니다. */
        const val HISTORY_MS = 180_000L
        const val WINDOW_PAD_MS = 1_000L
        /** 정지 장면에 담는 다가오는 길(다시보기용). */
        const val STOP_PREROLL_MS = 5_000L
        /** 다시보기에 담는 정지 장면은 길어도 멈추기 전 이만큼까지만. */
        const val STOP_SHOWN_MAX_MS = 14_000L
        const val OFF_WINDOW_MS = 10_000L

        const val SLIP_MIN_KMH = 5f
        const val SLIP_BEFORE_MS = 5_000L
        const val SLIP_AFTER_MS = 3_000L
        const val SLIP_GAP_MS = 10_000L

        /** 이보다 느리게 움직이기 시작하면 주차하려는 것으로 봅니다. */
        const val PARK_SLOW_KMH = 10f
        /** 이보다 느린 회전은 주차장 안에서 돈 것일 수 있어 도로 속도에 오를 때까지 보류합니다. */
        const val GARAGE_TURN_KMH = 20f
        /** 이 속도에 오르면 도로를 달리는 것으로 봅니다. */
        const val ROAD_KMH = 30
        /** 한 번에 이만큼 넘게 방향이 바뀌면 나선 램프나 회전교차로입니다. */
        const val LOOP_HEADING_DEG = 270f
        /** 후진으로 이만큼은 움직여야 주차로 봅니다. */
        const val PARK_MIN_REVERSE_M = 1.0f
        /** 앞뒤로 이만큼 넘게 움직여야 '한 번'으로 셉니다. */
        const val PARK_MIN_SEGMENT_M = 0.3f
    }
}
