package dev.driveform.form

import java.time.ZoneId

/**
 * 운전 중 불빛·소리로 알려 줄 것. 화면은 주행 중에 가려지므로 이것만이 운전자와 이야기하는 길입니다.
 * 잘할 때마다 반응하면 출퇴근 한 번에 열 번 넘게 울려 금방 소음이 되므로, 이번 주 연수 과제에만 반응하고
 * 과제 완료와 졸업 같은 큰 순간은 다르게 알려 줍니다. 아쉬운 동작에는 반응하지 않습니다(운전 중에 혼내면 더 긴장합니다).
 * 주차는 P에 둔 순간이라 운전에 방해되지 않으므로, 한 번에 깔끔하게 넣으면 과제와 상관없이 알려 줍니다.
 */
sealed interface DriveCue {
    /** 이번 주 과제를 잘했음. [count]는 지금 이어지는 연속 횟수(과제를 이미 끝낸 주에는 1)로, 그만큼 짧게 깜빡입니다. */
    data class Success(val count: Int) : DriveCue
    /** 이번 주 과제를 방금 끝냄(연속 [Coaching.MISSION_TARGET]번). */
    data object MissionDone : DriveCue
    /** 마지막 과목을 방금 이수해 졸업이 확정됨. */
    data object Graduated : DriveCue
    /** 심화 과목 하나를 방금 마쳐 배지를 받음. */
    data class Badge(val skill: Skill) : DriveCue
    /** 주차를 한 번에 깔끔하게 넣음(이번 주 과제가 주차가 아닐 때). */
    data object Parked : DriveCue

    companion object {
        /** [before]는 [move] 전까지의 기록, [after]는 [move]까지 넣은 기록입니다. 알릴 것이 없으면 null. */
        fun of(move: MoveForm, before: List<MoveForm>, after: List<MoveForm>, zone: ZoneId = ZoneId.systemDefault()): DriveCue? {
            if (!Graduation.of(before).graduated && Graduation.of(after).graduated) return Graduated
            val advWas = Graduation.of(before, Skill.ADVANCED)
            Graduation.of(after, Skill.ADVANCED).courses.firstOrNull { c -> c.passed && advWas.courses.first { it.skill == c.skill }.passedAt == null }
                ?.let { return Badge(it.skill) }
            val was = Coaching.missionAt(move.recordedAt, before, zone)
            val now = Coaching.missionAt(move.recordedAt, after, zone)
            if (now.skill.judge(move) != true) return if (move.kind == MoveKind.PARK && Skill.PARK.judge(move) == true) Parked else null
            return when {
                !was.completed && now.completed -> MissionDone
                was.completed -> Success(1)
                else -> Success(now.run.coerceIn(1, Coaching.MISSION_TARGET))
            }
        }
    }
}
