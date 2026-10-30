/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The builds of Instagram 453.0.0.57.79 the clone patch is held to, read straight from the fixture folder. */
internal object CloneFixtures {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /** Each build by its version code: the declared one's bundle and the other builds' base APKs. */
    fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val all = (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }
        assertEquals("every build of Instagram ${Fixtures.VERSION} has a fixture", Fixtures.BUILDS, all.size)
        return all.toSortedMap()
    }

    /** The build's AndroidManifest.xml as Manager hands a patch the decoded one: android: names, plain values. */
    fun manifest(build: File): Document {
        val binary = if (build.extension == "apk") {
            ZipFile(build).use { apk -> apk.getInputStream(apk.getEntry("AndroidManifest.xml")).use { it.readBytes() } }
        } else {
            ZipFile(build).use { bundle ->
                ZipInputStream(bundle.getInputStream(bundle.getEntry("base.apk")).buffered()).use { apk ->
                    generateSequence { apk.nextEntry }.first { it.name == "AndroidManifest.xml" }
                    apk.readBytes()
                }
            }
        }
        return decode(binary)
    }

    private fun decode(binary: ByteArray): Document {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(binary))
        val open = ArrayDeque<Element>()
        while (true) {
            when (parser.next()) {
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    val element = document.createElement(parser.name)
                    for (at in 0 until parser.attributeCount) {
                        val prefix = when (parser.getAttributeNamespace(at).orEmpty()) {
                            "" -> ""
                            ANDROID -> "android:"
                            else -> "other:"
                        }
                        val value = when (parser.getAttributeValueType(at)) {
                            AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(at)
                            AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(at).toString()
                            AndroidBinXmlParser.VALUE_TYPE_BOOLEAN -> parser.getAttributeBooleanValue(at).toString()
                            AndroidBinXmlParser.VALUE_TYPE_REFERENCE ->
                                runCatching { "@0x" + Integer.toHexString(parser.getAttributeIntValue(at)) }.getOrDefault("@reference")
                            else -> "?"
                        }
                        element.setAttribute(prefix + parser.getAttributeName(at), value)
                    }
                    (open.lastOrNull() ?: document).appendChild(element)
                    open.addLast(element)
                }
                AndroidBinXmlParser.EVENT_END_ELEMENT -> open.removeLast()
                AndroidBinXmlParser.EVENT_END_DOCUMENT -> return document
            }
        }
    }

    private fun Instruction.string(): String? =
        if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
            ((this as ReferenceInstruction).reference as StringReference).string
        } else {
            null
        }

    /** The numbers a static `(int)String` pool answers with a string [wanted] takes; the first switch that loads one decides. */
    private fun poolNumbers(method: Method, wanted: (String) -> Boolean): Set<Int> {
        if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Ljava/lang/String;" ||
            method.parameterTypes.map(Any::toString) != listOf("I")
        ) {
            return emptySet()
        }
        val code = method.implementation?.instructions?.toList() ?: return emptySet()
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        val at = (0 until code.size).associateBy { address[it] }
        val answered = HashMap<Int, String>()
        for (switch in code.indices) {
            if (code[switch].opcode != Opcode.PACKED_SWITCH && code[switch].opcode != Opcode.SPARSE_SWITCH) continue
            val payload = at[address[switch] + (code[switch] as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload ?: continue
            for (case in payload.switchElements) {
                if (case.key in answered) continue
                at[address[switch] + case.offset]?.let { code[it].string() }?.let { answered[case.key] = it }
            }
        }
        return answered.filterValues(wanted).keys
    }

    /**
     * Every class of [build] that loads Instagram's package name or one of [authorities], itself or
     * by asking a string pool for it, with the pools. That's every class the patch reads.
     */
    fun classes(build: File, authorities: List<String>): List<ClassDef> {
        val named: (String) -> Boolean = { text ->
            text == INSTAGRAM || authorities.any { text == it || text == "content://$it" || text.startsWith("content://$it/") }
        }
        val found = LinkedHashMap<String, ClassDef>()
        val asked = HashMap<String, Set<Int>>()
        FixtureDex.forEach(build) { dex ->
            if (dex.stringSection.none(named)) return@forEach
            for (classDef in dex.classes) {
                var holds = false
                for (method in classDef.methods) {
                    val code = method.implementation?.instructions ?: continue
                    if (code.none { instruction -> instruction.string()?.let(named) == true }) continue
                    holds = true
                    poolNumbers(method, named).takeIf { it.isNotEmpty() }?.let { asked["${classDef.type}->${method.name}"] = it }
                }
                if (holds) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        FixtureDex.forEach(build) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in found) continue
                val asks = classDef.methods.any { method ->
                    val code = method.implementation?.instructions?.toList() ?: return@any false
                    code.indices.any { at ->
                        val pool = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
                        val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction
                        code[at].opcode == Opcode.INVOKE_STATIC && pool != null && number != null &&
                            asked["${pool.definingClass}->${pool.name}"]?.contains(number.narrowLiteral) == true
                    }
                }
                if (asks) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return found.values.toList()
    }
}
