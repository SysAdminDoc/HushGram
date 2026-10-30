/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Reels footer's 'Are you interested?' row (#131): its control passes FeedInterest right before it's stored. */
class ReelsInterestControlTest {
    /** The hook the patch writes is in the FeedInterest the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_INTEREST).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REELS_CONTROL is not in the extension: $declared", REELS_CONTROL.substringAfter("->") in declared)
    }

    @Test
    fun theStoreIsFound() {
        val found = PatchContexts.of(classes()).findReelsInterestControl()

        assertEquals(USE_CASE, found.type)
        assertEquals("A02", found.name)
        assertEquals(listOf(STATE, CONTROL), found.parameters)
        assertEquals(COMPONENT, found.returnType)
        assertEquals("the store of the control", 10, found.storeAt)
        assertEquals("the register it stores", 4, found.register)
        assertEquals(CONTROL, found.controlType)
    }

    @Test
    fun theHookGoesRightBeforeTheStore() {
        val context = PatchContexts.of(classes())
        val found = context.findReelsInterestControl()
        found.write(context)
        assertHooked("stand-in", context, found)
    }

    @Test
    fun noMarkedMethodFailsThePatch() = refused("found 0", classes(leaveOut = true))

    @Test
    fun twoMarkedMethodsFailThePatch() = refused("found 2", classes(second = true))

    @Test
    fun aStaticMethodFailsThePatch() = refused("isn't an instance method", classes(static = true))

    @Test
    fun noStoreOfTheControlFailsThePatch() = refused("found 0", classes(variant = "nostore"))

    @Test
    fun twoStoresOfTheControlFailThePatch() = refused("found 2", classes(variant = "twostores"))

    /** Hook test over the declared build: the store is found and the hook sits right before it. */
    @Test
    fun eachDeclaredBuildHooksTheReelsControl() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksTheReelsControl(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other builds of each declared version. */
    @Test
    fun eachOtherBuildHooksTheReelsControl() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksTheReelsControl(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
        assertEquals("the builds besides the declared one", Fixtures.OTHER_BUILDS, checked)
    }

    /** Every class holding the marker, so a second method holding it anywhere in the app would be there to find. */
    private fun hooksTheReelsControl(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.classesHolding(bundle, "android_purge_26_q3_$REELS_CONTROL_MARKER"))

        val found = context.findReelsInterestControl()
        // 453: LX/08YT;->A02 (285) stores v13 into its component's A0A at instruction 57 in every build.
        assertEquals("$label: the use case", "A02", found.name)
        assertEquals("$label: the store's place", 57, found.storeAt)
        assertEquals("$label: the register it stores", 13, found.register)
        found.write(context)

        assertHooked("$label ${found.type}->${found.name}", context, found)
    }

    /**
     * Right before the store: the extension's filter over the stored register, its answer back into
     * that register, cast to the control's type, and the store itself still after it, once in the
     * method. A jump that reached the store now reaches the hook.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: ReelsInterestControl) {
        val code = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].reference()?.toString() == REELS_CONTROL }
        assertEquals("$what: one hook, where the store was", listOf(found.storeAt), calls)
        val at = calls.single()
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: with the control", listOf(1, found.register), listOf(call.registerCount, call.registerC))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: in the register", found.register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: cast back", Opcode.CHECK_CAST, code[at + 2].opcode)
        assertEquals("$what: to the control's type", found.controlType, code[at + 2].reference()?.toString())
        assertEquals("$what: the cast register", found.register, (code[at + 2] as OneRegisterInstruction).registerA)
        val store = code[at + 3]
        assertEquals("$what: the store follows", Opcode.IPUT_OBJECT, store.opcode)
        assertEquals("$what: of the same register", found.register, (store as TwoRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for (index in code.indices) {
            val instruction = code[index]
            if (instruction !is OffsetInstruction) continue
            val jumpsTo = addresses.indexOf(addresses[index] + instruction.codeOffset)
            assertTrue("$what: instruction $index jumps past the hook", jumpsTo !in (at + 1)..(at + 3))
        }
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findReelsInterestControl() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val USE_CASE = "Lfixture/FooterUseCase;"
        const val STATE = "Lfixture/State;"
        const val CONTROL = "Lfixture/Control;"
        const val COMPONENT = "Lfixture/RankingControls;"

        /**
         * Shaped like 453's LX/08YT;->A02: the marker, the reel's control wrapped when there is one or
         * the fallback kept, and the jump that joins both landing on the store.
         *   0 the fallback   1 the marker   5 the reel's control   7 none keeps the fallback
         *   10 the store of the control   11 another store   12 the return
         */
        fun smali(variant: String): String {
            val field = if (variant == "nostore") "A0A:Ljava/lang/Object;" else "A0A:$CONTROL"
            val other = if (variant == "twostores") "iput-object p2, v1, $COMPONENT->A0B:$CONTROL" else "iput-object p1, v1, $COMPONENT->A04:$STATE"
            return """
                move-object v4, p2
                const-string v0, "android_purge_26_q3_$REELS_CONTROL_MARKER"
                invoke-static {v0}, Lfixture/Purge;->A01(Ljava/lang/String;)V
                new-instance v1, $COMPONENT
                invoke-direct {v1}, $COMPONENT-><init>()V
                invoke-virtual {p1}, $STATE->control()$OBJECT
                move-result-object v0
                if-eqz v0, :keep
                new-instance v4, $CONTROL
                invoke-direct {v4, v0}, $CONTROL-><init>($OBJECT)V
                :keep
                iput-object v4, v1, $COMPONENT->$field
                $other
                return-object v1
            """.trimIndent()
        }

        fun useCase(owner: String, variant: String, static: Boolean): ClassDef {
            val flags = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val method = MutableMethod(
                ImmutableMethod(
                    owner, "A02", listOf(STATE, CONTROL).map { ImmutableMethodParameter(it, null, null) }, COMPONENT, flags,
                    null, null, ImmutableMethodImplementation(8, emptyList(), null, null),
                ),
            )
            // A static method has no this: its parameters are p0 and p1.
            val code = if (static) smali(variant).replace("p1", "p0").replace("p2", "p1") else smali(variant)
            method.addInstructionsWithLabels(0, code)
            return ImmutableClassDef(
                owner, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null,
                listOf(ImmutableMethod.of(method)),
            )
        }

        fun classes(leaveOut: Boolean = false, second: Boolean = false, static: Boolean = false, variant: String = "ok"): List<ClassDef> {
            val found = if (leaveOut) emptyList() else listOf(useCase(USE_CASE, variant, static))
            val other = if (second) listOf(useCase("Lfixture/OtherFooterUseCase;", "ok", false)) else emptyList()
            return found + other + listOf(
                ImmutableClassDef("Lfixture/Unrelated;", AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, emptyList()),
            )
        }
    }
}
