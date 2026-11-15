package dev.driveform

import ai.pleos.playground.vehicle.Vehicle
import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.driveform.form.Coaching
import dev.driveform.form.DriveCue
import dev.driveform.form.First
import dev.driveform.form.Firsts
import dev.driveform.form.Gear
import dev.driveform.form.Skill
import dev.driveform.form.Weather
import dev.driveform.form.WeatherTracker
import dev.driveform.form.DemoHistory
import dev.driveform.form.FormScore
import dev.driveform.form.Graduation
import dev.driveform.form.Intersections
import dev.driveform.form.MoveDetector
import dev.driveform.form.MoveForm
import dev.driveform.form.MoveKind
import dev.driveform.form.Side
import dev.driveform.form.MoveStore
import dev.driveform.form.Slim
import dev.driveform.form.Trip
import dev.driveform.form.TripSummary
import dev.driveform.form.Trips
import dev.driveform.vehicle.AmbientLight
import dev.driveform.vehicle.CarPaint
import dev.driveform.vehicle.CarProfile
import dev.driveform.vehicle.CarSignals
import dev.driveform.vehicle.Chime
import dev.driveform.vehicle.NaviPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/** 고스트로 겹쳐 그릴 기록. 같은 교차로 기록이 없으면 같은 방향 전체 최고 기록을 씁니다. */
data class Ghost(val move: MoveForm, val sameIntersection: Boolean)

/** 졸업 진도(홈), 연수 일지(주행 요약), 장면 복습(동작 기록). */
enum class Screen { GRAD, TRIP, MOVES }

data class UiState(
    val moves: List<MoveForm> = emptyList(),
    val selectedId: Int? = null,
    val parked: Boolean = true,
    val trips: List<Trip> = emptyList(),
    val screen: Screen = Screen.GRAD,
    val selectedTripId: Int? = null,
    /** 초보운전 딱지를 붙이고 시작했는지(처음 켜면 시작하기 화면을 보여 줍니다). */
    val started: Boolean = true,
    /** 지금 날씨(와이퍼·안개등으로 판단). 주행 중이면 머리글에 '빗길 연수 중'처럼 보여 줍니다. */
    val weather: Weather = Weather.CLEAR,
    /** 차가 알려 준 차종·치수. */
    val car: CarProfile = CarProfile(),
    /** '내 차'에서 고른 색. */
    val paint: CarPaint = CarPaint.SILVER,
) {
    /**
     * 기록에서 계산하는 값(요약·졸업 진도·코칭)은 기록이 바뀔 때만 다시 계산합니다.
     * 화면 상태는 탭을 넘기거나 날씨가 바뀔 때도 새로 만들어지므로, 같은 기록이면 앞서 계산한 것을 그대로 씁니다.
     */
    private val records: Records get() = Records.of(moves, trips)

    /** 주행마다 처음 해 본 것(첫 빗길·안갯길·밤 운전). 동작이 있는 주행만 봅니다. */
    val firsts: Map<Int, List<First>> get() = records.firsts

    /** 동작이 있는 주행의 요약. 최근 순입니다. */
    val tripSummaries: List<TripSummary> get() = records.tripSummaries

    val selectedTrip: TripSummary? get() = tripSummaries.firstOrNull { it.trip.id == selectedTripId } ?: tripSummaries.firstOrNull()

    fun coachingFor(s: TripSummary): Coaching = records.coaching(s)

    /** 지금까지의 졸업 진도와, 가장 최근 주행을 하기 전의 진도(홈에서 이번 주행만큼 차오르는 걸 보여 주려고). */
    val graduation: Graduation get() = records.graduation
    val graduationBefore: Graduation get() = records.graduationBefore

    /** 졸업 다음 단계인 심화 과정(주차·빗길·밤길)의 진도. 졸업 전 기록도 함께 셉니다. */
    val advanced: Graduation get() = records.advanced
    val advancedBefore: Graduation get() = records.advancedBefore

    val selected: MoveForm? get() = moves.firstOrNull { it.id == selectedId } ?: moves.lastOrNull()

    /** 동작이 속한 주행. 요약이 있는(동작이 있는) 주행일 때만. */
    fun tripOf(moveId: Int): Int? = moves.firstOrNull { it.id == moveId }?.tripId?.takeIf { id -> tripSummaries.any { it.trip.id == id } }

    val todayCount: Int get() {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return moves.count { it.recordedAt >= start }
    }

    fun ghostFor(move: MoveForm): Ghost? {
        val earlier = moves.filter { it.kind == move.kind && it.side == move.side && it.id < move.id }
        earlier.filter { move.intersectionId != null && it.intersectionId == move.intersectionId }
            .maxByOrNull { it.score }?.let { return Ghost(it, sameIntersection = true) }
        return earlier.maxByOrNull { it.score }?.let { Ghost(it, sameIntersection = false) }
    }

    /** 같은 교차로, 같은 방향으로 [move]까지 지나간 회전 기록(오래된 순). */
    fun visits(move: MoveForm): List<MoveForm> =
        if (move.intersectionId == null) emptyList()
        else moves.filter { it.intersectionId == move.intersectionId && it.kind == move.kind && it.side == move.side && it.id <= move.id }
}

