package dev.driveform.form

import java.time.Instant
import java.time.ZoneId

/**
 * 동작을 할 때의 길 상태. 차가 직접 알려 주는 신호로만 정합니다: 와이퍼가 켜져 있으면 빗길, 안개등이 켜져 있으면 안갯길.
 * 비나 안개가 있으면 도로교통법 시행규칙(제19조)의 '젖은 노면은 최고속도의 20% 감속'을 따라 기준을 엄하게 봅니다.
 * (가시거리 100m 이내면 50% 감속이지만, 차 신호로는 가시거리를 알 수 없어 20%만 씁니다.)
 */
enum class Weather(val label: String) {
    CLEAR(""), RAIN("빗길"), FOG("안갯길");

    /** 제한속도에 곱해 '이 날씨에 맞는 속도'를 냅니다. */
    val speedFactor: Float get() = if (this == CLEAR) 1f else 0.8f

    /** 부드러운 감속·가속 기준에 곱합니다. 젖은 길은 같은 힘에도 더 미끄러우니 더 살살 다뤄야 합니다. */
    val accelFactor: Float get() = if (this == CLEAR) 1f else 0.8f

    /** 주행 하나에 여러 날씨가 섞이면 더 조심해야 하는 쪽을 남깁니다. */
    fun worse(other: Weather) = if (other.ordinal > ordinal) other else this
}

/**
 * 와이퍼·안개등·전조등 신호로 지금 날씨를 정합니다. 시각은 [android.os.SystemClock.elapsedRealtime] 기준(ms)입니다.
 * - 와이퍼를 [RAIN_CONFIRM_MS] 넘게 켜 두면 빗길입니다(워셔액 한 번 뿌리는 건 빼려고).
 * - 와이퍼를 꺼도 [WET_AFTER_MS] 동안은 길이 젖어 있다고 봅니다.
 */
class WeatherTracker {
    private var wiperOnSince: Long? = null
    private var wetUntil = Long.MIN_VALUE
    var fog = false
        private set
    var lowBeam = false
        private set

    fun onWiper(ms: Long, on: Boolean) {
        if (on) {
            if (wiperOnSince == null) wiperOnSince = ms
        } else {
            val since = wiperOnSince
            if (since != null && ms - since >= RAIN_CONFIRM_MS) wetUntil = ms + WET_AFTER_MS
            wiperOnSince = null
        }
    }

    fun onFog(on: Boolean) { fog = on }
    fun onLowBeam(on: Boolean) { lowBeam = on }

    fun weather(ms: Long): Weather = when {
        fog -> Weather.FOG
        wiperOnSince?.let { ms - it >= RAIN_CONFIRM_MS } == true -> Weather.RAIN
        ms < wetUntil -> Weather.RAIN
        else -> Weather.CLEAR
    }

    /** 날씨가 바뀔 수 있는 다음 시각. 이때 다시 물어보면 됩니다. 없으면 null. */
    fun nextChangeAt(ms: Long): Long? = listOfNotNull(
        wiperOnSince?.plus(RAIN_CONFIRM_MS)?.takeIf { it > ms },
        wetUntil.takeIf { it > ms },
    ).minOrNull()

    companion object {
        const val RAIN_CONFIRM_MS = 10_000L
        const val WET_AFTER_MS = 5 * 60_000L
        /** 비·안개 속에서 전조등 없이 이만큼 달리면 알려 줍니다. */
        const val LIGHTS_OFF_NOTICE_MS = 10_000L
    }
}

/** 운전하며 처음 해 본 것. 연수 일지에서 축하하고, 졸업 증명에도 남깁니다. */
enum class First(val label: String, val praise: String) {
    RAIN("첫 빗길", "첫 빗길 운전이었는데 차분하게 잘 다녀오셨어요."),
    FOG("첫 안갯길", "앞이 잘 안 보이는 안갯길이었는데, 침착하게 잘 해내셨어요."),
    NIGHT("첫 밤 운전", "첫 밤 운전 잘 해내셨어요. 밤엔 낮보다 한 박자 천천히 가면 돼요."),
}

object Firsts {
    /** 이 시각부터 다음 날 [NIGHT_END_HOUR]시 전까지 출발한 주행을 밤 운전으로 봅니다(11월 해 질 무렵 기준의 초안). */
    const val NIGHT_START_HOUR = 18
    const val NIGHT_END_HOUR = 6

    fun isNight(trip: Trip, zone: ZoneId = ZoneId.systemDefault()): Boolean = isNightAt(trip.startedAt, zone)

    fun isNightAt(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val h = Instant.ofEpochMilli(ms).atZone(zone).hour
        return h >= NIGHT_START_HOUR || h < NIGHT_END_HOUR
    }

    /** 주행마다 처음 해 본 것. [trips]는 동작이 있는 주행만 넣습니다(순서는 상관없음). */
    fun of(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): Map<Int, List<First>> {
        val seen = mutableSetOf<First>()
        val out = mutableMapOf<Int, List<First>>()
        trips.sortedBy { it.startedAt }.forEach { t ->
            val did = listOfNotNull(
                First.RAIN.takeIf { t.weather == Weather.RAIN },
                First.FOG.takeIf { t.weather == Weather.FOG },
                First.NIGHT.takeIf { isNight(t, zone) },
            )
            val new = did.filter { seen.add(it) }
            if (new.isNotEmpty()) out[t.id] = new
        }
        return out
    }

    /** 지금까지 해 본 횟수(주행 수). */
    fun counts(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): Map<First, Int> = mapOf(
        First.RAIN to trips.count { it.weather == Weather.RAIN },
        First.FOG to trips.count { it.weather == Weather.FOG },
        First.NIGHT to trips.count { isNight(it, zone) },
    )
}
