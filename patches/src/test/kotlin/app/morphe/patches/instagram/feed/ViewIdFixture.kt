/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Whether a vendor Instagram build declares view ids by name, for the patches that change no
 * Instagram code and find their views at runtime by the ids Instagram's layouts give them. A name
 * counts only as the whole name of an `id` entry in the resource table, the way
 * Resources.getIdentifier(name, "id", package) finds it: a piece of a longer id, or the name of a
 * layout or a string, doesn't.
 */
internal object ViewIdFixture {
    private const val STRING_POOL = 0x0001
    private const val TABLE = 0x0002
    private const val PACKAGE = 0x0200
    private const val TYPE = 0x0201
    private const val UTF8 = 0x100
    private const val SPARSE = 0x01
    private const val OFFSET16 = 0x02
    private const val COMPACT = 0x0008

    /** The resource table of the build's base APK, read from a bundle or from a plain APK. */
    fun resourceTable(bundle: File): ByteArray {
        if (bundle.extension == "apk") {
            ZipFile(bundle).use { apk ->
                val entry = checkNotNull(apk.getEntry("resources.arsc")) { "${bundle.name} holds no resources.arsc" }
                return apk.getInputStream(entry).use { it.readBytes() }
            }
        }
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (entry.name == "resources.arsc") return apk.readBytes()
                }
            }
        }
        error("${bundle.name} holds no resources.arsc")
    }

    /** The names in [names] the resource table [table] doesn't declare as ids. */
    fun missing(table: ByteArray, names: List<String>): List<String> {
        val declared = ids(table)
        return names.filterNot { it in declared }
    }

    /** The name of every `id` entry in [table], across its packages. */
    private fun ids(table: ByteArray): Set<String> {
        val buffer = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        check(buffer.u16(0) == TABLE) { "not a resource table" }
        val ids = HashSet<String>()
        var chunk = buffer.u16(2)
        while (chunk < table.size) {
            if (buffer.u16(chunk) == PACKAGE) ids += packageIds(buffer, chunk)
            chunk += buffer.getInt(chunk + 4)
        }
        return ids
    }

    /** A package chunk's `id` names: its type chunks for the type named "id", each entry's key from the package's key strings. */
    private fun packageIds(buffer: ByteBuffer, start: Int): Set<String> {
        val end = start + buffer.getInt(start + 4)
        // After the chunk header, the package id and its 128-character name: where the type names and the keys are.
        val typeNames = strings(buffer, start + buffer.getInt(start + 268))
        val keys = strings(buffer, start + buffer.getInt(start + 276))
        val ids = HashSet<String>()
        var chunk = start + buffer.u16(start + 2)
        while (chunk < end) {
            if (buffer.u16(chunk) == TYPE && typeNames[buffer.u8(chunk + 8) - 1] == "id") {
                for (key in entryKeys(buffer, chunk)) ids += keys[key]
            }
            chunk += buffer.getInt(chunk + 4)
        }
        return ids
    }

    /** The key index of each entry a type chunk holds, in its sparse, 16-bit or 32-bit offset form. */
    private fun entryKeys(buffer: ByteBuffer, start: Int): List<Int> {
        val flags = buffer.u8(start + 9)
        val count = buffer.getInt(start + 12)
        val entries = start + buffer.getInt(start + 16)
        val offsets = start + buffer.u16(start + 2)
        return (0 until count).mapNotNull { index ->
            when {
                flags and SPARSE != 0 -> buffer.u16(offsets + index * 4 + 2) * 4
                flags and OFFSET16 != 0 -> buffer.u16(offsets + index * 2).takeIf { it != 0xFFFF }?.times(4)
                else -> buffer.getInt(offsets + index * 4).takeIf { it != -1 }
            }
        }.map { offset ->
            val entry = entries + offset
            if (buffer.u16(entry + 2) and COMPACT != 0) buffer.u16(entry) else buffer.getInt(entry + 4)
        }
    }

    /** Every string of the string pool chunk at [start], in order. */
    private fun strings(buffer: ByteBuffer, start: Int): List<String> {
        check(buffer.u16(start) == STRING_POOL) { "no string pool at $start" }
        val offsets = start + buffer.u16(start + 2)
        val count = buffer.getInt(start + 8)
        val utf8 = buffer.getInt(start + 16) and UTF8 != 0
        val data = start + buffer.getInt(start + 20)
        return List(count) { index ->
            var at = data + buffer.getInt(offsets + index * 4)
            if (utf8) {
                // The length in characters, then in bytes, each one byte or two with the high bit set.
                at += if (buffer.u8(at) and 0x80 != 0) 2 else 1
                var length = buffer.u8(at)
                if (length and 0x80 != 0) {
                    length = ((length and 0x7F) shl 8) or buffer.u8(at + 1)
                    at += 2
                } else {
                    at += 1
                }
                String(buffer.array(), at, length, Charsets.UTF_8)
            } else {
                var length = buffer.u16(at)
                if (length and 0x8000 != 0) {
                    length = ((length and 0x7FFF) shl 16) or buffer.u16(at + 2)
                    at += 4
                } else {
                    at += 2
                }
                String(buffer.array(), at, length * 2, Charsets.UTF_16LE)
            }
        }
    }

    private fun ByteBuffer.u8(at: Int): Int = get(at).toInt() and 0xFF

    private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xFFFF
}
