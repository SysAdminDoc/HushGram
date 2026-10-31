/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.proxy

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Instagram's proxy read asks AppProxy first, and a build where that can't be placed fails the patch unchanged. */
class AppProxyHookTest {
    @Test
    fun theRouteIsInTheExtension() {
        val declared = ExtensionDex.classDef(APP_PROXY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$APP_PROXY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("AppProxy declares no route(): $declared", APP_PROXY_ROUTE in declared)
    }

    @Test
    fun theRouteGoesFirstAndTheReadStays() {
        val layer = layer()
        val context = PatchContexts.of(listOf(layer))

        context.routeProxyRead()

        assertRouted("fixture", layer, context)
    }

    @Test
    fun aBuildWithoutTheLayerFailsThePatch() =
        refuses("has no $TIGON_SERVICE_LAYER", listOf(layer(type = "Lfixture/OtherLayer;")))

    @Test
    fun aReadThatNoLongerLooksAtTheProxyFailsThePatch() =
        refuses("no longer reads", listOf(layer(property = "java.home")))

    @Test
    fun aJumpBackToTheStartFailsThePatch() = refuses("jumps back to the first instruction", listOf(layer(loop = true)))

    @Test
    fun eachDeclaredBuildRoutesTheProxy() {
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
    fun eachOtherBuildRoutesTheProxyToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val layer = FixtureDex.classes(bundle, setOf(TIGON_SERVICE_LAYER))[TIGON_SERVICE_LAYER]
            ?: throw AssertionError("$label: no $TIGON_SERVICE_LAYER")
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, listOf(layer)))

        context.routeProxyRead()

        assertRouted(label, layer, context)
    }

    /** The read's first instruction calls the route, everything after it is the read as it was, and no other method changed. */
    private fun assertRouted(label: String, before: ClassDef, context: BytecodePatchContext) {
        val after = context.mutableClassDefBy(TIGON_SERVICE_LAYER).methods
        for (original in before.methods) {
            val now = after.single { it.sameSignatureAs(original) }.code()
            val was = original.code()
            if (original.name != PROXY_READ || original.parameterTypes.isNotEmpty()) {
                assertEquals("$label: ${original.name} changed", was.map(::text), now.map(::text))
                continue
            }
            assertEquals("$label: the call", "${Opcode.INVOKE_STATIC} $APP_PROXY_ROUTE", text(now[0]))
            assertEquals("$label: the read after it", was.map(::text), now.drop(1).map(::text))
        }
        assertEquals("$label: route calls", 1, after.sumOf { method -> method.code().count { text(it).endsWith(APP_PROXY_ROUTE) } })
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.routeProxyRead() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.toString()
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode} $reference"
    }

    private companion object {
        val GET_PROPERTY = ImmutableMethodReference("Ljava/lang/System;", "getProperty", listOf("Ljava/lang/String;"), "Ljava/lang/String;")

        /**
         * A layer whose read loads [property] and asks System for it, the way Instagram's reads the
         * proxy host, with a goto back to that load when [loop] is set. A read of a port beside it,
         * taking a parameter, isn't the proxy read and stays.
         */
        fun layer(type: String = TIGON_SERVICE_LAYER, property: String = PROXY_HOST_PROPERTY, loop: Boolean = false): ClassDef {
            fun read(parameters: List<String>, load: String) = ImmutableMethod(
                type, PROXY_READ, parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/String;",
                AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    3,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(load)),
                        if (loop && parameters.isEmpty()) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, GET_PROPERTY),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    null, null,
                ),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(read(emptyList(), property), read(listOf("I"), "https.proxyPort")),
            )
        }
    }
}
