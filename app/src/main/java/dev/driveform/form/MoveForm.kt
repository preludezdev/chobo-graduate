package dev.driveform.form

import kotlin.math.abs
import kotlin.math.roundToInt

data class Point(val ms: Long, val value: Float)

enum class Side { LEFT, RIGHT }

/** 기어. 차에서 오는 값을 주차·후진·중립·주행 넷으로 줄여 씁니다. */
enum class Gear { P, R, N, D }

enum class MoveKind {
    TURN, U_TURN, LANE_CHANGE, STOP, START, SPEED_BUMP, CAMERA, RAILROAD, SCHOOL_ZONE,
    /** 차체 자세 제어(ESC)가 미끄러지는 차를 잡아 준 순간. 졸업 과목에는 넣지 않고 장면으로만 남깁니다. */
    SLIP,
    /** 후진이 들어간 주차: 천천히 움직이기 시작해 P에 둘 때까지. 심화 과정의 '주차' 과목입니다. */
    PARK;

    /** 핸들을 크게 돌리는 동작. 깜빡이·감속·조향을 같은 기준으로 봅니다. */
    val isTurn: Boolean get() = this == TURN || this == U_TURN

    /** 속도만 보는 동작. 방향(Side)이 없습니다. */
    val isSpeedOnly: Boolean get() = this == STOP || this == START || this == SLIP || isRoadPoint

    /** 내비가 알려 주는 길 위 지점(과속방지턱, 단속 카메라, 철길 건널목, 어린이 보호구역)을 지나는 동작. */
    val isRoadPoint: Boolean get() = this == SPEED_BUMP || this == CAMERA || this == RAILROAD || this == SCHOOL_ZONE
}

