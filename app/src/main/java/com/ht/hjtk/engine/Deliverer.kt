package com.ht.hjtk.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ht.hjtk.core.forge.ApkForge
import com.ht.hjtk.core.forge.Bundle
import com.ht.hjtk.data.Role
import com.ht.hjtk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 결과물 전달 — 작업이 끝나면 '파일 목록'이 아니라 바로 쓸 수 있는 것을 건넨다.
 *  · 앱이면: 설치 가능한 APK(기기 안에서 포장·서명) + 바로 보는 미리보기
 *  · 문서면: 목차가 있는 HTML 완성본(인쇄하면 PDF) + 합본 마크다운
 *  · 언제나: 프로젝트 전체 ZIP
 * 모두 '다운로드/HJTK/프로젝트 이름/'에도 저장하고, 대화에 결과물 카드를 띄운다.
 */
class Deliverer(private val ctx: Context, private val store: Store) {
    data class Out(val kind: String, val file: File, val label: String)

    suspend fun deliver(jobId: String, convId: String, title: String, order: List<String>, subtitle: String = "", credit: String = ""): List<Out> = withContext(Dispatchers.IO) {
        val root = store.jobDir(jobId)
        val dist = File(root, "dist").apply { mkdirs() }
        val name = safe(title).ifBlank { "HJTK_$jobId" }
        val outs = ArrayList<Out>()
        // 1) 앱 → APK
        val app = File(root, "app")
        if (File(app, "index.html").isFile) runCatching {
            val www = app.walkTopDown().filter { it.isFile }.associate { it.relativeTo(app).path.replace('\\', '/') to it.readBytes() }
            val shell = ctx.assets.open("forge/shell.apk").use { it.readBytes() }
            val unsigned = File(ctx.cacheDir, "forge-$jobId.apk")
            unsigned.writeBytes(ApkForge.repack(shell, www, ApkForge.packageFor(jobId), title) { size -> icon(title, size) })
            val apk = File(dist, "$name.apk")
            val (key, cert) = signingKey()
            ApkForge.sign(unsigned, apk, key, listOf(cert))
            unsigned.delete()
            outs += Out("apk", apk, "설치 파일 · ${apk.length() / 1024}KB")
            outs += Out("preview", File(app, "index.html"), "바로 실행해 보기")
        }.onFailure { CrashLog.record(it, "forge") }
        // 2) 문서 → 완성본
        val parts = order.filter { !it.startsWith("app/") && !it.startsWith("dist/") && (it.endsWith(".md") || it.endsWith(".txt")) }
            .mapNotNull { p -> File(root, p).takeIf { it.isFile }?.let { Bundle.Part(p, it.readText()) } }
        if (parts.isNotEmpty()) {
            val html = File(dist, "$name.html").apply { writeText(Bundle.html(title, subtitle, parts, credit)) }
            File(dist, "$name.md").writeText(Bundle.markdown(title, parts))
            outs += Out("html", html, "완성본 · ${parts.size}장")
        }
        // 3) ZIP
        val zip = File(dist, "$name.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "foundry.json" && !it.relativeTo(root).path.startsWith("dist") }
                .forEach { f -> z.putNextEntry(ZipEntry(f.relativeTo(root).path.replace('\\', '/'))); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        outs += Out("zip", zip, "프로젝트 전체 · ${zip.length() / 1024}KB")
        // 4) 다운로드 폴더에도
        val saved = outs.filter { it.kind != "preview" }.count { saveToDownloads(it.file, name) }
        // 5) 대화에 결과물 카드
        val meta = HashMap<String, String>()
        meta["job"] = jobId; meta["title"] = title
        outs.forEach { o -> meta[o.kind] = o.file.relativeTo(root).path.replace('\\', '/'); meta[o.kind + "_label"] = o.label }
        if (saved > 0) meta["saved"] = "다운로드/HJTK/$name"
        store.msg(convId, Role.AI, title, "deliver", meta)
        outs
    }

    /** 실행 검사: 보이지 않는 창에서 app/index.html을 열고 3.5초 동안 오류를 모은다. 없으면 null */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun smoke(jobId: String, path: String = "app/index.html"): String? {
        val f = File(store.jobDir(jobId), path)
        if (!f.isFile) return "실행할 파일이 없어요: $path"
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        var web: WebView? = null
        withContext(Dispatchers.Main) {
            web = WebView(ctx).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = true
                measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(2200, android.view.View.MeasureSpec.EXACTLY)); layout(0, 0, 1080, 2200)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errs += "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { errs += "불러오기 실패: ${r.url.lastPathSegment} (${e.description})" }
                }
                loadUrl("file://" + f.absolutePath)
            }
        }
        delay(3_500)
        val empty = withContext(Dispatchers.Main) {
            var len = -1
            val d = kotlinx.coroutines.CompletableDeferred<Int>()
            web?.evaluateJavascript("(document.body&&document.body.innerText.trim().length)||0") { d.complete(it.toIntOrNull() ?: 0) }
            len = kotlinx.coroutines.withTimeoutOrNull(2_000) { d.await() } ?: 0
            web?.destroy(); len
        } == 0
        if (empty && errs.isEmpty()) errs += "화면에 아무것도 그려지지 않았어요(빈 body)"
        return errs.distinct().take(8).joinToString("\n").ifBlank { null }
    }

    /** 서명 키: 기기 Keystore에서 한 번 만들어 계속 쓴다(같은 키 → 같은 앱을 업데이트 설치 가능) */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setKeySize(2048)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=HJTK Made App, O=HT"))
                    .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 30L * 365 * 86_400_000)).build())
            }.generateKeyPair()
        }
        return (ks.getKey(ALIAS, null) as PrivateKey) to (ks.getCertificate(ALIAS) as X509Certificate)
    }

    /** 아이콘: 먹색 원에 앱 이름 첫 글자, 주홍 점 하나 */
    private fun icon(title: String, size: Int): ByteArray {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#141414"); c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = Color.parseColor("#F6F3EC"); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); p.textSize = size * 0.46f
        val ch = title.trim().firstOrNull { !it.isWhitespace() }?.toString() ?: "H"
        c.drawText(ch, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        p.color = Color.parseColor("#E44D2E"); c.drawCircle(size * 0.76f, size * 0.24f, size * 0.07f, p)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun saveToDownloads(f: File, folder: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return false
        val mime = when (f.extension) { "apk" -> "application/vnd.android.package-archive"; "html" -> "text/html"; "zip" -> "application/zip"; else -> "text/markdown" }
        val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HJTK/" + folder) }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        true
    }.getOrDefault(false)

    companion object {
        const val ALIAS = "hjtk_made_apps"
        fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), " ").trim().replace(Regex("\\s+"), "_").take(40)
    }
}
