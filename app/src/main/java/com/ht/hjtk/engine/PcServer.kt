package com.ht.hjtk.engine

import com.ht.hjtk.Hjtk
import com.ht.hjtk.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * PC에서 쓰기 — 폰이 같은 와이파이 안에서만 작은 웹 앱을 연다.
 * 폰이 엔진(DeepSeek 창·계정·명령)으로 남고, PC 브라우저는 화면과 키보드가 된다. 설치할 것 없음.
 * 보안: 같은 네트워크(사설 주소)만 받음 · 6자리 연결 코드 → 무작위 토큰 쿠키 · 코드 5번 틀리면 1분 잠금 · 2시간 쓰지 않으면 자동 종료.
 */
class PcServer(private val h: Hjtk) {
    data class State(val running: Boolean = false, val url: String = "", val code: String = "", val clients: Int = 0)
    val state = MutableStateFlow(State())
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val tokens = HashSet<String>()
    private var fails = 0; private var lockUntil = 0L; private var lastUse = 0L
    private val rnd = SecureRandom()

    fun start(port: Int = 8765) {
        if (state.value.running) return
        val ip = lanIp() ?: run { state.value = State(false, "와이파이에 연결되어 있지 않아요"); return }
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrElse { state.value = State(false, "포트를 열 수 없어요: ${it.message}"); return }
        server = s
        val code = (100000 + rnd.nextInt(900000)).toString()
        tokens.clear(); fails = 0; lastUse = System.currentTimeMillis()
        state.value = State(true, "http://$ip:$port", code)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.handler("pc")); scope = sc
        sc.launch { while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; sc.launch { runCatching { handle(c) }; runCatching { c.close() } } } }
        sc.launch { while (!s.isClosed) { kotlinx.coroutines.delay(60_000); if (System.currentTimeMillis() - lastUse > 2 * 3600_000L) stop() } }
    }

    fun stop() { runCatching { server?.close() }; server = null; scope?.cancel(); scope = null; tokens.clear(); state.value = State(false) }

    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun handle(sock: Socket) {
        sock.soTimeout = 15_000
        val remote = sock.inetAddress
        if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) return   // 같은 네트워크만
        val inp = BufferedInputStream(sock.getInputStream())
        val head = StringBuilder()
        while (true) { val b = inp.read(); if (b < 0) return; head.append(b.toChar()); if (head.endsWith("\r\n\r\n")) break; if (head.length > 16_000) return }
        val lines = head.lines()
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = lines.drop(1).filter { it.contains(":") }.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
        val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(200_000) ?: 0
        val body = if (len > 0) String(ByteArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } }, Charsets.UTF_8) else ""
        val out = sock.getOutputStream()
        val q = path.substringAfter("?", "").split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val route = path.substringBefore("?")
        val token = headers["cookie"]?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("hjtk=") }?.removePrefix("hjtk=")
        val authed = token != null && token in tokens
        lastUse = System.currentTimeMillis()
        when {
            route == "/" && method == "GET" -> send(out, 200, "text/html; charset=utf-8", PcPage.HTML)
            route == "/api/pair" && method == "POST" -> {
                if (System.currentTimeMillis() < lockUntil) return send(out, 429, "application/json", """{"error":"잠시 후 다시"}""")
                val code = runCatching { JSONObject(body).optString("code") }.getOrDefault("")
                if (code == state.value.code) {
                    val t = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    tokens += t; fails = 0; state.value = state.value.copy(clients = tokens.size)
                    send(out, 200, "application/json", """{"ok":true}""", "hjtk=$t; HttpOnly; SameSite=Strict; Path=/")
                } else { fails++; if (fails >= 5) { lockUntil = System.currentTimeMillis() + 60_000; fails = 0 }; send(out, 401, "application/json", """{"error":"코드가 달라요"}""") }
            }
            !route.startsWith("/api/") -> send(out, 404, "text/plain", "not found")
            !authed -> send(out, 401, "application/json", """{"error":"pair"}""")
            route == "/api/state" -> send(out, 200, "application/json", stateJson(q["conv"]).toString())
            route == "/api/send" && method == "POST" -> {
                val o = JSONObject(body)
                val conv = o.optString("conv").ifBlank { h.store.newConversation().id }
                val text = o.optString("text").trim()
                if (text.isNotEmpty()) kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    if (o.optString("mode") == "task") {
                        h.store.msg(conv, Role.USER, text, meta = mapOf("mission" to "true"))
                        h.runner.startMission(conv, text, difficulty = com.ht.hjtk.core.ai.Difficulty.of(o.optString("diff")))
                    } else { h.store.msg(conv, Role.USER, text); h.runner.quickAsk(conv, text) }
                }
                send(out, 200, "application/json", JSONObject().put("conv", conv).toString())
            }
            route == "/api/new" && method == "POST" -> send(out, 200, "application/json", JSONObject().put("conv", h.store.newConversation().id).toString())
            route == "/api/control" && method == "POST" -> {
                val o = JSONObject(body); val j = o.optString("job")
                when (o.optString("do")) { "pause" -> h.runner.pause(j); "resume" -> h.runner.unpause(j); "stop" -> h.runner.abort(j); "approve" -> h.runner.resolve(o.optString("pid"), "approve"); "reject" -> h.runner.resolve(o.optString("pid"), "reject"); "answer" -> h.runner.resolve(o.optString("pid"), o.optString("text")) }
                send(out, 200, "application/json", """{"ok":true}""")
            }
            else -> send(out, 404, "application/json", """{"error":"없음"}""")
        }
    }

    private fun stateJson(convId: String?): JSONObject {
        val convs = h.store.conversations.value.sortedByDescending { it.updatedAt }.take(60)
        val cid = convId?.takeIf { id -> convs.any { it.id == id } } ?: convs.firstOrNull()?.id
        cid?.let { h.store.ensureLoaded(it) }
        val msgs = cid?.let { h.store.messages.value[it] }.orEmpty().takeLast(120)
        val live = cid?.let { LiveBus.lines.value[it] }
        val jobs = h.store.jobs.value.filter { it.running }.take(5)
        return JSONObject()
            .put("convs", JSONArray(convs.map { JSONObject().put("id", it.id).put("title", it.title).put("at", it.updatedAt) }))
            .put("conv", cid ?: "")
            .put("busy", cid != null && cid in h.runner.busyQuick.value)
            .put("live", live?.let { JSONObject().put("who", it.who).put("text", com.ht.hjtk.core.stream.AepView.clean(it.text).takeLast(6000)) } ?: JSONObject.NULL)
            .put("msgs", JSONArray(msgs.map { m -> JSONObject().put("id", m.id).put("role", m.role.name).put("kind", m.kind).put("text", if (m.role == Role.AI) com.ht.hjtk.core.stream.AepView.clean(m.text) else m.text)
                .put("who", m.meta["who"] ?: "").put("at", m.at).put("pid", m.meta["pid"] ?: "").put("status", m.meta["status"] ?: "").put("cmd", m.meta["cmd"] ?: "").put("ok", m.meta["ok"] ?: "") }))
            .put("jobs", JSONArray(jobs.map { j -> JSONObject().put("id", j.id).put("goal", j.goal).put("state", j.state).put("p", j.progress).put("now", j.now).put("eta", j.eta).put("team", j.team).put("tok", j.tokens)
                .put("live", LiveBus.lines.value.values.filter { it.owner.startsWith(j.id) }.maxByOrNull { it.at }?.let { JSONObject().put("who", it.who).put("text", it.text.takeLast(1500)) } ?: JSONObject.NULL) }))
            .put("ai", h.ai.chatAssignment()?.name ?: "")
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String, cookie: String? = null) {
        val b = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'\r\n" + (cookie?.let { "Set-Cookie: $it\r\n" } ?: "") + "Connection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(b); out.flush()
    }
}

