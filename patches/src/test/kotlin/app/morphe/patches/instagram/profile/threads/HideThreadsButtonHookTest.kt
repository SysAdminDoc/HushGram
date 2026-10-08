/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.threads

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideThreadsButtonHookTest {
    private val builder = "Lfixture/Builder;"
    private val button = "Lfixture/Button;"
    private val source = "Lfixture/Source;"
    private val build = "$builder->build($BAR_GROUP${BAR_GROUP}JLjava/util/List;)V"
    private val getter = "invoke-virtual { p1 }, $source->buttons()Ljava/util/List;"

    /** The hook and the stub the patch writes are in the ThreadsButton the bundle ships, public and static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(THREADS_BUTTON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BUTTONS is not in the extension: $declared", BUTTONS.substringAfter("->") in declared)
        assertTrue("the $ICON_STUB stub is not in the extension: $declared", "$ICON_STUB(Ljava/lang/Object;)I" in declared)
    }

    /**
     * The list goes to the extension right after it's read, past a long the builder takes before
     * it, and the answer goes back in the same register, so the null check and the build use it.
     * The stub then reads the icon off the button type the builder casts to.
     */
    @Test
    fun theListGoesToTheExtensionAndTheStubReadsTheIcon() {
        val context = PatchContexts.of(classes())

        context.hide()

        assertHooked("the stand-in", context.binder(), register = 0)
        assertStub("the stand-in", context, button, "$button->icon:I")
    }

    /** Another list the builder walks, like 450's list of Booleans, isn't taken for the buttons. */
    @Test
    fun anotherListTheBuilderWalksIsLeftOut() {
        val context = PatchContexts.of(classes(otherWalk = true))

        context.hide()

        assertHooked("the stand-in with a second walk", context.binder(), register = 0)
        assertStub("the stand-in with a second walk", context, button, "$button->icon:I")
    }

    /** A branch inside the stretch from the read to the build, like 450's, is fine: every way in still passes the hook. */
    @Test
    fun aBranchInsideTheStretchIsFine() {
        val context = PatchContexts.of(classes(between = "if-nez v1, :skip\nconst/4 v4, 0x1\n:skip"))

        context.hide()

        assertHooked("the branching stand-in", context.binder(), register = 0)
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bar = PROFILE_ACTION_BAR
        val cases = listOf(
            classes(bar = false) to "this Instagram build has no $bar",
            classes(builds = 0) to "expected one call building the buttons in $bar, found 0",
            classes(builds = 2) to "expected one call building the buttons in $bar, found 2",
            classes(read = "invoke-virtual { p1, v1 }, $source->buttons(I)Ljava/util/List;") to "expected one list of buttons read into v0 in $bar->A00, found 0",
            classes(read = "invoke-virtual { p1 }, $source->buttons()Ljava/util/Collection;") to "expected one list of buttons read into v0 in $bar->A00, found 0",
            classes(reads = 2) to "expected one list of buttons read into v0 in $bar->A00, found 2",
            classes(readAfter = true) to "$bar->A00 reads its list of buttons after building them",
            classes(jumpIn = true) to "something jumps in between $bar->A00's list of buttons and the call building them",
            classes(between = "const/4 v0, 0x0") to "$bar->A00 writes over v0 between its list of buttons",
            classes(builder = false) to "the buttons' builder $builder isn't in the app",
            classes(builderName = "other") to "$builder has no build",
            classes(loops = 0) to "expected $builder->build to go through its buttons once, found 0",
            classes(loops = 2) to "expected $builder->build to go through its buttons once, found 2",
            classes(button = false) to "the button type $button isn't in the app",
            classes(buttonPublic = false) to "$button isn't public, so the extension can't read its icon",
            classes(iconFields = 0) to "expected one int field on $button, its icon, found 0",
            classes(iconFields = 2) to "expected one int field on $button, its icon, found 2",
            classes(iconPublic = false) to "$button's icon icon isn't public, so the extension can't read it",
            classes(extension = null) to "the extension has no $THREADS_BUTTON",
            classes(extension = fakeExtension(buttons = false)) to "the extension has no public static $BUTTONS",
            classes(extension = fakeExtension(icon = false)) to "$THREADS_BUTTON has no public static I $ICON_STUB(Ljava/lang/Object;)",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    val after = now.getValue(method.key())
                    assertEquals("$expected: ${original.type}->${method.name} changed", method.code().map { it.describe() }, after.code().map { it.describe() })
                }
            }
        }
    }

    /**
     * In each declared build the bar's list goes to the extension once, right after it's read, with
     * Instagram's own code otherwise untouched, and the stub reads the int field of the type every
     * button extends.
     */
    @Test
    fun eachDeclaredBuildHandsTheBarsListToTheExtension() {
        forEachFixture { bundle ->
            val bar = FixtureDex.classes(bundle, setOf(PROFILE_ACTION_BAR)).values.single()
            val builders = bar.methods.flatMap { it.code() }.mapNotNull { it.reference() as? MethodReference }
                .filter { call -> call.parameterTypes.count { it.toString() == BAR_GROUP } == 2 }.map { it.definingClass }.toSet()
            val builderClasses = FixtureDex.classes(bundle, builders).values
            val casts = builderClasses.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.CHECK_CAST }.map { (it.reference() as TypeReference).type }.toSet()
            val buttons = FixtureDex.classes(bundle, casts).values
            val context = PatchContexts.of((listOf(bar) + builderClasses + buttons).distinctBy { it.type } + ExtensionDex.classDef(THREADS_BUTTON))
            val site = context.findThreadsButton()
            val original = NeutralNativePath(context.binder(site))

            context.hideThreadsButton(site)

            val binder = context.binder(site)
            assertHooked(bundle.name, binder, site.register)
            original.assertPreserved(bundle.name, binder, setOf(site.hook, site.hook + 1))
            assertStub(bundle.name, context, site.button, site.icon)
            assertTrue("${bundle.name}: no button the builder takes extends ${site.button}", buttons.any { it.superclass == site.button })
        }
    }

    private fun forEachFixture(check: (java.io.File) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The list read, then the hook on its register and its answer back into it, once, with nothing jumping in. */
    private fun assertHooked(what: String, binder: MutableMethod, register: Int) {
        val code = binder.code()
        val hooks = code.indices.filter { code[it].referenceText() == BUTTONS }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: right after the list is read", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the read's register", register, (code[hook - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: hands the list over", listOf(register), code[hook].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertTrue("$what: a jump lands on the hook or its answer", hook !in binder.jumpTargets() && hook + 1 !in binder.jumpTargets())
    }

    /** The stub casts its button, reads [icon] off it and answers that. */
    private fun assertStub(what: String, context: BytecodePatchContext, type: String, icon: String) {
        val stub = context.mutableClassDefBy(THREADS_BUTTON).methods.single { it.name == ICON_STUB }.code()
        assertEquals("$what: the stub", listOf(Opcode.CHECK_CAST, Opcode.IGET, Opcode.RETURN), stub.map { it.opcode })
        assertEquals("$what: the stub's cast", type, (stub[0].reference() as TypeReference).type)
        assertEquals("$what: the stub's field", icon, stub[1].referenceText())
    }

    private fun BytecodePatchContext.hide() = hideThreadsButton(findThreadsButton())

    private fun BytecodePatchContext.binder(site: ThreadsButtonSite? = null): MutableMethod =
        mutableClassDefBy(PROFILE_ACTION_BAR).methods.single {
            if (site == null) it.name == "A00" else it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The bar, whose binder reads the list of buttons, skips the build on null and hands it to the
     * builder with the bar's two groups and a long; the builder, which casts each item to the
     * button type; the button type, public with its icon in a public int; and the extension.
     */
    private fun classes(
        bar: Boolean = true,
        builds: Int = 1,
        read: String = getter,
        reads: Int = 1,
        readAfter: Boolean = false,
        jumpIn: Boolean = false,
        between: String = "",
        builder: Boolean = true,
        builderName: String = "build",
        loops: Int = 1,
        otherWalk: Boolean = false,
        button: Boolean = true,
        buttonPublic: Boolean = true,
        iconFields: Int = 1,
        iconPublic: Boolean = true,
        extension: ClassDef? = ExtensionDex.classDef(THREADS_BUTTON),
    ): List<ClassDef> {
        val classes = mutableListOf<ClassDef>()
        if (bar) {
            val call = if (builds == 0) "invoke-static { v1, v2, v3, v0 }, ${this.builder}->build(${BAR_GROUP}JLjava/util/List;)V"
            else "invoke-static { v1, v1, v2, v3, v0 }, $build"
            val reading = List(reads) { "$read\nmove-result-object v0" }.joinToString("\n")
            val body = """
                const/4 v1, 0x0
                const-wide/16 v2, 0x0
                ${if (jumpIn) "if-nez p1, :inside" else ""}
                ${if (readAfter) "" else reading}
                if-eqz v0, :done
                $between
                :inside
                $call
                ${if (readAfter) reading else ""}
                :done
                return-void
            """
            val methods = mutableListOf(method(PROFILE_ACTION_BAR, "A00", listOf(source), "V", 6, body = body))
            if (builds == 2) {
                methods += method(PROFILE_ACTION_BAR, "A01", listOf(source), "V", 6, body = """
                    $getter
                    move-result-object v0
                    const/4 v1, 0x0
                    const-wide/16 v2, 0x0
                    $call
                    return-void
                """)
            }
            classes += classDef(PROFILE_ACTION_BAR, methods, superclass = "Landroid/widget/FrameLayout;")
        }
        if (builder) {
            val step = "invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;\nmove-result-object v1\ncheck-cast v1, ${this.button}"
            // 450's builder also walks a list of Booleans, through an iterator of its own.
            val other = """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v1
                invoke-interface { v1 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Ljava/lang/Boolean;
            """.takeIf { otherWalk }.orEmpty()
            val body = """
                $other
                invoke-interface { p4 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :loop
                invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :done
                ${List(loops) { step }.joinToString("\n")}
                goto :loop
                :done
                return-void
            """
            val parameters = listOf(BAR_GROUP, BAR_GROUP, "J", "Ljava/util/List;")
            classes += classDef(this.builder, listOf(method(this.builder, builderName, parameters, "V", 2, static = true, body = body)))
        }
        if (button) {
            val access = AccessFlags.PUBLIC.value.takeIf { iconPublic } ?: AccessFlags.PRIVATE.value
            val fields = (0 until iconFields).map { ImmutableField(this.button, if (it == 0) "icon" else "size", "I", access, null, null, null) } +
                ImmutableField(this.button, "label", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null)
            val flags = (if (buttonPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.ABSTRACT.value
            classes += ImmutableClassDef(this.button, flags, "Ljava/lang/Object;", null, null, null, fields, emptyList())
        }
        if (extension != null) classes += extension
        return classes
    }

    /** A ThreadsButton missing the hook or the stub. */
    private fun fakeExtension(buttons: Boolean = true, icon: Boolean = true): ClassDef {
        val methods = mutableListOf<Method>()
        if (buttons) methods += method(THREADS_BUTTON, "buttons", listOf("Ljava/util/List;"), "Ljava/util/List;", 0, static = true, body = "return-object p0")
        if (icon) methods += method(THREADS_BUTTON, ICON_STUB, listOf("Ljava/lang/Object;"), "I", 1, static = true, body = "const/4 v0, 0x0\nreturn v0")
        return classDef(THREADS_BUTTON, methods)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, emptyList(), methods)

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction>()
            .map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}
