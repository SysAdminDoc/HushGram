/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A reel opened from a chat that has a Blend goes through the chat's shared reels before the Blend's
 * (#804): right after the viewer's source picker builds the Blend's source, the hook may build the
 * chat's own source around it, as the second side.
 */
class BlendAfterChatReelsTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BLEND_AFTER_CHAT is not in the extension: $declared", BLEND_AFTER_CHAT.substringAfter("->") in declared)
        for (bridge in listOf("viewerSource(Ljava/lang/Object;)Ljava/lang/Object;", "chatThread(Ljava/lang/Object;)Ljava/lang/String;")) {
            assertTrue("$bridge is not in the extension: $declared", bridge in declared)
        }
    }

    @Test
    fun eachDeclaredBuildKeepsAChatsReelsBeforeItsBlend() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                keeps(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildKeepsThemToo() {
        for (base in Fixtures.otherBuilds()) keeps(base, base.parentFile.name)
    }

    private fun keeps(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, DUAL_PAGING) + FixtureDex.classesLoading(bundle, CHAT_REELS_PATH) +
            FixtureDex.classesLoading(bundle, BLEND_FEED)).distinctBy { it.type }
        val pooled = FixtureDex.withStringPools(bundle, loading).let { (it + builderCallers(bundle, it)).distinctBy { c -> c.type } }
        val byType = pooled.associateBy { it.type }
        val blend = pooled.single { holder -> holder.methods.any { it.name == "<init>" && FixtureDex.loads(it, BLEND_FEED, byType) } }.type
        val pickers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == blend } }) { method ->
            method.code().any { it.opcode == Opcode.NEW_INSTANCE && it.text() == blend }
        }.map { it.definingClass }.toSet()
        val extra = FixtureDex.classes(bundle, pickers + setOf(VIEWER_CONFIG)).values
        val context = PatchContexts.of((pooled + extra).distinctBy { it.type } + ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS)))

        val end = context.findChatReelsEnd()
        val found = context.findBlendAfterChat(end.chat)
        val before = found.picker.code()
        context.keepChatReelsBeforeBlend(found)
        context.readChatViewers(found)
        readsViewers(context, found, label)

        val picker = context.mutableClassDefBy(found.picker.definingClass).methods.single { method ->
            method.name == found.picker.name && method.parameterTypes.map(Any::toString) == found.picker.parameterTypes.map(Any::toString)
        }
        val code = picker.code()
        val (a, b, c, d) = found.scratch
        assertEquals("$label: the Blend's constructor comes just before", blend, (code[found.after - 1].reference() as MethodReference).definingClass)
        assertEquals("$label: the config's copy", Opcode.MOVE_OBJECT_FROM16, code[found.after].opcode)
        assertEquals("$label: from the config", found.config, (code[found.after] as TwoRegisterInstruction).registerB)
        val ask = code.indexOfFirst { it.text() == BLEND_AFTER_CHAT }
        assertEquals("$label: one ask", 1, code.count { it.text() == BLEND_AFTER_CHAT })
        assertEquals("$label: asked with where it opened and the chat's id", listOf(b, c), (code[ask] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals("$label: the config's source", found.configSource, code[found.after + 1].text())
        assertEquals("$label: the chat's id", found.thread, code[found.after + 6].text())
        assertEquals("$label: the Blend's own chat id", found.blendThread, code[found.after + 9].text())
        assertEquals("$label: a Blend paging the chat itself keeps Instagram's", Opcode.IF_NEZ, code[found.after + 10].opcode)

        val made = code.indexOfLast { it.opcode == Opcode.NEW_INSTANCE && it.text() == end.chat }
        assertTrue("$label: the chat's source is made after the ask", made > ask)
        val sets = code.subList(made, made + 7).filter { it.opcode == Opcode.IPUT_OBJECT }.map { it.text() to (it as TwoRegisterInstruction).registerA }
        assertEquals(
            "$label: the chat's source takes the chat's id, the Blend's source and the position",
            listOf(found.chatThread to c, found.chatSecond to d, found.chatPosition to a), sets,
        )
        val swap = code[made + 6]
        assertEquals("$label: the Blend's register takes the chat's source", Opcode.MOVE_OBJECT_FROM16, swap.opcode)
        assertEquals("$label: into the Blend's register", found.blend, (swap as OneRegisterInstruction).registerA)
        assertEquals("$label: Instagram's own code follows", before[found.after].opcode, code[made + 8].opcode)
        assertEquals("$label: only the hook was added", before.size + 25, code.size)
        assertTrue("$label: scratch registers fit a 4-bit form", found.scratch.all { it <= 15 } && found.blend !in found.scratch)
    }

    /**
     * The bridges the chat reels hooks read a viewer's config through (#804): where it opened, and the
     * chat it holds, null for a config holding no chat's data, so a Blend opened outside a chat is no
     * chat's viewer.
     */
    private fun readsViewers(context: BytecodePatchContext, found: BlendSites, label: String) {
        val bridges = context.mutableClassDefBy(CHAT_REELS).methods.groupBy { it.name }
        val source = bridges.getValue("viewerSource").single().code()
        assertEquals("$label: where the viewer opened", listOf(VIEWER_CONFIG, found.configSource), source.take(2).map { it.text() })
        assertEquals("$label: in the config's register", listOf(0, 0), (source[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$label: answered", Opcode.RETURN_OBJECT, source[2].opcode)

        val thread = bridges.getValue("chatThread").single().code()
        assertEquals(
            "$label: the chat's data, then its id",
            listOf(VIEWER_CONFIG, found.direct, null, null, null, found.thread, null), thread.take(7).map { it.text() },
        )
        assertEquals(
            "$label: a config holding no chat's data answers null",
            listOf(Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN_OBJECT), thread.subList(2, 5).map { it.opcode },
        )
        assertEquals("$label: null", 0L, (thread[3] as WideLiteralInstruction).wideLiteral)
        val address = IntArray(thread.size + 1)
        thread.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        assertEquals("$label: the chat's data goes on to its id", 5, address.indexOf(address[2] + (thread[2] as OffsetInstruction).codeOffset))
        assertEquals("$label: answered", Opcode.RETURN_OBJECT, thread[6].opcode)
    }

    /** Classes asking a static method of [pooled] that loads [CHAT_REELS_PATH], the chat's and the Blend's sources on 456. */
    private fun builderCallers(bundle: File, pooled: List<ClassDef>): Collection<ClassDef> {
        val byType = pooled.associateBy { it.type }
        val builders = pooled.flatMap { it.methods }.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                !(method.parameterTypes.map(Any::toString) == listOf("I") && method.returnType == "Ljava/lang/String;") &&
                FixtureDex.loads(method, CHAT_REELS_PATH, byType)
        }.map { it.signature() }.toSet()
        if (builders.isEmpty()) return emptyList()
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.signature() in builders } }) { method ->
            method.implementation?.instructions?.any { (it.reference() as? MethodReference)?.signature() in builders } == true
        }.map { it.definingClass }.toSet()
        return FixtureDex.classes(bundle, callers).values
    }

    private fun MethodReference.signature(): String =
        "$definingClass->$name(${parameterTypes.joinToString("") { it.toString() }})$returnType"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.text(): String? = reference()?.let { (it as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string ?: it.toString() }
}
