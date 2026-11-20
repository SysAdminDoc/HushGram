/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.font

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text size under the Font patch (#782): every configuration Instagram's own resources take goes
 * through TextSize.sized first, so the phone's configuration copied into them on a change of its
 * settings keeps the saved size, and anything the patch can't find exactly fails it before a change.
 */
class TextScaleHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(TEXT_SIZE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SIZED is not in the extension: $declared", SIZED in declared)
    }

    @Test
    fun everyConfigurationInstagramsResourcesTakeIsSizedFirst() {
        val stock = overriding()
        val context = PatchContexts.of(listOf(activity(), screenResources(), stock, extension()))
        val update = context.findTextScaleSite()
        assertEquals(OVERRIDING, update.definingClass)
        context.keepTextScale(update)
        assertSizedFirst("stand-in", context.mutableClassDefBy(OVERRIDING), stock)
    }

    /** A screen keeping the class that extends Resources itself, or two fields of one kind, is found the same. */
    @Test
    fun theClassItselfOrTwoFieldsOfOneKindAreFoundToo() {
        for (fields in listOf(listOf(OVERRIDING), listOf(SCREEN, SCREEN), listOf(SCREEN, OVERRIDING))) {
            val context = PatchContexts.of(listOf(activity(fields), screenResources(), overriding(), extension()))
            assertEquals("$fields", OVERRIDING, context.findTextScaleSite().definingClass)
        }
    }

    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        for ((case, classes) in listOf(
            "no IgActivity" to listOf(screenResources(), overriding(), extension()),
            "no resources kept" to listOf(activity(listOf(STRING)), screenResources(), overriding(), extension()),
            "resources kept only in a static" to listOf(activity(static = true), screenResources(), overriding(), extension()),
            "a chain that never reaches Resources" to listOf(activity(), screenResources(), extension()),
            "two kinds of resources kept" to listOf(
                activity(listOf(SCREEN, OTHER_SCREEN)), screenResources(), screenResources(OTHER_SCREEN, OTHER_OVERRIDING),
                overriding(), overriding(OTHER_OVERRIDING), extension(),
            ),
            "resources that don't take a configuration themselves" to listOf(activity(), screenResources(), overriding(overrides = false), extension()),
            "resources that keep no caches" to listOf(activity(), screenResources(), overriding(evicts = false), extension()),
            "resources handing the configuration to others" to listOf(activity(), screenResources(), overriding(handsOn = false), extension()),
            "a jump to the first instruction" to listOf(activity(), screenResources(), overriding(jumpToStart = true), extension()),
            "no hook in the extension" to listOf(activity(), screenResources(), overriding()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findTextScaleSite() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Font:"))
        }
    }

    /** The declared build: the class IgActivity's resources extend Resources through takes the hook first. */
    @Test
    fun theDeclaredBuildSizesEveryConfiguration() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                checkBuild(bundle.name, bundle)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other builds, where Instagram's resources classes have other names (`07p3`, `07Cc`). */
    @Test
    fun everyOtherBuildSizesEveryConfiguration() {
        val checked = Fixtures.otherBuilds().map { bundle ->
            checkBuild(bundle.parentFile.name, bundle)
            bundle.parentFile.name
        }
        assertEquals("the other builds: $checked", Fixtures.OTHER_BUILDS, checked.size)
    }

    /**
     * Patches the slice of [bundle] the finder reads: IgActivity and the classes of each field it
     * keeps up to Resources. Exactly one class extends Resources itself there, and that's the one
     * whose updateConfiguration takes the hook, with its stock body kept after it.
     */
    private fun checkBuild(label: String, bundle: File) {
        val supers = HashMap<String, String?>()
        var activity: ClassDef? = null
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (supers.putIfAbsent(classDef.type, classDef.superclass) == null && classDef.type == IG_ACTIVITY) {
                    activity = ImmutableClassDef.of(classDef)
                }
            }
        }
        val screen = checkNotNull(activity) { "$label: no $IG_ACTIVITY" }
        val chains = screen.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { field ->
            val chain = mutableListOf<String>()
            var at: String? = field.type
            while (at != null && at in supers && at !in chain) {
                chain += at
                if (supers[at] == RESOURCES) return@mapNotNull chain
                at = supers[at]
            }
            null
        }
        val tops = chains.map { it.last() }.toSet()
        assertEquals("$label: the class IgActivity's resources extend Resources through: $chains", 1, tops.size)
        val top = tops.single()
        val slice = FixtureDex.classes(bundle, chains.flatten().toSet())
        val context = PatchContexts.of(slice.values + screen + extension())
        val update = context.findTextScaleSite()
        assertEquals("$label: the hook's class", top, update.definingClass)
        context.keepTextScale(update)
        assertSizedFirst(label, context.mutableClassDefBy(top), slice.getValue(top))
    }

    /**
     * The (Configuration, DisplayMetrics) updateConfiguration of [patched] hands its configuration
     * to the hook first and takes the answer in its place, and the rest is [stock]'s body.
     */
    private fun assertSizedFirst(what: String, patched: ClassDef, stock: ClassDef) {
        val update = patched.methods.single { it.isUpdate() }
        val code = update.code()
        val configuration = update.implementation!!.registerCount - 2
        val hook = code[0]
        assertEquals("$what: the configuration goes to the extension first", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$what: the hook", SIZED, (hook as ReferenceInstruction).reference.toString())
        assertEquals("$what: handed the configuration", configuration, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$what: alone", 1, hook.registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: in the configuration's place", configuration, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: one hook", 1, patched.methods.sumOf { method -> method.code().count { it.isHook() } })
        assertEquals("$what: the stock body after it", stock.methods.single { it.isUpdate() }.code().shape(), code.drop(2).shape())
    }

    private fun Method.isUpdate() = name == "updateConfiguration" && returnType == "V" &&
        parameterTypes.map(Any::toString) == listOf(CONFIGURATION, DISPLAY_METRICS)

    private fun Instruction.isHook() =
        opcode == Opcode.INVOKE_STATIC_RANGE && (this as ReferenceInstruction).reference.toString() == SIZED

    /** Each instruction's kind and reference, payload nops left out and a widened goto read as a goto. */
    private fun List<Instruction>.shape(): List<String> = filter { it.opcode != Opcode.NOP }.map { instruction ->
        val kind = when (instruction.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        }
        "$kind ${(instruction as? ReferenceInstruction)?.reference ?: ""}"
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val SCREEN = "Lfixture/IgResources;"
        const val OVERRIDING = "Lfixture/OverridingResources;"
        const val OTHER_SCREEN = "Lfixture/OtherIgResources;"
        const val OTHER_OVERRIDING = "Lfixture/OtherOverridingResources;"
        const val RESOURCES = "Landroid/content/res/Resources;"
        const val CONFIGURATION = "Landroid/content/res/Configuration;"
        const val DISPLAY_METRICS = "Landroid/util/DisplayMetrics;"
        const val LRU_CACHE = "Landroid/util/LruCache;"
        const val STRING = "Ljava/lang/String;"

        fun extension(): ClassDef = ExtensionDex.classDef(TEXT_SIZE)

        /** IgActivity keeping a field of each of [fields], instance ones unless [static], and a String. */
        fun activity(fields: List<String> = listOf(SCREEN), static: Boolean = false): ClassDef {
            val flags = AccessFlags.PRIVATE.value or (if (static) AccessFlags.STATIC.value else 0)
            val all = fields.mapIndexed { i, type -> ImmutableField(IG_ACTIVITY, "A0$i", type, flags, null, null, null) } +
                ImmutableField(IG_ACTIVITY, "A09", STRING, AccessFlags.PRIVATE.value, null, null, null)
            return ImmutableClassDef(
                IG_ACTIVITY, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Landroid/app/Activity;",
                null, null, null, all, emptyList<Method>(),
            )
        }

        /** The resources a screen hands out (`07Aw` on 386214574): a class below [superclass] with nothing of its own. */
        fun screenResources(type: String = SCREEN, superclass: String = OVERRIDING): ClassDef =
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, emptyList<Method>())

        /**
         * Instagram's class extending Resources itself (`07B0` on 386214574): its updateConfiguration
         * empties a cache when it has one, hands the configuration on to Resources' own and returns.
         * [overrides], [evicts] and [handsOn] off leave out the method, the emptying and the hand-on
         * (it hands it to another Resources instead); [jumpToStart] adds a jump back to the top.
         */
        fun overriding(
            type: String = OVERRIDING,
            overrides: Boolean = true,
            evicts: Boolean = true,
            handsOn: Boolean = true,
            jumpToStart: Boolean = false,
        ): ClassDef {
            val body = buildString {
                if (jumpToStart) appendLine(":start")
                appendLine("iget-object v0, p0, $type->A02:$LRU_CACHE")
                appendLine("if-eqz v0, :cleared")
                if (evicts) appendLine("invoke-virtual { v0 }, $EVICT_ALL")
                appendLine(":cleared")
                if (handsOn) {
                    appendLine("invoke-super { p0, p1, p2 }, $RESOURCES_UPDATE")
                } else {
                    appendLine("iget-object v0, p0, $type->A03:$RESOURCES")
                    appendLine("invoke-virtual { v0, p1, p2 }, $RESOURCES_UPDATE")
                }
                if (jumpToStart) appendLine("if-eqz p1, :start")
                appendLine("return-void")
            }
            val methods = if (!overrides) emptyList() else listOf(
                method(type, "updateConfiguration", listOf(CONFIGURATION, DISPLAY_METRICS), "V", AccessFlags.PUBLIC.value, 4, body),
            )
            val fields = listOf(
                ImmutableField(type, "A02", LRU_CACHE, AccessFlags.PRIVATE.value, null, null, null),
                ImmutableField(type, "A03", RESOURCES, AccessFlags.PRIVATE.value, null, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, RESOURCES, null, null, null, fields, methods)
        }

        fun method(type: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, body: String): Method =
            ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
            )
    }
}
