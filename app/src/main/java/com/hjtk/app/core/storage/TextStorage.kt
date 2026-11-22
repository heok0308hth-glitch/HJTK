package com.hjtk.app.core.storage

import kotlinx.serialization.json.Json
import java.io.File

/** Where a store keeps its JSON. Swapped for an in-memory one in unit tests. */
interface TextStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    /** Append one line; used by the write-ahead journal. */
    fun append(name: String, line: String)
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
        f.appendText(line + "\n")
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
