/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The framework calls a link leaves Instagram through, and the stand-ins they're sent to. */
class LinkExitsTest {
    private val cleaner = "Lapp/hushgram/extension/instagram/misc/LinkCleaner;"
    private val exits = CLIPBOARD_EXITS + SHARE_SHEET_EXITS + DIRECT_SHARE_EXITS

    /**
     * Each exit's stand-in is in the LinkCleaner the bundle ships, public and static, with exactly
     * the descriptor the rewrite writes. Read from the compiled extension, so a Java parameter that
     * compiles to another type fails here rather than as a NoSuchMethodError on the phone.
     */
    @Test
    fun everyExitHasItsStandIn() {
        val declared = ExtensionDex.classDef(cleaner).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$cleaner->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        exits.forEach { exit ->
            assertTrue(
                "LinkCleaner declares no public static ${exit.standIn}. Its methods of that name: " +
                    declared.filter { it.contains("->${exit.name}(") },
                exit.standIn in declared,
            )
        }
    }

    /**
     * Instagram's activity starts go to the stand-ins; the extension's own are the real calls the
     * stand-ins make and stay. Sending those would make each stand-in call itself.
     */
    @Test
    fun directSharesSendEveryActivityStartOutsideTheExtension() {
        val instagram = "Lfixture/Launcher;"
        val context = PatchContexts.of(listOf(launcher(instagram), launcher(cleaner)))

        val sent = context.rerouteLinkExits(DIRECT_SHARE_EXITS)

        assertEquals("calls sent", DIRECT_SHARE_EXITS.size, sent)
        val rewritten = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals("activity starts left in Instagram's code", emptyList<String>(), rewritten.mapNotNull { it.exitCall() })
        assertEquals(
            "stand-ins in Instagram's code",
            DIRECT_SHARE_EXITS.map { it.standIn },
            rewritten.mapNotNull { it.standInCall() },
        )
        val kept = context.mutableClassDefBy(cleaner).methods.single().instructions()
        assertEquals("the extension's own starts", DIRECT_SHARE_EXITS.size, kept.mapNotNull { it.exitCall() }.size)
        assertEquals("stand-ins in the extension", emptyList<String>(), kept.mapNotNull { it.standInCall() })
    }

    /**
     * Every activity start, share sheet and clipboard copy in each declared build goes to its
     * stand-in with the registers it had and the instruction count unchanged. Instagram 449's
     * launchers, the one WhatsApp's share button uses among them, end in Context.startActivity, and
     * 454's share sheet Copy link ends in android.text.ClipboardManager.setText (#667).
     */
    @Test
    fun eachDeclaredBuildSendsEveryActivityStart() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildCleansTheLinkExitsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val callers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.methodSection.none { call -> exits.any { it.definingClass == call.definingClass && it.name == call.name } }) {
                return@forEach
            }
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.instructions().any { it.exitCall() != null } }) {
                    callers += ImmutableClassDef.of(classDef)
                }
            }
        }
        val calls = callers.flatMap { it.methods }.flatMap { it.instructions() }.mapNotNull { it.exitCall() }
        val starts = calls.count { call -> DIRECT_SHARE_EXITS.any { it.standIn == call } }
        assertTrue("${label}: Context.startActivity calls in the stock build: $starts", starts > 40)
        // The share sheet's Copy link writes the link with the android.text setText (#667).
        val copyLinks = callers.flatMap { it.methods }.filter { method ->
            val body = method.instructions()
            body.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "copy_link" } &&
                body.any { it.exitCall()?.contains("->setText(") == true }
        }
        assertTrue("${label}: no method loading copy_link sets clipboard text", copyLinks.isNotEmpty())

        val context = PatchContexts.of(callers)
        assertEquals("${label}: calls sent", calls.size, context.rerouteLinkExits(exits))

        for (before in callers) {
            val after = context.mutableClassDefBy(before.type).methods
            for (original in before.methods) {
                val was = original.instructions()
                if (was.none { it.exitCall() != null }) continue
                val where = "${label}: ${original.definingClass}->${original.name}"
                val now = after.single { it.sameSignatureAs(original) }.instructions()
                assertEquals("$where: instruction count", was.size, now.size)
                assertEquals("$where: activity starts left", emptyList<String>(), now.mapNotNull { it.exitCall() })
                was.forEachIndexed { index, instruction ->
                    val exit = instruction.exitCall() ?: return@forEachIndexed
                    val replacement = now[index]
                    assertEquals("$where at $index", exit, replacement.standInCall())
                    assertEquals(
                        "$where at $index: the static form of ${instruction.opcode}",
                        if (instruction is RegisterRangeInstruction) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC,
                        replacement.opcode,
                    )
                    assertEquals("$where at $index: registers", instruction.registers(), replacement.registers())
                }
            }
        }
    }

    /** A class with one method that starts an activity both ways. */
    private fun launcher(type: String): ClassDef {
        val calls = DIRECT_SHARE_EXITS.map { exit ->
            val parameters = exit.shape.substringAfter('(').substringBefore(')')
                .let { Regex("L[^;]+;|\\[?[ZBSCIJFD]").findAll(it).map { match -> match.value }.toList() }
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, parameters.size + 1, 0, 1, 2, 0, 0,
                ImmutableMethodReference(exit.definingClass, exit.name, parameters, exit.shape.substringAfter(')')),
            )
        }
        val body = ImmutableMethodImplementation(3, calls + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null)
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(type, "launch", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, body)),
        )
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

    /** The stand-in an exit call is sent to, or null when this isn't one. */
    private fun Instruction.exitCall(): String? = exits.firstOrNull { it.matches(this) }?.standIn

    private fun Instruction.standInCall(): String? {
        val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        if (call.definingClass != cleaner) return null
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
