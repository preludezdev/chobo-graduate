package dev.driveform.vehicle

/**
 * 차가 알려 주는 차종과 차체 치수(표준 차량 정보 속성 INFO_*). 색상은 차가 알려 주지 않아서 사용자가 '내 차'에서 고릅니다.
 * 치수는 mm로 오고 여기서는 m로 둡니다.
 */
data class CarProfile(
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val lengthM: Float? = null,
    val widthM: Float? = null,
    val heightM: Float? = null,
    val wheelbaseM: Float? = null,
) {
    /** 다시보기에 입힐 외관. 지금은 시연 차종(더 뉴 그랜저) 한 벌만 있어서, 모르는 차종도 이 외관을 씁니다. */
    val look: CarLook get() = CarLook.GRANDEUR

    companion object {
        /** INFO_EXTERIOR_DIMENSIONS: 높이, 길이, 폭, 미러 포함 폭, 축간거리, 앞 윤거, 뒤 윤거, 회전 지름(mm). */
        fun fromDimensions(make: String?, model: String?, year: Int?, dims: IntArray?): CarProfile = CarProfile(
            make = make?.takeIf { it.isNotBlank() },
            model = model?.takeIf { it.isNotBlank() },
            year = year?.takeIf { it > 0 },
            heightM = dims?.getOrNull(0)?.let { it / 1000f },
            lengthM = dims?.getOrNull(1)?.let { it / 1000f },
            widthM = dims?.getOrNull(2)?.let { it / 1000f },
            wheelbaseM = dims?.getOrNull(4)?.let { it / 1000f },
        )
    }
}

/** 렌더링해 둔 외관. [specs]는 제원표 값(m)입니다. */
enum class CarLook(val displayName: String, val lengthM: Float, val widthM: Float, val heightM: Float, val wheelbaseM: Float) {
    GRANDEUR("더 뉴 그랜저", 5.05f, 1.88f, 1.46f, 2.895f),
}

/** 고를 수 있는 차 색. [hex]는 렌더링 폴더 이름이기도 합니다. 어두운 색은 지도에서 잘 보이게 밝은 테두리를 함께 그립니다. */
enum class CarPaint(val label: String, val hex: String, val dark: Boolean) {
    SILVER("은색", "D9DEE4", false),
    WHITE("흰색", "F2F2EE", false),
    GRAY("회색", "70767E", true),
    BLACK("검정", "1C1F23", true),
    BURGUNDY("버건디", "5A1E24", true),
    NAVY("남색", "2B4163", true);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SILVER
    }
}
