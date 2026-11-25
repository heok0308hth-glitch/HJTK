package com.ht.mesh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshTest {
    private val out = File(System.getProperty("mesh.out") ?: "build/mesh-out").also { it.mkdirs() }

    /** 닫힌 도형: 모든 모서리를 정확히 두 면이 나눠 쓰고, 면은 바깥을 본다 */
    private fun closedAndOutward(name: String, m: Mesh) {
        val edges = HashMap<Pair<Int, Int>, Int>()
        m.faces.forEach { f -> for (i in f.indices) { val a = f[i]; val b = f[(i + 1) % f.size]; edges[minOf(a, b) to maxOf(a, b)] = (edges[minOf(a, b) to maxOf(a, b)] ?: 0) + 1 } }
        assertTrue("$name: open or non-manifold edges ${edges.filter { it.value != 2 }.size}", edges.values.all { it == 2 })
        val (lo, hi) = m.bounds(); val c = (lo + hi) * 0.5f
        val inward = m.faces.count { f -> val cen = f.fold(V3.ZERO) { s, i -> s + m.pos[i] } * (1f / f.size); m.faceNormal(f).dot(cen - c) < 0 }
        assertEquals("$name: faces pointing inward", 0, inward)
    }

    @Test fun primitives_areClosed_andFaceOutward() {
        closedAndOutward("box", Shapes.box(1f, 2f, 3f))
        closedAndOutward("sphere", Shapes.sphere(1f, 24))
        closedAndOutward("cylinder", Shapes.cylinder(0.5f, 2f, 20))
        closedAndOutward("cone", Shapes.cone(0.5f, 1f, 20))
        closedAndOutward("capsule", Shapes.capsule(0.3f, 1.2f, 16))
        val torus = Shapes.torus(1f, 0.2f, 24, 12)
        val tubeOutward = torus.faces.count { f -> val cen = f.fold(V3.ZERO) { s, i -> s + torus.pos[i] } * (1f / f.size); val ring = V3(cen.x, 0f, cen.z).norm(); torus.faceNormal(f).dot(cen - ring) > 0 }
        assertEquals("torus faces point away from the tube centre", torus.faces.size, tubeOutward)
        closedAndOutward("extrude (concave L)", Shapes.extrude(listOf(0f to 0f, 2f to 0f, 2f to 1f, 1f to 1f, 1f to 2f, 0f to 2f), 0.5f))
        closedAndOutward("vase", Shapes.lathe(listOf(0f to 0f, 0.3f to 0f, 0.4f to 0.4f, 0.15f to 0.8f, 0.2f to 1f, 0f to 1f), 24))
    }

    @Test fun concaveFaces_triangulateWithoutOverlap() {
        val l = Shapes.extrude(listOf(0f to 0f, 2f to 0f, 2f to 1f, 1f to 1f, 1f to 2f, 0f to 2f), 0.5f)
        val tri = l.triangles()
        // 윗면 L자 넓이 = 3. 삼각형 넓이 합이 같아야 한다(겹침·빠짐 없음)
        val top = l.faces[1]
        val sub = Mesh(l.pos, mutableListOf(top)).triangles()
        var area = 0f
        for (t in 0 until sub.size / 3) { val a = l.pos[sub[t * 3]]; val b = l.pos[sub[t * 3 + 1]]; val c = l.pos[sub[t * 3 + 2]]; area += (b - a).cross(c - a).len() / 2 }
        assertEquals(3f, area, 1e-3f)
        assertEquals(l.tris * 3, tri.size)
    }

    @Test fun subdivision_quadsAndSmooth() {
        val cube = Shapes.box(2f, 2f, 2f)
        val s1 = cube.subdivide()
        assertEquals(24, s1.faces.size)
        assertTrue(s1.faces.all { it.size == 4 })
        closedAndOutward("subdivided cube", s1)
        val s2 = s1.subdivide()
        val r = s2.pos.map { it.len() }
        assertTrue("rounds toward a sphere: ${r.min()}..${r.max()}", r.max() / r.min() < 1.5f)
        // 열린 판도 찢어지지 않는다
        val plane = Shapes.plane(2f, 2f).subdivide()
        assertEquals(4, plane.faces.size)
    }

    @Test fun modifiers_mirrorArrayWeld() {
        val centred = Node("c", "box", linkedMapOf("size" to listOf(1.0, 1.0, 1.0)), mods = mutableListOf(Modifier.Mirror('x')))
        assertEquals("a centred box mirrored onto itself welds back to 8 vertices", 8, centred.mesh().pos.size)
        val row = Node("r", "box", linkedMapOf("size" to listOf(1.0, 1.0, 1.0)), mods = mutableListOf(Modifier.Array(3, V3(2f, 0f, 0f), V3.ZERO)))
        assertEquals(24, row.mesh().pos.size); assertEquals(5f, row.mesh().bounds().second.x - row.mesh().bounds().first.x, 1e-4f)
        val petals = Node("p", "box", linkedMapOf("size" to listOf(0.4, 0.1, 0.2)), mods = mutableListOf(Modifier.Array(8, V3.ZERO, V3(0f, 45f, 0f), radius = 1f)))
        val (lo, hi) = petals.mesh().bounds()
        assertEquals("a ring around the origin", -lo.x, hi.x, 1e-3f); assertEquals(-lo.z, hi.z, 1e-3f)
        assertTrue(hi.x > 1.1f)
        val smooth = Node("s", "box", mods = mutableListOf(Modifier.Subdiv(2)))
        assertEquals(96, smooth.mesh().faces.size)
        assertTrue(smooth.smoothShading())
    }

    @Test fun scene_parsesForgivingly_andRoundTrips() {
        val s = Scene.parse("Here you go:\n```json\n" + """{"name":"T","objects":[{"type":"cube","position":[0,1,0],"material":"wood","children":[{"shape":"ball","r":0.2,"at":[0,0.7,0]}]},{"shape":"ring","R":1,"r":0.1,"mods":[{"subsurf":1}]}]}""" + "\n```")
        assertEquals(listOf("box", "sphere", "torus"), s.nodes.map { it.shape })
        assertEquals("box", s.nodes[1].parent)
        assertEquals(0xFF9A6B43.toInt(), s.materials["wood"]!!.color)
        val back = Scene.parse(s.toJson().toString())
        assertEquals(s.nodes.map { it.name }, back.nodes.map { it.name })
        assertEquals(s.nodes[0].at, back.nodes[0].at)
        assertEquals(s.nodes.sumOf { it.mesh().tris }, back.nodes.sumOf { it.mesh().tris })
        assertNull(Scene.problem(Samples.ROBOT))
        assertNotNull(Scene.problem("""{"objects":[{"shape":"teapot"}]}"""))
        assertNotNull(Scene.problem("not json"))
    }

    @Test fun parentTransforms_compose() {
        val s = Scene.parse("""{"objects":[{"name":"a","shape":"box","at":[1,0,0],"rot":[0,90,0]},{"name":"b","shape":"box","at":[0,0,2],"parent":"a"}]}""")
        val p = s.world(s.node("b")!!).point(V3.ZERO)
        assertEquals(3f, p.x, 1e-4f); assertEquals(0f, p.z, 1e-4f)          // 부모가 Y로 90° 돌아 b의 +Z가 +X로
        val cyc = Scene.parse("""{"objects":[{"name":"a","shape":"box","parent":"b"},{"name":"b","shape":"box","parent":"a"}]}""")
        cyc.world(cyc.nodes[0])                                              // 순환해도 멈추지 않는다
        assertTrue(Glb.write(cyc).isNotEmpty())
    }

    @Test fun glb_isWellFormed_andSamplesAreWrittenForTheValidator() {
        for ((name, json) in Samples.ALL.map { it.first to it.second } + ("empty" to """{"scene":"Empty","objects":[]}""")) {
            val s = Scene.parse(json)
            val glb = Glb.write(s)
            val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, b.getInt(0)); assertEquals(2, b.getInt(4)); assertEquals(glb.size, b.getInt(8))
            val jsonLen = b.getInt(12)
            assertEquals(0, jsonLen % 4)
            val gltf = JSONObject(String(glb, 20, jsonLen, Charsets.UTF_8))
            assertEquals("2.0", gltf.getJSONObject("asset").getString("version"))
            assertEquals(s.nodes.size, gltf.optJSONArray("nodes")?.length() ?: 0)
            val file = s.name.replace(' ', '_')
            File(out, "$file.glb").writeBytes(glb)
            val (obj, mtl) = Obj.write(s, "$file.mtl")
            File(out, "$file.obj").writeText(obj); File(out, "$file.mtl").writeText(mtl)
            assertTrue(name, obj.lines().count { it.startsWith("f ") } == s.nodes.sumOf { it.mesh().faces.size })
        }
    }

    @Test fun raster_drawsSamples_picksObjects_andOutlinesSelection() {
        for ((i, pair) in Samples.ALL.withIndex()) {
            val s = Scene.parse(pair.second)
            val cam = Camera.frame(s)
            val r = Raster(640, 480).render(s, cam, Raster.DARK, selected = 0)
            val covered = r.ids.count { it >= 0 }
            assertTrue("${s.name}: objects cover the frame ($covered px)", covered > 640 * 480 / 12)
            assertTrue("selection outline drawn", r.color.count { it == (Raster.DARK.accent or (0xFF shl 24)) } > 50)
            File(out, "sample$i.png").writeBytes(r.png())
            val c = r.ids.indexOfFirst { it == 0 }
            assertEquals(0, r.pick(c % 640, c / 640))
        }
        val s = Scene.parse(Samples.LAMP)
        File(out, "lamp_paper.png").writeBytes(Raster(640, 480).render(s, Camera.frame(s, yaw = -30f, pitch = 15f), Raster.PAPER).png())
    }
}
