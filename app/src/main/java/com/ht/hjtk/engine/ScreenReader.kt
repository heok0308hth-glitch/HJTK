package com.ht.hjtk.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.ht.hjtk.core.vision.ScreenMap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 화면·사진 속 글자를 기기에서 읽어 [ScreenMap]으로 만든다(인터넷·토큰 0).
 * 글자 인식은 기기 안 모델(ML Kit 한국어, 앱에 포함)이 하고, '이해'는 여전히 바깥 AI가 한다.
 */
object ScreenReader {
    data class Read(val map: String, val blocks: List<ScreenMap.Block>, val w: Int, val h: Int) {
        val enough get() = ScreenMap.enoughText(blocks)
        val saved get() = (ScreenMap.imageTokens(w, h) - ScreenMap.tokens(map)).coerceAtLeast(0)
    }

    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    suspend fun read(f: File, title: String = "화면"): Read? {
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        return try { read(bmp, title) } finally { bmp.recycle() }
    }

    suspend fun read(bmp: Bitmap, title: String): Read? = suspendCancellableCoroutine { k ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val blocks = r.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
                    val box = l.boundingBox ?: return@mapNotNull null
                    ScreenMap.Block(l.text, box.left, box.top, box.right, box.bottom, accent(bmp, box))
                }
                k.resume(Read(ScreenMap.build(bmp.width, bmp.height, blocks, title), blocks, bmp.width, bmp.height))
            }
            .addOnFailureListener { k.resume(null) }
    }

    /** 글자 색이 눈에 띄는 색이면 이름(빨강·파랑·초록·주황·보라), 무채색이면 null */
    private fun accent(bmp: Bitmap, box: android.graphics.Rect): String? {
        val hsv = FloatArray(3); var best = 0f; var hue = 0f
        val xs = 6; val ys = 3
        for (i in 1..xs) for (j in 1..ys) {
            val x = (box.left + box.width() * i / (xs + 1)).coerceIn(0, bmp.width - 1)
            val y = (box.top + box.height() * j / (ys + 1)).coerceIn(0, bmp.height - 1)
            Color.colorToHSV(bmp.getPixel(x, y), hsv)
            val score = hsv[1] * hsv[2]
            if (score > best) { best = score; hue = hsv[0] }
        }
        if (best < 0.35f) return null
        return when (hue) { in 0f..15f, in 345f..360f -> "빨강"; in 15f..45f -> "주황"; in 45f..70f -> "노랑"; in 70f..170f -> "초록"; in 170f..255f -> "파랑"; else -> "보라" }
    }

    /** 그림이 꼭 필요할 때만: 긴 변 768px로 줄여 보낸다(1280px 대비 토큰 약 1/3) */
    fun small(f: File, out: File, side: Int = 768): File {
        val b = BitmapFactory.decodeFile(f.path) ?: return f
        val s = side.toFloat() / maxOf(b.width, b.height)
        if (s >= 1f) { b.recycle(); return f }
        val r = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        out.parentFile?.mkdirs(); out.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        b.recycle(); r.recycle(); return out
    }
}