/** 같은 기록(같은 목록 객체)에 대한 계산 결과를 한 벌만 들고 있습니다. 메인 스레드에서만 씁니다. */
private class Records(val moves: List<MoveForm>, val trips: List<Trip>) {
    val tripSummaries: List<TripSummary> by lazy { TripSummary.all(trips, moves).asReversed() }
    val firsts: Map<Int, List<First>> by lazy { Firsts.of(tripSummaries.map { it.trip }) }
    val graduation: Graduation by lazy { Graduation.of(moves) }
    val advanced: Graduation by lazy { Graduation.of(moves, Skill.ADVANCED) }
    private val beforeLatest: List<MoveForm> by lazy {
        val latest = tripSummaries.firstOrNull()?.trip?.id
        if (latest == null) moves else moves.filter { it.tripId != latest }
    }
    val graduationBefore: Graduation by lazy { Graduation.of(beforeLatest) }
    val advancedBefore: Graduation by lazy { Graduation.of(beforeLatest, Skill.ADVANCED) }
    private val coachings = HashMap<Int, Coaching>()
    fun coaching(s: TripSummary): Coaching = coachings.getOrPut(s.trip.id) { Coaching.of(s, moves) }

    companion object {
        private var last: Records? = null
        fun of(moves: List<MoveForm>, trips: List<Trip>): Records {
            val l = last
            if (l != null && l.moves === moves && l.trips === trips) return l
            return Records(moves, trips).also { last = it }
        }
    }
}

class DriveFormApp : Application(), CarSignals.Listener {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 주행 속도. 화면 상태와 따로 두어, 속도가 바뀔 때 머리글만 다시 그리게 합니다. */
    private val _speedKmh = MutableStateFlow(0)
    val speedKmh: StateFlow<Int> = _speedKmh

    /** 차량 연결(Vehicle·NaviHelper). [connectVehicle]과 [disconnectVehicle] 사이에만 있습니다. */
    private var vehicle: Vehicle? = null
    var carSignals: CarSignals? = null
        private set
    private var ambient: AmbientLight? = null
    private lateinit var store: MoveStore
    private val io = Executors.newSingleThreadExecutor()
    private val chime by lazy { Chime(::log) }
    private var lastLocation: Location? = null
    private var locationStarted = false
    private var naviPlace: NaviPlace? = null

    private val detector = MoveDetector(
        onMove = { raw ->
            val loc = lastLocation
            val move = raw.copy(
                recordedAt = System.currentTimeMillis(),
                lat = loc?.latitude,
                lon = loc?.longitude,
                // 같은 자리 비교는 회전·유턴(교차로)과 주차(주차 자리)에만 씁니다. 주차 자리는 주차끼리만 묶습니다.
                intersectionId = when {
                    raw.kind.isTurn -> Intersections.assign(loc?.latitude, loc?.longitude, _state.value.moves.filter { it.kind != MoveKind.PARK })
                    raw.kind == MoveKind.PARK -> Intersections.assign(loc?.latitude, loc?.longitude, _state.value.moves.filter { it.kind == MoveKind.PARK })
                    else -> null
                },
                night = Firsts.isNightAt(System.currentTimeMillis()),
                tripId = currentTrip()?.id,
                // 동작을 마친 순간의 날씨로 기준을 정합니다.
                weather = conditions.weather(SystemClock.elapsedRealtime()),
            )
            log("동작 #${move.id} ${move.title} ${move.score}점 교차로=${move.intersectionId} ${move.checks.map { it.detail }}")
            val before = _state.value.moves
            updateMoves { it + move }
            // 주행 중 새 동작은 지금 주행(맨 위)의 것이므로 왼쪽 목록도 지금 주행을 고릅니다.
            _state.update { it.copy(selectedId = null, selectedTripId = null, screen = Screen.MOVES) }
            DriveCue.of(move, before, before + move)?.let(::cue)
            naviPlace?.requestAddress { place ->
                if (place != null) updateMoves { list -> list.map { if (it.id == move.id) it.copy(place = place) else it } }
            }
        },
        onMoveUpdated = { move -> updateMoves { list -> list.map { if (it.id == move.id) move.keepAppFields(it) else it } } },
    )