/** 동작 한 번의 기록과 폼 지표. 조향각은 SDK 그대로(왼쪽이 양수)입니다. */
data class MoveForm(
    val id: Int,
    /** 정지·출발은 방향이 없어 null입니다. */
    val side: Side?,
    val startMs: Long,
    val endMs: Long,
    val steering: List<Point>,
    val speed: List<Point>,
    val brakeSpans: List<Pair<Long, Long>>,
    val signalOnMs: Long?,
    /** 깜빡이를 켠 뒤 움직이기 시작할 때까지 달린 거리(m). 안 켰거나 시작한 뒤에 켰으면 null. */
    val signalLeadM: Float?,
    val signalAfterStart: Boolean,
    val brakeDuring: Boolean,
    val corrections: Int,
    val signalOffDelayS: Float?,
    val entryKmh: Int,
    val apexKmh: Int,
    val exitKmh: Int,
    val kind: MoveKind = MoveKind.TURN,
    /** 반대쪽 깜빡이를 켰는지. */
    val signalWrongSide: Boolean = false,
    /** 움직이기 시작해서 다시 곧게 펼 때까지 걸린 시간(초). 차선 변경 평가에 씁니다. */
    val durationS: Float = (endMs - startMs) / 1000f,
    /** 정지는 가장 셌던 감속, 출발은 가장 셌던 가속(m/s², 크기). */
    val peakAccelMs2: Float = 0f,
    /** 정지 직전 0.5초의 감속(m/s²). 크면 멈출 때 '울컥'합니다. */
    val finalDecelMs2: Float = 0f,
    /** 과속방지턱을 넘은 순간. 넘을 때 속도는 [apexKmh]에 둡니다. */
    val markMs: Long? = null,
    /** 내비가 알려 준 제한속도(km/h). 단속 카메라, 어린이 보호구역에 씁니다. */
    val limitKmh: Int? = null,
    /** 단속 카메라를 지난 직후 3초 동안 가장 셌던 가속(m/s²). '캥거루 운전'을 봅니다. */
    val afterAccelMs2: Float = 0f,
    /** 철길 건널목 앞에서 멈췄는지. */
    val stoppedBefore: Boolean = false,
    /** 어린이 보호구역을 벗어난 순간. 들어간 순간은 [markMs]입니다. */
    val zoneEndMs: Long? = null,
    /** 아래 값은 앱이 동작 직후에 채웁니다. 위치는 기기 밖으로 보내지 않습니다. */
    val recordedAt: Long = 0L,
    val lat: Double? = null,
    val lon: Double? = null,
    val intersectionId: Int? = null,
    /** 차 안의 내비가 알려 준 주소. 받지 못하면 null입니다. */
    val place: String? = null,
    /** 이 동작을 한 주행([Trip.id]). */
    val tripId: Int? = null,
    /** 이 동작을 할 때의 날씨. 비·안개면 감속 기준을 엄하게 봅니다. */
    val weather: Weather = Weather.CLEAR,
    /** 해가 진 뒤(밤)에 한 동작인지. 앱이 기록할 때 시각으로 채웁니다. 심화 과정의 '밤길' 과목에 씁니다. */
    val night: Boolean = false,
    /** 주차: 후진(R)에 있던 구간. 경로를 앞뒤로 나눠 그릴 때 씁니다. */
    val reverseSpans: List<Pair<Long, Long>> = emptyList(),
    /** 주차: 차가 멈춘 채로 돌린 핸들 각도의 합(도). '제자리 핸들'을 봅니다. */
    val dryTurnDeg: Int = 0,
    /** 주차: P에 둘 때의 핸들 각도(도). 바퀴를 펴고 세웠는지 봅니다. */
    val finalSteerDeg: Int = 0,
) {
    /** 주차: 몇 번 만에 넣었는지(처음 다가가는 전진은 빼고, 앞뒤로 움직인 횟수). 고쳐 넣은 횟수는 [corrections]입니다. */
    val tries: Int get() = corrections + 1
    val title: String get() = when (kind) {
        MoveKind.TURN -> if (side == Side.LEFT) "좌회전" else "우회전"
        MoveKind.U_TURN -> "유턴"
        MoveKind.LANE_CHANGE -> if (side == Side.LEFT) "왼쪽 차선 변경" else "오른쪽 차선 변경"
        MoveKind.STOP -> "정지"
        MoveKind.START -> "출발"
        MoveKind.SPEED_BUMP -> "과속방지턱"
        MoveKind.CAMERA -> "단속 카메라"
        MoveKind.RAILROAD -> "철길 건널목"
        MoveKind.SCHOOL_ZONE -> "어린이 보호구역"
        MoveKind.SLIP -> "미끄러질 뻔함"
        MoveKind.PARK -> "주차"
    }
    val peakDeg: Float get() = steering.maxOfOrNull { abs(it.value) } ?: 0f
    /** 점검 결과는 값이 바뀌지 않으므로 한 번만 계산합니다(목록·요약·졸업 진도가 여러 번 읽습니다). */
    val checks: List<FormCheck> by lazy { FormScore.checks(this) }
    val score: Int by lazy { (100 - checks.sumOf { it.penalty }).coerceIn(0, 100) }
}

data class FormCheck(val ok: Boolean, val title: String, val detail: String, val penalty: Int, val tip: String)

/**
 * 감점 방식 폼 점수. 기준값은 공모전 시연용 초안입니다.
 * 깜빡이 30m는 도로교통법 시행령의 '회전·진로 변경 지점 30m 전 신호' 기준을 따랐습니다(조문 확인 필요).
 * 정지·출발의 g 기준은 흔히 쓰는 급감속·급가속 기준(약 0.3~0.4g)을 참고한 초안입니다.
 */
object FormScore {
    const val SIGNAL_TARGET_M = 30f
    const val GOOD_SCORE = 80
    /** 이보다 빨리 끝난 차선 변경은 '급하게 바꿈'으로 봅니다. */
    const val LANE_MIN_DURATION_S = 2f

