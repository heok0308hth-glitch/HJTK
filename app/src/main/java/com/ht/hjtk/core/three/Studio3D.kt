package com.ht.hjtk.core.three

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ht.mesh.Camera
import com.ht.mesh.Glb
import com.ht.mesh.Obj
import com.ht.mesh.Raster
import com.ht.mesh.Scene
import java.io.File

/**
 * HJTK 3D — 장면 보관함과 내보내기.
 * 장면은 HT-Scene 글(JSON)로 저장한다: AI가 쓴 것과 사람이 만진 것이 같은 파일이고, 언제든 .glb·.obj로 굽는다.
 */
object Studio3D {
    data class Built(val glb: File, val obj: File, val mtl: File, val png: File, val stats: String)

    fun dir(workspace: File) = File(workspace, "3d").apply { mkdirs() }

    fun slug(name: String) = name.replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), " ").trim().replace(Regex("\\s+"), "_").take(40).ifBlank { "scene" }

    /** 보관함의 장면들(최근 순) */
    fun library(workspace: File): List<File> = dir(workspace).listFiles { f -> f.name.endsWith(".scene.json") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun save(workspace: File, scene: Scene, into: File? = null): File {
        val f = into ?: File(dir(workspace), slug(scene.name) + ".scene.json")
        f.parentFile?.mkdirs()
        f.writeText(scene.toJson().toString(2))
        return f
    }

    fun load(f: File): Scene? = runCatching { Scene.parse(f.readText()) }.getOrNull()

    /** .glb(블렌더·유니티·웹) + .obj/.mtl(어디서나) + 미리보기 .png */
    fun build(scene: Scene, out: File, name: String = slug(scene.name)): Built {
        out.mkdirs()
        val glb = File(out, "$name.glb").apply { writeBytes(Glb.write(scene)) }
        val (obj, mtl) = Obj.write(scene, "$name.mtl")
        val objF = File(out, "$name.obj").apply { writeText(obj) }
        val mtlF = File(out, "$name.mtl").apply { writeText(mtl) }
        val png = File(out, "$name.png").apply { writeBytes(Raster(960, 720).render(scene, Camera.frame(scene), Raster.DARK, grid = true).png()) }
        return Built(glb, objF, mtlF, png, scene.stats())
    }

    /** 다운로드/HJTK/3D/이름/ 에 저장. 저장한 파일 수 */
    fun toDownloads(ctx: Context, files: List<File>, folder: String): Int = files.count { f ->
        runCatching {
            if (Build.VERSION.SDK_INT < 29) return@runCatching false
            val mime = when (f.extension) { "glb" -> "model/gltf-binary"; "obj" -> "model/obj"; "png" -> "image/png"; "json" -> "application/json"; else -> "text/plain" }
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HJTK/3D/" + folder)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        }.getOrDefault(false)
    }

    /** 목표 문장이 3D 결과물을 원하는지 */
    fun wants3d(goal: String): Boolean = Regex("(3d|3D|3차원|모델링|블렌더|blender|\\.glb|\\.obj|gltf|소품 모델|캐릭터 모델|3D 에셋|에셋 만들|입체|조형물|가구 모델|피규어)", RegexOption.IGNORE_CASE).containsMatchIn(goal)

    /** 3D 목표일 때 팀에게 주는 지침 — 기획·작성·통합 모두에 들어간다 */
    val GUIDE = """3D 결과물이 필요하면 model/scene.json 파일에 HT-Scene 형식으로 실제 모델을 만든다(HJTK가 .glb·.obj·미리보기 그림으로 바꿔 블렌더·유니티에서 바로 연다).
회사 모드면 파일 계획에 model/scene.json을 반드시 넣고, 기획 문서(.md)는 그 모델의 비율·재질·구성 이유를 설명한다.
한 번에 끝내는 작업이면 최종 결과 안에 ```json 코드 블록 하나로 장면을 넣고, 그 바로 윗줄에 **model/scene.json** 이라고 적는다.
${Scene.SPEC}"""

    /** model/scene.json 같은 장면 파일에만 붙는 작성 지침 */
    fun fileGuide(path: String): String? = if (isScene(path)) "이 파일은 3D 장면이다. JSON 하나만 출력한다(설명·코드 블록 표시 없이).\n${Scene.SPEC}" else null

    fun isScene(path: String) = path.endsWith("scene.json") || path.endsWith(".htscene")

    /** 장면 파일 검사: 열리지 않거나 도형이 틀리면 이유 */
    fun validate(path: String, text: String): String? = if (isScene(path)) Scene.problem(text) else null
}
