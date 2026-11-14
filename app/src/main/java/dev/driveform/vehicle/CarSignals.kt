package dev.driveform.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.driveform.form.Gear
import dev.driveform.form.Side

/**
 * 폼 분석에 쓰는 차량 신호를 모아 메인 스레드에서 [Listener]로 넘깁니다.
 * Vehicle SDK 공식 샘플의 순서를 따릅니다: 지원 여부 확인 → 구독 → (끝날 때) 같은 리스너로 해제.
 * 차에 없는 신호는 구독하지 않고 건너뛰며, 그 신호를 쓰는 기능만 빠집니다.
 */
class CarSignals(
    context: Context,
    val vehicle: Vehicle,
    private val listener: Listener,
    private val log: (String) -> Unit,
) {
    interface Listener {
        fun onSpeed(ms: Long, mps: Float)
        fun onSteering(ms: Long, deg: Float)
        fun onBrake(ms: Long, pressed: Boolean)
        fun onTurnSignal(ms: Long, side: Side?)
        fun onGear(ms: Long, gear: Gear)
        /** 와이퍼(앞·뒤 중 하나라도)가 켜졌는지. 비가 오는지 보는 데 씁니다. */
        fun onWiper(ms: Long, on: Boolean)
        fun onLowBeam(ms: Long, on: Boolean)
        /** 앞·뒤 안개등 경고등이 켜졌는지. 안개등 상태 API는 값을 주지 않아 경고등으로 봅니다. */
        fun onFog(ms: Long, on: Boolean)
        /** ESC 경고등이 깜빡이기 시작함: 차가 미끄러지는 걸 자세 제어가 잡아 준 순간입니다. */
        fun onSlip(ms: Long)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** SDK 콜백은 바인더 스레드에서 오므로 받은 시각을 찍고 메인 스레드로 넘깁니다. */
    private fun post(block: (Long) -> Unit) {
        val ms = SystemClock.elapsedRealtime()
        main.post { block(ms) }
    }

    // 해제할 때 같은 리스너를 넘겨야 하므로 필드로 들고 있습니다.
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            val g = when (gear) {
                VehicleGear.GEAR_PARK -> Gear.P
                VehicleGear.GEAR_REVERSE -> Gear.R
                VehicleGear.GEAR_NEUTRAL -> Gear.N
                null, VehicleGear.GEAR_UNKNOWN -> return
                else -> Gear.D
            }
            post { listener.onGear(it, g) }
        }
        override fun onFailed(e: Exception) = log("기어 구독 실패: ${e.message}")
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            if (value != null) post { listener.onBrake(it, value) }
        }
        override fun onFailed(e: Exception) = log("브레이크 구독 실패: ${e.message}")
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) {
            if (value != null) post { listener.onSteering(it, value) }
        }
        override fun onFailed(e: Exception) = log("조향 구독 실패: ${e.message}")
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            val side = when (value) {
                TurnSignalValue.LEFT -> Side.LEFT
                TurnSignalValue.RIGHT -> Side.RIGHT
                else -> null
            }
            post { listener.onTurnSignal(it, side) }
        }
        override fun onFailed(e: Exception) = log("깜빡이 구독 실패: ${e.message}")
    }
    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(value: LightState?) {
            post { listener.onLowBeam(it, value == LightState.ON) }
        }
        override fun onFailed(e: Exception) = log("전조등 구독 실패: ${e.message}")
    }
    private var fog = false
    private var escBlinking = false
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(value: Telltales?) {
            val list = value?.telltales ?: return
            fun state(t: TelltaleType) = list.firstOrNull { it.type == t }?.state
            val fogNow = listOf(TelltaleType.FRONT_FOG_LAMP, TelltaleType.REAR_FOG_LAMP).any { state(it).let { s -> s != null && s != TelltaleState.OFF } }
            val escNow = state(TelltaleType.ESC) == TelltaleState.BLINK
            post { ms ->
                if (fogNow != fog) { fog = fogNow; listener.onFog(ms, fogNow) }
                if (escNow && !escBlinking) listener.onSlip(ms)
                escBlinking = escNow
            }
        }
        override fun onFailed(e: Exception) = log("경고등 구독 실패: ${e.message}")
    }
    private var wiperAreas: List<WindowArea> = emptyList()
    private var wiperOn = false
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(value: WiperState?) = recheckWiper()
        override fun onFailed(e: Exception) = log("와이퍼 구독 실패: ${e.message}")
    }

    /** 구독에 성공한 것만 모아 두었다가 [stop]에서 거꾸로 해제합니다. */
    private val unregisters = mutableListOf<() -> Unit>()
    private var stopped = false

    fun start() {
        stopped = false
        // 기어는 지원 여부를 묻는 API가 없어 바로 구독합니다(모든 차에 있는 신호).
        subscribe("기어", { vehicle.getGear().registerGearSelection(gearListener) }, { vehicle.getGear().unregisterGearSelection(gearListener) })
        whenReadable("브레이크", { ok, fail -> vehicle.getBrake().checkBrakePedalStatusCapabilities(ok, fail) }) {
            subscribe("브레이크", { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }, { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) })
        }
        whenReadable("조향", { ok, fail -> vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ c, _ -> ok(c) }, fail) }) {
            subscribe("조향", { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }, { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) })
        }
        whenReadable("깜빡이", { ok, fail -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities(ok, fail) }) {
            subscribe("깜빡이", { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) }, { vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener) })
        }
        startWeather()
        startSpeed()
    }

    /**
     * 날씨 신호: 와이퍼 켜짐·꺼짐, 전조등, 경고등(안개등, ESC). 모두 바뀔 때만 알려 주는 구독이라 따로 읽어 오지 않습니다.
     * (와이퍼 단계는 바뀔 때 알려 주는 API가 없어 쓰지 않습니다. 켜짐·꺼짐만으로 빗길인지는 충분히 압니다.)
     */
    private fun startWeather() {
        runCatching {
            vehicle.getWiper().checkWiperStateCapabilities(
                { map: Map<WindowArea, List<WiperState>> ->
                    wiperAreas = map.keys.toList()
                    wiperAreas.forEach { area ->
                        subscribe("와이퍼", { vehicle.getWiper().registerWiperState(area, wiperListener) }, { vehicle.getWiper().unregisterWiperState(area, wiperListener) })
                    }
                },
                { e -> log("와이퍼 미지원: ${e.message}") },
            )
        }.onFailure { log("와이퍼 확인 실패: ${it.message}") }
        whenReadable("전조등", { ok, fail -> vehicle.getLight().checkLowBeamLightStateCapabilities({ c, _ -> ok(c) }, fail) }) {
            subscribe("전조등", { vehicle.getLight().registerLowBeamLightState(lowBeamListener) }, { vehicle.getLight().unregisterLowBeamLightState(lowBeamListener) })
        }
        // 경고등은 PRIVATE_CAR_SAFETY 권한이 있어야 합니다. 없으면 안갯길 판단과 '미끄러질 뻔함' 장면만 빠집니다.
        whenReadable("경고등", { ok, fail -> vehicle.getSafety().checkTelltalesCapabilities(ok, fail) }) {
            subscribe("경고등", { vehicle.getSafety().registerTelltales(telltaleListener) }, { vehicle.getSafety().unregisterTelltales(telltaleListener) })
        }
    }

    /**
     * 구독 콜백은 앞·뒤 와이퍼 값이 섞여 들어올 때가 있어(에뮬레이터에서 확인), 신호가 오면 두 와이퍼의 지금 상태를 다시 읽어 정합니다.
     * 바뀔 때만 읽으므로 계속 묻지는 않습니다.
     */
    private fun recheckWiper() {
        val areas = wiperAreas
        val states = HashMap<WindowArea, Boolean>()
        areas.forEach { area ->
            vehicle.getWiper().getCurrentWiperState(area, { v ->
                post { ms ->
                    states[area] = v == WiperState.ON
                    if (states.size < areas.size) return@post
                    val now = states.values.any { it }
                    if (now != wiperOn) { wiperOn = now; listener.onWiper(ms, now) }
                }
            }, { e -> log("와이퍼 읽기 실패: ${e.message}") })
        }
    }

    /** 지원 여부를 먼저 묻고, 읽을 수 있다고 답한 신호만 구독합니다. */
    private fun whenReadable(
        name: String,
        check: (ok: (CapabilityConfig) -> Unit, fail: (Exception) -> Unit) -> Unit,
        register: () -> Unit,
    ) {
        runCatching {
            check(
                { config ->
                    if (config.accessType == AccessType.NONE || config.accessType == AccessType.WRITE) log("$name 읽기 미지원(${config.accessType})")
                    else main.post { if (!stopped) register() }
                },
                { e -> log("$name 미지원: ${e.message}") },
            )
        }.onFailure { log("$name 확인 실패: ${it.message}") }
    }

    private fun subscribe(name: String, register: () -> Unit, unregister: () -> Unit) {
        if (stopped) return
        runCatching(register)
            .onSuccess { synchronized(unregisters) { unregisters += unregister }; log("$name 구독") }
            .onFailure { log("$name 구독 실패: ${it.message}") }
    }

    /** 구독한 것을 모두 같은 리스너로 해제합니다. [Vehicle.release] 전에 불러야 합니다. */
    fun stop() {
        stopped = true
        val all = synchronized(unregisters) { unregisters.toList().also { unregisters.clear() } }
        all.asReversed().forEach { runCatching(it).onFailure { e -> log("구독 해제 실패: ${e.message}") } }
        runCatching { props?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        car = null
        props = null
        main.removeCallbacksAndMessages(null)
        log("차량 신호 구독 ${all.size}개와 속도 구독 해제")
    }

    // Car를 필드로 붙잡아 두지 않으면 GC 뒤에 연결이 끊겨 속도 콜백이 멈춥니다.
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            post { listener.onSpeed(it, mps) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = log("속도 오류 $propId")
    }

    /**
     * 속도는 Vehicle SDK에 없어서 표준 AAOS 속성으로 받습니다. CAR_SPEED 권한이 있어야 합니다.
     * 동작 판정은 약 10Hz를 기준으로 만들어서 그만큼(SENSOR_RATE_FAST)만 받습니다. 더 자주 받으면 메인 스레드 일만 늘어납니다.
     */
    fun startSpeed() {
        if (stopped) return
        runCatching {
            val car = car ?: Car.createCar(appContext).also { car = it }
            val props = props ?: (car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also { props = it }
            val ok = props.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            log("속도 구독 $ok")
        }.onFailure { log("속도 구독 실패: ${it.message}") }
    }

    /** 차종과 차체 치수를 한 번 읽습니다(CAR_INFO 권한). 읽지 못하면 null. */
    fun readProfile(): CarProfile? = runCatching {
        val car = car ?: Car.createCar(appContext).also { car = it }
        val props = props ?: (car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also { props = it }
        fun <T> read(id: Int): T? = runCatching { @Suppress("UNCHECKED_CAST") (props.getProperty<Any>(id, 0).value as T) }.getOrNull()
        val dims = read<Array<Int>>(VehiclePropertyIds.INFO_EXTERIOR_DIMENSIONS)?.toIntArray()
        CarProfile.fromDimensions(read(VehiclePropertyIds.INFO_MAKE), read(VehiclePropertyIds.INFO_MODEL), read(VehiclePropertyIds.INFO_MODEL_YEAR), dims)
            .also { log("차종 ${it.make} ${it.model} ${it.year} · ${it.lengthM}×${it.widthM}m") }
    }.onFailure { log("차종 읽기 실패: ${it.message}") }.getOrNull()
}