    const val G = 9.81f
    /** 이보다 약하면 부드러운 정지·출발. */
    const val SMOOTH_MS2 = 3.0f
    /** 이보다 세면 급제동. 사이는 '조금 셈'. */
    const val HARSH_STOP_MS2 = 4.5f
    /** 이보다 세면 급출발. */
    const val HARSH_START_MS2 = 3.5f
    /** 정지 직전 감속이 이보다 크면 '울컥'. */
    const val JERKY_FINAL_MS2 = 2.5f
    /** 부드럽게 멈출 때 기준으로 삼는 감속(약 0.25g). 이 감속으로 멈추려면 언제 밟기 시작해야 하는지 계산합니다. */
    const val IDEAL_STOP_MS2 = 2.5f

    /** 과속방지턱은 이 속도 이하로 넘으면 좋음, [BUMP_FAST_KMH]를 넘으면 빠르게 넘음. */
    const val BUMP_GOOD_KMH = 20
    const val BUMP_FAST_KMH = 30
    /** 턱 앞 이 거리(m)까지는 [BUMP_GOOD_KMH]로 줄여 두는 것을 모범으로 봅니다. */
    const val BUMP_SLOW_BY_M = 10f

    fun checks(m: MoveForm): List<FormCheck> = when (m.kind) {
        MoveKind.STOP -> stopChecks(m)
        MoveKind.START -> listOf(startCheck(m))
        MoveKind.SPEED_BUMP -> bumpChecks(m)
        MoveKind.CAMERA -> cameraChecks(m)
        MoveKind.RAILROAD -> railChecks(m)
        MoveKind.SCHOOL_ZONE -> schoolChecks(m)
        MoveKind.SLIP -> listOf(slipCheck(m))
        MoveKind.PARK -> parkChecks(m)
        else -> steerChecks(m)
    }

    /** 후진은 사람이 걷는 정도로. 이보다 빠르면 '조금 빠름', [REVERSE_FAST_KMH]를 넘으면 '빠름'. 초안입니다. */
    const val REVERSE_GOOD_KMH = 7
    const val REVERSE_FAST_KMH = 10
    /** 멈춘 채 이만큼 넘게 핸들을 돌리면 '제자리 핸들'(타이어와 조향 장치에 무리가 감). */
    const val DRY_TURN_OK_DEG = 180
    /** P에 둘 때 핸들이 이보다 꺾여 있으면 바퀴가 꺾인 채 세운 것으로 봅니다. */
    const val PARK_STRAIGHT_DEG = 45

    /**
     * 주차는 몇 번 만에 넣었는지가 주인공이고, 넣는 동안의 습관(후진 속도, 바퀴 정렬)을 함께 봅니다.
     * 멈춘 채 핸들을 돌리는 것(제자리 핸들)은 초보가 순서대로 따라 하며 익히는 방법이라 아쉬운 점으로 보지 않습니다.
     * 주차칸이 어디인지는 차가 알려 주지 않아서, 칸에 맞게 넣었는지는 판단하지 않습니다.
     */
    private fun parkChecks(m: MoveForm) = listOf(
        if (m.corrections == 0) FormCheck(true, "넣기", "한 번에 넣음", 0, "")
        else FormCheck(
            false, "넣기", "${m.tries}번 만에 넣음", minOf(m.corrections * 10, 30), parkSteerTip(m),
        ),
        when {
            m.apexKmh <= REVERSE_GOOD_KMH -> FormCheck(true, "후진 속도", "시속 ${m.apexKmh}km로 천천히", 0, "")
            m.apexKmh <= REVERSE_FAST_KMH -> FormCheck(
                false, "후진 속도", "시속 ${m.apexKmh}km (걷는 정도로)", 10, "후진은 브레이크에서 발을 살짝 떼는 정도로, 걷는 속도로 해 보세요",
            )
            else -> FormCheck(false, "후진 속도", "시속 ${m.apexKmh}km로 빠름", 25, "후진은 브레이크에서 발을 살짝 떼는 정도로, 걷는 속도로 해 보세요")
        },
        if (kotlin.math.abs(m.finalSteerDeg) <= PARK_STRAIGHT_DEG) FormCheck(true, "바퀴 정렬", "바퀴 펴고 세움", 0, "")
        else FormCheck(
            false, "바퀴 정렬", "바퀴가 꺾인 채 세움 (${kotlin.math.abs(m.finalSteerDeg)}°)", 5,
            "세우기 전에 핸들을 풀어 바퀴를 곧게 펴 두면, 나갈 때 차가 엉뚱한 쪽으로 가지 않아요",
        ),
    )

