package dev.driveform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * 앱이 화면 뒤에 있어도 차량 신호를 놓치지 않게 프로세스를 살려 두는 포그라운드 서비스.
 * 에뮬레이터에서 뒤로 간 앱은 얼어 있다가 앞으로 나올 때 신호를 한꺼번에 받아 시간이 뭉개졌습니다.
 * 실제 분석은 [DriveFormApp]이 하고, 이 서비스는 살아 있기만 합니다.
 */
class DriveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (application as DriveFormApp).connectVehicle()
    }

    /** 서비스가 끝나면 차량 구독을 해제하고 연결을 닫습니다(공식 가이드: 리스너가 세션보다 오래 살면 안 됨). */
    override fun onDestroy() {
        (application as DriveFormApp).disconnectVehicle()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "주행 폼 분석", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("드라이브폼")
            .setContentText("회전과 차선 변경 폼을 보고 있어요")
            .setOngoing(true)
            .build()
        // 위치 권한이 있으면 위치 유형으로 띄워야 뒤에서도 교차로 위치를 받을 수 있습니다.
        val type = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        runCatching { startForeground(NOTIFICATION_ID, notification, type) }
            .onFailure { Log.w(DriveFormApp.TAG, "포그라운드 서비스 시작 실패: ${it.message}") }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "drive_form"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveService::class.java)) }
                .onFailure { Log.w(DriveFormApp.TAG, "서비스 시작 실패: ${it.message}") }
        }
    }
}