/** PC용 한 장짜리 웹 앱(HTML·CSS·JS 한 파일). 먹·종이·주홍 한 점 — 앱과 같은 디자인 언어. */
object PcPage {
    val HTML = """<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HJTK</title>
<style>
:root{--ink:#16150F;--ink2:#2B2A24;--paper:#F3F0E8;--paper2:#E9E5DA;--hair:#D6D1C4;--mute:#8B867A;--red:#E2442F}
*{box-sizing:border-box}body{margin:0;font:15px/1.6 "Pretendard","Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif;background:var(--paper);color:var(--ink);height:100vh;display:flex}
aside{width:280px;border-right:1px solid var(--hair);display:flex;flex-direction:column}aside h1{font-size:15px;letter-spacing:.2em;margin:18px 20px 10px}
.new{margin:0 16px 10px;padding:9px;border:1px solid var(--ink);border-radius:6px;background:none;cursor:pointer;font:inherit}
.list{overflow:auto;flex:1}.c{padding:10px 20px;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:14px}.c.on{background:var(--paper2);font-weight:700}
main{flex:1;display:flex;flex-direction:column;min-width:0}header{padding:14px 28px;border-bottom:1px solid var(--hair);display:flex;gap:14px;align-items:center}
header b{font-size:16px}header span{color:var(--mute);font-size:12px;letter-spacing:.08em}
#jobs{padding:0 28px}.job{background:var(--ink);color:#F3F0E8;border-radius:8px;padding:14px 16px;margin-top:14px}.job .bar{height:2px;background:#3A3830;margin:10px 0}.job .bar i{display:block;height:2px;background:var(--red)}
.job small{color:#A8A396}.job pre{white-space:pre-wrap;margin:8px 0 0;font:13px/1.5 inherit;color:#DDD8CB;max-height:7.5em;overflow:hidden}.job button{background:none;border:1px solid #4A473E;color:#F3F0E8;border-radius:5px;padding:4px 10px;margin:8px 6px 0 0;cursor:pointer}
#msgs{flex:1;overflow:auto;padding:20px 28px}.m{max-width:760px;margin:0 0 14px}.u{margin-left:auto;background:var(--ink);color:#F3F0E8;border-radius:14px 14px 4px 14px;padding:10px 14px;width:fit-content;max-width:70%}
.a .who{font-size:11px;letter-spacing:.12em;color:var(--mute);text-transform:uppercase}.a .t{white-space:pre-wrap}.s{font-size:13px;color:var(--mute);border-left:3px solid var(--hair);padding-left:10px}.s.collab{border-color:var(--ink)}.err{border-color:var(--red);color:var(--ink)}
.appr{border:1px solid var(--red);border-radius:8px;padding:12px}.appr button{margin:8px 8px 0 0;padding:6px 14px;border-radius:5px;border:1px solid var(--ink);background:none;cursor:pointer}.appr button.go{background:var(--ink);color:#F3F0E8}
pre code{display:block;background:var(--paper2);padding:10px;border-radius:6px;overflow:auto;font:13px ui-monospace,monospace}
footer{border-top:1px solid var(--hair);padding:12px 28px}.modes{display:flex;gap:6px;margin-bottom:8px;font-size:13px}.modes b{padding:4px 10px;border-radius:5px;background:var(--paper2);cursor:pointer;font-weight:400}.modes b.on{background:var(--ink);color:#F3F0E8;font-weight:700}
.row{display:flex;gap:8px}textarea{flex:1;resize:none;border:0;border-radius:8px;background:var(--paper2);padding:12px;font:inherit;min-height:48px;max-height:200px;outline:none}
.send{width:48px;border:0;border-radius:8px;background:var(--ink);color:#F3F0E8;font-size:18px;cursor:pointer}
#pair{position:fixed;inset:0;background:var(--paper);display:flex;align-items:center;justify-content:center;flex-direction:column;gap:14px}#pair input{font-size:28px;letter-spacing:.4em;text-align:center;width:240px;padding:10px;border:1px solid var(--ink);border-radius:8px;background:none}
.hint{color:var(--mute);font-size:12px}.live{opacity:.85}
@media(max-width:760px){aside{display:none}}
</style></head><body>
<div id="pair"><b style="letter-spacing:.3em">HJTK</b><div class="hint">폰의 HJTK → 허브 → PC에서 쓰기에 보이는 6자리 코드</div><input id="code" maxlength="6" inputmode="numeric" autofocus><div id="perr" class="hint"></div></div>
<aside><h1>HJTK</h1><button class="new" onclick="newChat()">＋ 새 대화 <span class="hint">Ctrl+K</span></button><div class="list" id="list"></div></aside>
<main><header><b id="title">대화</b><span id="ai"></span></header><div id="jobs"></div><div id="msgs"></div>
<footer><div class="modes"><b id="mq" class="on" onclick="setMode('chat')">대화</b><b id="mt" onclick="setMode('task')">작업 맡기기</b><span id="diffs"></span></div>
<div class="row"><textarea id="box" placeholder="메시지 · Enter 보내기 · Shift+Enter 줄바꿈"></textarea><button class="send" onclick="send()">↑</button></div></footer></main>
<script>
let conv='',mode='chat',diff='STANDARD',last='';const ${'$'}=id=>document.getElementById(id);
const esc=s=>(s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const md=s=>esc(s).replace(/```([\s\S]*?)```/g,(m,c)=>'<pre><code>'+c+'</code></pre>').replace(/\*\*(.+?)\*\*/g,'<b>${'$'}1</b>');
function setMode(m){mode=m;${'$'}('mq').className=m=='chat'?'on':'';${'$'}('mt').className=m=='task'?'on':'';${'$'}('diffs').innerHTML=m=='task'?['QUICK:빠르게','STANDARD:보통','DEEP:깊게','PROJECT:프로젝트'].map(x=>{const[k,l]=x.split(':');return '<b class="'+(k==diff?'on':'')+'" onclick="diff=\''+k+'\';setMode(\'task\')">'+l+'</b>'}).join(''):''}
async function api(p,b){const r=await fetch(p,b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{});if(r.status==401){${'$'}('pair').style.display='flex';throw 0}return r.json()}
${'$'}('code').oninput=async e=>{if(e.target.value.length==6){const r=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:e.target.value})});if(r.ok){${'$'}('pair').style.display='none';tick()}else{${'$'}('perr').textContent='코드가 달라요';e.target.value=''}}};
async function tick(){try{const s=await api('/api/state?conv='+encodeURIComponent(conv));conv=s.conv;${'$'}('ai').textContent=s.ai;
${'$'}('list').innerHTML=s.convs.map(c=>'<div class="c'+(c.id==conv?' on':'')+'" onclick="conv=\''+c.id+'\';last=\'\';tick()">'+esc(c.title)+'</div>').join('');
const cur=s.convs.find(c=>c.id==conv);${'$'}('title').textContent=cur?cur.title:'새 대화';
${'$'}('jobs').innerHTML=s.jobs.map(j=>'<div class="job"><small>'+(j.state=='RUNNING'?'작업 중':j.state)+' · '+(j.eta||'')+(j.tok?' · 토큰 약 '+j.tok:'')+'</small><div><b>'+esc(j.goal)+'</b></div><div class="bar"><i style="width:'+Math.round(j.p*100)+'%"></i></div><small>지금 '+esc(j.now)+'</small>'+(j.team?'<pre>'+esc(j.team.split('\n').map(l=>l.split(' · ')[0].replace('=',' — ')).join('\n'))+'</pre>':'')+(j.live?'<pre class="live">✎ '+esc(j.live.who)+'\n'+esc(j.live.text.slice(-400))+'</pre>':'')+'<button onclick="ctl(\''+j.id+'\',\'pause\')">일시정지</button><button onclick="ctl(\''+j.id+'\',\'resume\')">재개</button><button onclick="ctl(\''+j.id+'\',\'stop\')">중지</button></div>').join('');
const html=s.msgs.map(m=>{if(m.role=='USER')return '<div class="m u">'+esc(m.text)+'</div>';
if(m.kind=='approval')return '<div class="m appr"><b>허락이 필요해요</b><div class="t">'+esc(m.text)+'</div>'+(m.status=='pending'?'<button class="go" onclick="ans(\''+m.pid+'\',\'approve\')">실행</button><button onclick="ans(\''+m.pid+'\',\'reject\')">거절</button>':'<span class="hint">'+m.status+'</span>')+'</div>';
if(m.role=='AI'&&(m.kind=='text'||m.kind=='final'))return '<div class="m a"><div class="who">'+esc(m.who||'AI')+'</div><div class="t">'+md(m.text)+'</div></div>';
if(m.kind=='ask')return '<div class="m appr"><b>질문</b><div class="t">'+esc(m.text)+'</div></div>';
return '<div class="m s '+m.kind+(m.kind=='error'?' err':'')+'">'+(m.cmd?'<b>'+esc(m.cmd)+'</b> ':'')+esc(m.text.slice(0,600))+'</div>'}).join('')+(s.live?'<div class="m a live"><div class="who">'+esc(s.live.who)+' · 받는 중</div><div class="t">'+md(s.live.text)+'</div></div>':(s.busy?'<div class="m s">생각하는 중…</div>':''));
if(html!=last){const box=${'$'}('msgs');const atEnd=box.scrollHeight-box.scrollTop-box.clientHeight<80;box.innerHTML=html;last=html;if(atEnd)box.scrollTop=box.scrollHeight}}catch(e){}}
async function send(){const t=${'$'}('box').value.trim();if(!t)return;${'$'}('box').value='';const r=await api('/api/send',{conv,text:t,mode,diff});conv=r.conv;tick()}
async function newChat(){const r=await api('/api/new',{});conv=r.conv;last='';tick();${'$'}('box').focus()}
async function ctl(job,d){await api('/api/control',{job,do:d});tick()}async function ans(pid,d){await api('/api/control',{pid,do:d});tick()}
${'$'}('box').onkeydown=e=>{if(e.key=='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send()}};
document.onkeydown=e=>{if((e.ctrlKey||e.metaKey)&&e.key=='k'){e.preventDefault();newChat()}};
setMode('chat');fetch('/api/state').then(r=>{if(r.ok){${'$'}('pair').style.display='none';tick()}});setInterval(tick,1200);
</script></body></html>"""
}
