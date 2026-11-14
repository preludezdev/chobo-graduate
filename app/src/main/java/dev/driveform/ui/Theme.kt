package dev.driveform.ui

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 차량 시스템의 주간·야간 모드를 따라가는 두 벌의 색. '초보운전 딱지 × 도로 표지판' 디자인입니다.
 * 색 하나에 뜻 하나: 초록 = 이수·졸업·진도, 노랑(딱지색) = 딱지·주의·남은 것·이번 주 과제, 파랑 = 안내, 검정·흰색 = 본문.
 * 빨강은 쓰지 않고, 주황([bad])은 노랑을 쓸 수 없는 글자 강조에만 씁니다.
 * 밤에는 큰 흰 면이 눈부시지 않게, 표지를 어두운 면 + 색 테두리로 그립니다(헤드라이트에 비친 표지처럼).
 */
internal data class Palette(
    val night: Boolean,
    val page: Color,
    val surface: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val seriesNow: Color,
    val seriesGhost: Color,
    /** 이수·졸업·진도. 선·테두리·아이콘에 씁니다. */
    val good: Color,
    /** 흰 글자를 올리는 초록 면(밤에는 [good]보다 어둡게 해서 대비를 지킵니다). */
    val goodFill: Color,
    /** 글자 강조용 주황(노랑을 글자색으로 쓸 수 없을 때만). */
    val bad: Color,
    val badSurface: Color,
    val onBadSurface: Color,
    val badTrack: Color,
    /** 초록 면 위의 글자 */
    val onAccent: Color,
    /** 딱지·주의 표지의 노랑 */
    val yellow: Color,
    /** 안내 표지의 파랑 */
    val blue: Color,
    /** 고른 줄의 바탕 */
    val select: Color,
    /** 아직 안 한 칸(빈 점) 테두리 */
    val dot: Color,
    /** 링·막대의 빈 부분 */
    val track: Color,
    // 모범 경로는 도로처럼 옅은 띠, 표시 지점은 상태 색과 구분되는 노랑(깜빡이)·보라(고쳐 꺾음)입니다.
    val idealBand: Color,
    val idealCenter: Color,
    val laneLine: Color,
    val signal: Color,
    val warn: Color,
    /** 어린이 보호구역 바닥색(노랑을 옅게) */
    val schoolZone: Color,
)

internal val DayPalette = Palette(
    night = false,
    page = Color(0xFFECEDEF),
    surface = Color(0xFFFFFFFF),
    line = Color(0xFFE1E3E6),
    textPrimary = Color(0xFF111111),
    textSecondary = Color(0xFF4A4F57),
    textMuted = Color(0xFF5F646B),
    seriesNow = Color(0xFF2F62D3),
    seriesGhost = Color(0xFF8A9099),
    good = Color(0xFF0B6B45),
    goodFill = Color(0xFF0B6B45),
    bad = Color(0xFFB4400A),
    badSurface = Color(0xFFFFF4C2),
    onBadSurface = Color(0xFF4A4F57),
    badTrack = Color(0xFFE6E8EB),
    onAccent = Color(0xFFFFFFFF),
    yellow = Color(0xFFFFD43B),
    blue = Color(0xFF1D4FA8),
    select = Color(0xFFFFF4C2),
    dot = Color(0xFF767C84),
    track = Color(0xFFE6E8EB),
    idealBand = Color(0x1A111111),
    idealCenter = Color(0x55111111),
    laneLine = Color(0xFFC9CDD2),
    signal = Color(0xFFB38A00),
    warn = Color(0xFF7553D6),
    schoolZone = Color(0x40E8B000),
)

internal val NightPalette = Palette(
    night = true,
    page = Color(0xFF111316),
    surface = Color(0xFF1C2026),
    line = Color(0xFF2C3138),
    textPrimary = Color(0xFFEEF0F2),
    textSecondary = Color(0xFFAEB4BC),
    textMuted = Color(0xFF9AA0A8),
    seriesNow = Color(0xFF6C9BF2),
    seriesGhost = Color(0xFF8A9099),
    good = Color(0xFF34B27A),
    goodFill = Color(0xFF1E7A50),
    bad = Color(0xFFF29A5E),
    badSurface = Color(0xFF2A2A20),
    onBadSurface = Color(0xFFC9C3A8),
    badTrack = Color(0xFF2C3138),
    onAccent = Color(0xFF111111),
    yellow = Color(0xFFE6BF35),
    blue = Color(0xFF6C9BF2),
    select = Color(0xFF2A2F36),
    dot = Color(0xFF8A9099),
    track = Color(0xFF2C3138),
    idealBand = Color(0x1FEEF1F5),
    idealCenter = Color(0x66EEF1F5),
    laneLine = Color(0xFF4A525D),
    signal = Color(0xFFE6D04D),
    warn = Color(0xFFB89BFF),
    schoolZone = Color(0x33C98500),
)

/** 지금 쓰는 색. 상태로 두어서 바뀌면 그리는 곳이 모두 다시 그려집니다. */
internal var palette by mutableStateOf(NightPalette)

internal fun paletteFor(config: Configuration) =
    if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) NightPalette else DayPalette

internal val Page get() = palette.page
internal val Surface get() = palette.surface
internal val Line get() = palette.line
internal val TextPrimary get() = palette.textPrimary
internal val TextSecondary get() = palette.textSecondary
internal val TextMuted get() = palette.textMuted
internal val SeriesNow get() = palette.seriesNow
internal val SeriesGhost get() = palette.seriesGhost
internal val Good get() = palette.good
internal val Bad get() = palette.bad
internal val BadSurface get() = palette.badSurface
internal val OnBadSurface get() = palette.onBadSurface
internal val BadTrack get() = palette.badTrack
internal val OnAccent get() = palette.onAccent
internal val GoodFill get() = palette.goodFill
internal val SignYellow get() = palette.yellow
internal val SignBlue get() = palette.blue
internal val Select get() = palette.select
internal val Dot get() = palette.dot
internal val Track get() = palette.track
internal val IsNight get() = palette.night
internal val IdealBand get() = palette.idealBand
internal val IdealCenter get() = palette.idealCenter
internal val LaneLine get() = palette.laneLine
internal val Signal get() = palette.signal
internal val Warn get() = palette.warn
internal val SchoolZone get() = palette.schoolZone
