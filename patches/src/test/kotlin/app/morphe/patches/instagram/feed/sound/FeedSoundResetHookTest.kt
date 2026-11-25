/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.media.taptoplay.REELS_PLAYER_PAUSE
import app.morphe.patches.instagram.stories.sound.findAudioState
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reset half of Start feed videos with sound (#762): Instagram forgetting the account's audio state. */
class FeedSoundResetHookTest {
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val state = "Lfixture/AudioState;"
    private val boolean = "Ljava/lang/Boolean;"
    private val factory = ImmutableMethodReference(state, "of", listOf(userSession), state)

    /** The hook the patch writes is in the FeedSound the bundle ships, public and static, and takes the value as an Object. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_AUDIO_STATE_CHANGING is not in the extension: $declared", FEED_AUDIO_STATE_CHANGING.substringAfter("->") in declared)
    }

    /**
     * Before the hook nothing in HushGram heard Instagram forget the state, so Home's controller kept its used turn
     * and the next video started muted. The hook is the setter's first instruction and hands the extension the
     * Boolean the setter was given, from its own register; the rest of the setter keeps its place, and the other
     * static setter of the state, which takes no Boolean, is left alone.
     */
    @Test
    fun theHookHandsTheSettersValueOverFirst() {
        val context = PatchContexts.of(classes())
        val original = context.setter().code().map { it.describe() }

        context.apply { applyAudioStateReset(findAudioStateReset(factory)) }

        val code = context.setter().code()
        assertEquals("the call comes first", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("to the extension", FEED_AUDIO_STATE_CHANGING, code[0].referenceText())
        val p1 = context.setter().implementation!!.registerCount - 1
        assertEquals("with the Boolean the setter was given", listOf(p1), code[0].arguments())
        assertEquals("the setter's own code follows", original, code.drop(1).map { it.describe() })
        val elsewhere = classes().flatMap { c ->
            context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == FEED_AUDIO_STATE_CHANGING } }
        }
        assertEquals("only the setter calls it", listOf("set"), elsewhere.map { it.name })
    }

    /** Two such setters, none, one storing something else, or one jumped back to at its start: the patch stops. */
    @Test
    fun aStateWithoutOneSetterTheHookCanLeadFails() {
        for (shape in listOf(Shape.TWO_SETTERS, Shape.NO_SETTER, Shape.STORES_ANOTHER, Shape.LOOPS_TO_START)) {
            val context = PatchContexts.of(classes(shape))
            assertThrows("$shape", PatchException::class.java) { context.findAudioStateReset(factory) }
        }
    }

    /**
     * In each declared build the account audio state the Reels toggle flips has one static setter storing its
     * Boolean, and the hook goes in once, at its first instruction, reading the setter's second parameter, with
     * every other instruction of the state class in place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsStateSetter() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every declared build", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val wanted = wanted(apk)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val (stateFactory, _, _) = context.findAudioState()
                val stateType = stateFactory.returnType
                val setter = context.findAudioStateReset(stateFactory)
                assertEquals("$what: on the state", stateType, setter.definingClass)
                assertEquals("$what: (state, Boolean)", listOf(stateType, boolean), setter.parameterTypes.map(Any::toString))
                assertTrue("$what: static", AccessFlags.STATIC.isSet(setter.accessFlags))
                val stores = setter.code().filter { it.opcode == Opcode.IPUT_OBJECT }
                    .map { (it as ReferenceInstruction).reference as FieldReference }
                assertTrue("$what: stores a Boolean of the state: $stores", stores.any { it.definingClass == stateType && it.type == boolean })
                val originals = context.mutableClassDefBy(stateType).methods.filter { it.implementation != null }.associateWith(::NeutralNativePath)
                context.apply { applyAudioStateReset(setter) }

                val calling = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == FEED_AUDIO_STATE_CHANGING } }
                }
                assertEquals("$what: methods calling", listOf(stateType to setter.name), calling.map { it.definingClass to it.name })
                val method = calling.single()
                val code = method.code()
                assertEquals("$what: calls once", 1, code.count { it.referenceText() == FEED_AUDIO_STATE_CHANGING })
                assertEquals("$what: first", FEED_AUDIO_STATE_CHANGING, code[0].referenceText())
                assertEquals("$what: the second parameter", listOf(method.implementation!!.registerCount - 1), code[0].arguments())
                assertTrue("$what: nothing jumps onto the call", 0 !in method.offsetTargets())
                for ((m, original) in originals) {
                    val hooked = m.name == method.name && m.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
                    original.assertPreserved("$what ${m.name}", m, if (hooked) setOf(0) else emptySet())
                }
            }
        }
    }

    // The classes a build is read from: the Reels audio toggles and the classes their code calls into, the audio state among them.
    private fun wanted(apk: File): Set<String> {
        val toggles = FixtureDex.classesLoading(apk, REELS_PLAYER_PAUSE)
        val called = toggles.flatMap { c -> c.methods.flatMap { m -> m.code().mapNotNull { (it.reference() as? MethodReference)?.definingClass } } }
        return toggles.map { it.type }.toSet() + called.filter { it.startsWith("L") }
    }

    private enum class Shape { PLAIN, TWO_SETTERS, NO_SETTER, STORES_ANOTHER, LOOPS_TO_START }

    /**
     * The account audio state as 457 has it: a Boolean value, a reset flag and a listener set; a static setter that
     * returns when the value is the same, stores it, clears the flag and tells each listener; a factory taking the
     * session; and a second static of the state that takes a long, which isn't the setter.
     */
    private fun classes(shape: Shape = Shape.PLAIN): List<ClassDef> {
        val stored = if (shape == Shape.STORES_ANOTHER) "v0" else "p1"
        val start = if (shape == Shape.LOOPS_TO_START) ":start\n" else ""
        val again = if (shape == Shape.LOOPS_TO_START) "goto :start" else "return-void"
        fun setter(name: String) = method(state, name, listOf(state, boolean), "V", 2, static = true, body = """
            ${start}iget-object v0, p0, $state->value:$boolean
            invoke-static { v0, p1 }, Lfixture/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :done
            iget-object v0, p0, $state->value:$boolean
            iput-object $stored, p0, $state->value:$boolean
            const/4 v0, 0x0
            iput-boolean v0, p0, $state->reset:Z
            iget-object v0, p0, $state->listeners:Ljava/util/concurrent/CopyOnWriteArraySet;
            invoke-virtual { v0 }, Ljava/util/AbstractCollection;->iterator()Ljava/util/Iterator;
            move-result-object v1
            :next
            invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
            move-result v0
            if-eqz v0, :done
            invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
            goto :next
            :done
            $again
        """)
        val setters = when (shape) {
            Shape.TWO_SETTERS -> listOf(setter("set"), setter("set2"))
            Shape.NO_SETTER -> emptyList()
            else -> listOf(setter("set"))
        }
        val methods = setters + listOf(
            method(state, "of", listOf(userSession), state, 1, static = true, body = "const/4 v0, 0x0\nreturn-object v0"),
            method(state, "stamp", listOf(state, "J"), "V", 0, static = true, body = "iput-wide p1, p0, $state->time:J\nreturn-void"),
        )
        val fields = listOf(
            ImmutableField(state, "time", "J", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(state, "value", boolean, AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(state, "reset", "Z", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(state, "listeners", "Ljava/util/concurrent/CopyOnWriteArraySet;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
        )
        return listOf(
            ImmutableClassDef(state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods),
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        static: Boolean = false,
    ): Method {
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.setter(): MutableMethod =
        mutableClassDefBy(state).methods.single { it.name == "set" }

    /** Where the method's branches and gotos land, as indexes. */
    private fun MutableMethod.offsetTargets(): List<Int> =
        implementation!!.instructions.filterIsInstance<com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction>()
            .map { it.target.location.index }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Instruction.describe(): String = "$opcode ${reference() ?: ""}"
}
