package dev.driveform.vehicle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 운전 중 짧은 효과음. 비프음 대신 실로폰처럼 맑은 음을 직접 만들어(사인파 + 잔향처럼 줄어드는 소리) 들려줍니다.
 * 음악이나 내비 안내를 가리지 않게 짧고 작게, 알림용(USAGE_ASSISTANCE_SONIFICATION)으로 냅니다.
 */
class Chime(private val log: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()

    /** 이번 주 과제를 잘함: 도-미, 짧게 올라가는 두 음. */
    fun success() = play(listOf(C6 to 110, E6 to 260))

    /** 과제 완료: 도-미-솔. */
    fun missionDone() = play(listOf(C6 to 130, E6 to 130, G6 to 420))

    /** 졸업: 도-미-솔-높은 도, 마지막 음을 길게. */
    fun graduated() = play(listOf(C6 to 160, E6 to 160, G6 to 160, C7 to 900))

    /** 심화 배지: 미-솔-높은 도, 밝고 짧게 올라갑니다. */
    fun badge() = play(listOf(E6 to 120, G6 to 120, C7 to 600))

    /** 천천히 해 주세요(후진이 빠름): 미-도, 낮게 내려가는 두 음. 놀라지 않게 작게. */
    fun slowDown() = play(listOf(E5 to 180, C5 to 380))

    /** 확인해 주세요(전조등): 솔-미, 내려가는 두 음. */
    fun notice() = play(listOf(G5 to 160, E5 to 360))

    private fun play(notes: List<Pair<Double, Int>>) = worker.execute {
        runCatching {
            val pcm = render(notes)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / RATE + 100)
            track.release()
        }.onFailure { log("효과음 실패: ${it.message}") }
    }

    /** 음마다 앞 음의 꼬리가 조금 겹치게 이어 붙입니다. */
    private fun render(notes: List<Pair<Double, Int>>): ShortArray {
        val tail = 350
        val total = notes.sumOf { it.second } + tail
        val out = FloatArray(RATE * total / 1000)
        var startMs = 0
        notes.forEach { (freq, ms) ->
            val from = RATE * startMs / 1000
            val len = RATE * (ms + tail) / 1000
            for (i in 0 until min(len, out.size - from)) {
                val t = i.toDouble() / RATE
                val attack = min(1.0, t / 0.006)
                val decay = exp(-t * 5.5)
                // 한 옥타브 위 배음을 조금 섞어 맑게.
                val v = sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)
                out[from + i] += (v * attack * decay * 0.28).toFloat()
            }
            startMs += ms
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    companion object {
        private const val RATE = 44_100
        private const val C5 = 523.25
        private const val E5 = 659.25
        private const val G5 = 783.99
        private const val C6 = 1046.50
        private const val E6 = 1318.51
        private const val G6 = 1567.98
        private const val C7 = 2093.00
    }
}
