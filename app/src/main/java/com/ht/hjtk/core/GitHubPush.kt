package com.ht.hjtk.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * GitHub에 올리기 — 파일 여러 개를 '커밋 한 번'으로(Git Data API: blob → tree → commit → ref).
 * 저장소가 없으면 만든다(기본 비공개). 비밀 파일(키스토어·.env·키)은 절대 올리지 않는다.
 * 통신은 밖에서 넣어 준다(call) → 기기 없이 JVM에서 시험할 수 있다.
 */
class GitHubPush(private val call: suspend (method: String, path: String, body: JSONObject?) -> Pair<Int, String>) {
    data class File(val path: String, val bytes: ByteArray)
    data class Result(val url: String, val commit: String, val files: Int, val created: Boolean, val skipped: List<String>)
    class Fail(msg: String) : Exception(msg)

    suspend fun push(repo: String, files: List<File>, message: String, branch: String = "", private: Boolean = true, description: String = ""): Result {
        val (ok, skipped) = files.partition { safe(it.path) && it.bytes.size <= MAX_BYTES }
        if (ok.isEmpty()) throw Fail("올릴 파일이 없어요" + if (skipped.isNotEmpty()) " (제외: ${skipped.joinToString { it.path }})" else "")
        val owner = if (repo.contains('/')) repo.substringBefore('/') else login()
        val name = clean(repo.substringAfter('/'))
        // 1) 저장소(없으면 만든다)
        var created = false
        var info = get("/repos/$owner/$name")
        if (info.first == 404) {
            val r = call("POST", "/user/repos", JSONObject().put("name", name).put("private", private).put("auto_init", true).put("description", description.take(200)))
            if (r.first !in 200..299) throw Fail("저장소를 만들지 못했어요(${r.first}): ${msg(r.second)}")
            created = true; info = r
        } else if (info.first !in 200..299) throw Fail("저장소를 열지 못했어요(${info.first}): ${msg(info.second)}")
        val defaultBranch = JSONObject(info.second).optString("default_branch", "main").ifBlank { "main" }
        val br = branch.ifBlank { defaultBranch }
        // 2) 현재 커밋(빈 저장소면 README로 시작)
        var ref = get("/repos/$owner/$name/git/ref/heads/$br")
        if (ref.first == 409 || (ref.first == 404 && created)) {
            call("PUT", "/repos/$owner/$name/contents/README.md", JSONObject().put("message", "init").put("content", b64("# $name\n".toByteArray())).put("branch", br))
            ref = get("/repos/$owner/$name/git/ref/heads/$br")
        }
        var newBranch = false
        val parent = if (ref.first in 200..299) JSONObject(ref.second).getJSONObject("object").getString("sha") else {
            newBranch = true
            val base = get("/repos/$owner/$name/git/ref/heads/$defaultBranch")
            if (base.first !in 200..299) throw Fail("브랜치를 찾지 못했어요(${base.first})")
            JSONObject(base.second).getJSONObject("object").getString("sha")
        }
        val baseTree = JSONObject(get("/repos/$owner/$name/git/commits/$parent").also { if (it.first !in 200..299) throw Fail("커밋을 읽지 못했어요(${it.first})") }.second).getJSONObject("tree").getString("sha")
        // 3) 파일 → blob → tree → commit
        val entries = JSONArray()
        for (f in ok) {
            val b = call("POST", "/repos/$owner/$name/git/blobs", JSONObject().put("content", b64(f.bytes)).put("encoding", "base64"))
            if (b.first !in 200..299) throw Fail("'${f.path}' 올리기 실패(${b.first}): ${msg(b.second)}")
            entries.put(JSONObject().put("path", f.path.trimStart('/')).put("mode", "100644").put("type", "blob").put("sha", JSONObject(b.second).getString("sha")))
        }
        val tree = call("POST", "/repos/$owner/$name/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
        if (tree.first !in 200..299) throw Fail("파일 목록을 만들지 못했어요(${tree.first})")
        val commit = call("POST", "/repos/$owner/$name/git/commits", JSONObject().put("message", message.ifBlank { "HJTK 업로드" }).put("tree", JSONObject(tree.second).getString("sha")).put("parents", JSONArray().put(parent)))
        if (commit.first !in 200..299) throw Fail("커밋을 만들지 못했어요(${commit.first})")
        val sha = JSONObject(commit.second).getString("sha")
        val upd = if (newBranch) call("POST", "/repos/$owner/$name/git/refs", JSONObject().put("ref", "refs/heads/$br").put("sha", sha))
            else call("PATCH", "/repos/$owner/$name/git/refs/heads/$br", JSONObject().put("sha", sha).put("force", false))
        if (upd.first !in 200..299) throw Fail("브랜치를 옮기지 못했어요(${upd.first}): ${msg(upd.second)}")
        return Result("https://github.com/$owner/$name/tree/$br", sha.take(7), ok.size, created, skipped.map { it.path })
    }

    suspend fun login(): String {
        val r = get("/user")
        if (r.first == 401) throw Fail("GitHub 토큰이 맞지 않아요(허브 → GitHub에서 다시 연결)")
        if (r.first !in 200..299) throw Fail("GitHub에 연결하지 못했어요(${r.first})")
        return JSONObject(r.second).getString("login")
    }

    private suspend fun get(path: String) = call("GET", path, null)
    private fun msg(body: String) = runCatching { JSONObject(body).optString("message") }.getOrDefault(body.take(120))

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private val SECRET = Regex("""(?i)(^|/)(\.env(\..*)?|.*\.(jks|keystore|p12|pfx|pem|key)|id_rsa.*|.*secret.*|.*credential.*|local\.properties|google-services\.json)$""")
        /** 올려도 되는 파일인가(비밀·빌드 찌꺼기 제외) */
        fun safe(path: String): Boolean = !SECRET.containsMatchIn(path) && !Regex("""(^|/)(\.git|build|\.gradle|node_modules)/""").containsMatchIn(path)
        /** 저장소 이름 규칙: 영문·숫자·.-_ 만 */
        fun clean(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(80).ifBlank { "hjtk-upload" }
    }
}
