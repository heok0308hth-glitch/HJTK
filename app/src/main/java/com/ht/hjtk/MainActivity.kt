package com.ht.hjtk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ht.hjtk.data.Role
import com.ht.hjtk.ui.HjtkApp
import com.ht.hjtk.ui.MainVm
import com.ht.hjtk.ui.Route
import com.ht.hjtk.ui.Mode

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainVm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = MainVm(Hjtk.get(this))
        handle(intent)
        setContent { HjtkApp(vm) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onStart() {
        super.onStart()
        // DeepSeek 화면을 앱 화면 '뒤'에 붙인다: 실제 크기로 그려져야 입력창·버튼이 동작한다(첫 프레임 뒤 유휴 시간에 생성)
        Hjtk.get(this).deepseek.dock(findViewById(android.R.id.content))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Gemini 답을 복사하고 돌아왔다면 자동으로 받는다
        if (hasFocus) com.ht.hjtk.engine.RelayInbox.captureClipboard(this)
    }

    override fun onStop() {
        // 액티비티 창에서 떼어 낸다(누수 방지). 진행 중인 답은 투명 창 또는 창 없는 상태에서 계속 받는다.
        Hjtk.get(this).deepseek.undock()
        super.onStop()
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("shared")?.let { t ->
            vm.newChat(); vm.store.msg(vm.convId.value, Role.SYSTEM, "공유받은 내용", "note"); vm.draft.value = t
        }
        i.getStringExtra("sharedImage")?.let { path -> vm.newChat(); vm.attachImage(android.net.Uri.fromFile(java.io.File(path))) }
        i.getStringExtra("number")?.let { vm.phonePrefill.value = it }
        when (val r = i.getStringExtra("route")) {
            null -> {}
            "tasks" -> vm.tab(Route.Tasks)
            // 앱 아이콘 길게 누르기 바로가기
            "new" -> vm.newChat()
            "task" -> { vm.newChat(); vm.mode.value = Mode.MISSION }
            "live" -> vm.go(Route.Live)
            "pc" -> vm.go(Route.Pc)
            "phone" -> vm.go(Route.Phone)
            "web" -> i.getStringExtra("url")?.let { vm.openWeb(it) }
            else -> when {
                r.startsWith("job:") -> vm.go(Route.Job(r.removePrefix("job:")))
                r.startsWith("need:") -> {
                    val p = vm.runner.pending.value[r.removePrefix("need:")]
                    if (p != null) vm.openConversation(p.convId) else vm.tab(Route.Tasks)
                }
            }
        }
    }
}
