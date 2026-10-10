/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.ghost

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.direct.seen.visualString
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ghost mode from the inbox: the New message button in the inbox's top bar gets the extension's
 * long press, put in its configuration right after its tap listener, on every declared 450 build
 * and the other builds of that version. The button's tap, and every other method, stay as they were.
 */
class GhostModeEntryHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(GHOST_LONG_PRESS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$GHOST_LONG_PRESS is not in the extension: $declared", GHOST_LONG_PRESS.substringAfter("->") in declared)
    }

    /** With no tap to find, the search says so, and the patch goes on without the shortcut and changes nothing. */
    @Test
    fun aBuildWithoutTheNewMessageTapKeepsEverythingAsItWas() {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findGhostEntries() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("expected one method that logs $NEW_MESSAGE_TAPPED, found 0"))
        assertFalse(PatchContexts.of(emptyList()).addGhostModeEntry())
    }

    /** The tap alone, with no inbox header controller beside it, is not enough to hook anything. */
    @Test
    fun aTapWithoutTheInboxHeaderControllerFailsTheSearch() {
        val tap = ImmutableClassDef(
            "Lfixture/Tap;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    "Lfixture/Tap;", "A08", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(
                        1,
                        listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(NEW_MESSAGE_TAPPED)), ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null, null,
                    ),
                ),
            ),
        )
        val context = PatchContexts.of(listOf(tap))
        val refusal = assertThrows(PatchException::class.java) { context.findGhostEntries() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("expected one inbox header controller"))
        assertFalse(context.addGhostModeEntry())
    }

    /** On each build: the one place found, the three instructions written, and nothing else touched. */
    @Test
    fun eachBuildOfADeclaredVersionGetsTheLongPressOnNewMessage() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        for (bundle in bundles + others) checkBuild(bundle)
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
    }

    private fun checkBuild(bundle: File) {
        val classes = slice(bundle)
        val context = PatchContexts.of(classes)
        val entries = context.findGhostEntries()
        val before = classes.associate { it.type to snapshot(it) }
        assertEquals(bundle.name, entries.size, entries.map { "${it.type}->${it.name}" }.distinct().size)

        val hooked = entries.map { "${it.type}->${it.name}(${it.parameters.joinToString("")})V" }.toSet()
        assertTrue(bundle.name, context.addGhostModeEntry())
        for (found in entries) checkEntry(bundle, context, found, before, hooked)
    }

    private fun checkEntry(
        bundle: File, context: BytecodePatchContext, found: GhostEntry,
        before: Map<String, Map<String, List<Triple<Opcode, String?, List<Int>>>>>,
        hooked: Set<String>,
    ) {
        val signature = "${found.type}->${found.name}(${found.parameters.joinToString("")})V"

        val builder = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val code = builder.visualCode()
        val at = found.insertAt
        val where = "${bundle.parentFile.name}/${bundle.name} $signature"
        // The button's tap listener goes into its configuration, and the hook comes right after.
        val tapStore = code[at - 1]
        assertEquals("$where: the tap listener's store", Opcode.IPUT_OBJECT, tapStore.opcode)
        assertEquals("$where: stored in a View.OnClickListener field", "Landroid/view/View\$OnClickListener;",
            (tapStore.visualReference() as FieldReference).type)
        assertEquals("$where: the configuration", found.config, tapStore.namedRegisters()[1])
        assertEquals("$where: the call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$where: the hook", GHOST_LONG_PRESS, code[at].visualReference().toString())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$where: the answer's register", listOf(found.free), code[at + 1].namedRegisters())
        assertEquals("$where: the store", Opcode.IPUT_OBJECT, code[at + 2].opcode)
        assertEquals("$where: the registers", listOf(found.free, found.config), code[at + 2].namedRegisters())
        assertEquals("$where: the field", found.longPress, code[at + 2].visualReference().toString())
        assertTrue("$where: a long press field", found.longPress.endsWith(":Landroid/view/View\$OnLongClickListener;"))
        assertTrue("$where: the free register is not the configuration", found.free != found.config && found.free <= 15)
        assertEquals("$where: hooks", 1, code.count { it.visualReference().toString() == GHOST_LONG_PRESS })

        // Only that builder changed, and only by those three instructions.
        for ((type, original) in before) {
            val now = snapshot(context.mutableClassDefBy(type))
            for ((method, instructions) in original) {
                if (method == signature) {
                    val after = now.getValue(method)
                    assertEquals("$where: instructions added", instructions.size + 3, after.size)
                    assertEquals("$where: before the hook", instructions.take(at), after.take(at))
                    assertEquals("$where: after the hook", instructions.drop(at), after.drop(at + 3))
                } else if (method !in hooked) {
                    assertEquals("$where: $method changed", instructions, now.getValue(method))
                }
            }
        }
    }

    private companion object {
        const val CLICK = "Landroid/view/View\$OnClickListener;"

        fun snapshot(classDef: ClassDef) = classDef.methods.associate { method ->
            method.toString() to method.visualCode().map { Triple(it.opcode, it.visualReference()?.toString(), it.namedRegisters()) }
        }

        /**
         * What the search reads in a build: the class logging the New message tap, the inbox header
         * controller, the tap listener classes that call into it, the classes that make a tap
         * listener, and the classes those store into.
         */
        fun slice(bundle: File): List<ClassDef> {
            val kept = LinkedHashMap<String, ClassDef>()
            val anchors = setOf(NEW_MESSAGE_TAPPED, INBOX_OPTIONS_TAPPED)
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.none { it in anchors }) return@forEach
                for (candidate in dex.classes) {
                    if (candidate.methods.any { method -> method.visualCode().any { it.visualString() in anchors } }) {
                        kept[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val controller = kept.values.single { classDef ->
                classDef.methods.any { method -> method.visualCode().any { it.visualString() == INBOX_OPTIONS_TAPPED } }
            }.type
            FixtureDex.forEach(bundle) { dex ->
                for (candidate in dex.classes) {
                    if (candidate.type in kept || CLICK !in candidate.interfaces) continue
                    if (candidate.methods.any { method -> method.visualCode().any { (it.visualReference() as? MethodReference)?.definingClass == controller } }) {
                        kept[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val listeners = kept.values.filter { CLICK in it.interfaces }.map { it.type }.toSet()
            FixtureDex.forEach(bundle) { dex ->
                for (candidate in dex.classes) {
                    if (candidate.type in kept) continue
                    if (candidate.methods.any { method ->
                            method.visualCode().any { it.opcode == Opcode.NEW_INSTANCE && (it.visualReference() as? TypeReference)?.type in listeners }
                        }) {
                        kept[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val stored = kept.values.filter { classDef ->
                classDef.methods.any { method -> method.visualCode().any { it.opcode == Opcode.NEW_INSTANCE && (it.visualReference() as? TypeReference)?.type in listeners } }
            }.flatMap { it.methods }.flatMap { it.visualCode() }
                .mapNotNull { (it.visualReference() as? FieldReference)?.definingClass }.toSet()
            kept += FixtureDex.classes(bundle, stored.filter { it !in kept }.toSet())
            return kept.values.toList()
        }
    }
}
