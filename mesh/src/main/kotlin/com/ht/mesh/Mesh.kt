package com.ht.mesh

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 3차원 벡터(불변). 메시 계산은 Float 배열로, 장면 계산은 이걸로 한다. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun norm(): V3 { val l = len(); return if (l < 1e-9f) V3(0f, 1f, 0f) else this * (1f / l) }
    companion object { val ZERO = V3(0f, 0f, 0f); val ONE = V3(1f, 1f, 1f) }
}

/** 4×4 행렬(열 우선, glTF·OpenGL과 같은 배치) */
class M4(val m: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }) {
    operator fun times(o: M4): M4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rw in 0 until 4) { var s = 0f; for (k in 0 until 4) s += m[k * 4 + rw] * o.m[c * 4 + k]; r[c * 4 + rw] = s }
        return M4(r)
    }
    fun point(p: V3) = V3(m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13], m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14])
    fun dir(p: V3) = V3(m[0] * p.x + m[4] * p.y + m[8] * p.z, m[1] * p.x + m[5] * p.y + m[9] * p.z, m[2] * p.x + m[6] * p.y + m[10] * p.z)
    /** 역행렬(가우스-조르단). 특이하면 단위 행렬 */
    fun inverse(): M4 {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c * 4 + r].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0 until 4) {
            val piv = (col until 4).maxBy { kotlin.math.abs(a[it][col]) }
            if (kotlin.math.abs(a[piv][col]) < 1e-12) return M4()
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val d = a[col][col]; for (c in 0 until 8) a[col][c] /= d
            for (r in 0 until 4) if (r != col) { val f = a[r][col]; if (f != 0.0) for (c in 0 until 8) a[r][c] -= f * a[col][c] }
        }
        return M4(FloatArray(16) { i -> a[i % 4][4 + i / 4].toFloat() })
    }

    /** 행렬식 부호: 음수면(거울 변환) 면의 감김을 뒤집어야 앞면이 유지된다 */
    fun det3() = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    companion object {
        fun translate(t: V3) = M4().also { it.m[12] = t.x; it.m[13] = t.y; it.m[14] = t.z }
        fun scale(s: V3) = M4().also { it.m[0] = s.x; it.m[5] = s.y; it.m[10] = s.z }
        /** 오일러 XYZ(도): X로 먼저, 그다음 Y, 마지막 Z — 블렌더 기본 회전 순서와 같다 */
        fun euler(deg: V3): M4 = rotZ(deg.z) * rotY(deg.y) * rotX(deg.x)
        fun rotX(d: Float) = rad(d).let { a -> M4().also { val c = cos(a); val s = sin(a); it.m[5] = c; it.m[6] = s; it.m[9] = -s; it.m[10] = c } }
        fun rotY(d: Float) = rad(d).let { a -> M4().also { val c = cos(a); val s = sin(a); it.m[0] = c; it.m[2] = -s; it.m[8] = s; it.m[10] = c } }
        fun rotZ(d: Float) = rad(d).let { a -> M4().also { val c = cos(a); val s = sin(a); it.m[0] = c; it.m[1] = s; it.m[4] = -s; it.m[5] = c } }
        fun trs(t: V3, rDeg: V3, s: V3) = translate(t) * euler(rDeg) * scale(s)
        private fun rad(d: Float) = (d * PI / 180.0).toFloat()
    }
}

/** 오일러(도, XYZ) → 사원수 [x, y, z, w] — glTF 노드 회전용 */
fun quat(deg: V3): FloatArray {
    fun q(ax: Int, d: Float): DoubleArray { val h = d * PI / 360.0; val s = sin(h); return doubleArrayOf(if (ax == 0) s else 0.0, if (ax == 1) s else 0.0, if (ax == 2) s else 0.0, cos(h)) }
    fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1], a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3], a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])
    val r = mul(q(2, deg.z), mul(q(1, deg.y), q(0, deg.x)))
    return floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
}

/**
 * 다각형 메시. 면은 삼각형만이 아니라 사각형·n각형도 그대로 둔다(블렌더와 같은 방식).
 * 서브디비전은 사각형 위에서 가장 예쁘고, OBJ로 내보낼 때도 n각형을 유지해 블렌더에서 다시 만지기 좋다.
 */
class Mesh(val pos: MutableList<V3> = ArrayList(), val faces: MutableList<IntArray> = ArrayList()) {
    val tris: Int get() = faces.sumOf { (it.size - 2).coerceAtLeast(0) }

    fun copy() = Mesh(pos.toMutableList(), faces.map { it.copyOf() }.toMutableList())

