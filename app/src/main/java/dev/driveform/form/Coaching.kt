package dev.driveform.form

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 과정. 초보 과정(네 과목)을 마치면 졸업하고(딱지 떼기), 그다음은 심화 과정입니다. */
enum class Stage { BASIC, ADVANCED }

/**
 * 주행 요약에서 키워 가는 기술(과목). 동작 하나를 보고 이 기술로 잘했는지(true/false) 판단하고,
 * 이 기술과 상관없는 동작이면 null입니다.
 * - 초보 과정: 깜빡이, 핸들, 정지·출발, 방지턱·카메라. 매일 운전하며 몸에 익히는 기본기입니다.
 * - 심화 과정: 주차, 빗길·안갯길, 밤길. 졸업 뒤에도 초보가 오래 어려워하는 순간들입니다.
 *   빗길·밤길은 같은 기본기를 더 어려운 조건에서 해내는지 보며, 날씨와 시간은 고를 수 없으니 [situational]입니다.
 */
enum class Skill(
    val label: String,
    private val topic: String,
    val mission: String,
    val stage: Stage = Stage.BASIC,
    /** 비·밤처럼 기회가 와야 연습할 수 있는 과목. 이번 주 과제로는 고르지 않습니다. */
    val situational: Boolean = false,
) {
    SIGNAL("깜빡이 습관", "깜빡이 습관은", "꺾기 30m 전에 깜빡이 켜기"),
    STEER("핸들 조작", "핸들 조작은", "한 번에 꺾고 한 번에 풀기"),
    SMOOTH("부드러운 정지·출발", "정지·출발은", "멈출 곳이 보이면 일찍, 살살 밟기"),
    ROAD("방지턱·카메라", "방지턱·카메라 앞은", "방지턱·카메라 앞에서 미리 속도 맞추기"),
    PARK("주차", "주차는", "뒤로 한 번에, 천천히 넣기", Stage.ADVANCED),
    WET("빗길·안갯길", "빗길·안갯길에서는", "", Stage.ADVANCED, situational = true),
    NIGHT("밤길", "밤길에서는", "", Stage.ADVANCED, situational = true);

    fun judge(m: MoveForm): Boolean? = when (this) {
        SIGNAL -> if (m.steers) m.checks.firstOrNull { it.title == "깜빡이" }?.ok else null
        STEER -> if (m.steers) m.checks.filter { !it.title.startsWith("깜빡이") }.all { it.ok } else null
        SMOOTH -> if (m.kind == MoveKind.STOP || m.kind == MoveKind.START) m.checks.all { it.ok } else null
        ROAD -> if (m.kind.isRoadPoint) m.checks.all { it.ok } else null
        PARK -> if (m.kind == MoveKind.PARK) m.checks.all { it.ok } else null
        WET -> if (m.weather != Weather.CLEAR) basics(m) else null
        NIGHT -> if (m.night) basics(m) else null
    }

    /** 이 동작을 초보 과정 기준으로 다 잘했는지. 미끄러질 뻔한 순간은 아쉬움입니다. */
    private fun basics(m: MoveForm): Boolean? {
        if (m.kind == MoveKind.SLIP) return false
        val judged = BASIC.mapNotNull { it.judge(m) }
        return if (judged.isEmpty()) null else judged.all { it }
    }

    companion object {
        val BASIC: List<Skill> get() = entries.filter { it.stage == Stage.BASIC }
        val ADVANCED: List<Skill> get() = entries.filter { it.stage == Stage.ADVANCED }
    }

    /** 이번 주행에서 이 기술을 [n]번 모두 잘했을 때의 칭찬. */
    fun praise(n: Int): String {
        val all = if (n >= 2) "${n}번 모두 " else ""
        // 옆자리 선생님이 말하듯 칭찬합니다.
        return when (this) {
            SIGNAL -> "깜빡이를 ${all}미리 켜셨어요."
            STEER -> if (n >= 2) "핸들 다루는 게 많이 늘었어요. ${n}번 모두 깔끔했어요." else "핸들을 깔끔하게 다루셨어요."
            SMOOTH -> "출발도 정지도 ${all}부드러웠어요."
            ROAD -> "방지턱이랑 카메라 앞에서 ${all}잘 줄이셨어요."
            PARK -> "주차를 ${all}한 번에 넣으셨어요."
            WET -> "빗길에서도 ${all}차분하게 하셨어요."
            NIGHT -> "밤길에서도 ${all}깔끔하게 하셨어요."
        }
    }

    fun partly(ok: Int, total: Int) = "$topic ${total}번 중 ${ok}번 잘하셨어요."
}

private val MoveForm.steers get() = kind.isTurn || kind == MoveKind.LANE_CHANGE

/**
 * 기술 하나의 기록. [recent]는 최근 [Coaching.RECENT]번 중 잘한 비율(0~100), 기록이 없으면 null.
 * [streak]은 가장 최근부터 잇달아 잘한 횟수, [bestStreak]은 지금까지 가장 길게 이어진 횟수입니다.
 */