    /** 주차 첫 후진: 핸들을 가장 많이 감은 각도(도)와 그동안 차가 돈 각도(도). 후진이 없으면 null. */
    fun firstReverse(m: MoveForm): Pair<Float, Float>? {
        val (from, to) = m.reverseSpans.firstOrNull() ?: return null
        val steer = m.steering.filter { it.ms in from..to }.maxOfOrNull { kotlin.math.abs(it.value) } ?: 0f
        val turned = kotlin.math.abs(Kinematics.headingChangeDeg(m.steering, m.speed, from, to))
        return steer to turned
    }

    /** 끝까지 감은 핸들(약 1.5바퀴). */
    private const val FULL_LOCK_DEG = 540f

    /**
     * 여러 번 만에 넣었을 때, 첫 후진에서 핸들을 얼마나 감았고 차가 얼마나 돌았는지로 다음 방법을 알려 줍니다.
     * 직각 주차는 차가 90° 돌아야 칸과 나란해집니다.
     */
    private fun parkSteerTip(m: MoveForm): String {
        val (steer, turned) = firstReverse(m)
            ?: return "뒤로 가기 전에 차를 주차칸과 나란히 맞추고, 조금 더 앞으로 빼 두면 한 번에 들어가요"
        val deg = turned.roundToInt()
        return if (steer < FULL_LOCK_DEG - 60f) {
            "첫 후진 때 핸들을 ${"%.1f".format(steer / 360f)}바퀴만 감고, 차가 ${deg}°쯤 돌았을 때 멈췄어요. " +
                "핸들을 끝까지(1.5바퀴) 감은 채로, 차가 칸과 나란해질 때까지 천천히 들어가 보세요"
        } else {
            "첫 후진 때 핸들은 끝까지 감았는데, 차가 ${deg}°쯤 돌았을 때 멈췄어요. " +
                "감은 채로 차가 칸과 나란해질 때까지 조금 더 들어간 뒤 핸들을 풀어 보세요"
        }
    }

    /** 미끄러질 뻔한 순간은 그 자체로 아쉬운 장면입니다. 무엇을 하다 그랬는지는 단정하지 않습니다. */
    private fun slipCheck(m: MoveForm) = FormCheck(
        false, "미끄러짐", "차가 미끄러져 자세를 잡아 줌 (시속 ${m.apexKmh}km)", 30,
        if (m.weather == Weather.CLEAR) "속도를 미리 줄이고 핸들과 브레이크를 천천히 써 보세요"
        else "${m.weather.label}에선 속도를 미리 줄이고 핸들과 브레이크를 천천히 써 보세요",
    )

    /** 날씨에 맞춘 제한속도. 비·안개면 20% 줄인 값입니다. */
    fun weatherLimit(limit: Int, w: Weather) = (limit * w.speedFactor).roundToInt()

    /** 상세 문구 뒤에 붙는 날씨 기준 표시. */
    private fun wx(m: MoveForm) = if (m.weather == Weather.CLEAR) "" else " · ${m.weather.label} 기준"

    /** 카메라를 지난 뒤 이보다 세게 가속하면 '지나자마자 급가속'. */
    const val CAMERA_RUSH_MS2 = 2.5f
    /** 철길 건널목은 멈췄다가 이 속도 이하로 천천히 건너는 것을 모범으로 봅니다. */
    const val RAIL_CROSS_KMH = 20
    /** 어린이 보호구역 제한속도를 내비가 주지 않으면 쓰는 값. */
    const val SCHOOL_DEFAULT_LIMIT_KMH = 30

