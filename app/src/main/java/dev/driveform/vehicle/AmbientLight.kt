package dev.driveform.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.os.Handler
import android.os.Looper

/**
 * 운전 중 앰비언트 라이트 연출. 색과 시간을 이어 붙인 장면([Frame])을 차례로 켜고, 끝나면 원래 색으로 되돌립니다.
 * 연출 도중에 새 연출이 오면 앞의 것을 멈추고 새 것을 틀되, 되돌릴 '원래 색'은 처음 읽은 값을 그대로 씁니다
 * (연출 중의 초록을 원래 색으로 잘못 읽어 불이 초록으로 남지 않게).
 * 앰비언트 라이트가 없는 차량에서는 아무 일도 하지 않습니다(소리만 남음).
 */
class AmbientLight(
    private val vehicle: Vehicle,
    private val log: (String) -> Unit,
) {
    /** [color]가 null이면 원래 색. [ms] 동안 유지합니다. */
    data class Frame(val color: Int?, val ms: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var areas: List<SeatArea>? = null
    private var original: Int? = null

    /** 한 가지 색을 잠깐 켭니다. */
    fun flash(color: Int, holdMillis: Long = 2_000) = play(listOf(Frame(color, holdMillis)))

    /** [count]번 짧게 깜빡입니다. 화면 없이도 '몇 번째'인지 느낄 수 있게 합니다. */
    fun blink(color: Int, count: Int, onMs: Long = 260, offMs: Long = 200) =
        play(List(count) { listOf(Frame(color, onMs), Frame(null, offMs)) }.flatten())

    /** 천천히 세 번 출렁이고 잠시 머뭅니다(과제 완료). */
    fun swell(color: Int) = play(
        List(3) { listOf(Frame(color, 420), Frame(null, 240)) }.flatten() + Frame(color, 1_600),
    )

    /** [from] 색으로 켰다가 [to] 색으로 천천히 바뀌고 머뭅니다(졸업: 딱지 노랑 → 졸업 초록). */
    fun morph(from: Int, to: Int, holdFromMs: Long = 1_200, steps: Int = 14, stepMs: Long = 130, holdToMs: Long = 3_000) = play(
        listOf(Frame(from, holdFromMs)) + (1..steps).map { Frame(mix(from, to, it / steps.toFloat()), stepMs) } + Frame(to, holdToMs),
    )

    /** 연출 중이면 멈추고 원래 색으로 되돌립니다. 연결을 닫기 전에 부릅니다. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val list = areas
        val base = original
        if (list != null && base != null) set(list, base)
        original = null
    }

    fun play(frames: List<Frame>) {
        withAreas { list ->
            val known = original
            if (known != null) start(list, known, frames)
            else vehicle.getLight().getAmbientLightBrightnessColor(
                list.first(),
                { now ->
                    if (now == null) { log("앰비언트 현재 색을 읽지 못해 연출을 건너뜁니다"); return@getAmbientLightBrightnessColor }
                    original = now
                    handler.post { start(list, now, frames) }
                },
                { log("앰비언트 조회 실패: ${it.message}") },
            )
        }
    }

    private fun start(list: List<SeatArea>, base: Int, frames: List<Frame>) {
        handler.removeCallbacksAndMessages(null)
        var at = 0L
        frames.forEach { f ->
            handler.postDelayed({ set(list, f.color ?: base) }, at)
            at += f.ms
        }
        handler.postDelayed({ set(list, base); original = null; log("앰비언트 원래 색 복원") }, at)
        log("앰비언트 연출 ${frames.size}장면 ${at}ms")
    }

    private fun set(list: List<SeatArea>, color: Int) = list.forEach { area ->
        vehicle.getLight().setAmbientLightBrightnessColor(area, color, { _, _ -> }, { log("앰비언트 변경 실패: ${it.message}") })
    }

    /** 지원 영역은 한 번만 묻습니다. */
    private fun withAreas(block: (List<SeatArea>) -> Unit) {
        areas?.let { handler.post { block(it) }; return }
        val light = runCatching { vehicle.getLight() }.getOrElse { log("조명 API 없음: ${it.message}"); return }
        light.checkAmbientLightBrightnessColorCapabilities(
            { _: CapabilityConfig, list: List<SeatArea> ->
                if (list.isEmpty()) { log("앰비언트 색상 지원 영역 없음"); return@checkAmbientLightBrightnessColorCapabilities }
                areas = list
                handler.post { block(list) }
            },
            { log("앰비언트 색상 미지원: ${it.message}") },
        )
    }

    companion object {
        /** 0xRRGGBB 두 색 사이. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt().coerceIn(0, 255)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
