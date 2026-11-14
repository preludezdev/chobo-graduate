package dev.driveform.form

import kotlin.math.roundToInt

/**
 * 주행 한 번: P에서 출발해 다시 P에 둘 때까지. 동작은 [MoveForm.tripId]로 이 주행에 묶입니다.
 * [endedAt]이 null이면 아직 달리는 중입니다. 거리는 앱이 속도를 적분해 채우며, 모르면 null입니다.
 */
data class Trip(
    val id: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double? = null,
    /** 이 주행에서 만난 가장 조심해야 할 날씨(와이퍼·안개등으로 판단). */
    val weather: Weather = Weather.CLEAR,
    /** 비·안개 속에서 전조등 없이 달린 시간(초). */
    val lightsOffWetS: Int = 0,
)

/** 요약에서 동작을 묶는 갈래. 길 위 지점(방지턱·카메라·건널목·보호구역)은 한 갈래로 봅니다. */
enum class MoveGroup(val label: String) {
    TURN("회전·유턴"), LANE("차선 변경"), STOP_START("정지·출발"), ROAD("길 위 지점"), PARK("주차");

    companion object {
        fun of(kind: MoveKind) = when {
            kind.isTurn -> TURN
            kind == MoveKind.LANE_CHANGE -> LANE
            kind.isRoadPoint -> ROAD
            kind == MoveKind.PARK -> PARK
            else -> STOP_START
        }
    }
}

data class GroupScore(val group: MoveGroup, val count: Int, val avgScore: Int, val previousAvg: Int?)

/**
 * 이번 주행에서 놓친 점검 항목. 이 갈래의 동작 [of]번 중 [missed]번 놓쳤습니다.
 * [exampleId]는 이 항목에서 가장 크게 감점된 동작으로, 눌러서 폼 카드를 볼 수 있게 합니다.
 */
data class Weakness(
    val group: MoveGroup, val checkTitle: String, val missed: Int, val of: Int, val penalty: Int, val tip: String, val exampleId: Int,
) {
    val label: String get() = "${group.label} · $checkTitle"
}

data class TripSummary(
    val trip: Trip,
    val moves: List<MoveForm>,
    val score: Int,
    val goodCount: Int,
    val groups: List<GroupScore>,
    val weaknesses: List<Weakness>,
    val best: MoveForm?,
    val worst: MoveForm?,
    val previous: TripSummary?,
) {
    val tier: Tier get() = Tier.of(score)

    /** 주행 시간(분). 끝나지 않았으면 마지막 동작까지. */
    val minutes: Int? get() {
        val end = trip.endedAt ?: moves.maxOfOrNull { it.recordedAt } ?: return null
        return ((end - trip.startedAt) / 60_000.0).roundToInt().takeIf { trip.startedAt > 0 && it >= 0 }
    }

    companion object {
        /** 주행 점수에서 아쉬운 동작을 얼마나 무겁게 볼지. 3이면 급제동(55점 깎임) 하나가 동작 13개 주행에서 약 13점을 깎습니다. */
        const val STRICTNESS = 3.0

        /**
         * [moves]는 이 주행의 동작, [previousMoves]는 바로 앞 주행의 동작입니다.
         * 주행 점수는 동작마다 깎인 점수의 평균을 [STRICTNESS]배 해서 100점에서 뺍니다.
         * 그냥 평균을 내면 출퇴근 한 번(동작 13개 안팎)에 급제동이 있어도 96점이 되어, 거의 모든 주행이 '고수'로 나왔습니다.
         */
        fun of(trip: Trip, moves: List<MoveForm>, previous: TripSummary? = null): TripSummary {
            val sorted = moves.sortedBy { it.id }
            val score = if (sorted.isEmpty()) 0
            else (100 - STRICTNESS * sorted.map { 100 - it.score }.average()).roundToInt().coerceIn(0, 100)
            val groups = sorted.groupBy { MoveGroup.of(it.kind) }.toSortedMap().map { (g, ms) ->
                GroupScore(
                    g, ms.size, ms.map { it.score }.average().roundToInt(),
                    previous?.groups?.firstOrNull { it.group == g }?.avgScore,
                )
            }
            val weaknesses = sorted.groupBy { MoveGroup.of(it.kind) }.flatMap { (g, ms) ->
                ms.flatMap { m -> m.checks.filter { !it.ok }.map { m to it } }.groupBy { it.second.title }.map { (title, missed) ->
                    val (example, top) = missed.maxBy { it.second.penalty }
                    Weakness(g, title, missed.size, ms.size, missed.sumOf { it.second.penalty }, top.tip, example.id)
                }
            }.sortedWith(compareByDescending<Weakness> { it.penalty }.thenByDescending { it.missed })
            return TripSummary(
                trip = trip,
                moves = sorted,
                score = score,
                goodCount = sorted.count { it.score >= FormScore.GOOD_SCORE },
                groups = groups,
                weaknesses = weaknesses,
                // 같은 점수면 먼저 한 동작을 고릅니다.
                best = sorted.maxByOrNull { it.score },
                worst = sorted.minByOrNull { it.score }?.takeIf { it.score < FormScore.GOOD_SCORE },
                previous = previous,
            )
        }

        /** 주행마다 요약을 만듭니다. 동작이 없는 주행은 뺍니다. 오래된 순입니다. */
        fun all(trips: List<Trip>, moves: List<MoveForm>): List<TripSummary> {
            val byTrip = moves.groupBy { it.tripId }
            var previous: TripSummary? = null
            return trips.sortedBy { it.id }.mapNotNull { t ->
                val ms = byTrip[t.id].orEmpty()
                if (ms.isEmpty()) null else of(t, ms, previous).also { previous = it.copy(previous = null) }
            }
        }
    }
}

/** 주행 점수의 단계. 90점이 '고수' 기준입니다(초안). */
enum class Tier(val label: String, val min: Int) {
    MASTER("고수", 90), SKILLED("능숙", 80), LEARNING("익히는 중", 65), PRACTICE("연습 필요", 0);

    companion object {
        fun of(score: Int) = entries.first { score >= it.min }
    }
}

object Trips {
    /** 이 간격보다 오래 동작이 없으면 다른 주행으로 봅니다. 주행 구분이 없던 기록을 나눌 때만 씁니다. */
    const val GAP_MS = 10 * 60_000L

    /**
     * 주행 번호가 없는 옛 기록에 번호를 붙이고, 기록은 있는데 주행 정보가 없는 주행(앱이 주행 중에 꺼진 경우)을 되살립니다.
     * 되살린 주행은 첫 동작부터 마지막 동작까지로 보고 거리는 모릅니다.
     */
    fun backfill(trips: List<Trip>, moves: List<MoveForm>): Pair<List<Trip>, List<MoveForm>> {
        var nextId = maxOf(trips.maxOfOrNull { it.id } ?: 0, moves.maxOfOrNull { it.tripId ?: 0 } ?: 0) + 1
        var lastAt = Long.MIN_VALUE
        var current = -1
        val filled = moves.sortedBy { it.id }.map { m ->
            if (m.tripId != null) return@map m
            if (current < 0 || m.recordedAt - lastAt > GAP_MS) current = nextId++
            lastAt = m.recordedAt
            m.copy(tripId = current)
        }
        val known = trips.map { it.id }.toSet()
        val revived = filled.filter { it.tripId !in known }.groupBy { it.tripId!! }.map { (id, ms) ->
            Trip(id, ms.minOf { it.recordedAt }, ms.maxOf { it.recordedAt })
        }
        return (trips + revived).sortedBy { it.id } to filled
    }
}
