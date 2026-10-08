/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.keep

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep in chat: the parser of a photo or video message's media hands the view mode it read to the
 * extension before storing it. Anything the patch can't tell apart fails it before an instruction changes.
 */
class KeepInChatHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(KEEP_IN_CHAT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("viewMode is not in the extension: $declared", VIEW_MODE_HOOK.substringAfter("->") in declared)
    }

    @Test
    fun theViewModePassesThroughTheExtension() {
        val context = PatchContexts.of(listOf(parser()))
        val (parse, store) = context.findViewModeStore()
        keepViewModeInChat(parse, store)
        assertHooked("stand-in", context, PARSER)
    }

    @Test
    fun aMissingOrDoubledParserFailsThePatch() {
        refuses("found none", listOf(parser(keys = VISUAL_MEDIA_KEYS.drop(1))))
        refuses("Lfixture/OtherMedia", listOf(parser(), parser(type = "Lfixture/OtherMediaParser;")))
    }

    @Test
    fun aStoreItCantTellApartFailsThePatch() {
        refuses("doesn't load", listOf(parser(twice = true)))
        refuses("stores no view mode", listOf(parser(store = false)))
        refuses("isn't stored as text", listOf(parser(fieldType = "Ljava/lang/Object;")))
        refuses("not the media the parser builds", listOf(parser(fieldClass = "Lfixture/Other;")))
        refuses("2 times", listOf(parser(storeAgain = true)))
        refuses("jumps straight", listOf(parser(jump = true)))
    }

    /** In each declared build: the media parser's one view mode store, through the extension first. */
    @Test
    fun eachDeclaredBuildKeepsTheViewMode() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, "url_expire_at_secs"))
                val (parse, store) = context.findViewModeStore()
                keepViewModeInChat(parse, store)
                assertHooked(bundle.name, context, parse.definingClass)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val (parse, store) = context.findViewModeStore()
            keepViewModeInChat(parse, store)
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The parser loads "view_mode", and its store of the mode follows the hook's call and answer on the same register. */
    private fun assertHooked(what: String, context: BytecodePatchContext, parser: String) {
        val calls = context.mutableClassDefBy(parser).methods.sumOf { method ->
            method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        }
        assertEquals("$what: view mode calls in the parser", 1, calls)
        val code = context.mutableClassDefBy(parser).methods.single { method ->
            method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        }.instructions()
        val call = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        val store = code[call + 2]
        assertTrue("$what: the hook comes after the key", code.take(call).any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == VIEW_MODE })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the store", Opcode.IPUT_OBJECT, store.opcode)
        assertEquals("$what: the store's type", "Ljava/lang/String;", ((store as ReferenceInstruction).reference as FieldReference).type)
        val mode = (store as TwoRegisterInstruction).registerA
        assertEquals("$what: the answer's register", mode, (code[call + 1] as OneRegisterInstruction).registerA)
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val PARSER = "Lfixture/MediaParser;"
        const val MEDIA = "Lfixture/Media;"

        /** Shaped like Instagram's parser: builds the media, loads its keys, then reads the view mode into v0 and stores it. */
        fun parser(
            type: String = PARSER,
            keys: List<String> = VISUAL_MEDIA_KEYS,
            twice: Boolean = false,
            store: Boolean = true,
            fieldType: String = "Ljava/lang/String;",
            fieldClass: String = MEDIA,
            storeAgain: Boolean = false,
            jump: Boolean = false,
        ): ClassDef {
            val field = ImmutableFieldReference(fieldClass, "mode", fieldType)
            val code = mutableListOf<Instruction>(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(MEDIA)))
            for (key in keys.reversed() + (if (twice) listOf(VIEW_MODE) else emptyList())) {
                code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key))
            }
            // A jump back to the store, from past the return, to show the patch won't put the hook where a jump skips it.
            code += ImmutableInstruction10x(Opcode.NOP)
            if (store) code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 2, field)
            if (storeAgain) code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 2, field)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)
            if (jump) code += ImmutableInstruction10t(Opcode.GOTO, -3)
            val parse = ImmutableMethod(
                type, "parse", listOf(ImmutableMethodParameter("Lfixture/Reader;", null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(5, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(parse))
        }
    }
}
