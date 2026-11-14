package dev.driveform.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WeatherTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun wiperBecomesRainAfterTenSecondsAndStaysWetForAWhile() {
        val w = WeatherTracker()
        w.onWiper(0, true)
        // 워셔액 한 번 뿌리듯 잠깐 켠 건 비로 보지 않습니다.
        assertEquals(Weather.CLEAR, w.weather(9_000))
        assertEquals(WeatherTracker.RAIN_CONFIRM_MS, w.nextChangeAt(0))
        assertEquals(Weather.RAIN, w.weather(10_000))
        // 와이퍼를 꺼도 5분 동안은 길이 젖어 있습니다.
        w.onWiper(60_000, false)
        assertEquals(Weather.RAIN, w.weather(60_000 + WeatherTracker.WET_AFTER_MS - 1))
        assertEquals(Weather.CLEAR, w.weather(60_000 + WeatherTracker.WET_AFTER_MS))
    }

    @Test
    fun aQuickSwipeLeavesTheRoadDry() {
        val w = WeatherTracker()
        w.onWiper(0, true)
        w.onWiper(3_000, false)
        assertEquals(Weather.CLEAR, w.weather(4_000))
        assertNull(w.nextChangeAt(4_000))
    }

    @Test
    fun fogLampMeansFog() {
        val w = WeatherTracker()
        w.onWiper(0, true)
        w.onFog(true)
        assertEquals(Weather.FOG, w.weather(20_000))
        w.onFog(false)
        assertEquals(Weather.RAIN, w.weather(20_000))
    }

    private fun stop(peak: Float, weather: Weather) = MoveForm(
        id = 1, side = null, startMs = 0, endMs = 5_000, steering = emptyList(), speed = emptyList(), brakeSpans = emptyList(),
        signalOnMs = null, signalLeadM = null, signalAfterStart = false, brakeDuring = false, corrections = 0, signalOffDelayS = null,
        entryKmh = 40, apexKmh = 0, exitKmh = 0, kind = MoveKind.STOP, peakAccelMs2 = peak, weather = weather,
    )

    /** 맑은 날엔 부드러운 정지가 빗길에선 조금 센 정지입니다(젖은 길은 20% 더 살살). */
    @Test
    fun rainMakesTheBrakingBarStricter() {
        assertTrue(stop(2.7f, Weather.CLEAR).checks.first().ok)
        val wet = stop(2.7f, Weather.RAIN).checks.first()
        assertFalse(wet.ok)
        assertTrue(wet.detail, wet.detail.contains("빗길 기준"))
        assertTrue(wet.tip, wet.tip.startsWith("빗길엔"))
    }

    /** 빗길 단속 카메라: 제한 50이면 시속 40km가 기준입니다. */
    @Test
    fun rainLowersTheCameraSpeed() {
        val cam = stop(1f, Weather.RAIN).copy(kind = MoveKind.CAMERA, apexKmh = 45, limitKmh = 50)
        val check = cam.checks.first { it.title == "통과 속도" }
        assertFalse(check.ok)
        assertTrue(check.tip, check.tip.contains("40km"))
        assertTrue(cam.copy(weather = Weather.CLEAR).checks.first { it.title == "통과 속도" }.ok)
    }

    @Test
    fun firstsAreCountedOncePerKind() {
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(seoul).toInstant().toEpochMilli()
        val trips = listOf(
            Trip(1, at(2, 8)),
            Trip(2, at(2, 19)),
            Trip(3, at(3, 8), weather = Weather.RAIN),
            Trip(4, at(3, 19), weather = Weather.RAIN),
            Trip(5, at(4, 7), weather = Weather.FOG),
        )
        val firsts = Firsts.of(trips, seoul)
        assertEquals(listOf(First.NIGHT), firsts[2])
        assertEquals(listOf(First.RAIN), firsts[3])
        assertNull(firsts[4])
        assertEquals(listOf(First.FOG), firsts[5])
        assertEquals(mapOf(First.RAIN to 2, First.FOG to 1, First.NIGHT to 2), Firsts.counts(trips, seoul))
    }

    /** ESC가 깜빡이면 3초 뒤 '미끄러질 뻔함' 장면이 하나 생기고, 졸업 과목에는 넣지 않습니다. */
    @Test
    fun slipBecomesOneScene() {
        val moves = mutableListOf<MoveForm>()
        val d = MoveDetector(onMove = { moves += it }, onMoveUpdated = {})
        var t = 0L
        repeat(100) { t += 100; d.onSpeed(t, 50 / 3.6f) }
        d.onSlip(t)
        d.onSlip(t + 200) // 같은 순간에 여러 번 깜빡여도 한 장면
        repeat(40) { t += 100; d.onSpeed(t, 45 / 3.6f) }
        val slip = moves.single { it.kind == MoveKind.SLIP }
        assertEquals(50, slip.apexKmh)
        assertFalse(slip.checks.single().ok)
        assertTrue(Skill.entries.all { it.judge(slip) == null })
    }

    @Test
    fun demoHasARainyAndAFoggyDrive() {
        val r = DemoHistory.build(LocalDateTime.of(2026, 11, 11, 9, 0).atZone(seoul).toInstant().toEpochMilli(), seoul)
        val rain = r.trips.single { it.weather == Weather.RAIN }
        assertTrue(rain.lightsOffWetS > 0)
        assertEquals(1, r.moves.count { it.kind == MoveKind.SLIP && it.tripId == rain.id })
        assertTrue(r.moves.filter { it.tripId == rain.id }.all { it.weather == Weather.RAIN })
        assertEquals(1, r.trips.count { it.weather == Weather.FOG })
        // 시연 직전 주행들은 맑아서 이야기(깜빡이 졸업)에 영향을 주지 않습니다.
        assertTrue(r.trips.takeLast(5).all { it.weather == Weather.CLEAR })
    }
}
