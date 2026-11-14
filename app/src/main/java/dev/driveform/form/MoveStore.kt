package dev.driveform.form

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 동작 기록과 주행 정보를 앱 내부 저장소의 JSON 파일(turns.json, trips.json)에 보관합니다. 서버로 보내지 않습니다. */
class MoveStore(dir: File) {
    private val file = File(dir, "turns.json")
    private val tripFile = File(dir, "trips.json")

    fun load(): List<MoveForm> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    fun save(turns: List<MoveForm>) {
        val tmp = File(file.parentFile, "turns.json.tmp")
        tmp.writeText(JSONArray(turns.map(::toJson)).toString())
        tmp.renameTo(file)
    }

    private fun toJson(t: MoveForm) = JSONObject().apply {
        put("id", t.id)
        put("kind", t.kind.name)
        putOpt("side", t.side?.name)
        put("peakAccelMs2", t.peakAccelMs2.toDouble())
        put("finalDecelMs2", t.finalDecelMs2.toDouble())
        putOpt("markMs", t.markMs)
        putOpt("limitKmh", t.limitKmh)
        put("afterAccelMs2", t.afterAccelMs2.toDouble())
        put("stoppedBefore", t.stoppedBefore)
        putOpt("zoneEndMs", t.zoneEndMs)
        put("signalWrongSide", t.signalWrongSide)
        put("durationS", t.durationS.toDouble())
        put("startMs", t.startMs)
        put("endMs", t.endMs)
        put("steering", points(t.steering))
        put("speed", points(t.speed))
        put("brakeSpans", JSONArray(t.brakeSpans.map { JSONArray(listOf(it.first, it.second)) }))
        putOpt("signalOnMs", t.signalOnMs)
        putOpt("signalLeadM", t.signalLeadM?.toDouble())
        put("signalAfterStart", t.signalAfterStart)
        put("brakeDuring", t.brakeDuring)
        put("corrections", t.corrections)
        putOpt("signalOffDelayS", t.signalOffDelayS?.toDouble())
        put("entryKmh", t.entryKmh)
        put("apexKmh", t.apexKmh)
        put("exitKmh", t.exitKmh)
        put("recordedAt", t.recordedAt)
        putOpt("lat", t.lat)
        putOpt("lon", t.lon)
        putOpt("intersectionId", t.intersectionId)
        putOpt("place", t.place)
        putOpt("tripId", t.tripId)
        if (t.weather != Weather.CLEAR) put("weather", t.weather.name)
        if (t.night) put("night", true)
        if (t.reverseSpans.isNotEmpty()) put("reverseSpans", JSONArray(t.reverseSpans.map { JSONArray(listOf(it.first, it.second)) }))
        if (t.dryTurnDeg != 0) put("dryTurnDeg", t.dryTurnDeg)
        if (t.finalSteerDeg != 0) put("finalSteerDeg", t.finalSteerDeg)
    }