    private fun cameraChecks(m: MoveForm) = listOfNotNull(
        m.limitKmh?.let { posted ->
            val limit = weatherLimit(posted, m.weather)
            val label = if (m.weather == Weather.CLEAR) "제한 $posted" else "${m.weather.label} 권장 $limit"
            if (m.apexKmh <= limit) FormCheck(true, "통과 속도", "시속 ${m.apexKmh}km ($label)", 0, "")
            else FormCheck(
                false, "통과 속도", "시속 ${m.apexKmh}km로 초과 ($label)", 40,
                if (m.weather == Weather.CLEAR) "카메라와 상관없이 제한속도에 맞춰 달려 보세요"
                else "${m.weather.label}엔 제한속도보다 20% 줄여 시속 ${limit}km로 달려 보세요",
            )
        },
        if (m.peakAccelMs2 > SMOOTH_MS2 * m.weather.accelFactor) FormCheck(
            false, "카메라 앞", "급하게 줄임 (${g(m.peakAccelMs2)})", 25, "미리 제한속도에 맞춰 두면 급하게 줄일 일이 없어요",
        )
        else FormCheck(true, "카메라 앞", "미리 맞춰 둠", 0, ""),
        if (m.afterAccelMs2 > CAMERA_RUSH_MS2) FormCheck(
            false, "지난 뒤", "지나자마자 급가속 (${g(m.afterAccelMs2)})", 15, "카메라를 지나서도 같은 속도를 유지해 보세요",
        )
        else FormCheck(true, "지난 뒤", "속도 유지", 0, ""),
    )

    private fun railChecks(m: MoveForm) = listOf(
        if (m.stoppedBefore) FormCheck(true, "일시정지", "건너기 전 멈춤", 0, "")
        else FormCheck(false, "일시정지", "멈추지 않고 건넘", 50, "건널목 앞에서는 꼭 멈췄다가 좌우를 보고 건너세요"),
        if (m.apexKmh <= RAIL_CROSS_KMH) FormCheck(true, "건널 때", "시속 ${m.apexKmh}km로 천천히", 0, "")
        else FormCheck(false, "건널 때", "시속 ${m.apexKmh}km (천천히 건너기)", 10, "건널목 위에서는 천천히, 멈추지 않고 건너세요"),
    )

    private fun schoolChecks(m: MoveForm): List<FormCheck> {
        val limit = weatherLimit(m.limitKmh ?: SCHOOL_DEFAULT_LIMIT_KMH, m.weather)
        return listOf(
            when {
                m.apexKmh <= limit -> FormCheck(true, "최고 속도", "시속 ${m.apexKmh}km (제한 $limit${wx(m)})", 0, "")
                m.apexKmh <= limit + 5 -> FormCheck(
                    false, "최고 속도", "시속 ${m.apexKmh}km (제한 $limit)", 15, "보호구역에서는 시속 ${limit}km 아래로 천천히 달려 보세요",
                )
                else -> FormCheck(
                    false, "최고 속도", "시속 ${m.apexKmh}km로 초과 (제한 $limit)", 40, "보호구역에서는 시속 ${limit}km 아래로 천천히 달려 보세요",
                )
            },
            if (m.peakAccelMs2 > SMOOTH_MS2 * m.weather.accelFactor) FormCheck(
                false, "급조작", "급가속·급제동 (${g(m.peakAccelMs2)})", 15, "보호구역에서는 언제든 멈출 수 있게 부드럽게 달려 보세요",
            )
            else FormCheck(true, "급조작", "급가속·급제동 없음", 0, ""),
        )
    }

