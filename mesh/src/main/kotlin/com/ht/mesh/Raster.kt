package com.ht.mesh

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** 궤도 카메라: 중심점을 보며 좌우(yaw)·위아래(pitch)·거리로 돈다 */
data class Camera(val target: V3 = V3(0f, 0.5f, 0f), val yaw: Float = 35f, val pitch: Float = 22f, val dist: Float = 6f, val fov: Float = 40f) {
    fun eye(): V3 {
        val y = yaw * PI.toFloat() / 180f; val p = pitch * PI.toFloat() / 180f
        return target + V3(dist * cos(p) * sin(y), dist * sin(p), dist * cos(p) * cos(y))
    }
    fun orbit(dYaw: Float, dPitch: Float) = copy(yaw = yaw + dYaw, pitch = (pitch + dPitch).coerceIn(-85f, 85f))
    fun zoom(f: Float) = copy(dist = (dist / f).coerceIn(0.3f, 400f))
    /** 화면 기준으로 중심점을 옮긴다(dx, dy는 화면 높이에 대한 비율) */
    fun pan(dx: Float, dy: Float): Camera {
        val f = (target - eye()).norm(); val r = f.cross(V3(0f, 1f, 0f)).norm(); val u = r.cross(f).norm()
        val k = dist * 2f * tan(fov * PI.toFloat() / 360f)
        return copy(target = target - r * (dx * k) + u * (dy * k))
    }
    companion object {
        /** 장면 전체가 보이게 */
        fun frame(s: Scene, yaw: Float = 35f, pitch: Float = 22f): Camera {
            val boxes = s.nodes.map { n -> n.mesh().transformed(s.world(n)).bounds() }
            if (boxes.isEmpty()) return Camera()
            val lo = boxes.map { it.first }.reduce { a, b -> V3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)) }
            val hi = boxes.map { it.second }.reduce { a, b -> V3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)) }
            val c = (lo + hi) * 0.5f; val r = ((hi - lo).len() * 0.5f).coerceAtLeast(0.25f)
            return Camera(c, yaw, pitch, r / sin(20f * PI.toFloat() / 180f) * 1.08f)
        }
    }
}

/**
 * 소프트웨어 래스터라이저 — 블렌더 Workbench 같은 '형태를 읽는' 솔리드 셰이딩.
 * GPU·드라이버에 기대지 않아 어떤 폰에서도 같은 그림이 나오고, JVM 시험에서도 실제 화면을 PNG로 확인할 수 있다.
 * 픽셀마다 어느 오브젝트인지(id) 기록해 탭 한 번으로 고르기와 선택 윤곽선을 만든다.
 */
class Raster(val w: Int, val h: Int) {
    val color = IntArray(w * h)
    val ids = IntArray(w * h)
    private val depth = FloatArray(w * h)

    data class Look(val bgTop: Int, val bgBottom: Int, val grid: Int, val accent: Int)