    private fun fromJson(o: JSONObject) = MoveForm(
        id = o.getInt("id"),
        // kind가 없는 기록은 회전만 있던 첫 버전에서 저장한 것입니다.
        kind = MoveKind.valueOf(o.optString("kind", MoveKind.TURN.name)),
        side = if (o.has("side")) Side.valueOf(o.getString("side")) else null,
        peakAccelMs2 = o.optDouble("peakAccelMs2", 0.0).toFloat(),
        finalDecelMs2 = o.optDouble("finalDecelMs2", 0.0).toFloat(),
        markMs = if (o.has("markMs")) o.getLong("markMs") else null,
        limitKmh = if (o.has("limitKmh")) o.getInt("limitKmh") else null,
        afterAccelMs2 = o.optDouble("afterAccelMs2", 0.0).toFloat(),
        stoppedBefore = o.optBoolean("stoppedBefore"),
        zoneEndMs = if (o.has("zoneEndMs")) o.getLong("zoneEndMs") else null,
        signalWrongSide = o.optBoolean("signalWrongSide"),
        durationS = o.optDouble("durationS", (o.getLong("endMs") - o.getLong("startMs")) / 1000.0).toFloat(),
        startMs = o.getLong("startMs"),
        endMs = o.getLong("endMs"),
        steering = points(o.getJSONArray("steering")),
        speed = points(o.getJSONArray("speed")),
        brakeSpans = o.getJSONArray("brakeSpans").let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
        },
        signalOnMs = o.optLongOrNull("signalOnMs"),
        signalLeadM = o.optDoubleOrNull("signalLeadM")?.toFloat(),
        signalAfterStart = o.getBoolean("signalAfterStart"),
        brakeDuring = o.optBoolean("brakeDuring", o.optBoolean("brakeInTurn")),
        corrections = o.getInt("corrections"),
        signalOffDelayS = o.optDoubleOrNull("signalOffDelayS")?.toFloat(),
        entryKmh = o.getInt("entryKmh"),
        apexKmh = o.getInt("apexKmh"),
        exitKmh = o.getInt("exitKmh"),
        recordedAt = o.optLong("recordedAt"),
        lat = o.optDoubleOrNull("lat"),
        lon = o.optDoubleOrNull("lon"),
        intersectionId = if (o.has("intersectionId")) o.getInt("intersectionId") else null,
        place = if (o.has("place")) o.getString("place") else null,
        tripId = if (o.has("tripId")) o.getInt("tripId") else null,
        weather = weatherOf(o),
        night = o.optBoolean("night"),
        reverseSpans = o.optJSONArray("reverseSpans")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
        } ?: emptyList(),
        dryTurnDeg = o.optInt("dryTurnDeg"),
        finalSteerDeg = o.optInt("finalSteerDeg"),
    )

    private fun weatherOf(o: JSONObject) = runCatching { Weather.valueOf(o.optString("weather", Weather.CLEAR.name)) }.getOrDefault(Weather.CLEAR)

    fun loadTrips(): List<Trip> = runCatching {
        if (!tripFile.exists()) return emptyList()
        val arr = JSONArray(tripFile.readText())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                Trip(
                    id = o.getInt("id"),
                    startedAt = o.getLong("startedAt"),
                    endedAt = o.optLongOrNull("endedAt"),
                    distanceM = o.optDoubleOrNull("distanceM"),
                    weather = weatherOf(o),
                    lightsOffWetS = o.optInt("lightsOffWetS"),
                )
            }
        }
    }.getOrElse { emptyList() }

    fun saveTrips(trips: List<Trip>) {
        val tmp = File(tripFile.parentFile, "trips.json.tmp")
        tmp.writeText(JSONArray(trips.map { t ->
            JSONObject().apply {
                put("id", t.id)
                put("startedAt", t.startedAt)
                putOpt("endedAt", t.endedAt)
                putOpt("distanceM", t.distanceM)
                if (t.weather != Weather.CLEAR) put("weather", t.weather.name)
                if (t.lightsOffWetS > 0) put("lightsOffWetS", t.lightsOffWetS)
            }
        }).toString())
        tmp.renameTo(tripFile)
    }

    private fun points(list: List<Point>) = JSONArray(list.map { JSONArray(listOf(it.ms, it.value.toDouble())) })

    private fun points(a: JSONArray) = (0 until a.length()).map {
        a.getJSONArray(it).let { p -> Point(p.getLong(0), p.getDouble(1).toFloat()) }
    }

    private fun JSONObject.optLongOrNull(key: String) = if (has(key)) getLong(key) else null
    private fun JSONObject.optDoubleOrNull(key: String) = if (has(key)) getDouble(key) else null
}

/**
 * 기록이 쌓여도 가볍게: 점수·졸업 진도는 동작의 요약 값(감속 세기, 고쳐 꺾은 횟수 등)만으로 계산하므로,
 * 오래된 동작은 경로 그림용 곡선(속도·핸들·브레이크)을 지웁니다. 최근 [KEEP_DETAILED]개와,
 * 고스트로 겹쳐 그릴 수 있는 최고 기록(같은 교차로·같은 동작별)은 그대로 둡니다.
 */
object Slim {
    const val KEEP_DETAILED = 600

    fun of(moves: List<MoveForm>): List<MoveForm> {
        if (moves.size <= KEEP_DETAILED) return moves
        val keep = HashSet<Int>()
        moves.takeLast(KEEP_DETAILED).forEach { keep += it.id }
        moves.groupBy { Triple(it.kind, it.side, it.intersectionId) }.values.forEach { g -> keep += g.maxBy { it.score }.id }
        moves.groupBy { it.kind to it.side }.values.forEach { g -> keep += g.maxBy { it.score }.id }
        var changed = false
        val out = moves.map { m ->
            if (m.id in keep || (m.speed.isEmpty() && m.steering.isEmpty())) m
            else { changed = true; m.copy(speed = emptyList(), steering = emptyList(), brakeSpans = emptyList()) }
        }
        return if (changed) out else moves
    }
}