    private fun bumpChecks(m: MoveForm) = listOf(
        when {
            m.apexKmh <= BUMP_GOOD_KMH -> FormCheck(true, "넘을 때", "시속 ${m.apexKmh}km로 천천히", 0, "")
            m.apexKmh <= BUMP_FAST_KMH -> FormCheck(
                false, "넘을 때", "시속 ${m.apexKmh}km (권장 ${BUMP_GOOD_KMH} 이하)", 15, "턱 앞에서 시속 ${BUMP_GOOD_KMH}km 아래로 줄여 보세요",
            )
            else -> FormCheck(false, "넘을 때", "빠르게 넘음 (시속 ${m.apexKmh}km)", 40, "턱 앞에서 시속 ${BUMP_GOOD_KMH}km 아래로 줄여 보세요")
        },
        if (m.brakeDuring) FormCheck(false, "턱 위", "브레이크를 밟은 채 넘음", 15, "턱 바로 앞에서는 브레이크를 떼고 굴러서 넘어 보세요")
        else FormCheck(true, "턱 위", "브레이크 떼고 넘음", 0, ""),
        if (m.peakAccelMs2 > SMOOTH_MS2) FormCheck(
            false, "미리 감속", "턱 바로 앞에서 급하게 줄임 (${g(m.peakAccelMs2)})", 10, "턱이 보이면 더 일찍부터 천천히 줄이세요",
        )
        else FormCheck(true, "미리 감속", "미리 천천히 줄임", 0, ""),
    )

    private fun what(m: MoveForm) = when (m.kind) {
        MoveKind.TURN -> "회전"
        MoveKind.U_TURN -> "유턴"
        else -> "차선 변경"
    }

    private fun steerChecks(m: MoveForm) = buildList {
        add(signalCheck(m))
        add(
            when {
                !m.brakeDuring -> FormCheck(true, "감속", if (m.kind.isTurn) "꺾기 전에 끝냄" else "속도 유지", 0, "")
                m.kind.isTurn -> FormCheck(false, "감속", "꺾는 중에 브레이크", 20, "감속은 꺾기 전에 끝내세요")
                else -> FormCheck(false, "감속", "차선 변경 중 브레이크", 15, "차선을 바꿀 때는 속도를 유지하세요")
            },
        )
        add(steeringCheck(m))
        val off = m.signalOffDelayS
        if (off != null) add(
            if (off <= 2f) FormCheck(true, "깜빡이 끄기", "${what(m)} 직후", 0, "")
            else FormCheck(false, "깜빡이 끄기", "%.1f초 늦음".format(off), 5, "끝나면 깜빡이를 바로 끄세요"),
        )
    }

    private fun g(ms2: Float) = "%.2fg".format(ms2 / G)

    private fun stopChecks(m: MoveForm): List<FormCheck> {
        val f = m.weather.accelFactor
        val wet = m.weather != Weather.CLEAR
        return listOf(
        when {
            m.peakAccelMs2 <= SMOOTH_MS2 * f -> FormCheck(true, "감속", "부드럽게 멈춤 (${g(m.peakAccelMs2)}${wx(m)})", 0, "")
            m.peakAccelMs2 <= HARSH_STOP_MS2 * f -> FormCheck(
                false, "감속", "조금 세게 멈춤 (${g(m.peakAccelMs2)}${wx(m)})", 10,
                if (wet) "${m.weather.label}엔 멈추는 데 더 오래 걸려요. 평소보다 한 박자 일찍, 살살 밟아 보세요"
                else "브레이크를 조금 더 일찍, 약하게 밟아 보세요",
            )
            else -> FormCheck(
                false, "감속", "급제동 (${g(m.peakAccelMs2)}${wx(m)})", 40,
                if (wet) "${m.weather.label}엔 멈추는 데 더 오래 걸려요. 더 멀리 보고 일찍부터 천천히 멈춰 보세요"
                else "앞을 더 멀리 보고 일찍부터 천천히 멈춰 보세요",
            )
        },
        if (m.finalDecelMs2 <= JERKY_FINAL_MS2) FormCheck(true, "멈출 때", "울컥임 없음", 0, "")
        else FormCheck(false, "멈출 때", "멈추는 순간 울컥", 15, "멈추기 직전에 브레이크를 살짝 풀어 주세요"),
        )
    }

