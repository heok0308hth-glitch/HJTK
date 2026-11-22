package com.hjtk.app.android.ports

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import com.hjtk.app.MainActivity
import com.hjtk.app.android.prefs.Settings as AppSettings
import com.hjtk.app.core.DevicePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DevicePortImpl(
    private val context: Context,
    private val settings: AppSettings
) : DevicePort {

    override suspend fun battery(): String = withContext(Dispatchers.IO) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = when (status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중"
            BatteryManager.BATTERY_STATUS_FULL -> "충전 완료"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "사용 중"
            else -> "상태 확인 불가"
        }
        val temp = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        "배터리 $level% · $charging" + if (temp > 0) " · ${"%.1f".format(temp / 10.0)}°C" else ""
    }

    override suspend fun storage(): String = withContext(Dispatchers.IO) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val used = total - free
        "저장 공간: 사용 ${gb(used)}GB / 전체 ${gb(total)}GB (여유 ${gb(free)}GB)"
    }

    private fun gb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0 / 1024.0)

    override suspend fun network(): String = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return@withContext "네트워크에 연결되어 있지 않습니다."
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "이더넷"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "알 수 없는 연결"
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        "$type · 인터넷 ${if (internet) "가능" else "불가"} · ${if (validated) "검증됨" else "검증 안 됨"}"
    }

    override suspend fun setVolume(percent: Int): String = withContext(Dispatchers.Main) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100.0).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        "미디어 볼륨을 ${percent.coerceIn(0, 100)}%로 설정했습니다."
    }

    override suspend fun setBrightness(percent: Int): String = withContext(Dispatchers.Main) {
        if (!AndroidSettings.System.canWrite(context)) {
            error("화면 밝기를 바꾸려면 ‘시스템 설정 변경’ 권한이 필요합니다. 설정 > 권한에서 허용해 주세요.")
        }
        val value = (255 * percent.coerceIn(1, 100) / 100.0).toInt()
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, value)
        "화면 밝기를 ${percent.coerceIn(1, 100)}%로 설정했습니다."
    }

    override suspend fun torch(on: Boolean): String = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: error("이 기기에는 사용할 수 있는 플래시가 없습니다.")
        cm.setTorchMode(id, on)
        if (on) "손전등을 켰습니다." else "손전등을 껐습니다."
    }

    override suspend fun openSettings(section: String) = withContext(Dispatchers.Main) {
        val internal = setOf("mail", "ai", "app", "dev", "hub", "memory", "about")
        if (section in internal) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("settings_section", section)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return@withContext
        }
        val intent = when (section) {
            "notification" -> Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            "write_settings" -> Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            "battery" -> Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "overlay" -> Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "wifi" -> Intent(AndroidSettings.ACTION_WIFI_SETTINGS)
            "calendar" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            "app" -> Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            else -> Intent(AndroidSettings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Diagnostics helper: is this app exempt from battery optimisation? */
    fun batteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }
}
