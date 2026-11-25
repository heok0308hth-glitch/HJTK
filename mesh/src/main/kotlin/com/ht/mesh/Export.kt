package com.ht.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * glTF 2.0 바이너리(.glb) 쓰기. 블렌더·유니티·언리얼·웹(three.js)이 그대로 연다.
 * 오브젝트마다 노드 하나(부모 관계·이동·회전·크기 유지), 메시 하나, PBR 재질(색·금속성·거칠기).
 * 모디파이어는 적용된 모양으로 굽는다(블렌더 glTF 내보내기의 'Apply Modifiers'와 같음).
 */
object Glb {
    fun write(scene: Scene, generator: String = "HJTK 3D"): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = JSONArray(); val accessors = JSONArray(); val meshes = JSONArray(); val nodes = JSONArray(); val mats = JSONArray()
        val matIndex = LinkedHashMap<String, Int>()
        fun pad4() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int): Int {
            pad4(); val off = bin.size(); bin.write(bytes)
            views.put(JSONObject().put("buffer", 0).put("byteOffset", off).put("byteLength", bytes.size).put("target", target))
            return views.length() - 1
        }
        fun floats(a: FloatArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> a.forEach { b.putFloat(it) } }.array()
        fun ints(a: IntArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> a.forEach { b.putInt(it) } }.array()
        fun material(m: Material): Int = matIndex.getOrPut(m.name) {
            val c = m.rgb()
            mats.put(JSONObject().put("name", m.name).put("pbrMetallicRoughness", JSONObject()
                .put("baseColorFactor", JSONArray(listOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), 1.0)))
                .put("metallicFactor", m.metal.toDouble()).put("roughnessFactor", m.rough.toDouble())))
            mats.length() - 1
        }

        val order = scene.nodes
        val index = order.withIndex().associate { it.value.name to it.index }
        for (n in order) {
            val node = JSONObject().put("name", n.name)
            val (p, nr, idx) = n.mesh().shaded(n.smoothShading())
            if (idx.isNotEmpty()) {
                var lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (i in 0 until p.size / 3) for (k in 0..2) { lo[k] = minOf(lo[k], p[i * 3 + k]); hi[k] = maxOf(hi[k], p[i * 3 + k]) }
                val count = p.size / 3
                accessors.put(JSONObject().put("bufferView", view(floats(p), 34962)).put("componentType", 5126).put("count", count).put("type", "VEC3")
                    .put("min", JSONArray(lo.map { it.toDouble() })).put("max", JSONArray(hi.map { it.toDouble() })))
                val pa = accessors.length() - 1
                accessors.put(JSONObject().put("bufferView", view(floats(nr), 34962)).put("componentType", 5126).put("count", count).put("type", "VEC3"))
                val na = accessors.length() - 1
                accessors.put(JSONObject().put("bufferView", view(ints(idx), 34963)).put("componentType", 5125).put("count", idx.size).put("type", "SCALAR"))
                val ia = accessors.length() - 1
                meshes.put(JSONObject().put("name", n.name).put("primitives", JSONArray().put(JSONObject()
                    .put("attributes", JSONObject().put("POSITION", pa).put("NORMAL", na)).put("indices", ia).put("material", material(scene.material(n))))))
                node.put("mesh", meshes.length() - 1)
            }
            if (n.at != V3.ZERO) node.put("translation", JSONArray(listOf(n.at.x.toDouble(), n.at.y.toDouble(), n.at.z.toDouble())))
            if (n.rot != V3.ZERO) node.put("rotation", JSONArray(quat(n.rot).map { it.toDouble() }))
            if (n.scale != V3.ONE) node.put("scale", JSONArray(listOf(n.scale.x.toDouble(), n.scale.y.toDouble(), n.scale.z.toDouble())))
            val kids = order.filter { it.parent == n.name && it.name != n.name && !cyclic(scene, it) }.mapNotNull { index[it.name] }
            if (kids.isNotEmpty()) node.put("children", JSONArray(kids))
            nodes.put(node)
        }
        // 부모가 없거나(또는 없는 부모를 가리키거나) 순환에 걸린 노드가 장면의 뿌리
        val roots = order.filter { n -> n.parent == null || index[n.parent] == null || cyclic(scene, n) }.map { index[it.name]!! }
        pad4()
        val binBytes = bin.toByteArray()
        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", generator))
            .put("scene", 0).put("scenes", JSONArray().put(JSONObject().put("name", scene.name).also { if (roots.isNotEmpty()) it.put("nodes", JSONArray(roots)) }))
        if (nodes.length() > 0) gltf.put("nodes", nodes)
        if (meshes.length() > 0) gltf.put("meshes", meshes).put("accessors", accessors).put("bufferViews", views).put("materials", mats)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size)))
        var json = gltf.toString().toByteArray(Charsets.UTF_8)
        if (json.size % 4 != 0) json += ByteArray(4 - json.size % 4) { 0x20 }
        val total = 12 + 8 + json.size + if (binBytes.isNotEmpty()) 8 + binBytes.size else 0
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(json.size).putInt(0x4E4F534A).put(json)
        if (binBytes.isNotEmpty()) out.putInt(binBytes.size).putInt(0x004E4942).put(binBytes)
        return out.array()
    }

    /** 부모를 따라가다 자기 자신으로 돌아오면 순환 — 노드 트리가 깨지지 않게 뿌리로 올린다 */
    private fun cyclic(s: Scene, n: Node): Boolean {
        var p = n.parent; val seen = HashSet<String>()
        while (p != null) { if (p == n.name || !seen.add(p)) return true; p = s.node(p)?.parent }
        return false
    }
}

/** OBJ + MTL — 가장 오래 살아남은 교환 형식. n각형 면을 그대로 둬서 블렌더에서 다시 모델링하기 좋다 */
object Obj {
    fun write(scene: Scene, mtlName: String): Pair<String, String> {
        val obj = StringBuilder("# HJTK 3D · ${scene.name}\nmtllib $mtlName\n")
        val mtl = StringBuilder("# HJTK 3D\n")
        var base = 1
        val used = LinkedHashSet<String>()
        for (n in scene.nodes) {
            val m = n.mesh().transformed(scene.world(n))
            obj.append("o ${n.name.replace(' ', '_')}\n")
            m.pos.forEach { obj.append(String.format(Locale.US, "v %.5f %.5f %.5f\n", it.x, it.y, it.z)) }
            val mat = scene.material(n); used += mat.name
            obj.append("usemtl ${mat.name}\n")
            obj.append(if (n.smoothShading()) "s 1\n" else "s off\n")
            m.faces.forEach { f -> obj.append("f ").append(f.joinToString(" ") { (it + base).toString() }).append('\n') }
            base += m.pos.size
        }
        for (name in used) {
            val m = scene.materials[name] ?: Scene.DEFAULT
            val c = m.rgb()
            mtl.append(String.format(Locale.US, "newmtl %s\nKd %.4f %.4f %.4f\nKa 0 0 0\nKs %.3f %.3f %.3f\nNs %.1f\nPr %.3f\nPm %.3f\nd 1\nillum 2\n\n",
                name, c[0], c[1], c[2], 0.5f - m.rough * 0.45f, 0.5f - m.rough * 0.45f, 0.5f - m.rough * 0.45f, (1 - m.rough) * 900 + 10, m.rough, m.metal))
        }
        return obj.toString() to mtl.toString()
    }
}
