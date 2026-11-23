package com.ht.hjtk.engine

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 화면 공유(접근성 없이). 안드로이드 기본 '화면 녹화 허락'을 매번 사용자가 승인하고, 한 장만 찍은 뒤 바로 멈춘다.
 * 접근성 권한을 쓰지 않으므로 Play 프로텍트 사이드로드 차단에 걸리지 않는다.
 * 찍은 화면은 그림을 볼 수 있는 AI(Claude·GPT·Gemini API)가 읽는다.
 */
object ScreenCapture {
    val results = MutableSharedFlow<File?>(extraBufferCapacity = 4)
    fun deliver(f: File?) { results.tryEmit(f) }

    fun intent(ctx: Context) = Intent(ctx, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /** 앱이 화면에 있을 때 바로 부른다(알림에서는 PendingIntent로 CaptureActivity를 연다) */
    suspend fun captureNow(ctx: Context, launcher: Launcher): File? {
        launcher.launch(intent(ctx), "화면 보내기", "허락을 누르면 지금 화면 한 장만 찍어요")
        return withTimeoutOrNull(120_000) { results.first() }
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "workspace/screens").apply { mkdirs() }
}

/** 투명한 액티비티: 시스템 허락 창만 띄우고 바로 사라진다 */
class CaptureActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else ScreenCapture.deliver(null)
        finish(); overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { ask.launch(mpm.createScreenCaptureIntent()) }.onFailure { ScreenCapture.deliver(null); finish() }
    }
}

/** 한 장 찍기 전용 서비스(안드로이드 14+는 mediaProjection 유형의 포그라운드 서비스 안에서만 찍을 수 있다) */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.session(this, "화면 한 장 찍는 중", "곧 끝나요", null)
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, Notifier.ID_SESSION + 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(Notifier.ID_SESSION + 7, n)
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { finish(null); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: run { finish(null); return START_NOT_STICKY }
        mp!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { cleanup() } }, main)
        // 허락 창이 사라질 시간을 준 뒤 찍는다
        main.postDelayed({ shoot() }, 650)
        main.postDelayed({ if (!done) finish(null) }, 6_000)
        return START_NOT_STICKY
    }

    private fun shoot() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = r
        vd = runCatching { mp?.createVirtualDisplay("hjtk-shot", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main) }.getOrNull()
        r.setOnImageAvailableListener({ rd ->
            if (done) return@setOnImageAvailableListener
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val p = img.planes[0]
                val pad = (p.rowStride - p.pixelStride * w) / p.pixelStride
                val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(p.buffer) }
                val crop = Bitmap.createBitmap(full, 0, 0, w, h)
                // AI에게 보낼 크기로(긴 변 1280) → 토큰·전송량 절약
                val s = 1280f / maxOf(w, h)
                val out = if (s < 1f) Bitmap.createScaledBitmap(crop, (w * s).toInt(), (h * s).toInt(), true) else crop
                val f = File(ScreenCapture.dir(this), "screen_${System.currentTimeMillis()}.jpg")
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                finish(f)
            } catch (e: Exception) { finish(null) } finally { img.close() }
        }, main)
    }

    private fun finish(f: File?) {
        if (done) return
        done = true
        ScreenCapture.deliver(f)
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun cleanup() {
        runCatching { vd?.release() }; vd = null
        runCatching { reader?.close() }; reader = null
        runCatching { mp?.stop() }; mp = null
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
