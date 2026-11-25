package com.ht.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * HT-Scene v1 — AI가 글로 쓰고 사람이 화면에서 만지는 장면 형식.
 *
 * 블렌더의 '오브젝트 + 모디파이어 스택' 개념을 JSON 한 장으로 옮겼다. 도형 이름·숫자 몇 개만 알면
 * AI가 정확한 3D를 만들 수 있고, 같은 데이터를 앱이 그리고, glTF·OBJ로 블렌더에 넘긴다.
 *
 * ```json
 * {"scene":"Desk lamp","materials":{"brass":{"color":"#C9A45C","metal":1,"rough":0.35}},
 *  "objects":[
 *   {"name":"base","shape":"cylinder","r":0.6,"h":0.08,"at":[0,0.04,0],"mat":"brass"},
 *   {"name":"arm","shape":"box","size":[0.06,1.2,0.06],"at":[0,0.68,0],"rot":[0,0,-15],"mods":[{"subdiv":1}]},
 *   {"name":"shade","shape":"lathe","profile":[[0.05,0],[0.35,-0.3],[0.36,-0.32]],"at":[0.3,1.25,0],"parent":"arm"}]}
 * ```
 */
data class Material(val name: String, val color: Int = 0xC8C2B8.toInt() or (0xFF shl 24), val metal: Float = 0f, val rough: Float = 0.6f) {
    fun rgb(): FloatArray = floatArrayOf(((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f)
    fun hex(): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)
}

sealed interface Modifier {
    /** 축 기준 대칭 복사. 이음매는 붙인다 */
    data class Mirror(val axis: Char) : Modifier
    /**
     * 개수만큼 복사. i번째 복사본 = 회전(rotate×i) · 이동(offset×i + [radius],0,0).
     * 일렬(offset), 원형(radius + rotate: 꽃잎·바퀴살·의자 둘레), 둘 다(나선 계단)를 한 규칙으로.
     */
    data class Array(val count: Int, val offset: V3, val rotate: V3, val radius: Float = 0f) : Modifier
    /** 서브디비전(캣멀-클라크) 단계 */
    data class Subdiv(val levels: Int) : Modifier
}

class Node(
    var name: String,
    var shape: String,
    var params: MutableMap<String, Any> = LinkedHashMap(),
    var at: V3 = V3.ZERO,
    var rot: V3 = V3.ZERO,
    var scale: V3 = V3.ONE,
    var mat: String? = null,
    var mods: MutableList<Modifier> = ArrayList(),
    var parent: String? = null,
    var smooth: Boolean? = null,
) {
    fun num(k: String, d: Float): Float = (params[k] as? Number)?.toFloat() ?: d
    fun vec(k: String): V3? = (params[k] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 3 }?.let { V3(it[0], it[1], it[2]) }
    @Suppress("UNCHECKED_CAST")
    fun pairs(k: String): List<Pair<Float, Float>> = (params[k] as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.orEmpty()

    fun copy() = Node(name, shape, LinkedHashMap(params), at, rot, scale, mat, mods.toMutableList(), parent, smooth)

    @Transient private var memoKey: String? = null
    @Transient private var memo: Mesh? = null

    /** 도형 + 모디파이어(자기 좌표계). 같은 모양이면 다시 만들지 않는다(화면을 돌릴 때마다 서브디비전을 새로 하지 않게) */
    fun mesh(): Mesh {
        val key = shape + params.toString() + mods.toString() + if (mods.any { it is Modifier.Mirror }) "$at$rot$scale" else ""
        memo?.let { if (key == memoKey) return it }
        return build().also { memo = it; memoKey = key }
    }

    private fun build(): Mesh {
        val seg = num("seg", 32f).toInt().coerceIn(3, 128)
        var m = when (shape) {
            "box" -> (vec("size") ?: V3(num("x", 1f), num("y", 1f), num("z", 1f))).let { if (num("round", 0f) > 0f) Shapes.roundedBox(it.x, it.y, it.z, num("round", 0f)) else Shapes.box(it.x, it.y, it.z) }
            "plane" -> Shapes.plane(vec("size")?.x ?: num("w", 2f), vec("size")?.z ?: num("d", num("w", 2f)))
            "sphere" -> Shapes.sphere(num("r", 0.5f), seg)
            "cylinder" -> Shapes.cylinder(num("r", 0.5f), num("h", 1f), seg)
            "cone" -> Shapes.cone(num("r", 0.5f), num("h", 1f), seg)
            "capsule" -> Shapes.capsule(num("r", 0.3f), num("h", 1f), seg)
            "torus" -> Shapes.torus(num("R", num("major", 0.5f)), num("r", num("minor", 0.15f)), seg.coerceAtLeast(12), num("side", 16f).toInt().coerceIn(3, 64))
            "lathe" -> pairs("profile").takeIf { it.size >= 2 }?.let { Shapes.lathe(it, seg) } ?: Shapes.cylinder(0.5f, 1f, seg)
            "extrude" -> pairs("outline").takeIf { it.size >= 3 }?.let { Shapes.extrude(it, num("depth", 0.2f)) } ?: Shapes.box(1f, 0.2f, 1f)
            else -> Shapes.box(1f, 1f, 1f)
        }
        for (mod in mods) m = when (mod) {
            is Modifier.Mirror -> {
                // 부모(또는 월드)의 축 평면을 기준으로 반대편에 복사한다: 한쪽 다리를 "at"으로 놓으면 거울이 반대쪽을 만든다
                val s = when (mod.axis) { 'x' -> V3(-1f, 1f, 1f); 'y' -> V3(1f, -1f, 1f); else -> V3(1f, 1f, -1f) }
                val l = local()
                m.copy().also { it.append(m.transformed(l.inverse() * M4.scale(s) * l)) }.weld()
            }
            is Modifier.Array -> {
                val out = Mesh()
                for (i in 0 until mod.count.coerceIn(1, 64)) out.append(m.transformed(M4.euler(mod.rotate * i.toFloat()) * M4.translate(mod.offset * i.toFloat() + V3(mod.radius, 0f, 0f))))
                out.weld()
            }
            is Modifier.Subdiv -> { var s = m; repeat(mod.levels.coerceIn(0, 3)) { s = s.subdivide() }; s }
        }
        return m
    }

    fun local(): M4 = M4.trs(at, rot, scale)
    /** 각진 면(상자·평면·밀어 올리기)은 각지게, 둥근 도형과 서브디비전은 부드럽게 */
    fun smoothShading(): Boolean = smooth ?: (shape in setOf("sphere", "capsule", "torus") || mods.any { it is Modifier.Subdiv })
}

class Scene(var name: String = "Untitled", val materials: MutableMap<String, Material> = LinkedHashMap(), val nodes: MutableList<Node> = ArrayList()) {
    fun copy() = Scene(name, LinkedHashMap(materials), nodes.map { it.copy() }.toMutableList())

    fun node(name: String): Node? = nodes.firstOrNull { it.name == name }

    /** 부모를 따라 올라가며 곱한 월드 행렬. 순환 부모는 끊는다 */
    fun world(n: Node): M4 {
        var m = n.local(); var p = n.parent; val seen = HashSet<String>(); seen += n.name
        while (p != null && seen.add(p)) { val pn = node(p) ?: break; m = pn.local() * m; p = pn.parent }
        return m
    }

    fun material(n: Node): Material = n.mat?.let { materials[it] } ?: DEFAULT

    /** 이름이 겹치지 않게 */
    fun unique(base: String): String { var i = 1; var s = base; while (node(s) != null) s = "$base.${String.format(Locale.US, "%03d", i++)}"; return s }

    fun stats(): String {
        val tris = nodes.sumOf { it.mesh().tris }
        return "${nodes.size} objects · ${if (tris >= 1000) String.format(Locale.US, "%.1fk", tris / 1000.0) else tris.toString()} tris"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "ht-scene/1"); put("scene", name)
        put("materials", JSONObject().also { o -> materials.values.forEach { m -> o.put(m.name, JSONObject().put("color", m.hex()).put("metal", m.metal.toDouble()).put("rough", m.rough.toDouble())) } })
        put("objects", JSONArray(nodes.map { n ->
            JSONObject().apply {
                put("name", n.name); put("shape", n.shape)
                n.params.forEach { (k, v) -> put(k, if (v is List<*>) JSONArray(v.map { if (it is List<*>) JSONArray(it) else it }) else v) }
                if (n.at != V3.ZERO) put("at", arr(n.at)); if (n.rot != V3.ZERO) put("rot", arr(n.rot)); if (n.scale != V3.ONE) put("scale", arr(n.scale))
                n.mat?.let { put("mat", it) }; n.parent?.let { put("parent", it) }; n.smooth?.let { put("smooth", it) }
                if (n.mods.isNotEmpty()) put("mods", JSONArray(n.mods.map { m ->
                    when (m) {
                        is Modifier.Mirror -> JSONObject().put("mirror", m.axis.toString())
                        is Modifier.Array -> JSONObject().put("array", JSONObject().put("count", m.count).put("offset", arr(m.offset)).also { if (m.rotate != V3.ZERO) it.put("rotate", arr(m.rotate)); if (m.radius != 0f) it.put("radius", r(m.radius)) })
                        is Modifier.Subdiv -> JSONObject().put("subdiv", m.levels)
                    }
                }))
            }
        }))
    }

    companion object {
        val DEFAULT = Material("default")
        private val SHAPE_KEYS = setOf("name", "shape", "at", "rot", "scale", "mat", "mods", "parent", "smooth", "children")
        private fun arr(v: V3) = JSONArray(listOf(r(v.x), r(v.y), r(v.z)))
        private fun r(f: Float) = Math.round(f * 10000.0) / 10000.0

        /** 문제가 있으면 사람이 읽을 이유, 없으면 null — AI 결과물 검사·되묻기에 쓴다 */
        fun problem(text: String): String? {
            val s = runCatching { parse(text) }.getOrElse { return "HT-Scene JSON을 읽지 못했어요: ${it.message?.take(80)}" }
            if (s.nodes.isEmpty()) return "오브젝트가 하나도 없어요"
            val unknown = s.nodes.filter { it.shape !in Shapes.NAMES }.map { it.shape }.distinct()
            if (unknown.isNotEmpty()) return "모르는 도형: ${unknown.joinToString()} (가능: ${Shapes.NAMES.joinToString()})"
            val tris = s.nodes.sumOf { it.mesh().tris }
            if (tris > 400_000) return "폴리곤이 너무 많아요(${tris}개, 최대 40만)"
            return null
        }

        /** 관대하게 읽는다: 코드 블록·앞뒤 설명이 섞여도, 키 이름이 조금 달라도 */
        fun parse(text: String): Scene {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "JSON 객체가 없음" }
            val o = JSONObject(text.substring(start, end + 1))
            val scene = Scene(o.optString("scene", o.optString("name", "Untitled")).ifBlank { "Untitled" })
            o.optJSONObject("materials")?.let { ms -> ms.keys().forEach { k -> ms.optJSONObject(k)?.let { scene.materials[k] = mat(k, it) } } }
            val list = o.optJSONArray("objects") ?: o.optJSONArray("nodes") ?: JSONArray()
            fun add(j: JSONObject, parent: String?) {
                val shape = j.optString("shape", j.optString("type", "box")).lowercase().let { ALIASES[it] ?: it }
                val name = scene.unique(j.optString("name", shape).ifBlank { shape })
                val params = LinkedHashMap<String, Any>()
                j.keys().forEach { k -> if (k !in SHAPE_KEYS) j.opt(k)?.let { v -> params[k] = plain(v) } }
                val inlineMat = j.optJSONObject("mat")?.let { mat(name + "_mat", it).also { m -> scene.materials[m.name] = m } }
                val n = Node(name, shape, params, v3(j.opt("at") ?: j.opt("position"), V3.ZERO), v3(j.opt("rot") ?: j.opt("rotation"), V3.ZERO),
                    scaleOf(j.opt("scale")), inlineMat?.name ?: j.optString("mat", j.optString("material", "")).ifBlank { null },
                    mods(j.optJSONArray("mods") ?: j.optJSONArray("modifiers")), j.optString("parent", "").ifBlank { parent },
                    if (j.has("smooth")) j.optBoolean("smooth") else null)
                scene.nodes += n
                j.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) c.optJSONObject(i)?.let { add(it, n.name) } }
            }
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { add(it, null) }
            scene.nodes.forEach { n -> n.mat?.let { if (it !in scene.materials) scene.materials[it] = Material(it, guessColor(it)) } }
            return scene
        }

        private val ALIASES = mapOf("cube" to "box", "ball" to "sphere", "uv_sphere" to "sphere", "tube" to "cylinder", "pipe" to "cylinder",
            "ring" to "torus", "donut" to "torus", "ground" to "plane", "floor" to "plane", "revolve" to "lathe", "pill" to "capsule", "prism" to "extrude")

        private fun plain(v: Any): Any = when (v) {
            is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
            is JSONObject -> v.toString()
            else -> v
        }

        private fun v3(v: Any?, d: V3): V3 = when (v) {
            is JSONArray -> if (v.length() >= 3) V3(v.optDouble(0).toFloat(), v.optDouble(1).toFloat(), v.optDouble(2).toFloat()) else d
            is Number -> V3(v.toFloat(), v.toFloat(), v.toFloat())
            else -> d
        }
        private fun scaleOf(v: Any?) = if (v is Number) V3(v.toFloat(), v.toFloat(), v.toFloat()) else v3(v, V3.ONE)

        private fun mods(a: JSONArray?): MutableList<Modifier> {
            val out = ArrayList<Modifier>()
            a ?: return out
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                when {
                    m.has("mirror") -> m.optString("mirror", "x").lowercase().forEach { c -> if (c in "xyz") out += Modifier.Mirror(c) }
                    m.has("array") -> { val ar = m.optJSONObject("array"); if (ar != null) out += Modifier.Array(ar.optInt("count", 2), v3(ar.opt("offset"), if (ar.has("rotate")) V3.ZERO else V3(1f, 0f, 0f)), v3(ar.opt("rotate"), V3.ZERO), ar.optDouble("radius", 0.0).toFloat()) else out += Modifier.Array(m.optInt("array", 2), V3(1f, 0f, 0f), V3.ZERO) }
                    m.has("subdiv") || m.has("subsurf") || m.has("smooth") -> out += Modifier.Subdiv(m.optInt("subdiv", m.optInt("subsurf", m.optInt("smooth", 1))))
                }
            }
            return out
        }

        private fun mat(name: String, j: JSONObject) = Material(name, color(j.optString("color", "")) ?: guessColor(name), j.optDouble("metal", j.optDouble("metallic", 0.0)).toFloat().coerceIn(0f, 1f),
            j.optDouble("rough", j.optDouble("roughness", 0.6)).toFloat().coerceIn(0f, 1f))

        fun color(s: String): Int? {
            val h = s.trim().removePrefix("#")
            val v = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6 -> h; else -> return NAMED[s.trim().lowercase()] }
            return v.toLongOrNull(16)?.toInt()?.or(0xFF shl 24)
        }

        private val NAMED = mapOf("white" to 0xFFF2F0EB.toInt(), "black" to 0xFF1C1C1E.toInt(), "red" to 0xFFD2463C.toInt(), "green" to 0xFF4E8A54.toInt(),
            "blue" to 0xFF3E6FB0.toInt(), "yellow" to 0xFFE6BE3C.toInt(), "wood" to 0xFF9A6B43.toInt(), "gold" to 0xFFC9A45C.toInt(), "brass" to 0xFFC9A45C.toInt(),
            "silver" to 0xFFC4C7CC.toInt(), "steel" to 0xFF8E949B.toInt(), "glass" to 0xFFBFD8DE.toInt(), "clay" to 0xFFC8B8A6.toInt(), "grey" to 0xFF8C8C8C.toInt(), "gray" to 0xFF8C8C8C.toInt())

        /** 재질 이름만 있을 때(예: "wood") 그럴듯한 색 */
        fun guessColor(name: String): Int = NAMED.entries.firstOrNull { name.lowercase().contains(it.key) }?.value ?: (0xFFC8C2B8).toInt()

        /** AI에게 주는 형식 설명 — 짧고 정확하게 */
        const val SPEC = """HT-Scene v1 (JSON). Y is up, units are metres, rotations are degrees (X then Y then Z).
{"scene":"name","materials":{"<id>":{"color":"#RRGGBB","metal":0..1,"rough":0..1}},
 "objects":[{"name":"unique","shape":"box|sphere|cylinder|cone|torus|plane|capsule|lathe|extrude",
   size params: box {"size":[x,y,z],"round":r (rounded edges, e.g. cushions, phones, car bodies)} · sphere {"r"} · cylinder/cone {"r","h"} · capsule {"r","h"} · torus {"R","r"} · plane {"size":[x,0,z]}
                lathe {"profile":[[radius,y],...]} (spun around Y: vases, bottles, lamps, wheels) · extrude {"outline":[[x,z],...],"depth"} (flat shapes pushed up: signs, rooms, logos)
   optional: "seg" (roundness, 8..64), "at":[x,y,z], "rot":[x,y,z], "scale":[x,y,z], "mat":"<id>", "parent":"<name>" (moves with it), "smooth":true|false,
   "mods":[{"mirror":"x"} (copies the object to the other side of its parent's axis plane: place the right leg with "at", mirror makes the left),{"array":{"count":n,"offset":[x,y,z],"rotate":[x,y,z],"radius":r}},{"subdiv":1..3}]}]}
array copy i = rotate(rotate*i) after moving by offset*i + [radius,0,0]: rows use offset; rings (petals, spokes, chairs round a table) use radius + rotate; spirals use all three.
Build real proportions from parts; place each part with "at" so parts touch; use parents for assemblies; mirror for symmetric pairs."""
    }
}