    private fun startCheck(m: MoveForm): FormCheck {
        val f = m.weather.accelFactor
        val wet = m.weather != Weather.CLEAR
        return when {
            m.peakAccelMs2 <= SMOOTH_MS2 * f -> FormCheck(true, "가속", "부드럽게 출발 (${g(m.peakAccelMs2)}${wx(m)})", 0, "")
            m.peakAccelMs2 <= HARSH_START_MS2 * f -> FormCheck(
                false, "가속", "조금 빠른 출발 (${g(m.peakAccelMs2)}${wx(m)})", 10,
                if (wet) "${m.weather.label}엔 바퀴가 헛돌기 쉬워요. 가속 페달을 더 천천히 밟아 보세요" else "가속 페달을 조금 더 천천히 밟아 보세요",
            )
            else -> FormCheck(
                false, "가속", "급출발 (${g(m.peakAccelMs2)}${wx(m)})", 30,
                if (wet) "${m.weather.label}엔 바퀴가 헛돌기 쉬워요. 가속 페달을 천천히, 나눠서 밟아 보세요" else "가속 페달을 천천히, 나눠서 밟아 보세요",
            )
        }
    }

    private fun signalCheck(m: MoveForm): FormCheck {
        val what = what(m)
        val lead = m.signalLeadM
        return when {
            m.signalWrongSide -> FormCheck(false, "깜빡이", "반대쪽 깜빡이", 30, "가려는 쪽 깜빡이를 켜세요")
            m.signalAfterStart -> FormCheck(false, "깜빡이", "움직인 뒤에 켬", 30, "움직이기 전에 깜빡이를 먼저 켜세요")
            lead == null -> FormCheck(false, "깜빡이", "켜지 않음", 30, "$what 30m 전에 깜빡이를 켜 보세요")
            lead >= SIGNAL_TARGET_M -> FormCheck(true, "깜빡이", "$what ${lead.roundToInt()}m 전", 0, "")
            else -> FormCheck(
                false, "깜빡이", "$what ${lead.roundToInt()}m 전 (목표 30m)",
                ((SIGNAL_TARGET_M - lead) / SIGNAL_TARGET_M * 20).roundToInt(),
                "깜빡이를 조금 더 일찍 켜 보세요",
            )
        }
    }

    private fun steeringCheck(m: MoveForm): FormCheck {
        val correctionPenalty = minOf(m.corrections * 10, 30)
        if (m.kind.isTurn) {
            return if (m.corrections == 0) FormCheck(true, "조향", "한 번에 꺾고 한 번에 풂", 0, "")
            else FormCheck(false, "조향", "고쳐 꺾기 ${m.corrections}회", correctionPenalty, "한 번에 꺾고 한 번에 풀어 보세요")
        }
        val rushed = m.durationS < LANE_MIN_DURATION_S
        val duration = "%.1f초".format(m.durationS)
        return when {
            !rushed && m.corrections == 0 -> FormCheck(true, "조향", "부드럽게 옮김 ($duration)", 0, "")
            rushed -> FormCheck(
                false, "조향",
                listOfNotNull("급하게 바꿈 ($duration)", m.corrections.takeIf { it > 0 }?.let { "고쳐 꺾기 ${it}회" }).joinToString(", "),
                20 + correctionPenalty, "3초 정도에 걸쳐 천천히 옮겨 가세요",
            )
            else -> FormCheck(false, "조향", "고쳐 꺾기 ${m.corrections}회", correctionPenalty, "한 번에 옮기고 한 번에 펴 보세요")
        }
    }

    fun score(m: MoveForm): Int = m.score

    /** 감점이 가장 큰 항목 하나만 코칭합니다. */
    fun tip(m: MoveForm): String =
        checks(m).filter { !it.ok }.maxByOrNull { it.penalty }?.tip ?: "완벽한 폼이에요. 이 느낌 그대로!"
}
