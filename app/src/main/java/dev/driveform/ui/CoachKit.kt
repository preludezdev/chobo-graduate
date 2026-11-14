package dev.driveform.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.driveform.form.MapBuilding
import dev.driveform.form.MapJunction
import dev.driveform.form.MapRoad
import dev.driveform.form.V2
import dev.driveform.vehicle.CarPaint
import org.json.JSONObject

/**
 * 'AI 운전 코치' 화면의 색. 어두운 운전석 화면에 강조색은 청록 하나, 고칠 점은 호박색 하나만 씁니다.
 * 낮·밤 모드와 상관없이 같은 색입니다(지도와 차가 잘 보이도록).
 */
internal object Coach {
    val Bg = Color(0xFF0B0F14)
    val Panel = Color(0xFF121820)
    val Panel2 = Color(0xFF18202A)
    val Stage = Color(0xFF0F151D)
    val Line = Color(0xFF1E2833)
    val Line2 = Color(0xFF2A3646)
    val Track = Color(0xFF24303D)
    val Text = Color(0xFFE8EEF5)
    val Text2 = Color(0xFFC3CEDA)
    val Muted = Color(0xFF9AA8B8)
    val Faint = Color(0xFF6B7A8C)
    val Accent = Color(0xFF3FD0C9)
    val AccentDeep = Color(0xFF12302F)
    val AccentInk = Color(0xFF06201F)
    val AccentSoft = Color(0xFF9FE9E4)
    val Amber = Color(0xFFFFB547)
    val AmberSoft = Color(0xFFFFD08A)
    val AmberDeep = Color(0xFF2A2213)
    val Road = Color(0xFF1B2531)
    val RoadLine = Color(0xFF3A4757)
    val Wall = Color(0xFF131A23)
    val Roof = Color(0xFF1A2430)
    val RoofEdge = Color(0xFF2A3746)
    val Grid = Color(0xFF17202A)
}

/** 예전 화면 부품(이름표, 경로 그리기)도 같은 색을 쓰도록 맞춘 팔레트. */
internal val CoachPalette = NightPalette.copy(
    page = Coach.Bg, surface = Coach.Panel, line = Coach.Line, textPrimary = Coach.Text, textSecondary = Coach.Text2, textMuted = Coach.Muted,
    seriesNow = Coach.Accent, seriesGhost = Coach.Faint, good = Coach.Accent, goodFill = Coach.AccentDeep, bad = Coach.Amber,
    onAccent = Coach.AccentInk, track = Coach.Track, idealBand = Color(0x22E8EEF5), idealCenter = Color(0xFF56677B),
    signal = Coach.Amber, warn = Coach.Amber,
)

internal fun Modifier.panel(radius: Dp = 24.dp, color: Color = Coach.Panel) = clip(RoundedCornerShape(radius)).background(color)

/** 둥근 테두리 단추. */
@Composable
internal fun Pill(label: String, color: Color, filled: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else Color.Transparent)
            .border(2.dp, color, RoundedCornerShape(50))
            .press(RoundedCornerShape(50), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (filled) Coach.AccentInk else color, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

/** 코치 표시: 청록 고리와 가운데 점. */
@Composable
internal fun CoachMark(size: Dp) {
    Box(Modifier.size(size).border(size / 14, Coach.Accent, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size / 3).background(Coach.Accent, CircleShape))
    }
}

@Composable
internal fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 18.dp, height = 8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(7.dp))
        Text(label, color = Coach.Text2, fontSize = 16.sp)
    }
}

@Composable
internal fun SectionLabel(text: String) = Text(text, color = Coach.Muted, fontSize = 18.sp)

/** 굵은 숫자 + 작은 단위. */
@Composable
internal fun BigNumber(value: String, unit: String = "", size: Int = 56, color: Color = Coach.Text) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = size.sp)
        if (unit.isNotEmpty()) Text(unit, color = Coach.Faint, fontSize = (size * 0.45).toInt().sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = (size * 0.08).dp))
    }
}

@Composable
internal fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

internal val Arrangement.gap16 get() = spacedBy(16.dp)

// ---------- 자산: 차 이미지와 시연 지도 ----------

/**
 * 렌더링해 둔 차 이미지(assets/car). 방향은 10°마다 하나이고, 불빛(깜빡이·브레이크·후진등)은 색과 상관없는 따로 된 층이라 겹쳐서 켭니다.
 * 이미지 한 장은 [RES]px에 [ORTHO_M]m를 담습니다(지도 카메라와 같은 50° 내려다보기).
 */
internal class CarSprites(private val context: Context) {
    private val cache = LruCache<String, ImageBitmap>(96)

    fun body(paint: CarPaint, headingDeg: Double) = load("car/${paint.hex}/car_%03d.webp".format(index(headingDeg)))
    fun light(layer: String, headingDeg: Double) = load("car/lights/${layer}_%03d.webp".format(index(headingDeg)))
    fun hero(paint: CarPaint) = load("car/hero_${paint.hex}.webp")

    private fun index(deg: Double): Int = ((Math.round(deg / 10.0).toInt() % 36 + 36) % 36) * 10

    private fun load(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bmp = runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        return bmp.asImageBitmap().also { cache.put(path, it) }
    }

    companion object {
        const val RES = 320f
        const val ORTHO_M = 6.6f
        const val PX_PER_M = RES / ORTHO_M
    }
}

/** 시연 교차로 지도(assets/demo_map.json, OpenStreetMap). */
internal object DemoMap {
    @Volatile private var cached: List<MapJunction>? = null

    fun junctions(context: Context): List<MapJunction> = cached ?: runCatching {
        val root = JSONObject(context.assets.open("demo_map.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("junctions")
        (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            fun pts(a: org.json.JSONArray) = (0 until a.length()).map { k -> a.getJSONArray(k).let { V2(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
            val roads = j.getJSONArray("roads").let { a -> (0 until a.length()).map { k -> a.getJSONObject(k).let { MapRoad(it.getDouble("w").toFloat(), pts(it.getJSONArray("p"))) } } }
            val blds = j.getJSONArray("buildings").let { a -> (0 until a.length()).map { k -> a.getJSONObject(k).let { MapBuilding(it.getDouble("h").toFloat(), pts(it.getJSONArray("p"))) } } }
            val arms = j.getJSONArray("arms").let { a -> (0 until a.length()).map { k -> a.getDouble(k).toFloat() } }
            MapJunction(j.getDouble("lat"), j.getDouble("lon"), j.optString("name"), arms, roads, blds)
        }
    }.getOrElse { emptyList() }.also { cached = it }

    const val CREDIT = "지도 © OpenStreetMap contributors"
}