    fun transformed(m: M4): Mesh {
        val out = Mesh(pos.map { m.point(it) }.toMutableList(), faces.map { it.copyOf() }.toMutableList())
        if (m.det3() < 0) out.faces.replaceAll { it.reversedArray() }
        return out
    }

    fun append(o: Mesh) { val base = pos.size; pos += o.pos; o.faces.forEach { f -> faces += IntArray(f.size) { base + f[it] } } }

    fun bounds(): Pair<V3, V3> {
        if (pos.isEmpty()) return V3.ZERO to V3.ZERO
        var lo = pos[0]; var hi = pos[0]
        for (p in pos) { lo = V3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z)); hi = V3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z)) }
        return lo to hi
    }

    fun faceNormal(f: IntArray): V3 {
        // 뉴웰 방법: 오목한 n각형에서도 안정적
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in f.indices) { val a = pos[f[i]]; val b = pos[f[(i + 1) % f.size]]; nx += (a.y - b.y) * (a.z + b.z); ny += (a.z - b.z) * (a.x + b.x); nz += (a.x - b.x) * (a.y + b.y) }
        return V3(nx, ny, nz).norm()
    }

    /** 면을 삼각형으로(오목한 면은 귀 자르기). 결과는 원래 정점 번호를 쓴다 */
    fun triangles(): IntArray {
        val out = ArrayList<Int>(tris * 3)
        for (f in faces) {
            if (f.size < 3) continue
            if (f.size == 3) { out += f[0]; out += f[1]; out += f[2]; continue }
            if (convex(f)) { for (i in 1 until f.size - 1) { out += f[0]; out += f[i]; out += f[i + 1] }; continue }
            earClip(f, out)
        }
        return out.toIntArray()
    }

    private fun convex(f: IntArray): Boolean {
        val n = faceNormal(f)
        for (i in f.indices) {
            val a = pos[f[i]]; val b = pos[f[(i + 1) % f.size]]; val c = pos[f[(i + 2) % f.size]]
            if ((b - a).cross(c - b).dot(n) < -1e-7f) return false
        }
        return true
    }

    private fun earClip(f: IntArray, out: MutableList<Int>) {
        val n = faceNormal(f)
        val idx = f.toMutableList()
        var guard = 0
        while (idx.size > 3 && guard++ < 10_000) {
            var cut = false
            for (i in idx.indices) {
                val ia = idx[(i + idx.size - 1) % idx.size]; val ib = idx[i]; val ic = idx[(i + 1) % idx.size]
                val a = pos[ia]; val b = pos[ib]; val c = pos[ic]
                if ((b - a).cross(c - b).dot(n) <= 1e-9f) continue
                if (idx.any { it != ia && it != ib && it != ic && inside(pos[it], a, b, c, n) }) continue
                out += ia; out += ib; out += ic
                idx.removeAt(i); cut = true; break
            }
            if (!cut) break
        }
        if (idx.size >= 3) for (i in 1 until idx.size - 1) { out += idx[0]; out += idx[i]; out += idx[i + 1] }
    }

    private fun inside(p: V3, a: V3, b: V3, c: V3, n: V3): Boolean =
        (b - a).cross(p - a).dot(n) >= 0 && (c - b).cross(p - b).dot(n) >= 0 && (a - c).cross(p - c).dot(n) >= 0

    /**
     * 캣멀-클라크 서브디비전 한 단계. 모든 면이 사각형이 되고 표면이 부드러워진다(블렌더 Subdivision Surface와 같은 규칙).
     * 열린 가장자리는 가장자리 규칙(중점·경계 평균)으로 처리해 판·컵 같은 모양도 찢어지지 않는다.
     */
    fun subdivide(): Mesh {
        val fp = faces.map { f -> f.fold(V3.ZERO) { s, i -> s + pos[i] } * (1f / f.size) }
        val edgeFaces = HashMap<Long, MutableList<Int>>()
        fun key(a: Int, b: Int) = if (a < b) a.toLong() shl 32 or b.toLong() else b.toLong() shl 32 or a.toLong()
        faces.forEachIndexed { fi, f -> for (i in f.indices) edgeFaces.getOrPut(key(f[i], f[(i + 1) % f.size])) { ArrayList() } += fi }
        val out = Mesh()
        val edgeIndex = HashMap<Long, Int>()
        // 새 정점: 원래 정점(옮김) → 면 점 → 모서리 점
        val vFaces = Array(pos.size) { ArrayList<Int>() }
        val vEdges = Array(pos.size) { ArrayList<Long>() }
        faces.forEachIndexed { fi, f -> f.forEach { vFaces[it] += fi } }
        edgeFaces.keys.forEach { k -> vEdges[(k ushr 32).toInt()] += k; vEdges[(k and 0xffffffffL).toInt()] += k }
        fun ends(k: Long) = pos[(k ushr 32).toInt()] to pos[(k and 0xffffffffL).toInt()]
        for (v in pos.indices) {
            val p = pos[v]
            val boundary = vEdges[v].filter { edgeFaces[it]!!.size == 1 }
            out.pos += when {
                vFaces[v].isEmpty() -> p
                boundary.size >= 2 -> {
                    val (a1, b1) = ends(boundary[0]); val (a2, b2) = ends(boundary[1])
                    val m1 = (a1 + b1) * 0.5f; val m2 = (a2 + b2) * 0.5f
                    p * 0.5f + (m1 + m2) * 0.25f
                }
                else -> {
                    val n = vFaces[v].size.toFloat()
                    val f = vFaces[v].fold(V3.ZERO) { s, i -> s + fp[i] } * (1f / n)
                    val r = vEdges[v].fold(V3.ZERO) { s, k -> val (a, b) = ends(k); s + (a + b) * 0.5f } * (1f / vEdges[v].size)
                    (f + r * 2f + p * (n - 3f)) * (1f / n)
                }
            }
        }
        val faceBase = out.pos.size
        out.pos += fp
        for ((k, fs) in edgeFaces) {
            val (a, b) = ends(k)
            edgeIndex[k] = out.pos.size
            out.pos += if (fs.size == 2) (a + b + fp[fs[0]] + fp[fs[1]]) * 0.25f else (a + b) * 0.5f
        }
        faces.forEachIndexed { fi, f ->
            for (i in f.indices) {
                val prev = f[(i + f.size - 1) % f.size]; val cur = f[i]; val next = f[(i + 1) % f.size]
                out.faces += intArrayOf(cur, edgeIndex[key(cur, next)]!!, faceBase + fi, edgeIndex[key(prev, cur)]!!)
            }
        }
        return out
    }

    /** 같은 자리(허용 오차 [eps])의 정점을 하나로 — 거울·배열 뒤 이음매를 붙인다 */
    fun weld(eps: Float = 1e-4f): Mesh {
        val map = IntArray(pos.size)
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        val out = Mesh()
        fun cell(v: Float) = Math.round(v / eps)
        for ((i, p) in pos.withIndex()) {
            val c = Triple(cell(p.x), cell(p.y), cell(p.z))
            val hit = grid[c]?.firstOrNull { (out.pos[it] - p).len() <= eps }
            if (hit != null) map[i] = hit else { map[i] = out.pos.size; out.pos += p; grid.getOrPut(c) { ArrayList() } += map[i] }
        }
        for (f in faces) {
            val g = f.map { map[it] }.fold(ArrayList<Int>()) { acc, v -> if (acc.isEmpty() || acc.last() != v) acc += v; acc }
            if (g.size > 1 && g.first() == g.last()) g.removeAt(g.size - 1)
            if (g.size >= 3) out.faces += g.toIntArray()
        }
        return out
    }

    /** 정점 법선. 부드럽게면 공유 정점 평균, 각지게면 면마다 따로(정점 복제) */
    fun shaded(smooth: Boolean): Triple<FloatArray, FloatArray, IntArray> {
        val tri = triangles()
        if (smooth) {
            val acc = Array(pos.size) { V3.ZERO }
            faces.forEach { f -> val n = faceNormal(f); f.forEach { acc[it] = acc[it] + n } }
            val p = FloatArray(pos.size * 3); val n = FloatArray(pos.size * 3)
            pos.forEachIndexed { i, v -> p[i * 3] = v.x; p[i * 3 + 1] = v.y; p[i * 3 + 2] = v.z; val nn = acc[i].norm(); n[i * 3] = nn.x; n[i * 3 + 1] = nn.y; n[i * 3 + 2] = nn.z }
            return Triple(p, n, tri)
        }
        val p = FloatArray(tri.size * 3); val n = FloatArray(tri.size * 3)
        for (t in 0 until tri.size / 3) {
            val a = pos[tri[t * 3]]; val b = pos[tri[t * 3 + 1]]; val c = pos[tri[t * 3 + 2]]
            val nn = (b - a).cross(c - a).norm()
            for ((k, v) in listOf(a, b, c).withIndex()) { val o = (t * 3 + k) * 3; p[o] = v.x; p[o + 1] = v.y; p[o + 2] = v.z; n[o] = nn.x; n[o + 1] = nn.y; n[o + 2] = nn.z }
        }
        return Triple(p, n, IntArray(tri.size) { it })
    }

    companion object {
        fun near(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps
    }
}
