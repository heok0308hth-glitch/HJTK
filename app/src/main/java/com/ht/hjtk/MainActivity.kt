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

    override fun onResume() {
        super.onResume()
        // 포그라운드 복귀 = 재개 시점(부팅 리시버가 아니라). DeepSeek 화면 상태도 확인.
        Hjtk.get(this).deepseek.let { it.webView(); it.refreshState() }
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("shared")?.let { t ->
            vm.newChat(); vm.store.msg(vm.convId.value, Role.SYSTEM, "공유받은 내용", "note"); vm.draft.value = t
        }
        when (val r = i.getStringExtra("route")) {
            null -> {}
            "tasks" -> vm.tab(Route.Tasks)
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