    override fun onCreate() {
        super.onCreate()
        store = MoveStore(filesDir)
        val (trips, loaded) = Trips.backfill(store.loadTrips(), store.load())
        val saved = Slim.of(loaded)
        // 앱이 주행 중에 꺼졌으면 그 주행은 마지막 동작에서 끝난 것으로 봅니다.
        val closed = trips.map { t -> if (t.endedAt != null) t else t.copy(endedAt = saved.filter { it.tripId == t.id }.maxOfOrNull { it.recordedAt } ?: t.startedAt) }
        _state.update { it.copy(moves = saved, trips = closed, started = prefs.getBoolean(PREF_STARTED, false), paint = CarPaint.of(prefs.getString(PREF_PAINT, null))) }
        io.execute { runCatching { store.save(saved); store.saveTrips(closed) } }
        saved.maxOfOrNull { it.id }?.let(detector::resumeAfter)
        log("저장된 동작 ${saved.size}개 불러옴")

        registerDemoReceiver()
        startLocation()
        connectVehicle()
    }

    /**
     * Vehicle SDK 공식 샘플의 순서: initialize → 지원 확인 → 구독 → 해제 → release.
     * 앱이 뜰 때 연결하고, 차량 신호를 보는 [DriveService]가 끝나면 [disconnectVehicle]로 닫습니다(서비스가 다시 뜨면 다시 연결).
     */
    fun connectVehicle() {
        if (vehicle != null) return
        val v = Vehicle(this)
        runCatching { v.initialize() }.onFailure { log("Vehicle 초기화 실패: ${it.message}"); return }
        vehicle = v
        ambient = AmbientLight(v, ::log)
        carSignals = CarSignals(this, v, this, ::log).also { it.start() }
        carSignals?.readProfile()?.let { p -> _state.update { it.copy(car = p) } }
        // 내비 연결은 시간이 걸려서 첫 동작 전에 미리 붙여 둡니다.
        naviPlace = NaviPlace(this, onRoadPoint = ::onRoadPoint, log = ::log).also { it.start() }
        log("차량 연결")
    }

    /** 구독을 모두 같은 리스너로 해제한 다음 연결을 닫습니다. */
    fun disconnectVehicle() {
        val v = vehicle ?: return
        ambient?.stop()
        carSignals?.stop()
        naviPlace?.stop()
        runCatching { v.release() }.onFailure { log("Vehicle 해제 실패: ${it.message}") }
        vehicle = null
        ambient = null
        carSignals = null
        naviPlace = null
        log("차량 연결 해제")
    }

    private fun updateMoves(change: (List<MoveForm>) -> List<MoveForm>) {
        _state.update { it.copy(moves = Slim.of(change(it.moves))) }
        saveMoves()
    }

    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 저장 요청이 몰려도(동작 하나에 주소·깜빡이 끄기 갱신이 잇따름) 아직 안 한 저장이 있으면 마지막 기록으로 한 번만 씁니다. */
    private fun saveMoves() {
        if (!savePending.compareAndSet(false, true)) return
        io.execute {
            savePending.set(false)
            val snapshot = _state.value.moves
            runCatching { store.save(snapshot) }.onFailure { log("저장 실패: ${it.message}") }
        }
    }

