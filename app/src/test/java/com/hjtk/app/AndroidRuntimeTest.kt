package com.hjtk.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hjtk.app.android.Engine
import com.hjtk.app.android.ports.FilePortImpl
import com.hjtk.app.android.ports.NotifyPortImpl
import com.hjtk.app.android.prefs.KeyProvider
import com.hjtk.app.android.prefs.SecurePrefs
import com.hjtk.app.android.prefs.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime checks on a real (shadowed) Android runtime.
 *
 * The unit tests above prove the pure engine; these prove that the Android
 * wiring actually constructs, that secrets round-trip through the Keystore
 * wrapper, and that files/notifications behave. Without this layer a
 * composition-root mistake would only show up on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun engineBuildsEverySubsystem() {
        val engine = Engine.of(context)
        assertTrue(engine.registry.size() >= 40, "명령 등록 실패")
        assertNotNull(engine.ports)
        assertNotNull(engine.orchestrator)
        assertTrue(engine.skills.all().isNotEmpty())
        assertTrue(engine.pipelines.all().isNotEmpty())
        assertTrue(engine.journal.verify().ok)
        assertNotNull(engine.carriers.statusLine())
        assertTrue(engine.diagnosticsForUi().contains("명령"))
    }

    @Test
    fun securePrefsNeedsAKeyAndNeverCrashesWithoutOne() {
        // Robolectric has no AndroidKeyStore, which is also true of a wiped
        // device: writing must be a no-op and reading must return null.
        val withoutKey = SecurePrefs(context, object : KeyProvider { override fun key(): SecretKey? = null })
        withoutKey.put("test_key", "sk-very-secret-value")
        assertNull(withoutKey.get("test_key"))
        assertFalse(withoutKey.has("test_key"))

        // With a key the full round trip works, including masking.
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val prefs = SecurePrefs(context, object : KeyProvider { override fun key(): SecretKey? = key })
        prefs.put("test_key", "sk-very-secret-value")
        assertEquals("sk-very-secret-value", prefs.get("test_key"))
        assertTrue(prefs.has("test_key"))
        assertTrue(prefs.masked("test_key").contains("••"))
        assertFalse(prefs.masked("test_key").contains("very-secret"))
        prefs.remove("test_key")
        assertFalse(prefs.has("test_key"))
    }

    @Test
    fun settingsPersistAndPublish() {
        val settings = Settings(context)
        settings.themeMode = "dark"
        assertEquals("dark", Settings(context).themeMode)
        settings.reportMinutes = 10
        assertEquals(10, settings.state.value.reportMinutes)
        settings.mailAddress = "user@example.com"
        assertEquals("user@example.com", Settings(context).mailAddress)
        settings.themeMode = "system"
    }

    @Test
    fun filePortWritesAndListsExports() = runBlocking {
        val port = FilePortImpl(context)
        val name = port.saveExport("runtime-test.txt", "내용 123")
        assertEquals("runtime-test.txt", name)
        val files = port.listExports()
        assertTrue(files.any { it.name == "runtime-test.txt" })
        assertTrue(files.first { it.name == "runtime-test.txt" }.sizeBytes > 0)
    }

    @Test
    fun unsafeFileNamesAreSanitised() = runBlocking {
        val port = FilePortImpl(context)
        val saved = port.saveExport("../../etc/passwd", "x")
        assertFalse(saved.contains("/"))
    }

    @Test
    fun notificationChannelIsCreated() {
        val port = NotifyPortImpl(context)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(NotifyPortImpl.CHANNEL_TASK))
        assertTrue(port.canPost())
    }

    @Test
    fun orchestratorRunsARealCommandEndToEnd() {
        val engine = Engine.of(context)
        engine.orchestrator.runExplicit("device.battery")
        awaitUntil(5_000, "배터리 명령이 끝나지 않았습니다") { engine.orchestrator.state.value.jobs.isEmpty() }
        val said = engine.orchestrator.state.value.items.any {
            it is com.hjtk.app.core.model.ChatItem.AssistantText && it.text.contains("배터리")
        }
        assertTrue(said, "결과 카드가 대화에 남지 않았습니다")
    }

    @Test
    fun unknownTextProducesAnHonestAnswer() {
        val engine = Engine.of(context)
        engine.orchestrator.submit("우주선 조립 매뉴얼")
        val last = engine.orchestrator.state.value.items.lastOrNull()
        assertTrue(last is com.hjtk.app.core.model.ChatItem.AssistantText)
    }
}
