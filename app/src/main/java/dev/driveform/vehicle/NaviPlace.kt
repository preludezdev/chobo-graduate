package dev.driveform.vehicle

import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.driveform.form.MoveKind

/**
 * 차 안의 내비(NaviHelper)와 연결합니다.
 * - 현재 위치의 주소: 위치를 외부 서버로 보내지 않고 사람이 읽을 수 있는 장소 이름을 얻기 위해서입니다.
 *   응답은 [onCurrentLocationInfo]로 오며, 오지 않으면 [TIMEOUT_MS] 뒤에 null로 끝냅니다.
 * - 길 위 지점 알림: 경로 안내 중 내비가 주는 주의 지점(과속방지턱, 어린이 보호구역, 철길 건널목)과
 *   단속 카메라(제한속도 포함)를 남은 거리와 함께 [onRoadPoint]로 넘깁니다.
 */
class NaviPlace(
    context: Context,
    private val onRoadPoint: (kind: MoveKind, id: String, remainM: Int, limitKmh: Int?) -> Unit,
    private val log: (String) -> Unit,
) : NaviHelperEventListener {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private val waiting = ArrayDeque<(String?) -> Unit>()

    fun start() {
        helper = runCatching {
            NaviHelper(appContext).apply {
                initialize()
                addListener(this@NaviPlace)
            }
        }.onFailure { log("NaviHelper 초기화 실패: ${it.message}") }.getOrNull()
    }

    /** 등록한 리스너를 떼고 연결을 닫습니다. 기다리던 주소 요청은 null로 끝냅니다. */
    fun stop() {
        helper?.let { h ->
            runCatching { h.removeListener(this) }
            runCatching { h.release() }.onFailure { log("NaviHelper 해제 실패: ${it.message}") }
        }
        helper = null
        main.removeCallbacksAndMessages(null)
        while (waiting.isNotEmpty()) waiting.removeFirst().invoke(null)
    }

    /** 결과는 메인 스레드에서 한 번만 불립니다. */
    fun requestAddress(onResult: (String?) -> Unit) {
        val h = helper ?: return onResult(null)
        var done = false
        val once: (String?) -> Unit = { if (!done) { done = true; onResult(it) } }
        waiting.addLast(once)
        main.postDelayed({ if (waiting.remove(once)) once(null) }, TIMEOUT_MS)
        runCatching { h.getCurrentLocationInfo() }.onFailure {
            log("현재 위치 정보 요청 실패: ${it.message}")
            if (waiting.remove(once)) once(null)
        }
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        val address = currentLocationInfo.address?.trim()?.takeIf { it.isNotEmpty() }
        log("내비 현재 위치: $address (${currentLocationInfo.latitude}, ${currentLocationInfo.longitude})")
        main.post { waiting.removeFirstOrNull()?.invoke(address) }
    }

    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
        cautionPointList.forEach {
            val kind = when (it.type) {
                CautionPointType.SPEED_BUMP -> MoveKind.SPEED_BUMP
                CautionPointType.SCHOOL_ZONE -> MoveKind.SCHOOL_ZONE
                CautionPointType.RAILROAD_CROSSING -> MoveKind.RAILROAD
                else -> return@forEach
            }
            main.post { onRoadPoint(kind, it.id, it.remainDistance, null) }
        }
    }

    /** 구간 단속(시작·끝·구간 알림)은 한 지점이 아니라서 제외하고, 지점 단속 카메라만 씁니다. */
    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
        cameraAlertList.filter { it.type !in SECTION_TYPES }.forEach {
            val limit = it.speedLimit.takeIf { l -> l > 0 }
            main.post { onRoadPoint(MoveKind.CAMERA, it.id, it.remainDistance, limit) }
        }
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
        private val SECTION_TYPES = setOf(
            CameraType.SPEED_CAMERA_SECTION_START, CameraType.SECTION_SPEED_ALERT, CameraType.SPEED_CAMERA_SECTION_END,
        )
    }
}