data class SkillStat(
    val skill: Skill,
    val recent: Int?,
    val recentTotal: Int,
    val tripOk: Int,
    val tripTotal: Int,
    val streak: Int,
    val bestStreak: Int,
)

/**
 * 이번 주 미션: 한 주 동안 하나의 기술만 [target]번 연속 성공하기. [attempts]는 이번 주에 이 기술을 해 본 결과(오래된 순)입니다.
 * 출퇴근 한 번에도 깜빡이를 켤 일이 다섯 번은 있어서, '몇 번 성공'은 월요일 아침에 끝나 버립니다. 그래서 습관이 됐는지를 보는 연속 성공으로 셉니다.
 */
data class Mission(val skill: Skill, val attempts: List<Boolean>, val target: Int = Coaching.MISSION_TARGET) {
    /** 지금 이어지고 있는 연속 성공. */
    val run: Int get() = attempts.takeLastWhile { it }.size

    /** 이번 주에 가장 길게 이어진 연속 성공. */
    val bestRun: Int get() {
        var best = 0
        var now = 0
        attempts.forEach { ok -> now = if (ok) now + 1 else 0; best = maxOf(best, now) }
        return best
    }

    val completed: Boolean get() = bestRun >= target

    /** 진행 막대에 채울 칸 수. 한 번 해내면 그 주는 다 찬 채로 둡니다. */
    val done: Int get() = if (completed) target else run
}

