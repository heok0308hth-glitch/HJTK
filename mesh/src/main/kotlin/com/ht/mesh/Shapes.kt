package com.ht.mesh

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 기본 도형. 모두 원점 중심, Y가 위(glTF·블렌더 내보내기 기준과 같은 Y-up), 바깥쪽이 앞면(반시계 감김).
 * 도형 이름은 AI가 쓰기 쉽게 짧은 영어 한 단어다.
 */
object Shapes {
    val NAMES = listOf("box", "sphere", "cylinder", "cone", "torus", "plane", "capsule", "lathe", "extrude")

    /**
     * 둥근 상자: 모서리 가까이에 받침 고리(support loop)를 넣은 뒤 서브디비전 — 블렌더에서 손으로 하던 방식을 자동으로.
     * [round]는 모서리 반지름에 가까운 값(미터).
     */
    fun roundedBox(sx: Float, sy: Float, sz: Float, round: Float): Mesh {
        fun cuts(s: Float): FloatArray { val r = round.coerceIn(0.001f, s / 2 * 0.95f); return floatArrayOf(-s / 2, -s / 2 + r, s / 2 - r, s / 2) }
        val xs = cuts(sx); val ys = cuts(sy); val zs = cuts(sz)
        val m = Mesh()
        // 여섯 면을 각자 격자로 만들고 바깥을 보게 맞춘 뒤 이음매를 붙인다
        fun face(fixed: Int, value: Float, ua: FloatArray, va: FloatArray, outward: V3) {
            val base = m.pos.size
            for (v in va) for (u in ua) m.pos += when (fixed) { 0 -> V3(value, v, u); 1 -> V3(u, value, v); else -> V3(u, v, value) }
            val n = ua.size
            for (j in 0 until va.size - 1) for (i in 0 until n - 1) {
                var f = intArrayOf(base + j * n + i, base + j * n + i + 1, base + (j + 1) * n + i + 1, base + (j + 1) * n + i)
                if (m.faceNormal(f).dot(outward) < 0) f = f.reversedArray()
                m.faces += f
            }
        }
        face(0, xs.last(), zs, ys, V3(1f, 0f, 0f)); face(0, xs.first(), zs, ys, V3(-1f, 0f, 0f))
        face(1, ys.last(), xs, zs, V3(0f, 1f, 0f)); face(1, ys.first(), xs, zs, V3(0f, -1f, 0f))
        face(2, zs.last(), xs, ys, V3(0f, 0f, 1f)); face(2, zs.first(), xs, ys, V3(0f, 0f, -1f))
        return m.weld().subdivide().subdivide()
    }

    fun box(sx: Float, sy: Float, sz: Float): Mesh {
        val x = sx / 2; val y = sy / 2; val z = sz / 2
        val p = mutableListOf(V3(-x, -y, -z), V3(x, -y, -z), V3(x, y, -z), V3(-x, y, -z), V3(-x, -y, z), V3(x, -y, z), V3(x, y, z), V3(-x, y, z))
        val f = mutableListOf(intArrayOf(4, 5, 6, 7), intArrayOf(1, 0, 3, 2), intArrayOf(0, 4, 7, 3), intArrayOf(5, 1, 2, 6), intArrayOf(7, 6, 2, 3), intArrayOf(0, 1, 5, 4))
        return Mesh(p, f)
    }

    fun plane(sx: Float, sz: Float): Mesh {
        val x = sx / 2; val z = sz / 2
        return Mesh(mutableListOf(V3(-x, 0f, z), V3(x, 0f, z), V3(x, 0f, -z), V3(-x, 0f, -z)), mutableListOf(intArrayOf(0, 1, 2, 3)))
    }

    fun sphere(r: Float, seg: Int = 32, rings: Int = seg / 2): Mesh {
        val m = Mesh()
        m.pos += V3(0f, r, 0f)
        for (i in 1 until rings) {
            val phi = PI * i / rings
            for (j in 0 until seg) { val th = 2 * PI * j / seg; m.pos += V3((r * sin(phi) * cos(th)).toFloat(), (r * cos(phi)).toFloat(), (-r * sin(phi) * sin(th)).toFloat()) }
        }
        m.pos += V3(0f, -r, 0f)
        val bottom = m.pos.size - 1
        fun ring(i: Int, j: Int) = 1 + (i - 1) * seg + (j % seg)
        for (j in 0 until seg) m.faces += intArrayOf(0, ring(1, j), ring(1, j + 1))
        for (i in 1 until rings - 1) for (j in 0 until seg) m.faces += intArrayOf(ring(i, j), ring(i + 1, j), ring(i + 1, j + 1), ring(i, j + 1))
        for (j in 0 until seg) m.faces += intArrayOf(ring(rings - 1, j), bottom, ring(rings - 1, j + 1))
        return m
    }

