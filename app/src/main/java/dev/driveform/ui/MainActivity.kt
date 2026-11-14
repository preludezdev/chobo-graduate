package dev.driveform.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.driveform.DriveFormApp
import dev.driveform.DriveService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DriveFormApp
        val missing = arrayOf(CAR_SPEED, LOCATION).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        // 화면이 앞에 있을 때 시작해야 위치 유형 포그라운드 서비스가 허용됩니다.
        DriveService.start(this)
        // 운전 코치 화면은 지도와 차가 잘 보이도록 낮·밤 상관없이 어두운 화면입니다.
        palette = CoachPalette
        setContent {
            CoachApp(
                app.state.collectAsStateWithLifecycle().value,
                onSelect = app::select, onShow = app::show, onSelectTrip = app::selectTrip, onStart = app::start, onPaint = app::choosePaint,
                speedKmh = app.speedKmh.collectAsStateWithLifecycle(),
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val app = application as DriveFormApp
        permissions.zip(grantResults.toList()).filter { it.second == PackageManager.PERMISSION_GRANTED }.forEach { (p, _) ->
            when (p) {
                CAR_SPEED -> app.carSignals?.startSpeed()
                LOCATION -> {
                    app.startLocation()
                    // 위치 유형으로 다시 띄웁니다.
                    DriveService.start(this)
                }
            }
        }
    }

    companion object {
        private const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        private const val REQUEST_PERMISSIONS = 1
    }
}
