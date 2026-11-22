package com.hjtk.app.core.storage

import kotlinx.serialization.json.Json
import java.io.File

/** Where a store keeps its JSON. Swapped for an in-memory one in unit tests. */
interface TextStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    /** Append one line; used by the write-ahead journal. */
    fun append(name: String, line: String)
    /** Cut the file back to [bytes]; used to drop a torn final line. */
    fun truncate(name: String, bytes: Long)
    fun delete(name: String)
}

class FileTextStorage(private val dir: File) : TextStorage {
    init {
        if (!dir.exists()) dir.mkdirs()
    }

    override fun read(name: String): String? {
        val f = File(dir, name)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    override fun write(name: String, text: String) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        // Write then rename so a process death never leaves half a JSON file.
        tmp.writeText(text)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    override fun append(name: String, line: String) {
        val f = File(dir, name)
        // fsync: a journal line that reached the page cache but not the disk is
        // exactly the record we need after a crash.
        java.io.FileOutputStream(f, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    override fun truncate(name: String, bytes: Long) {
        val f = File(dir, name)
        if (!f.exists()) return
        java.io.RandomAccessFile(f, "rw").use { raf ->
            if (bytes < raf.length()) raf.setLength(bytes)
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

class MemoryTextStorage : TextStorage {
    private val map = LinkedHashMap<String, String>()
    override fun read(name: String): String? = map[name]
    override fun write(name: String, text: String) {
        map[name] = text
    }

    override fun append(name: String, line: String) {
        map[name] = (map[name] ?: "") + line + "\n"
    }

    override fun truncate(name: String, bytes: Long) {
        val current = map[name] ?: return
        val cut = current.toByteArray(Charsets.UTF_8).take(bytes.toInt().coerceIn(0, current.length)).toByteArray()
        map[name] = String(cut, Charsets.UTF_8)
    }

    override fun delete(name: String) {
        map.remove(name)
    }
}

object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = false
    }

    val pretty: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = true
    }
}
