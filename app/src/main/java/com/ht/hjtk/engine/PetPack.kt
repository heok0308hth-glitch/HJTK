package com.ht.hjtk.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.ht.hjtk.R
import com.ht.hjtk.core.pet.SpriteSlicer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 사용자 펫. 사용자가 고른 스프라이트 시트를 기기 안에서 잘라 아틀라스로 저장한다(앱 밖으로 나가지 않는다).
 * 동작(역할)마다 어느 행을 쓸지 정할 수 있다. 저장된 것이 없으면 기본 마스코트를 쓴다.
 */
object PetPack {
    /** 마스코트가 쓰는 동작 역할. 기본 아틀라스의 행 순서와 같다. */
    enum class Role(val label: String, val fps: Int, val loop: Boolean) {
        IDLE("대기", 5, true), RUN_R("오른쪽 달리기", 12, true), RUN_L("왼쪽 달리기", 12, true), WAVE("손 흔들기", 6, true),
        JUMP("점프", 10, false), SLEEP("잠", 3, true), SIT("앉기", 4, true), LOAF("엎드림", 4, true), POUT("삐짐", 4, true),
        LOOK("두리번", 6, true), SNEAK("살금살금", 8, true)
    }

    class Pack(val atlas: Bitmap, val cell: Int, val frames: IntArray, val roles: Map<Role, Int>, val custom: Boolean) {
        fun row(r: Role): Int = roles[r]?.takeIf { it in frames.indices } ?: roles[Role.IDLE] ?: 0
        fun count(r: Role): Int = frames[row(r)].coerceAtLeast(1)
    }

    /** 바뀌면 오버레이·화면이 다시 읽는다 */
    val version = MutableStateFlow(0)

    private fun dir(ctx: Context) = File(ctx.filesDir, "pet").apply { mkdirs() }
    fun hasCustom(ctx: Context) = File(dir(ctx), "pack.json").exists()

    fun load(ctx: Context): Pack {
        if (hasCustom(ctx)) runCatching {
            val j = JSONObject(File(dir(ctx), "pack.json").readText())
            val bmp = BitmapFactory.decodeFile(File(dir(ctx), "atlas.png").path) ?: error("atlas")
            val fr = j.getJSONArray("frames").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val roles = j.getJSONObject("roles").let { o -> Role.values().mapNotNull { r -> if (o.has(r.name)) r to o.getInt(r.name) else null }.toMap() }
            return Pack(bmp, j.getInt("cell"), fr, roles, true)
        }
        val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.mascot_atlas, BitmapFactory.Options().apply { inScaled = false })
        return Pack(bmp, 160, intArrayOf(6, 8, 8, 4, 5, 8, 6, 6, 6, 8, 8), Role.values().associateWith { it.ordinal }, false)
    }

    /** 가져오기 결과(아직 저장 전): 미리보기와 역할 지정 화면에서 쓴다 */
    class Draft(val atlas: Bitmap, val cell: Int, val frames: IntArray, val roles: MutableMap<Role, Int>)

    suspend fun slice(ctx: Context, uri: Uri): Result<Draft> = withContext(Dispatchers.Default) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 4096 || opts.outHeight / sample > 4096) sample *= 2
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
                ?: error("이미지를 열 수 없어요")
            val w = src.width; val h = src.height
            val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
            val sheet = SpriteSlicer.slice(px, w, h)
            require(sheet.rows.isNotEmpty()) { "캐릭터를 찾지 못했어요. 배경이 한 가지 색이거나 투명한 시트를 골라 주세요." }
            require(sheet.rows.size <= 16 && sheet.rows.maxOf { it.size } <= 24) { "행·칸이 너무 많아요(시트가 아닌 사진일 수 있어요)." }
            val (a, aw, ah) = SpriteSlicer.atlas(px, w, h, sheet)
            // 오버레이에서 쓰기 좋은 크기로: 셀이 크면 줄인다(메모리·그리기 속도)
            val raw = Bitmap.createBitmap(a, aw, ah, Bitmap.Config.ARGB_8888)
            val scale = if (sheet.cell > 192) 192f / sheet.cell else 1f
            val atlas = if (scale < 1f) Bitmap.createScaledBitmap(raw, (aw * scale).toInt(), (ah * scale).toInt(), true) else raw
            val cell = (sheet.cell * scale).toInt()
            val n = sheet.rows.size
            // 기본 역할: 행 순서 = 기본 마스코트 순서. 행이 모자라면 대기 행으로 대신한다.
            val roles = Role.values().associateWith { r -> if (r.ordinal < n) r.ordinal else 0 }.toMutableMap()
            Draft(atlas, cell, IntArray(n) { sheet.rows[it].size }, roles)
        }
    }

    fun save(ctx: Context, d: Draft) {
        val dd = dir(ctx)
        File(dd, "atlas.png").outputStream().use { d.atlas.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val roles = JSONObject().apply { d.roles.forEach { (k, v) -> put(k.name, v) } }
        File(dd, "pack.json").writeText(JSONObject().put("cell", d.cell).put("frames", JSONArray(d.frames.toList())).put("roles", roles).toString())
        version.value++
    }

    fun reset(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() }; version.value++ }
}