    /** 위치는 교차로를 구분하는 데만 씁니다. 권한이 없으면 교차로 비교 없이 동작합니다. */
    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locationStarted || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locationStarted = true
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { lastLocation = it }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER).forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper()) }
                .onFailure { log("위치 구독 실패 $p: ${it.message}") }
        }
    }

    /**
     * 주행 중 피드백은 화면 없이 소리와 조명으로만 합니다([DriveCue]: 이번 주 과제에만, 큰 순간은 크게).
     * - 과제 성공: 도-미 + 연속 횟수만큼 초록 깜빡임
     * - 과제 완료: 도-미-솔 + 초록이 세 번 출렁임
     * - 졸업: 도-미-솔-도 + 딱지 노랑에서 졸업 초록으로 천천히 바뀜
     */
    private fun cue(c: DriveCue) {
        log("주행 중 알림 $c")
        when (c) {
            is DriveCue.Success -> { chime.success(); ambient?.blink(GOOD_COLOR, c.count) }
            DriveCue.MissionDone -> { chime.missionDone(); ambient?.swell(GOOD_COLOR) }
            DriveCue.Graduated -> { chime.graduated(); ambient?.morph(GOOD_COLOR, COACH_COLOR) }
            is DriveCue.Badge -> { chime.badge(); ambient?.swell(BADGE_COLOR) }
            DriveCue.Parked -> { chime.success(); ambient?.flash(GOOD_COLOR, holdMillis = 2_000) }
        }
    }

    // ───── 주차 중 실시간: 후진이 빠르면 한 번 알려 줍니다 ─────

    private var gear = Gear.P
    private var reverseWarned = false

    /** 후진 중 시속 [FormScore.REVERSE_GOOD_KMH]를 넘으면 그 후진에서 한 번만 낮은 소리와 주황 불빛으로 알려 줍니다. */
    private fun trackReverse(mps: Float) {
        if (gear != Gear.R || reverseWarned || mps * 3.6f <= FormScore.REVERSE_GOOD_KMH) return
        reverseWarned = true
        log("후진이 빠름: 시속 ${(mps * 3.6f).toInt()}km")
        chime.slowDown()
        ambient?.flash(SLOW_COLOR, holdMillis = 1_500)
    }

    private fun onRoadPoint(kind: MoveKind, id: String, remainM: Int, limitKmh: Int?) {
        log("내비 알림 $kind $id ${remainM}m 앞" + (limitKmh?.let { " 제한 $it" } ?: ""))
        detector.onRoadPoint(SystemClock.elapsedRealtime(), kind, id, remainM.toFloat(), limitKmh)
    }

    /**
     * 시연용 입구. 에뮬레이터 내비는 경로가 진행되지 않아 주의 지점·단속 카메라 알림이 오지 않으므로,
     * 시연 도구가 켜진 빌드(BuildConfig.DEMO_TOOLS)에서만 adb로 내비와 같은 모양의 알림을 넣을 수 있게 합니다.
     *   adb shell am broadcast --user 10 -a dev.driveform.DEMO_ROAD -p dev.driveform --es kind camera --ei distance 150 --ei limit 50
     * kind: bump(과속방지턱), camera(단속 카메라), rail(철길 건널목), school(어린이 보호구역)
     */
    private fun registerDemoReceiver() {
        if (!BuildConfig.DEMO_TOOLS) return
        var count = 0
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val kind = when (intent.getStringExtra("kind")) {
                    "camera" -> MoveKind.CAMERA
                    "rail" -> MoveKind.RAILROAD
                    "school" -> MoveKind.SCHOOL_ZONE
                    else -> MoveKind.SPEED_BUMP
                }
                val limit = intent.getIntExtra("limit", -1).takeIf { it > 0 }
                onRoadPoint(kind, intent.getStringExtra("id") ?: "demo-${++count}", intent.getIntExtra("distance", 40), limit)
            }
        }, IntentFilter(DEMO_ROAD), ContextCompat.RECEIVER_EXPORTED)

        // 주행 중 알림(불빛·소리)만 따로 틀어 봅니다. 영상에서 콘솔의 앰비언트 라이트를 보여 줄 때 씁니다.
        //   adb shell am broadcast --user 10 -a dev.driveform.DEMO_CUE -p dev.driveform --es cue success|mission|graduate|badge|parked|slow|notice [--ei count 3]
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("cue")) {
                    "mission" -> cue(DriveCue.MissionDone)
                    "graduate" -> cue(DriveCue.Graduated)
                    "notice" -> { chime.notice(); ambient?.flash(NOTICE_COLOR, holdMillis = 2_000) }
                    "badge" -> cue(DriveCue.Badge(Skill.PARK))
                    "parked" -> cue(DriveCue.Parked)
                    "slow" -> { chime.slowDown(); ambient?.flash(SLOW_COLOR, holdMillis = 1_500) }
                    else -> cue(DriveCue.Success(intent.getIntExtra("count", 1)))
                }
            }
        }, IntentFilter(DEMO_CUE), ContextCompat.RECEIVER_EXPORTED)

        // 다시보기 회전 게이지 비교: adb shell am broadcast --user 10 -a dev.driveform.DEMO_STYLE -p dev.driveform --es gauge A|B
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("gauge")?.let { dev.driveform.ui.ReplayStyle.turnGauge = it }
            }
        }, IntentFilter("dev.driveform.DEMO_STYLE"), ContextCompat.RECEIVER_EXPORTED)

        // 시연 기록 준비. 지금 기록은 처음 한 번만 backup 폴더에 옮겨 두고, restore로 되돌립니다.
        //   adb shell am broadcast --user 10 -a dev.driveform.DEMO_SEED -p dev.driveform [--es mode restore|graduate|badge|start]
        // graduate: 졸업 직전 기록 + 깜빡이를 미리 켠 짧은 시연 주행까지 넣어, 졸업 화면(스티커 떼기)을 바로 띄웁니다.
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!_state.value.parked || currentTrip() != null) { log("시연 기록: 주행 중에는 바꾸지 않습니다"); return }
                val mode = intent.getStringExtra("mode")
                // start: 기록은 그대로 두고 시작하기 화면(딱지 붙이기)부터 다시 봅니다.
                if (mode == "start") {
                    prefs.edit().putBoolean(PREF_STARTED, false).apply()
                    _state.update { it.copy(started = false) }
                    return
                }
                io.execute { replaceRecords(restore = mode == "restore", finale = mode == "graduate" || mode == "badge", badge = mode == "badge") }
            }
        }, IntentFilter(DEMO_SEED), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun replaceRecords(restore: Boolean, finale: Boolean = false, badge: Boolean = false) {
        val backup = File(filesDir, "backup")
        val files = listOf("turns.json", "trips.json")
        if (restore) {
            if (!backup.exists()) { log("시연 기록: 되돌릴 백업이 없습니다"); return }
            files.forEach { name ->
                val b = File(backup, name)
                if (b.exists()) b.copyTo(File(filesDir, name), overwrite = true) else File(filesDir, name).delete()
            }
            backup.deleteRecursively()
        } else {
            // 두 번 넣어도 처음의 실제 기록이 백업으로 남게, 백업이 없을 때만 옮깁니다.
            if (!backup.exists()) {
                backup.mkdirs()
                files.forEach { name -> File(filesDir, name).takeIf { it.exists() }?.copyTo(File(backup, name)) }
            }
            val demo = DemoHistory.build(System.currentTimeMillis(), finale = finale, badge = badge, untilYesterday = true)
            store.save(demo.moves)
            store.saveTrips(demo.trips)
        }
        val moves = store.load()
        val trips = store.loadTrips()
        _state.update {
            val fresh = UiState(moves = moves, trips = trips, parked = it.parked, started = it.started, car = it.car, paint = it.paint)
            // 졸업 장면을 넣었으면 그 주행 직후처럼 졸업 화면을 띄웁니다.
            if (finale) fresh.copy(screen = Screen.GRAD) else fresh
        }
        moves.maxOfOrNull { it.id }?.let(detector::resumeAfter)
        log("시연 기록: ${if (restore) "되돌림" else "넣음"} 주행 ${trips.size}번, 동작 ${moves.size}개")
    }

    /**
     * 요약과 동작 기록은 좌우로 넘기는 이웃이라, 넘기는 도중에도 같은 주행을 보이도록 고를 때 함께 맞춥니다.
     * 동작을 고르면 그 동작의 주행을 요약에 둡니다.
     */
    fun select(id: Int) = _state.update { it.copy(selectedId = id, selectedTripId = it.tripOf(id) ?: it.selectedTripId) }

    override fun onSpeed(ms: Long, mps: Float) {
        detector.onSpeed(ms, mps)
        trackReverse(mps)
        lastSpeed?.let { (pms, pv) ->
            if (currentTrip() != null && ms > pms) tripDistanceM += pv * (ms - pms) / 1000.0
            if (ms > pms) trackLights(ms - pms, pv)
        }
        lastSpeed = ms to mps
        val kmh = (mps * 3.6f).toInt()
        _speedKmh.value = kmh
    }

    // ───── 날씨 연수: 와이퍼·안개등·전조등·ESC ─────

    private val conditions = WeatherTracker()
    private val main = Handler(Looper.getMainLooper())
    private var lightsOffWetMs = 0L
    private var lightsNoticed = false
    private val refreshWeather = Runnable { refreshWeather() }

    override fun onWiper(ms: Long, on: Boolean) { conditions.onWiper(ms, on); log("와이퍼 ${if (on) "켜짐" else "꺼짐"}"); refreshWeather() }
    override fun onFog(ms: Long, on: Boolean) { conditions.onFog(on); log("안개등 ${if (on) "켜짐" else "꺼짐"}"); refreshWeather() }
    override fun onLowBeam(ms: Long, on: Boolean) { conditions.onLowBeam(on); log("전조등 ${if (on) "켜짐" else "꺼짐"}") }
    override fun onSlip(ms: Long) { log("ESC 작동: 미끄러짐"); detector.onSlip(ms) }

    /** 날씨가 바뀌었으면 화면과 지금 주행에 반영하고, 다음에 바뀔 수 있는 때(와이퍼 10초, 젖은 길 5분)에 다시 봅니다. */
    private fun refreshWeather() {
        val now = SystemClock.elapsedRealtime()
        val w = conditions.weather(now)
        if (w != _state.value.weather) {
            log("날씨 $w")
            _state.update { it.copy(weather = w) }
        }
        currentTrip()?.let { t -> if (t.weather.worse(w) != t.weather) updateTrips { list -> list.map { if (it.id == t.id) it.copy(weather = t.weather.worse(w)) else it } } }
        main.removeCallbacks(refreshWeather)
        conditions.nextChangeAt(now)?.let { main.postDelayed(refreshWeather, it - now + 50) }
    }

    /** 비·안개 속에서 전조등 없이 달린 시간을 셉니다. [WeatherTracker.LIGHTS_OFF_NOTICE_MS]를 넘으면 주행마다 한 번 소리와 노란 불빛으로 알려 줍니다. */
    private fun trackLights(dtMs: Long, mps: Float) {
        if (currentTrip() == null || mps * 3.6f < 5f || conditions.lowBeam || _state.value.weather == Weather.CLEAR) return
        lightsOffWetMs += dtMs
        if (!lightsNoticed && lightsOffWetMs >= WeatherTracker.LIGHTS_OFF_NOTICE_MS) {
            lightsNoticed = true
            log("비·안개 속 전조등 꺼짐 알림")
            chime.notice()
            ambient?.flash(NOTICE_COLOR, holdMillis = 2_000)
        }
    }

    override fun onSteering(ms: Long, deg: Float) = detector.onSteering(ms, deg)
    override fun onBrake(ms: Long, pressed: Boolean) = detector.onBrake(ms, pressed)
    override fun onTurnSignal(ms: Long, side: Side?) = detector.onTurnSignal(ms, side)
    override fun onGear(ms: Long, gear: Gear) {
        val parked = gear == Gear.P
        if (gear != this.gear) reverseWarned = false
        this.gear = gear
        val wasParked = _state.value.parked
        // 주차 장면은 P에 둔 순간 지금 주행에 붙어야 하므로 주행을 끝내기 전에 넘깁니다.
        detector.onGear(ms, gear)
        _state.update { it.copy(parked = parked) }
        if (parked == wasParked) return
        if (parked) endTrip() else startTrip()
    }

    private var lastSpeed: Pair<Long, Float>? = null
    private var tripDistanceM = 0.0

    private fun currentTrip(): Trip? = _state.value.trips.lastOrNull()?.takeIf { it.endedAt == null }

    private fun startTrip() {
        val id = (_state.value.trips.maxOfOrNull { it.id } ?: 0) + 1
        tripDistanceM = 0.0
        lightsOffWetMs = 0L
        lightsNoticed = false
        updateTrips { it + Trip(id, System.currentTimeMillis(), weather = _state.value.weather) }
        log("주행 #$id 시작")
    }

    /** P에 두면 주행을 닫고, 동작이 있었으면 연수 일지(졸업했으면 졸업 화면)를 띄웁니다. */
    private fun endTrip() {
        val trip = currentTrip() ?: return
        val ended = trip.copy(endedAt = System.currentTimeMillis(), distanceM = tripDistanceM, lightsOffWetS = (lightsOffWetMs / 1000).toInt())
        updateTrips { list -> list.map { if (it.id == trip.id) ended else it } }
        val summary = _state.value.tripSummaries.firstOrNull { it.trip.id == trip.id }
        log("주행 #${trip.id} 끝 ${"%.1f".format(tripDistanceM / 1000)}km 동작 ${summary?.moves?.size ?: 0}개 점수 ${summary?.score}")
        if (summary == null) return
        // 이번 주행으로 마지막 과목까지 이수했으면 졸업 화면(스티커 떼기)을, 아니면 이번 연수 일지를 띄웁니다.
        val graduatedNow = (_state.value.graduation.graduatedAt ?: 0L) >= trip.startedAt
        _state.update { it.copy(screen = if (graduatedNow) Screen.GRAD else Screen.TRIP, selectedTripId = trip.id) }
    }

    private fun updateTrips(change: (List<Trip>) -> List<Trip>) {
        _state.update { it.copy(trips = change(it.trips)) }
        val snapshot = _state.value.trips
        io.execute { runCatching { store.saveTrips(snapshot) }.onFailure { log("주행 저장 실패: ${it.message}") } }
    }

    fun show(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun choosePaint(p: CarPaint) {
        prefs.edit().putString(PREF_PAINT, p.name).apply()
        _state.update { it.copy(paint = p) }
    }

    private val prefs by lazy { getSharedPreferences("drive_form", MODE_PRIVATE) }

    /** 시작하기 화면에서 딱지를 붙였습니다. */
    fun start() {
        prefs.edit().putBoolean(PREF_STARTED, true).apply()
        _state.update { it.copy(started = true, screen = Screen.GRAD) }
    }

    /** 주행을 고르면 동작 기록은 그 주행의 마지막 동작에 둡니다(이미 그 주행의 동작을 보고 있으면 그대로). */
    fun selectTrip(id: Int) = _state.update { s ->
        val keep = s.selected?.tripId == id
        s.copy(selectedTripId = id, selectedId = if (keep) s.selectedId else s.moves.filter { it.tripId == id }.maxOfOrNull { it.id } ?: s.selectedId)
    }

    /** 요약에서 동작을 누르면 그 동작의 폼 카드로 갑니다. */
    fun openMove(id: Int) = _state.update { it.copy(selectedId = id, selectedTripId = it.tripOf(id) ?: it.selectedTripId, screen = Screen.MOVES) }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object {
        const val TAG = "DriveForm"
        const val GOOD_COLOR = 0x00FF66
        /** 비·안개 속 전조등 알림. 초보운전 딱지의 노랑입니다. */
        const val NOTICE_COLOR = 0xFFD43B
        /** 졸업 연출의 시작 색: 초보운전 딱지 노랑. */
        const val STICKER_COLOR = 0xFFD43B
        /** 운전 코치의 청록(졸업할 때 실내등이 이 색으로 바뀝니다). */
        const val COACH_COLOR = 0x3FD0C9
        /** 심화 배지: 금빛. */
        const val BADGE_COLOR = 0xFFC233
        /** 천천히: 주황. */
        const val SLOW_COLOR = 0xFF8A00
        const val DEMO_ROAD = "dev.driveform.DEMO_ROAD"
        const val DEMO_SEED = "dev.driveform.DEMO_SEED"
        const val DEMO_CUE = "dev.driveform.DEMO_CUE"
        private const val PREF_STARTED = "started"
        private const val PREF_PAINT = "paint"
    }
}

/** 검출기가 고친 기록에 앱이 붙인 시각·위치·교차로 정보를 다시 옮겨 줍니다. */
private fun MoveForm.keepAppFields(old: MoveForm) =
    copy(recordedAt = old.recordedAt, lat = old.lat, lon = old.lon, intersectionId = old.intersectionId, place = old.place, tripId = old.tripId, weather = old.weather, night = old.night)