    /** 옆면 프로필 [r, y] 목록을 Y축으로 돌려 만든다(화병·병·기둥·바퀴 테 등). 반지름 0인 끝점은 꼭짓점 하나로 모인다 */
    fun lathe(profile: List<Pair<Float, Float>>, seg: Int = 32): Mesh {
        val m = Mesh()
        val rows = profile.map { (r, y) ->
            if (r <= 1e-6f) listOf(m.pos.size).also { m.pos += V3(0f, y, 0f) }
            else List(seg) { j -> val th = 2 * PI * j / seg; m.pos.size.also { m.pos += V3((r * cos(th)).toFloat(), y, (-r * sin(th)).toFloat()) } }
        }
        for (k in 0 until rows.size - 1) {
            val a = rows[k]; val b = rows[k + 1]
            for (j in 0 until seg) {
                val a0 = a[j % a.size]; val a1 = a[(j + 1) % a.size]; val b0 = b[j % b.size]; val b1 = b[(j + 1) % b.size]
                // 아래(a)에서 위(b)로 올라가는 프로필 기준 바깥쪽이 앞면
                val f = listOf(a0, a1, b1, b0).fold(ArrayList<Int>()) { acc, v -> if (v !in acc) acc += v; acc }
                if (f.size >= 3) m.faces += f.toIntArray()
            }
        }
        return m
    }

    fun cylinder(r: Float, h: Float, seg: Int = 32) = lathe(listOf(0f to -h / 2, r to -h / 2, r to h / 2, 0f to h / 2), seg)
    fun cone(r: Float, h: Float, seg: Int = 32) = lathe(listOf(0f to -h / 2, r to -h / 2, 0f to h / 2), seg)

    fun capsule(r: Float, h: Float, seg: Int = 32): Mesh {
        val half = (h / 2 - r).coerceAtLeast(0f)
        val prof = ArrayList<Pair<Float, Float>>()
        val k = (seg / 4).coerceAtLeast(3)
        for (i in 0..k) { val a = -PI / 2 + PI / 2 * i / k; prof += (r * cos(a)).toFloat() to (-half + r * sin(a)).toFloat() }
        for (i in 0..k) { val a = PI / 2 * i / k; prof += (r * cos(a)).toFloat() to (half + r * sin(a)).toFloat() }
        return lathe(prof.distinct(), seg)
    }

    fun torus(major: Float, minor: Float, seg: Int = 48, side: Int = 16): Mesh {
        val m = Mesh()
        for (i in 0 until seg) for (j in 0 until side) {
            val u = 2 * PI * i / seg; val v = 2 * PI * j / side
            val rr = major + minor * cos(v)
            m.pos += V3((rr * cos(u)).toFloat(), (minor * sin(v)).toFloat(), (-rr * sin(u)).toFloat())
        }
        fun id(i: Int, j: Int) = (i % seg) * side + (j % side)
        for (i in 0 until seg) for (j in 0 until side) m.faces += intArrayOf(id(i, j), id(i + 1, j), id(i + 1, j + 1), id(i, j + 1))
        return m
    }

    /** 바닥 윤곽선 [x, z] 다각형(오목해도 됨)을 위로 [depth]만큼 밀어 올린다(글자판·로고·방 바닥·간판) */
    fun extrude(outline: List<Pair<Float, Float>>, depth: Float): Mesh {
        var o = outline.map { V3(it.first, 0f, it.second) }
        // 위에서 볼 때 반시계가 되도록(윗면 법선이 +Y)
        val area = o.indices.sumOf { i -> val a = o[i]; val b = o[(i + 1) % o.size]; (a.x * b.z - b.x * a.z).toDouble() }
        if (area > 0) o = o.reversed()
        val n = o.size
        val m = Mesh()
        o.forEach { m.pos += it }
        o.forEach { m.pos += V3(it.x, depth, it.z) }
        m.faces += IntArray(n) { n - 1 - it }               // 바닥(아래를 향함)
        m.faces += IntArray(n) { n + it }                   // 윗면
        for (i in 0 until n) { val j = (i + 1) % n; m.faces += intArrayOf(i, j, n + j, n + i) }
        // 감김이 반대로 나온 경우를 위해 윗면 법선으로 확인
        if (m.faceNormal(m.faces[1]).y < 0) m.faces.replaceAll { it.reversedArray() }
        return m
    }
}