data class Coaching(
    /** 모든 과목의 기록. 화면에는 [current] 과정의 과목만 보여 줍니다. */
    val stats: List<SkillStat>,
    /** 이 주행을 마친 시점의 졸업 진도. */
    val graduation: Graduation,
    /** 이 주행을 마친 시점의 심화 과정 진도. */
    val advanced: Graduation,
    val mission: Mission,
    /** 가장 길게 이어지고 있는 연속 기록. 2번 미만이면 null. */
    val streak: SkillStat?,
    /** 코치의 한마디: 잘한 것 한 문장. */
    val praise: String,
    val trend: String?,
) {
    /** 지금 다니는 과정: 졸업 전엔 초보 과정, 졸업 뒤엔 심화 과정. */
    val stage: Stage get() = if (graduation.graduated) Stage.ADVANCED else Stage.BASIC
    val current: List<SkillStat> get() = stats.filter { it.skill.stage == stage }

    companion object {
        const val RECENT = 30
        const val MISSION_TARGET = 5
        /** 미션을 고를 때 기술마다 이만큼은 기록이 있어야 비교합니다. */
        const val MISSION_MIN_SAMPLES = 3

        /** [all]은 모든 동작 기록입니다. 이 주행의 마지막 동작까지만 봅니다. */
        fun of(s: TripSummary, all: List<MoveForm>, zone: ZoneId = ZoneId.systemDefault()): Coaching {
            val lastId = s.moves.maxOfOrNull { it.id } ?: 0
            val history = all.filter { it.id <= lastId }.sortedBy { it.id }
            val stats = Skill.entries.map { stat(it, history, s.moves) }

            val graduation = Graduation.of(history)
            // 졸업 뒤엔 심화 과목인 주차를 깔끔하게 했으면 그것부터 칭찬합니다.
            val park = stats.first { it.skill == Skill.PARK }
            val parkPraise = if (graduation.graduated && park.tripTotal > 0 && park.tripOk == park.tripTotal) Skill.PARK.praise(park.tripTotal) else null
            return Coaching(
                stats = stats,
                graduation = graduation,
                advanced = Graduation.of(history, Skill.ADVANCED),
                mission = missionAt(s.trip.startedAt, history, zone),
                streak = stats.filter { it.streak >= 2 }.maxByOrNull { it.streak },
                // 비·밤 칭찬은 '처음 해 본 것' 칭찬이 따로 있어서 여기선 기술만 봅니다.
                praise = parkPraise ?: praise(stats.filter { !it.skill.situational }),
                trend = s.previous?.let { p ->
                    val d = s.score - p.score
                    when {
                        d > 3 -> "지난번보다 ${d}점 올랐어요"
                        d < -3 -> "지난번보다 ${-d}점 내려갔어요"
                        else -> "지난번이랑 비슷해요"
                    }
                },
            )
        }

        /**
         * [at]이 속한 주의 연수 과제. [history]는 그때까지의 동작 기록입니다.
         * 과제는 주가 시작할 때 아직 이수하지 못한 과목 중 가장 약한 것입니다(다 이수했으면 전체 중에서).
         */
        fun missionAt(at: Long, history: List<MoveForm>, zone: ZoneId = ZoneId.systemDefault()): Mission {
            val weekStart = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
            val before = history.filter { it.recordedAt < weekStart }
            // 졸업 전엔 초보 과목, 졸업 뒤엔 심화 과목(비·밤은 고를 수 없으니 빼고), 다 마치면 전체에서 고릅니다.
            val choosable = Skill.entries.filter { !it.situational }
            val open = Graduation.of(before).remaining.map { it.skill }
                .ifEmpty { Graduation.of(before, Skill.ADVANCED).remaining.map { it.skill }.filter { it in choosable } }
                .ifEmpty { choosable }
            val skill = weakest(before, MISSION_MIN_SAMPLES, open) ?: weakest(history, 1, open) ?: open.first()
            return Mission(skill, history.filter { it.recordedAt >= weekStart }.mapNotNull { skill.judge(it) })
        }

        private fun stat(skill: Skill, history: List<MoveForm>, today: List<MoveForm>): SkillStat {
            val judged = history.mapNotNull { skill.judge(it) }
            val recent = judged.takeLast(RECENT)
            var best = 0
            var run = 0
            judged.forEach { ok -> run = if (ok) run + 1 else 0; best = maxOf(best, run) }
            val tripJudged = today.mapNotNull { skill.judge(it) }
            return SkillStat(
                skill = skill,
                recent = if (recent.isEmpty()) null else recent.count { it } * 100 / recent.size,
                recentTotal = recent.size,
                tripOk = tripJudged.count { it },
                tripTotal = tripJudged.size,
                streak = judged.takeLastWhile { it }.size,
                bestStreak = best,
            )
        }

        private fun weakest(moves: List<MoveForm>, minSamples: Int, among: List<Skill>): Skill? = among.mapNotNull { skill ->
            val judged = moves.mapNotNull { skill.judge(it) }
            if (judged.size < minSamples) null else skill to judged.count { it }.toDouble() / judged.size
        }.minByOrNull { it.second }?.first

        /** 잘한 것부터 말합니다. 이번 주행에서 모두 잘한 기술 중 가장 많이 한 것, 없으면 가장 잘한 비율의 기술. */
        private fun praise(stats: List<SkillStat>): String {
            val done = stats.filter { it.tripTotal > 0 }
            done.filter { it.tripOk == it.tripTotal }.maxByOrNull { it.tripTotal }?.let { return it.skill.praise(it.tripTotal) }
            done.filter { it.tripOk > 0 }.maxByOrNull { it.tripOk.toDouble() / it.tripTotal }
                ?.let { return it.skill.partly(it.tripOk, it.tripTotal) }
            return "오늘도 안전하게 잘 다녀오셨어요."
        }

        /** 점검 항목을 말하듯 풀어 씁니다. */
        fun soft(m: MoveForm, checkTitle: String): String = when (checkTitle) {
            "깜빡이" -> "${m.title}에서 " + when {
                m.signalWrongSide -> "반대쪽 깜빡이를 켰어요"
                m.signalAfterStart -> "움직인 뒤에야 깜빡이를 켰어요"
                m.signalLeadM == null -> "깜빡이를 켜지 않았어요"
                else -> "깜빡이가 조금 늦었어요"
            }
            "깜빡이 끄기" -> "깜빡이를 늦게 껐어요"
            "조향" -> if (m.kind == MoveKind.LANE_CHANGE && m.durationS < FormScore.LANE_MIN_DURATION_S) "차선을 급하게 바꿨어요"
                else "${m.title}에서 핸들을 고쳐 잡았어요"
            "감속" -> when {
                m.kind == MoveKind.STOP -> "조금 급하게 멈췄어요"
                m.kind.isTurn -> "꺾으면서 브레이크를 밟았어요"
                else -> "차선을 바꾸며 브레이크를 밟았어요"
            }
            "멈출 때" -> "멈추는 순간 울컥했어요"
            "가속" -> "조금 급하게 출발했어요"
            "넘을 때" -> "방지턱을 조금 빠르게 넘었어요"
            "턱 위" -> "방지턱 위에서 브레이크를 밟았어요"
            "미리 감속" -> "방지턱 바로 앞에서 급하게 줄였어요"
            "통과 속도" -> "단속 카메라 앞에서 제한속도를 넘었어요"
            "카메라 앞" -> "카메라 앞에서 급하게 줄였어요"
            "지난 뒤" -> "카메라를 지나자마자 속도를 올렸어요"
            "일시정지" -> "건널목 앞에서 멈추지 않았어요"
            "건널 때" -> "건널목을 조금 빠르게 건넜어요"
            "최고 속도" -> "보호구역에서 제한속도를 넘었어요"
            "급조작" -> "보호구역에서 급하게 조작했어요"
            "미끄러짐" -> "차가 미끄러질 뻔한 순간이 있었어요"
            "넣기" -> "주차를 ${m.tries}번 만에 넣었어요"
            "후진 속도" -> "후진이 조금 빨랐어요"
            "제자리 핸들" -> "멈춘 채로 핸들을 돌렸어요"
            "바퀴 정렬" -> "바퀴가 꺾인 채로 세웠어요"
            else -> "${m.title}에서 ${checkTitle}이 아쉬웠어요"
        }
    }
}
