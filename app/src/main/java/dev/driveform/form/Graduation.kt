package dev.driveform.form

/**
 * 초보 졸업. 기술 네 가지를 졸업 과목으로 두고, 과목마다 최근 [WINDOW]번 중 [PASS]번 이상 잘하면 이수합니다.
 * 한 번 이수한 과목은 그 뒤에 실수해도 이수로 남깁니다(학점처럼). 네 과목을 모두 이수하면 '초보운전' 스티커를 떼는 졸업입니다.
 *
 * 한두 번 잘한 걸로는 이수되지 않고, 최근 열 번에서 습관이 됐는지를 봅니다.
 */
data class Course(
    val skill: Skill,
    /** 최근 [WINDOW]번의 결과(오래된 순). 아직 그만큼 안 해 봤으면 더 짧습니다. */
    val recent: List<Boolean>,
    /** 이수한 순간(그 동작의 기록 시각). 아직이면 null. */
    val passedAt: Long?,
) {
    val passed: Boolean get() = passedAt != null
    val ok: Int get() = recent.count { it }

    /** 이수까지 남은 '잘함' 수. 최근 열 번 중 [PASS]번(아홉 번)을 채워야 합니다. */
    val toGo: Int get() = if (passed) 0 else (PASS - ok).coerceAtLeast(1)

    /** 진도(0~1). 이수했으면 1, 아니면 최근 열 번 중 잘한 수 / [PASS]. */
    val progress: Float get() = if (passed) 1f else (ok.toFloat() / PASS).coerceAtMost(0.95f)

    companion object {
        const val WINDOW = 10
        const val PASS = 9
    }
}

data class Graduation(val courses: List<Course>) {
    /** 모든 과목을 이수한 순간. 아직이면 null. */
    val graduatedAt: Long? get() = if (courses.all { it.passed }) courses.maxOf { it.passedAt!! } else null
    val graduated: Boolean get() = graduatedAt != null
    val progress: Float get() = courses.map { it.progress }.average().toFloat()
    val remaining: List<Course> get() = courses.filter { !it.passed }

    companion object {
        /**
         * [moves]는 지금까지의 동작 기록입니다(이 시점까지만 넘기면 그때의 졸업 진도가 나옵니다).
         * 기본은 초보 과정 네 과목이고, [Skill.ADVANCED]를 넘기면 심화 과정의 진도입니다(모두 마치면 심화 수료).
         */
        fun of(moves: List<MoveForm>, skills: List<Skill> = Skill.BASIC): Graduation {
            val sorted = moves.sortedBy { m -> m.id }
            return Graduation(skills.map { course(it, sorted) })
        }

        private fun course(skill: Skill, history: List<MoveForm>): Course {
            val window = ArrayDeque<Boolean>()
            var passedAt: Long? = null
            history.forEach { m ->
                val ok = skill.judge(m) ?: return@forEach
                window.addLast(ok)
                if (window.size > Course.WINDOW) window.removeFirst()
                if (passedAt == null && window.count { it } >= Course.PASS) passedAt = m.recordedAt
            }
            return Course(skill, window.toList(), passedAt)
        }
    }
}
