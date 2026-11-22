package com.hjtk.app.android.ports

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.hjtk.app.core.AppPort
import com.hjtk.app.core.ClipboardPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppPortImpl(private val context: Context) : AppPort {

    private fun launchable(): List<Triple<String, String, Intent>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
            val label = info.loadLabel(context.packageManager).toString()
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val component = info.activityInfo?.name ?: return@mapNotNull null
            Triple(
                label,
                pkg,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, component)
            )
        }
    }

    override suspend fun launch(query: String): Boolean = withContext(Dispatchers.Main) {
        val q = query.trim().lowercase()
        if (q.isBlank()) return@withContext false
        val apps = launchable()
        val hit = apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }
            ?: apps.firstOrNull { it.second.equals(query.trim(), ignoreCase = true) }
        val target = hit?.third ?: return@withContext false
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(target) }.isSuccess
    }

    override suspend fun installed(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        launchable().map { it.first to it.second }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    override suspend fun shareText(text: String, chooserTitle: String) = withContext(Dispatchers.Main) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun openCustomTab(url: String): Unit = withContext(Dispatchers.Main) {
        val parsed = Uri.parse(url)
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        val ok = runCatching { intent.launchUrl(context, parsed) }.isSuccess
        if (!ok) {
            context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override suspend fun dial(number: String) = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun sms(number: String, body: String) = withContext(Dispatchers.Main) {
        val uri = Uri.parse("smsto:" + Uri.encode(number))
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (body.isNotBlank()) putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override suspend fun openMap(destination: String) = withContext(Dispatchers.Main) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun openStoreSearch(query: String): Unit = withContext(Dispatchers.Main) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(market) }.isSuccess
        if (!opened) context.startActivity(web)
    }
}

class ClipboardPortImpl(private val context: Context) : ClipboardPort {
    override suspend fun read(): String = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    override suspend fun copy(text: String) = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HJTK", text))
    }
}