    fun render(scene: Scene, cam: Camera, look: Look, selected: Int = -1, grid: Boolean = true): Raster {
        for (y in 0 until h) { val c = mix(look.bgTop, look.bgBottom, y / (h - 1f).coerceAtLeast(1f)); for (x in 0 until w) color[y * w + x] = c }
        depth.fill(Float.MAX_VALUE); ids.fill(-1)
        val eye = cam.eye()
        val fwd = (cam.target - eye).norm(); val right = fwd.cross(V3(0f, 1f, 0f)).norm(); val up = right.cross(fwd).norm()
        val f = 1f / tan(cam.fov * PI.toFloat() / 360f)
        val aspect = w.toFloat() / h
        val near = 0.02f
        // 빛: 카메라를 따라오는 주광 + 위에서 내리는 보조광 + 테두리광(형태 윤곽을 살림)
        val key = (right * -0.35f + up * 0.75f - fwd * 0.55f).norm()
        val fill = V3(0.3f, 1f, 0.2f).norm()

        scene.nodes.forEachIndexed { ni, n ->
            val world = scene.world(n)
            val mesh = n.mesh().transformed(world)
            val (p, nr, idx) = mesh.shaded(n.smoothShading())
            val mat = scene.material(n)
            val base = mat.rgb()
            val sel = ni == selected
            val vc = IntArray(p.size / 3)
            val vv = FloatArray(p.size)                     // 카메라 좌표(x, y, -z=앞)
            for (i in 0 until p.size / 3) {
                val pw = V3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]); val nw = V3(nr[i * 3], nr[i * 3 + 1], nr[i * 3 + 2])
                val d = pw - eye
                vv[i * 3] = d.dot(right); vv[i * 3 + 1] = d.dot(up); vv[i * 3 + 2] = d.dot(fwd)
                val view = (eye - pw).norm()
                val nd = if (nw.dot(view) < 0) nw * -1f else nw     // 뒷면이 보여도 밝게(열린 메시)
                val diff = 0.22f + 0.62f * max(0f, nd.dot(key)) + 0.22f * max(0f, nd.dot(fill))
                val rim = (1f - max(0f, nd.dot(view))).pow(3) * 0.35f
                val half = (key + view).norm()
                val spec = max(0f, nd.dot(half)).pow(8f + (1f - mat.rough) * 120f) * (0.08f + (1f - mat.rough) * 0.5f) * (0.6f + mat.metal)
                val dm = 1f - mat.metal * 0.55f
                var r = base[0] * diff * dm + spec + rim * 0.6f; var g = base[1] * diff * dm + spec + rim * 0.6f; var b = base[2] * diff * dm + spec + rim * 0.6f
                if (sel) { val a = 0.10f; r = r * (1 - a) + ((look.accent shr 16) and 255) / 255f * a; g = g * (1 - a) + ((look.accent shr 8) and 255) / 255f * a; b = b * (1 - a) + (look.accent and 255) / 255f * a }
                vc[i] = rgb(tone(r), tone(g), tone(b))
            }
            for (t in 0 until idx.size / 3) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                clipAndDraw(vv, vc, intArrayOf(a, b, c), near, f, aspect, ni)
            }
        }
        if (grid) drawGrid(cam, eye, right, up, fwd, f, aspect, near, look.grid)
        if (selected >= 0) outline(selected, look.accent)
        return this
    }

    private fun clipAndDraw(v: FloatArray, c: IntArray, tri: IntArray, near: Float, f: Float, aspect: Float, id: Int) {
        // 가까운 면 자르기(서덜랜드-호지먼): 카메라 뒤로 넘어간 부분을 잘라 낸다
        val poly = ArrayList<FloatArray>(4)
        for (i in tri) poly += floatArrayOf(v[i * 3], v[i * 3 + 1], v[i * 3 + 2], ((c[i] shr 16) and 255).toFloat(), ((c[i] shr 8) and 255).toFloat(), (c[i] and 255).toFloat())
        val clipped = ArrayList<FloatArray>(5)
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val ina = a[2] >= near; val inb = b[2] >= near
            if (ina) clipped += a
            if (ina != inb) { val t = (near - a[2]) / (b[2] - a[2]); clipped += FloatArray(6) { a[it] + (b[it] - a[it]) * t } }
        }
        if (clipped.size < 3) return
        val s = clipped.map { q -> floatArrayOf((q[0] / q[2] * f / aspect * 0.5f + 0.5f) * w, (0.5f - q[1] / q[2] * f * 0.5f) * h, q[2], q[3], q[4], q[5]) }
        for (i in 1 until s.size - 1) tri(s[0], s[i], s[i + 1], id)
    }

    private fun tri(a: FloatArray, b: FloatArray, c: FloatArray, id: Int) {
        val area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        if (abs(area) < 1e-6f) return
        val x0 = max(0, min(a[0], min(b[0], c[0])).toInt()); val x1 = min(w - 1, max(a[0], max(b[0], c[0])).toInt() + 1)
        val y0 = max(0, min(a[1], min(b[1], c[1])).toInt()); val y1 = min(h - 1, max(a[1], max(b[1], c[1])).toInt() + 1)
        if (x0 > x1 || y0 > y1) return
        val iz0 = 1f / a[2]; val iz1 = 1f / b[2]; val iz2 = 1f / c[2]
        val inv = 1f / area
        for (y in y0..y1) {
            val py = y + 0.5f
            for (x in x0..x1) {
                val px = x + 0.5f
                val w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) * inv
                val w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val iz = w0 * iz0 + w1 * iz1 + w2 * iz2
                val z = 1f / iz
                val k = y * w + x
                if (z >= depth[k]) continue
                depth[k] = z; ids[k] = id
                // 원근 보정 보간
                val q0 = w0 * iz0 * z; val q1 = w1 * iz1 * z; val q2 = w2 * iz2 * z
                color[k] = rgb((a[3] * q0 + b[3] * q1 + c[3] * q2) / 255f, (a[4] * q0 + b[4] * q1 + c[4] * q2) / 255f, (a[5] * q0 + b[5] * q1 + c[5] * q2) / 255f)
            }
        }
    }

    private fun drawGrid(cam: Camera, eye: V3, right: V3, up: V3, fwd: V3, f: Float, aspect: Float, near: Float, ink: Int) {
        val step = when { cam.dist > 60 -> 10f; cam.dist > 12 -> 2f; cam.dist < 2 -> 0.25f; else -> 1f }
        val n = 8
        val cx = Math.round(cam.target.x / step) * step; val cz = Math.round(cam.target.z / step) * step
        for (i in -n..n) {
            val axis = abs(cx + i * step) < 1e-4f
            line(cam, V3(cx + i * step, 0f, cz - n * step), V3(cx + i * step, 0f, cz + n * step), eye, right, up, fwd, f, aspect, near, ink, if (abs(cx + i * step) < 1e-4f) 0.55f else 0.22f, axis)
            line(cam, V3(cx - n * step, 0f, cz + i * step), V3(cx + n * step, 0f, cz + i * step), eye, right, up, fwd, f, aspect, near, ink, if (abs(cz + i * step) < 1e-4f) 0.55f else 0.22f, false)
        }
    }

    private fun line(cam: Camera, p0: V3, p1: V3, eye: V3, right: V3, up: V3, fwd: V3, f: Float, aspect: Float, near: Float, ink: Int, alpha: Float, @Suppress("UNUSED_PARAMETER") axis: Boolean) {
        fun cam(p: V3) = (p - eye).let { floatArrayOf(it.dot(right), it.dot(up), it.dot(fwd)) }
        var a = cam(p0); var b = cam(p1)
        if (a[2] < near && b[2] < near) return
        if (a[2] < near) { val t = (near - a[2]) / (b[2] - a[2]); a = FloatArray(3) { a[it] + (b[it] - a[it]) * t } }
        if (b[2] < near) { val t = (near - b[2]) / (a[2] - b[2]); b = FloatArray(3) { b[it] + (a[it] - b[it]) * t } }
        val ax = (a[0] / a[2] * f / aspect * 0.5f + 0.5f) * w; val ay = (0.5f - a[1] / a[2] * f * 0.5f) * h
        val bx = (b[0] / b[2] * f / aspect * 0.5f + 0.5f) * w; val by = (0.5f - b[1] / b[2] * f * 0.5f) * h
        val steps = max(abs(bx - ax), abs(by - ay)).toInt().coerceIn(1, 4 * (w + h))
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val x = (ax + (bx - ax) * t).toInt(); val y = (ay + (by - ay) * t).toInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val z = 1f / ((1f / a[2]) * (1 - t) + (1f / b[2]) * t)
            val k = y * w + x
            if (z > depth[k] + 1e-3f) continue
            val fade = alpha * (1f - (z / (cam.dist * 3f)).coerceIn(0f, 1f))
            color[k] = mix(color[k], ink, fade)
        }
    }

    /** 선택한 오브젝트 둘레에 강조색 윤곽선(블렌더의 주황 테두리처럼) */
    private fun outline(id: Int, accent: Int) {
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val k = y * w + x
            if (ids[k] != id) continue
            if (ids[k - 1] != id || ids[k + 1] != id || ids[k - w] != id || ids[k + w] != id) edge[k] = true
        }
        for (k in edge.indices) if (edge[k]) color[k] = accent or (0xFF shl 24)
    }

    /** 화면 좌표의 오브젝트 번호(없으면 -1) — 손가락 크기만큼 둘레도 본다 */
    fun pick(x: Int, y: Int, radius: Int = 6): Int {
        if (x in 0 until w && y in 0 until h && ids[y * w + x] >= 0) return ids[y * w + x]
        for (r in 1..radius) for (dy in -r..r) for (dx in -r..r) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && ids[yy * w + xx] >= 0) return ids[yy * w + xx]
        }
        return -1
    }

    fun png(): ByteArray = Png.encode(w, h, color)

    companion object {
        val DARK = Look(0xFF16171A.toInt(), 0xFF0B0C0E.toInt(), 0xFFE9E6DF.toInt(), 0xFFE4572E.toInt())
        val PAPER = Look(0xFFF4F1EA.toInt(), 0xFFE7E2D8.toInt(), 0xFF2A2A2A.toInt(), 0xFFD2462D.toInt())

        private fun tone(v: Float) = (v / (1f + v * 0.25f) * 1.18f).coerceIn(0f, 1f)
        fun rgb(r: Float, g: Float, b: Float) = (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** 의존성 없는 PNG 인코더(안드로이드 단위 시험에는 AWT가 없다) */
object Png {
    fun encode(w: Int, h: Int, argb: IntArray): ByteArray {
        val raw = ByteArray((w * 3 + 1) * h)
        var o = 0
        for (y in 0 until h) { raw[o++] = 0; for (x in 0 until w) { val c = argb[y * w + x]; raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte() } }
        val def = Deflater(6); def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(int(data.size)); val t = type.toByteArray(); out.write(t); out.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); out.write(int(crc.value.toInt()))
        }
        chunk("IHDR", int(w) + int(h) + byteArrayOf(8, 2, 0, 0, 0))
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
