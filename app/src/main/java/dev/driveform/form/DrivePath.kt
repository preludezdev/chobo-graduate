package dev.driveform.form

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/** 위에서 내려다본 위치(m). 처음 진행 방향이 +y, 오른쪽이 +x입니다. */
data class PathPoint(val ms: Long, val x: Float, val y: Float)

/**
 * 속도와 조향각으로 차가 지나간 경로를 계산하고(추측 항법, 자전거 모델), 모범 경로와 표시할 지점을 함께 만듭니다.
 * GPS는 오차가 수 m라서 회전 한 번을 그리기에 부족합니다.
 *
 * 조향비와 축거는 가정값이라 방향 변화가 과장되거나 모자랄 수 있습니다. 그래서 회전은 끝 방향이 90°(또는 180°)가 되도록,
 * 차선 변경은 끝에서 다시 곧게 가도록 보정합니다. 도로 모양에 맞추는 보정이며, 흔들린 모양은 그대로 남습니다.
 */
class DrivePath private constructor(
    val actual: List<PathPoint>,
    val ideal: List<PathPoint>,
    val signalAt: PathPoint?,
    /** 깜빡이를 늦게 켰거나 안 켰을 때, 켰어야 할 지점(시작 30m 전). */
    val signalTarget: PathPoint?,
    val brakeSegments: List<List<PathPoint>>,
    /** 고쳐 꺾은 지점. */
    val corrections: List<PathPoint>,
    /** 차선 변경에서 옆으로 옮겨 간 거리(m, 왼쪽이 음수). */
    val lateralM: Float,
    /** [actual] 각 점의 가속도(m/s², 감속은 음수). 정지·출발 경로를 세기별 색으로 칠할 때 씁니다. */
    val accel: List<Float>,
    /** 정지: 실제로 감속을 시작한 곳. */
    val brakeFrom: PathPoint?,
    /** 정지: 부드럽게(약 0.25g) 멈추려면 감속을 시작했어야 할 곳. */
    val idealBrakeFrom: PathPoint?,
    /** 내비가 알려 준 지점(과속방지턱·단속 카메라·철길 건널목·보호구역 시작)을 지난 곳. */
    val markAt: PathPoint?,
    /**
     * 모범대로라면 미리 해 뒀어야 할 곳. 그렇게 하지 못했을 때만 있습니다.
     * 과속방지턱: 시속 20km로 줄여 뒀어야 할 곳(턱 10m 앞). 단속 카메라: 천천히 줄이기 시작했어야 할 곳.
     * 철길 건널목: 멈췄어야 할 곳(건널목 5m 앞).
     */
    val slowTarget: PathPoint?,
    /** 철길 건널목: 실제로 멈춘 곳. */
    val stopAt: PathPoint?,
    /** 어린이 보호구역: 구간이 끝난 곳. */
    val zoneEnd: PathPoint?,
    /** [actual] 각 점의 속도(km/h). 보호구역에서 제한속도를 넘은 구간을 칠할 때 씁니다. */
    val speedKmh: List<Float>,
    /** 주차: [actual] 각 점이 후진 중이었는지. */
    val reverse: List<Boolean> = emptyList(),
    /** 주차: 앞뒤 방향을 바꾼 곳. */
    val switches: List<PathPoint> = emptyList(),
    /** 주차: 멈춘 채 핸들을 가장 많이 돌린 곳. 제자리 핸들이 아쉬웠을 때만 있습니다. */
    val dryAt: PathPoint? = null,
    /** 주차: 세운 차의 방향(라디안, 0 = +y, 왼쪽으로 돌면 +). 차 앞이 향한 쪽입니다. */
    val parkedHeading: Float = 0f,
) {
    companion object {
        const val IDEAL_LANE_CHANGE_S = 3f
        /** 주차 그림에 남기는 다가온 길(m). */
        private const val APPROACH_M = 3f
        const val LANE_WIDTH_M = 3.5f
        /** 이 이상 감속하면 '브레이크를 밟기 시작했다'고 봅니다. */
        private const val BRAKING_MS2 = 1.0f
        /** 단속 카메라 앞에서 속도를 맞출 때 모범으로 삼는 감속(약 0.15g). */
        private const val CAMERA_EASE_MS2 = 1.5f
        /** 철길 건널목은 이만큼 앞에서 멈추는 것을 모범으로 봅니다. */
        private const val RAIL_STOP_BEFORE_M = 5f

        fun of(m: MoveForm): DrivePath {
            if (m.kind == MoveKind.PARK) return park(m)
            val actual = integrate(m)
            val startIdx = actual.indexOfFirst { it.ms >= m.startMs }.coerceAtLeast(0)
            val start = actual[startIdx]
            val accels = Kinematics.accelerations(m.speed)
            val accel = actual.map { p -> accels.lastOrNull { it.ms <= p.ms }?.value ?: 0f }

            val needsTarget = !m.kind.isSpeedOnly && (
                m.signalWrongSide || m.signalOnMs == null || m.signalAfterStart ||
                    (m.signalLeadM ?: 0f) < FormScore.SIGNAL_TARGET_M
                )
            val target = if (needsTarget) pointBefore(actual, startIdx, FormScore.SIGNAL_TARGET_M) else null

            var brakeFrom: PathPoint? = null
            var idealBrakeFrom: PathPoint? = null
            if (m.kind == MoveKind.STOP) {
                brakeFrom = actual.indices.firstOrNull { actual[it].ms >= m.startMs && accel[it] <= -BRAKING_MS2 }?.let { actual[it] }
                val v0 = m.entryKmh / 3.6f
                val need = v0 * v0 / (2 * FormScore.IDEAL_STOP_MS2)
                // 실제로 늦게 밟았을 때만 '여기서부터 밟기'를 보여 줍니다.
                val brakeDist = brakeFrom?.let { actual.last().y - it.y } ?: 0f
                if (need > brakeDist + 3f) idealBrakeFrom = pointBefore(actual, actual.lastIndex, need)
            }

            val markIdx = m.markMs?.let { mark -> actual.indexOfLast { it.ms <= mark } }?.takeIf { it >= 0 }
            val markAt = markIdx?.let { actual[it] }
            var slowTarget: PathPoint? = null
            var stopAt: PathPoint? = null
            if (markIdx != null) when (m.kind) {
                MoveKind.SPEED_BUMP -> if (m.apexKmh > FormScore.BUMP_GOOD_KMH) {
                    slowTarget = pointBefore(actual, markIdx, FormScore.BUMP_SLOW_BY_M)
                }
                MoveKind.CAMERA -> if (m.peakAccelMs2 > FormScore.SMOOTH_MS2) {
                    // 들어올 때 속도에서 통과 속도까지 약 0.15g로 줄이려면 얼마나 앞에서 시작해야 했는지
                    val v0 = m.entryKmh / 3.6f
                    val v1 = minOf(m.apexKmh, m.limitKmh ?: m.apexKmh) / 3.6f
                    val need = (v0 * v0 - v1 * v1) / (2 * CAMERA_EASE_MS2)
                    if (need > 5f) slowTarget = pointBefore(actual, markIdx, need)
                }
                MoveKind.RAILROAD -> {
                    val speeds = m.speed
                    stopAt = speeds.lastOrNull { it.ms <= m.markMs!! && it.value <= MoveDetector.STOPPED_MPS }
                        ?.let { s -> actual.lastOrNull { it.ms <= s.ms } }
                    if (!m.stoppedBefore) slowTarget = pointBefore(actual, markIdx, RAIL_STOP_BEFORE_M)
                }
                else -> {}
            }
            val zoneEnd = if (m.kind == MoveKind.SCHOOL_ZONE) actual.last() else null
            val speedKmh = actual.map { p -> (m.speed.lastOrNull { it.ms <= p.ms }?.value ?: 0f) * 3.6f }

            // 모범 경로는 표시할 지점까지 거슬러 깔아서 그 지점이 도로 위에 놓이게 합니다.
            val fromY = minOf(
                actual.first().y, target?.y ?: Float.MAX_VALUE, idealBrakeFrom?.y ?: Float.MAX_VALUE, slowTarget?.y ?: Float.MAX_VALUE,
            )
            val ideal = when (m.kind) {
                MoveKind.TURN -> idealTurn(actual, start, fromY)
                MoveKind.U_TURN -> idealUTurn(actual, start, fromY)
                MoveKind.LANE_CHANGE -> idealLane(m, actual, start, fromY)
                MoveKind.PARK -> emptyList()
                MoveKind.STOP, MoveKind.START, MoveKind.SPEED_BUMP, MoveKind.CAMERA, MoveKind.RAILROAD, MoveKind.SCHOOL_ZONE, MoveKind.SLIP -> listOf(
                    PathPoint(0, start.x, fromY),
                    PathPoint(0, start.x, actual.last().y + if (m.kind == MoveKind.STOP) 0f else 5f),
                )
            }

            return DrivePath(
                actual = actual,
                ideal = ideal,
                signalAt = m.signalOnMs?.let { at -> actual.lastOrNull { it.ms <= at } ?: actual.first() },
                signalTarget = target,
                brakeSegments = if (m.kind.isSpeedOnly) emptyList()
                else m.brakeSpans.map { (a, b) -> actual.filter { it.ms in a..b } }.filter { it.size >= 2 },
                // 점수 카드와 같은 횟수만 표시합니다. 고쳐 꺾기 한 번 = 되돌렸다가 다시 꺾는 방향 전환 두 번.
                corrections = reversals(m).drop(if (m.kind.isTurn) 1 else 2)
                    .chunked(2).map { it.first() }.take(m.corrections)
                    .mapNotNull { ms -> actual.lastOrNull { it.ms <= ms } },
                lateralM = actual.last().x - start.x,
                accel = accel,
                brakeFrom = brakeFrom,
                idealBrakeFrom = idealBrakeFrom,
                markAt = markAt,
                slowTarget = slowTarget,
                stopAt = stopAt,
                zoneEnd = zoneEnd,
                speedKmh = speedKmh,
            )
        }

        /** 속도 이벤트마다 그 순간의 조향각이 유지된다고 보고 적분합니다. */
        private fun integrate(m: MoveForm): List<PathPoint> {
            val speed = m.speed
            if (speed.size < 2) return listOf(PathPoint(m.startMs, 0f, 0f))
            // 1) 구간마다 방향 변화량(라디안)
            val dPsi = Kinematics.yawSteps(m.steering, speed)
            // 2) 도로 모양에 맞춘 보정
            val inMove = { i: Int -> speed[i].ms >= m.startMs && speed[i].ms < m.endMs }
            val raw = dPsi.indices.filter(inMove).sumOf { dPsi[it].toDouble() }.toFloat()
            when {
                m.kind.isTurn && abs(raw) > 0.01f -> {
                    // 교차로 회전은 90°, 유턴은 180°로 맞춥니다.
                    val target = (if (m.kind == MoveKind.U_TURN) PI else PI / 2).toFloat()
                    val k = sign(raw) * target / raw
                    dPsi.indices.filter(inMove).forEach { dPsi[it] *= k }
                }
                m.kind == MoveKind.LANE_CHANGE -> {
                    val total = (m.endMs - m.startMs).coerceAtLeast(1)
                    dPsi.indices.filter(inMove).forEach { i ->
                        dPsi[i] -= raw * (speed[i + 1].ms - speed[i].ms) / total
                    }
                }
                // 정지·출발은 곧은 길로 그립니다.
                m.kind.isSpeedOnly -> dPsi.fill(0f)
            }
            // 3) 위치 적분. 방향 0 = +y, 왼쪽으로 돌면 +, 이때 x는 줄어듭니다.
            var x = 0f
            var y = 0f
            var heading = 0f
            val pts = mutableListOf(PathPoint(speed[0].ms, 0f, 0f))
            for (i in 0 until speed.size - 1) {
                val dt = (speed[i + 1].ms - speed[i].ms) / 1000f
                heading += dPsi[i]
                x += -sin(heading) * speed[i].value * dt
                y += cos(heading) * speed[i].value * dt
                pts += PathPoint(speed[i + 1].ms, x, y)
            }
            // 4) 차선 변경은 옆으로 정확히 한 차로 옮겨 간 것으로 맞춥니다(가정한 조향비로는 옆 이동이 작게 나옵니다).
            val scaled = if (m.kind == MoveKind.LANE_CHANGE) {
                val startX = pts.lastOrNull { it.ms <= m.startMs }?.x ?: 0f
                val lateral = pts.last().x - startX
                val k = if (abs(lateral) > 0.05f) (LANE_WIDTH_M / abs(lateral)).coerceIn(0.2f, 20f) else 1f
                pts.map { p -> if (p.ms > m.startMs) p.copy(x = startX + (p.x - startX) * k) else p }
            } else pts
            // 멈춰 있던 구간은 점이 겹치므로 줄입니다.
            return scaled.filterIndexed { i, p -> i == 0 || hypot(p.x - scaled[i - 1].x, p.y - scaled[i - 1].y) > 0.01f || p.ms >= m.startMs }
        }

        /**
         * 주차: 후진 구간은 속도를 음수로 두고 그대로 적분합니다. 주차칸 위치는 모르므로 모범 경로는 없고,
         * 앞으로 간 길과 뒤로 간 길, 방향을 바꾼 곳, 세운 차의 자세를 보여 줍니다. 회전처럼 각도를 맞추는 보정은 하지 않습니다.
         */
        private fun park(m: MoveForm): DrivePath {
            fun back(ms: Long) = m.reverseSpans.any { ms >= it.first && ms < it.second }
            val signed = m.speed.map { if (back(it.ms)) it.copy(value = -it.value) else it }
            val pts = mutableListOf<PathPoint>()
            var heading = 0f
            if (signed.size >= 2) {
                val dPsi = Kinematics.yawSteps(m.steering, signed)
                var x = 0f
                var y = 0f
                pts += PathPoint(signed[0].ms, 0f, 0f)
                for (i in 0 until signed.size - 1) {
                    val dt = (signed[i + 1].ms - signed[i].ms) / 1000f
                    heading += dPsi[i]
                    x += -sin(heading) * signed[i].value * dt
                    y += cos(heading) * signed[i].value * dt
                    pts += PathPoint(signed[i + 1].ms, x, y)
                }
            } else pts += PathPoint(m.startMs, 0f, 0f)
            // 멈춰 있던 점은 겹치므로 줄이되, 방향을 바꾼 순간은 남깁니다.
            val dense = pts.filterIndexed { i, p -> i == 0 || i == pts.lastIndex || hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y) > 0.01f }
            // 주차하러 곧게 다가온 길은 [APPROACH_M]만 남겨, 그림이 주차 동작에 맞춰 크게 보이게 합니다.
            val firstTurn = m.steering.firstOrNull { kotlin.math.abs(it.value) >= MoveDetector.TURN_START_DEG && it.ms >= m.startMs }?.ms
            val firstBack = m.reverseSpans.firstOrNull()?.first
            val action = listOfNotNull(firstTurn, firstBack).minOrNull()
            val from = action?.let { a -> dense.indexOfLast { it.ms <= a } }?.takeIf { it > 0 }?.let { idx ->
                var left = APPROACH_M
                var k = idx
                while (k > 0 && left > 0f) { left -= hypot(dense[k].x - dense[k - 1].x, dense[k].y - dense[k - 1].y); k-- }
                k
            } ?: 0
            val actual = dense.drop(from)
            val reverse = actual.map { back(it.ms - 1) }
            val switches = actual.indices.drop(1).filter { reverse[it] != reverse[it - 1] && it > 1 }.map { actual[it - 1] }
            val dryAt = if (m.dryTurnDeg > FormScore.DRY_TURN_OK_DEG) {
                // 멈춘 채 돌린 양이 가장 큰 순간의 자리.
                val stopped = m.steering.zipWithNext().filter { (a, b) ->
                    (m.speed.lastOrNull { it.ms <= b.ms }?.value ?: 0f) <= MoveDetector.STOPPED_MPS && b.ms in m.startMs..m.endMs
                }.maxByOrNull { (a, b) -> abs(b.value - a.value) }
                stopped?.let { (_, b) -> actual.lastOrNull { it.ms <= b.ms } }
            } else null
            return DrivePath(
                actual = actual, ideal = emptyList(), signalAt = null, signalTarget = null, brakeSegments = emptyList(),
                corrections = emptyList(), lateralM = 0f, accel = actual.map { 0f }, brakeFrom = null, idealBrakeFrom = null,
                markAt = null, slowTarget = null, stopAt = null, zoneEnd = null,
                speedKmh = actual.map { p -> (m.speed.lastOrNull { it.ms <= p.ms }?.value ?: 0f) * 3.6f },
                reverse = reverse, switches = switches, dryAt = dryAt, parkedHeading = heading,
            )
        }

        /** 시작점에서 반원을 그려 반대 방향으로 돌아 나오는 유턴. */
        private fun idealUTurn(actual: List<PathPoint>, start: PathPoint, fromY: Float): List<PathPoint> {
            val end = actual.last()
            val dir = if (end.x < start.x) -1f else 1f
            val r = (abs(end.x - start.x) / 2f).coerceAtLeast(3f)
            val pts = mutableListOf(PathPoint(0, start.x, fromY), PathPoint(0, start.x, start.y))
            for (i in 1..32) {
                val a = (PI * i / 32).toFloat()
                pts += PathPoint(0, start.x + dir * r * (1 - cos(a)), start.y + r * sin(a))
            }
            pts += PathPoint(0, start.x + dir * 2 * r, minOf(end.y, start.y) - 5f)
            return pts
        }

        /** 시작점에서 같은 반지름으로 한 번에 돌아 실제 끝 방향과 맞는 곡선. 앞뒤는 직선입니다. */
        private fun idealTurn(actual: List<PathPoint>, start: PathPoint, fromY: Float): List<PathPoint> {
            val end = actual.last()
            val left = end.x < start.x
            val dir = if (left) -1f else 1f
            val r = ((abs(end.x - start.x) + (end.y - start.y)) / 2f).coerceAtLeast(3f)
            val pts = mutableListOf(PathPoint(0, start.x, fromY), PathPoint(0, start.x, start.y))
            for (i in 1..24) {
                val a = (PI / 2 * i / 24).toFloat()
                pts += PathPoint(0, start.x + dir * r * (1 - cos(a)), start.y + r * sin(a))
            }
            val exitLen = (abs(end.x - start.x) - r).coerceAtLeast(0f) + 5f
            pts += PathPoint(0, start.x + dir * (r + exitLen), start.y + r)
            return pts
        }

        /** 같은 폭만큼 [IDEAL_LANE_CHANGE_S]초에 걸쳐 부드럽게 옮겨 가는 S자. */
        private fun idealLane(m: MoveForm, actual: List<PathPoint>, start: PathPoint, fromY: Float): List<PathPoint> {
            val lateral = actual.last().x - start.x
            val avg = m.speed.filter { it.ms in m.startMs..m.endMs }.map { it.value }.average().toFloat().takeIf { !it.isNaN() } ?: 10f
            val d = avg * IDEAL_LANE_CHANGE_S
            val pts = mutableListOf(PathPoint(0, start.x, fromY), PathPoint(0, start.x, start.y))
            for (i in 1..24) {
                val s = d * i / 24
                pts += PathPoint(0, start.x + lateral * (1 - cos(PI.toFloat() * i / 24)) / 2, start.y + s)
            }
            pts += PathPoint(0, start.x + lateral, maxOf(actual.last().y, start.y + d) + 5f)
            return pts
        }

        /** 시작점에서 경로를 거슬러 [dist]m 떨어진 곳. 경로가 짧으면 직선으로 연장합니다(시작 전에는 곧게 달렸다고 봅니다). */
        private fun pointBefore(actual: List<PathPoint>, startIdx: Int, dist: Float): PathPoint {
            var left = dist
            for (i in startIdx downTo 1) {
                val a = actual[i]
                val b = actual[i - 1]
                val seg = hypot(a.x - b.x, a.y - b.y)
                if (seg >= left) {
                    val t = left / seg
                    return PathPoint(b.ms, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
                left -= seg
            }
            val first = actual.first()
            return PathPoint(first.ms, first.x, first.y - left)
        }

        /** 동작 구간에서 조향 방향이 바뀐 시각들. 검출기가 고쳐 꺾기를 셀 때와 같은 기준입니다. */
        private fun reversals(m: MoveForm): List<Long> {
            val noise = if (m.kind == MoveKind.TURN) MoveDetector.TURN_NOISE_DEG else MoveDetector.LANE_NOISE_DEG
            val out = mutableListOf<Long>()
            var dir = 0
            m.steering.filter { it.ms in m.startMs..m.endMs }.zipWithNext { a, b ->
                if (abs(b.value - a.value) < noise) return@zipWithNext
                val d = if (b.value > a.value) 1 else -1
                if (dir != 0 && d != dir) out += a.ms
                dir = d
            }
            return out
        }
    }
}
