/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accent color: every list AndroidX's color state list loader returns goes through
 * AccentColor.list, the color each of the two theme color reads returns goes through
 * AccentColor.color, and anything the patch can't find exactly fails it before a change.
 */
class AccentColorHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_COLOR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWAP_COLOR is not in the extension: $declared", SWAP_COLOR in declared)
        assertTrue("$SWAP_LIST is not in the extension: $declared", SWAP_LIST in declared)
        assertTrue("$SWAP_VALUE is not in the extension: $declared", SWAP_VALUE in declared)
    }

    @Test
    fun everyReturnGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(loader(), themes(), colors(), fallback(), resources(), resources(OTHER_RESOURCES, lists = false), extension()))

        val found = context.findAccentReads()
        assertEquals("$LOADER->A01", "${found.loader.definingClass}->${found.loader.name}")
        assertEquals(setOf("$THEMES->A0Y", "$COLORS->A03"), found.colorReads.map { "${it.definingClass}->${it.name}" }.toSet())
        assertEquals(setOf("$THEMES->A0c", "$FALLBACK->A05"), found.fallbackReads.map { "${it.definingClass}->${it.name}" }.toSet())
        assertEquals("$THEMES->A0Z", "${found.valueRead.definingClass}->${found.valueRead.name}")
        context.swapAccentColors()

        assertListsSwapped("stand-in", context.loaderMethod(), 2)
        assertColorSwapped("stand-in with a check first", context.method(THEMES, "A0Y"))
        assertColorSwapped("stand-in", context.method(COLORS, "A03"))
        assertColorSwapped("fallback stand-in with a check first", context.method(THEMES, "A0c", FALLBACK_SHAPE))
        assertColorSwapped("fallback stand-in", context.method(FALLBACK, "A05", FALLBACK_SHAPE))
        assertValueSwapped("stand-in", context.method(THEMES, "A0Z"))
        assertEquals("the resolver itself is left alone", 0, context.method(THEMES, "A0b").hooks(SWAP_COLOR).size)
        assertEquals("the fallback resolver itself is left alone", 0, context.method(THEMES, "A0d", FALLBACK_SHAPE).hooks(SWAP_COLOR).size)
        assertResourcesSwapped("stand-in", context, RESOURCES_STAND_IN, lists = true)
        assertResourcesSwapped("stand-in without lists", context, OTHER_RESOURCES, lists = false)
    }

    /** A color read straight from a resource can't be reached without Instagram's own Resources, so it refuses. */
    @Test
    fun noResourcesOfItsOwnFailsThePatch() {
        val context = PatchContexts.of(listOf(loader(), themes(), colors(), fallback(), resources(getColorWithTheme = false), extension()))
        val failure = assertThrows(PatchException::class.java) { context.swapAccentColors() }
        assertTrue(failure.message!!, failure.message!!.startsWith("Accent color: found no class extending Resources"))
    }

    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        for ((case, classes) in listOf(
            "no loader" to listOf(themes(), colors(), fallback(), extension()),
            "two loaders" to listOf(loader(), loader(type = "Lfixture/OtherCompat;"), themes(), colors(), fallback(), extension()),
            "a loader that only throws" to listOf(loader(throws = true), themes(), colors(), fallback(), extension()),
            "no attribute resolver" to listOf(loader(), themes(resolver = false), colors(), fallback(), extension()),
            "one color read" to listOf(loader(), themes(), fallback(), extension()),
            "three color reads" to listOf(loader(), themes(), colors(), colors(type = "Lfixture/MoreColors;"), fallback(), extension()),
            "a read asking about another attribute" to listOf(loader(), themes(), colors(attribute = "v0"), fallback(), extension()),
            "a read answering the resource, not its color" to listOf(loader(), themes(), colors(getColor = false), fallback(), extension()),
            "no fallback resolver" to listOf(loader(), themes(fallbackResolver = false), colors(), fallback(), extension()),
            "one fallback read" to listOf(loader(), themes(), colors(), extension()),
            "three fallback reads" to listOf(loader(), themes(), colors(), fallback(), fallback(type = "Lfixture/MoreFallback;"), extension()),
            "a fallback read passing another fallback" to listOf(loader(), themes(), colors(), fallback(fallbackColor = "v0"), extension()),
            "no value read" to listOf(loader(), themes(valueRead = false), colors(), fallback(), extension()),
            "a value read answering another field" to listOf(loader(), themes(valueField = "resourceId"), colors(), fallback(), extension()),
            "two value reads" to listOf(loader(), themes(secondValueRead = true), colors(), fallback(), extension()),
            "no hook in the extension" to listOf(loader(), themes(), colors(), fallback()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findAccentReads() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color:"))
        }
    }

    /** A refusal comes before any change: the loader keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = loader()
        val context = PatchContexts.of(listOf(stub, themes(), extension()))
        val before = stub.methods.single().code().map { it.opcode }
        assertThrows(PatchException::class.java) { context.swapAccentColors() }
        assertEquals(before, context.classDefBy(LOADER).methods.single().code().map { it.opcode })
    }

    /** The declared build: the loader's three returns and both color reads go through the extension. */
    @Test
    fun theDeclaredBuildSwapsAtEveryRead() {
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

    /**
     * The same in the other builds of 452 (#77, #95). The loader, the two reads and the
     * resolver they call move class and name from build to build, and each is found by its shape.
     */
    @Test
    fun everyOtherBuildSwapsAtEveryRead() {
        val checked = Fixtures.otherBuilds().map { bundle ->
            checkBuild(bundle.parentFile.name, bundle)
            bundle.parentFile.name
        }
        assertEquals("the other builds: $checked", Fixtures.OTHER_BUILDS, checked.size)
    }

    /** Patches the slice of [bundle] the finder reads and checks each change. */
    private fun checkBuild(label: String, bundle: java.io.File) {
        val holders = FixtureDex.classesHolding(bundle, INFLATE_FAILED)
        val resolvers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass == THEME && it.name == "resolveAttribute" } }) {
            it.isStaticContextAndInt() && it.code().any { instruction -> instruction.calls(THEME, "resolveAttribute") }
        }
        val reads = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass == CONTEXT && it.name == "getColor" } }) {
            (it.isStaticContextAndInt() || it.isStaticContextAndInts()) && it.code().size <= 7 &&
                it.code().any { instruction -> instruction.calls(CONTEXT, "getColor") }
        }
        val types = (resolvers + reads).mapTo(HashSet()) { it.definingClass }
        val ownResources = ArrayList<ClassDef>()
        FixtureDex.forEach(bundle) { dex -> dex.classes.filterTo(ownResources) { it.superclass == RESOURCES } }
        val context = PatchContexts.of((holders + FixtureDex.classes(bundle, types).values + ownResources).distinctBy { it.type } + extension())

        val found = context.findAccentReads()
        val loader = found.loader.definingClass to found.loader.name
        val colorReads = found.colorReads.map { it.definingClass to it.name }
        val fallbackReads = found.fallbackReads.map { it.definingClass to it.name }
        val valueRead = found.valueRead.definingClass to found.valueRead.name
        val returns = found.loader.code().count { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: the loader's returns", 3, returns)
        context.swapAccentColors()

        assertListsSwapped("$label ${loader.first}->${loader.second}", context.method(loader.first, loader.second), returns)
        assertEquals("$label: the color reads", 2, colorReads.size)
        for ((type, name) in colorReads) assertColorSwapped("$label $type->$name", context.method(type, name))
        assertReadsShareTheResolver(label, context, colorReads)
        assertEquals("$label: the fallback reads", 2, fallbackReads.size)
        for ((type, name) in fallbackReads) assertColorSwapped("$label $type->$name", context.method(type, name, FALLBACK_SHAPE))
        assertValueSwapped("$label ${valueRead.first}->${valueRead.second}", context.method(valueRead.first, valueRead.second))
        val resolver = (context.method(colorReads[0].first, colorReads[0].second).code()
            .let { code -> code[code.indexOfFirst { it.calls(CONTEXT, "getColor") } - 2] } as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: the value read sits beside the resolver", resolver.definingClass, valueRead.first)
        val wrapping = ownResources.filter { owner -> owner.methods.count { it.name == "getColor" && it.implementation != null } == 2 }
        assertTrue("$label: no Resources of Instagram's own overrides getColor", wrapping.isNotEmpty())
        for (owner in wrapping) assertResourcesSwapped("$label ${owner.type}", context, owner.type, lists = true)
    }

    /** Each getColor form, and each getColorStateList form when [lists], passes everything it returns through the extension. */
    private fun assertResourcesSwapped(what: String, context: BytecodePatchContext, type: String, lists: Boolean) {
        val methods = context.mutableClassDefBy(type).methods
        for (parameters in listOf(listOf("I"), listOf("I", THEME))) {
            val color = methods.single { it.name == "getColor" && it.parameterTypes.map(Any::toString) == parameters }
            val returns = color.code().indices.filter { color.code()[it].opcode == Opcode.RETURN }
            assertTrue("$what getColor$parameters: no return", returns.isNotEmpty())
            assertEquals("$what getColor$parameters: hooks", returns.size, color.hooks(SWAP_COLOR).size)
            for (at in returns) {
                assertHook("$what getColor$parameters at $at", color.code(), at, (color.code()[at] as OneRegisterInstruction).registerA, SWAP_COLOR, Opcode.MOVE_RESULT)
            }
            val list = methods.singleOrNull { it.name == "getColorStateList" && it.parameterTypes.map(Any::toString) == parameters }
            if (lists) {
                assertListsSwapped("$what getColorStateList$parameters", list!!, list.code().count { it.opcode == Opcode.RETURN_OBJECT })
            }
        }
    }

    /**
     * The value read answers TypedValue.data of the attribute it resolved: the hook sits right before
     * its one return, passing that value and the TypedValue it came from, and writes the answer back.
     */
    private fun assertValueSwapped(what: String, method: MutableMethod) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
        assertEquals("$what: returns", 1, returns.size)
        assertEquals("$what: hooks", 1, method.hooks(SWAP_VALUE).size)
        assertEquals("$what: no color hook", 0, method.hooks(SWAP_COLOR).size)
        val at = returns.single()
        val register = (code[at] as OneRegisterInstruction).registerA
        val read = code[at - 3]
        assertEquals("$what: TypedValue.data read before the hook", Opcode.IGET, read.opcode)
        assertEquals("$what: TypedValue.data read before the hook", "data", ((read as ReferenceInstruction).reference as FieldReference).name)
        val call = code[at - 2]
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC, call.opcode)
        val passed = call as FiveRegisterInstruction
        assertEquals("$what: the value and its TypedValue", 2, passed.registerCount)
        assertEquals("$what: the value passed", register, passed.registerC)
        assertEquals("$what: the TypedValue passed", (read as TwoRegisterInstruction).registerB, passed.registerD)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$what: the answer replaces the value", register, (code[at - 1] as OneRegisterInstruction).registerA)
    }

    /**
     * What the two reads are, by what they do and not by where Redex put them: each is a static
     * (Context, int) int that asks the one same resolver for the resource of its attribute, and that
     * resolver resolves a theme attribute (Theme.resolveAttribute, TypedValue.resourceId) and asks
     * for no color. Where the reads sit differs from build to build (a class holding Room's schema in
     * some, one holding the pagination limit in others), so a method of another class that merely
     * has their shape and asks another method would fail here.
     */
    private fun assertReadsShareTheResolver(label: String, context: BytecodePatchContext, reads: List<Pair<String, String>>) {
        val resolvers = reads.map { (type, name) ->
            val code = context.method(type, name).code()
            val at = code.indexOfFirst { it.calls(CONTEXT, "getColor") } - 2
            val call = code[at]
            assertEquals("$label $type->$name: asks the resolver right before getColor", Opcode.INVOKE_STATIC, call.opcode)
            (call as ReferenceInstruction).reference as MethodReference
        }
        assertEquals("$label: both reads ask one resolver", 1, resolvers.map { it.toString() }.toSet().size)
        val resolver = resolvers.first()
        assertEquals("$label: the resolver takes the Context and the attribute", listOf(CONTEXT, "I"), resolver.parameterTypes.map(Any::toString))
        val code = context.method(resolver.definingClass, resolver.name).code()
        assertTrue("$label: the resolver calls Theme.resolveAttribute", code.any { it.calls(THEME, "resolveAttribute") })
        assertTrue("$label: the resolver reads TypedValue.resourceId", code.any {
            it.opcode == Opcode.IGET && ((it as ReferenceInstruction).reference as FieldReference).let { f ->
                f.definingClass == "Landroid/util/TypedValue;" && f.name == "resourceId"
            }
        })
        assertTrue("$label: the resolver asks for no color", code.none { it.calls(CONTEXT, "getColor") })
        assertEquals("$label: the reads are two methods", 2, reads.toSet().size)
        assertTrue("$label: neither read is the resolver", reads.none { it == resolver.definingClass to resolver.name })
    }

    /**
     * [count] returns, each right after the list hook on its own register, no jump past the hook,
     * and no hook inside a try range. On 453 the loader returns from inside its synchronized
     * block's catch-all, and a hook there made ART fail the method's lock verification.
     */
    private fun assertListsSwapped(what: String, method: MutableMethod, count: Int) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("$what: returns", count, returns.size)
        assertEquals("$what: hooks", count, method.hooks(SWAP_LIST).size)
        val targets = method.jumpTargets()
        val covered = method.implementation!!.tryBlocks.flatMap { it.start.location.index until it.end.location.index }.toSet()
        for (at in returns) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertHook("$what at $at", code, at, register, SWAP_LIST, Opcode.MOVE_RESULT_OBJECT)
            assertFalse("$what: a jump lands past the hook before $at", at in targets || at - 1 in targets)
            assertFalse("$what: the hook before $at sits inside a try range", at - 2 in covered || at in covered)
        }
    }

    /** One hook, right before the one return, passing and getting back the color returned. */
    private fun assertColorSwapped(what: String, method: MutableMethod) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
        assertEquals("$what: returns", 1, returns.size)
        assertEquals("$what: hooks", 1, method.hooks(SWAP_COLOR).size)
        val at = returns.single()
        val register = (code[at] as OneRegisterInstruction).registerA
        assertHook(what, code, at, register, SWAP_COLOR, Opcode.MOVE_RESULT)
        assertEquals("$what: getColor's answer is what's swapped", Opcode.MOVE_RESULT, code[at - 3].opcode)
        assertEquals("$what: getColor's answer is what's swapped", register, (code[at - 3] as OneRegisterInstruction).registerA)
        assertTrue("$what: getColor right before", code[at - 4].calls(CONTEXT, "getColor"))
    }

    private fun assertHook(what: String, code: List<Instruction>, at: Int, register: Int, hook: String, move: Opcode) {
        val call = code[at - 2]
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the hook", hook, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the value passed", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the value alone", 1, call.registerCount)
        assertEquals("$what: the answer", move, code[at - 1].opcode)
        assertEquals("$what: the answer replaces the value", register, (code[at - 1] as OneRegisterInstruction).registerA)
    }

    private fun BytecodePatchContext.loaderMethod(): MutableMethod = method(LOADER, "A01")

    private fun BytecodePatchContext.method(type: String, name: String, shape: List<String> = listOf(CONTEXT, "I")): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(Any::toString) == shape }

    private fun Method.hooks(hook: String) = code().filter { (it as? ReferenceInstruction)?.reference?.toString() == hook }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.isStaticContextAndInt() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
        parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") && implementation != null

    private fun Method.isStaticContextAndInts() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
        parameterTypes.map(Any::toString) == FALLBACK_SHAPE && implementation != null

    private fun Instruction.calls(definingClass: String, name: String) =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == definingClass && it.name == name } == true

    private companion object {
        const val LOADER = "Lfixture/ResourcesCompat;"
        const val THEMES = "Lfixture/Themes;"
        const val COLORS = "Lfixture/Colors;"
        const val FALLBACK = "Lfixture/Fallback;"
        const val CONTEXT = "Landroid/content/Context;"
        val FALLBACK_SHAPE = listOf(CONTEXT, "I", "I")
        const val THEME = "Landroid/content/res/Resources\$Theme;"
        const val LIST = "Landroid/content/res/ColorStateList;"
        const val RESOURCES = "Landroid/content/res/Resources;"
        const val RESOURCES_STAND_IN = "Lfixture/IgResources;"
        const val OTHER_RESOURCES = "Lfixture/OtherResources;"

        fun extension(): ClassDef = ExtensionDex.classDef(ACCENT_COLOR)

        /**
         * A class extending Resources the way Instagram's own do, overriding both getColor forms
         * and, when [lists], both getColorStateList forms. The two-argument getColor answers from
         * either of two returns, as a cached color does.
         */
        fun resources(type: String = RESOURCES_STAND_IN, lists: Boolean = true, getColorWithTheme: Boolean = true): ClassDef {
            val methods = mutableListOf(
                virtual(
                    type, "getColor", "I", 3, listOf("I"),
                    """
                        invoke-super { p0, p1 }, $RESOURCES->getColor(I)I
                        move-result v0
                        return v0
                    """,
                ),
            )
            if (getColorWithTheme) {
                methods += virtual(
                    type, "getColor", "I", 4, listOf("I", THEME),
                    """
                        if-nez p2, :themed
                        invoke-super { p0, p1 }, $RESOURCES->getColor(I)I
                        move-result v0
                        return v0
                        :themed
                        invoke-super { p0, p1, p2 }, $RESOURCES->getColor(I$THEME)I
                        move-result v0
                        return v0
                    """,
                )
            }
            if (lists) {
                methods += virtual(
                    type, "getColorStateList", LIST, 3, listOf("I"),
                    """
                        invoke-super { p0, p1 }, $RESOURCES->getColorStateList(I)$LIST
                        move-result-object v0
                        return-object v0
                    """,
                )
                methods += virtual(
                    type, "getColorStateList", LIST, 4, listOf("I", THEME),
                    """
                        invoke-super { p0, p1, p2 }, $RESOURCES->getColorStateList(I$THEME)$LIST
                        move-result-object v0
                        return-object v0
                    """,
                )
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, RESOURCES, null, null, null, null, methods)
        }

        fun virtual(type: String, name: String, returns: String, registers: Int, parameters: List<String>, body: String): Method =
            ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
            )

        /**
         * A static (Context, int)ColorStateList shaped like ResourcesCompat's loader: it holds the
         * log line, and its second return is where a branch lands, as the third is on 450.
         */
        fun loader(type: String = LOADER, throws: Boolean = false): ClassDef = classOf(
            type,
            static(
                type, "A01", LIST, 4,
                if (throws) {
                    """
                        const-string v0, "$INFLATE_FAILED"
                        new-instance v1, Ljava/lang/IllegalStateException;
                        invoke-direct { v1 }, Ljava/lang/IllegalStateException;-><init>()V
                        throw v1
                    """
                } else {
                    """
                        const-string v0, "$INFLATE_FAILED"
                        const/4 v1, 0x0
                        if-eqz p1, :none
                        invoke-virtual { p0, p1 }, $CONTEXT->getColorStateList(I)$LIST
                        move-result-object v1
                        return-object v1
                        :none
                        return-object v1
                    """
                },
            ),
        )

        /**
         * The resolver, shaped like 450's and 451's: it resolves the attribute against the Context's
         * theme and answers the resource id. With it, the read that runs a check first and then calls
         * it, the fallback resolver that asks it and answers its fallback for no resource, the
         * fallback read that runs the same check, and the value read answering TypedValue.data.
         */
        fun themes(
            resolver: Boolean = true,
            fallbackResolver: Boolean = true,
            valueRead: Boolean = true,
            valueField: String = "data",
            secondValueRead: Boolean = false,
        ): ClassDef = classOf(
            THEMES,
            *listOfNotNull(
                if (fallbackResolver) {
                    static(
                        THEMES, "A0d", "I", 5,
                        """
                            invoke-static { p0, p1 }, $THEMES->A0b(${CONTEXT}I)I
                            move-result v0
                            if-eqz v0, :none
                            return v0
                            :none
                            return p2
                        """,
                        FALLBACK_SHAPE,
                    )
                } else {
                    null
                },
                static(
                    THEMES, "A0c", "I", 4,
                    """
                        const/4 v0, 0x0
                        invoke-static { p0, v0 }, $THEMES->A0W(Ljava/lang/Object;I)V
                        invoke-static { p0, p1, p2 }, $THEMES->A0d(${CONTEXT}II)I
                        move-result v0
                        invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I
                        move-result v0
                        return v0
                    """,
                    FALLBACK_SHAPE,
                ),
                if (valueRead) valueRead("A0Z", valueField) else null,
                if (secondValueRead) valueRead("A0a", valueField) else null,
            ).toTypedArray(),
            static(
                THEMES, "A0b", "I", 5,
                """
                    new-instance v0, Landroid/util/TypedValue;
                    invoke-direct { v0 }, Landroid/util/TypedValue;-><init>()V
                    invoke-virtual { p0 }, $CONTEXT->getTheme()$THEME
                    move-result-object v1
                    const/4 v2, 0x1
                    ${if (resolver) "invoke-virtual { v1, p1, v0, v2 }, $THEME->resolveAttribute(ILandroid/util/TypedValue;Z)Z" else ""}
                    iget v0, v0, Landroid/util/TypedValue;->resourceId:I
                    return v0
                """,
            ),
            static(
                THEMES, "A0Y", "I", 3,
                """
                    const/4 v0, 0x0
                    invoke-static { p0, v0 }, $THEMES->A0W(Ljava/lang/Object;I)V
                    invoke-static { p0, p1 }, $THEMES->A0b($CONTEXT I)I
                    move-result v0
                    invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I
                    move-result v0
                    return v0
                """.replace("$CONTEXT I", "${CONTEXT}I"),
            ),
        )

        /** The read with nothing before its call to the resolver, or one broken as each flag says. */
        fun colors(type: String = COLORS, attribute: String = "p1", getColor: Boolean = true): ClassDef = classOf(
            type,
            static(
                type, "A03", "I", 3,
                """
                    const/4 v0, 0x0
                    invoke-static { p0, $attribute }, $THEMES->A0b(${CONTEXT}I)I
                    move-result v0
                    ${if (getColor) "invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I" else "invoke-static { v0 }, $THEMES->A0W(I)I"}
                    move-result v0
                    return v0
                """,
            ),
        )

        /** A value read like 451's: the attribute resolved against the theme and [field] of the TypedValue answered. */
        fun valueRead(name: String, field: String): Method = static(
            THEMES, name, "I", 5,
            """
                invoke-virtual { p0 }, $CONTEXT->getTheme()$THEME
                move-result-object v2
                new-instance v1, Landroid/util/TypedValue;
                invoke-direct { v1 }, Landroid/util/TypedValue;-><init>()V
                const/4 v0, 0x1
                invoke-virtual { v2, p1, v1, v0 }, $THEME->resolveAttribute(ILandroid/util/TypedValue;Z)Z
                iget v0, v1, Landroid/util/TypedValue;->$field:I
                return v0
            """,
        )

        /** The fallback read with nothing before its call, or one passing [fallbackColor] in place of its own. */
        fun fallback(type: String = FALLBACK, fallbackColor: String = "p2"): ClassDef = classOf(
            type,
            static(
                type, "A05", "I", 4,
                """
                    invoke-static { p0, p1, $fallbackColor }, $THEMES->A0d(${CONTEXT}II)I
                    move-result v0
                    invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I
                    move-result v0
                    return v0
                """,
                FALLBACK_SHAPE,
            ),
        )

        fun static(
            type: String,
            name: String,
            returns: String,
            registers: Int,
            body: String,
            parameters: List<String> = listOf(CONTEXT, "I"),
        ): Method = ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
        )

        fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods.toList(),
        )
    }
}
