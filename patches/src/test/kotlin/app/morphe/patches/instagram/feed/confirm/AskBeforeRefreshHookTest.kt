/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instagram's nested-scrolling refresh layout hands the listener it reads to RefreshConfirm.pull, and
 * a SwipeRefreshLayout's end-of-pull animation hands the one it reads to RefreshConfirm.listener,
 * each right after the read, ahead of its own null check.
 */
class AskBeforeRefreshHookTest {
    @Test
    fun theEndOfAPullAsksBeforeItCallsTheListener() {
        val context = PatchContexts.of(listOf(listener(), layout(), pullEnd(), nested(), extension()))
        context.askBeforeRefresh()

        val code = context.pullEndCode()!!
        assertEquals(
            listOf("iget-object", "iget-boolean", "if-eqz", "iget-object", REFRESH_LISTENER, "move-result-object", "check-cast",
                "if-eqz", "invoke-interface", "return-void"),
            code.map(::shape),
        )
        val ask = code[4] as FiveRegisterInstruction
        assertEquals("the layout, the listener and the animation itself", listOf(2, 0, 3), listOf(ask.registerC, ask.registerD, ask.registerE))
        assertEquals(0, (code[5] as OneRegisterInstruction).registerA)
        assertEquals(LISTENER, (code[6] as ReferenceInstruction).reference.toString())
        assertEquals(0, (code[6] as OneRegisterInstruction).registerA)
        assertSpinnerOff(context, LAYOUT)
    }

    @Test
    fun aPullPastThePointAsksBeforeTheNestedLayoutCallsItsListener() {
        val context = PatchContexts.of(listOf(listener(), layout(), pullEnd(), nested(), extension()))
        context.askBeforeRefresh()

        val code = context.triggerCode()!!
        assertEquals(
            listOf("iget-boolean", "if-eqz", "iget-object", "if-eqz", "iget-boolean", "if-eqz", "iget-object", PULL_LISTENER,
                "move-result-object", "check-cast", "if-eqz", "invoke-interface", "return-void"),
            code.map(::shape),
        )
        val ask = code[7] as FiveRegisterInstruction
        assertEquals("the layout and the listener", listOf(1, 0), listOf(ask.registerC, ask.registerD))
        assertEquals(0, (code[8] as OneRegisterInstruction).registerA)
        assertEquals(NESTED_LISTENER, (code[9] as ReferenceInstruction).reference.toString())
        assertRefresh(context, "$NESTED_LISTENER->refresh()V")
        assertSpinnerOff(context, LAYOUT)
    }

    /**
     * Cancel's spinnerOff calls setRefreshing(false) directly on whichever layout it's handed, in two
     * registers: the nested-scrolling layout's, or the SwipeRefreshLayout's.
     */
    private fun assertSpinnerOff(context: BytecodePatchContext, layout: String, case: String = "") {
        val stub = context.spinnerOff()
        assertEquals("$case: two registers", 2, stub.implementation!!.registerCount)
        val code = stub.implementation!!.instructions.toList()
        val off = listOf(Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID)
        assertEquals(case, listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ) + off + off, code.map { it.opcode })
        assertEquals(case, NESTED_LAYOUT, (code[0] as ReferenceInstruction).reference.toString())
        for ((at, type) in listOf(2 to NESTED_LAYOUT, 6 to layout)) {
            assertEquals(case, type, (code[at] as ReferenceInstruction).reference.toString())
            assertEquals(case, "$type->setRefreshing(Z)V", (code[at + 2] as ReferenceInstruction).reference.toString())
            val call = code[at + 2] as FiveRegisterInstruction
            assertEquals("$case: the layout and false", listOf(1, 0), listOf(call.registerC, call.registerD))
        }
    }

    /** Refresh on the nested layout calls [call], the method the layout calls on its listener, directly. */
    private fun assertRefresh(context: BytecodePatchContext, call: String, case: String = "") {
        val stub = context.refreshStub()
        assertEquals("$case: one register", 1, stub.implementation!!.registerCount)
        val code = stub.implementation!!.instructions.toList()
        assertEquals(case, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID), code.map { it.opcode })
        assertEquals(case, call.substringBefore("->"), (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(case, call, (code[1] as ReferenceInstruction).reference.toString())
        assertEquals(case, 0, (code[1] as FiveRegisterInstruction).registerC)
    }

    @Test
    fun aRefreshThatIsntTheOneFailsThePatchUnchanged() {
        refuses("no layout with setRefreshing", listOf(listener(), layout(spinner = false), pullEnd(), nested(), extension()))
        refuses("two layouts", listOf(listener(), layout(), layout(type = OTHER_LAYOUT), pullEnd(), nested(), extension()))
        refuses("a setter that does more than store", listOf(listener(), layout(setter = "iput-object p1, p0, $LAYOUT->listener:$LISTENER\ninvoke-virtual { p0 }, $LAYOUT->requestLayout()V\nreturn-void"), pullEnd(), nested(), extension()))
        refuses("a setter storing elsewhere", listOf(listener(), layout(setter = "iput-object p1, p0, $OTHER_LAYOUT->listener:$LISTENER\nreturn-void"), pullEnd(), nested(), extension()))
        refuses("no animation reading the listener", listOf(listener(), layout(), pullEnd(listens = false), nested(), extension()))
        refuses("two reads", listOf(listener(), layout(), pullEnd(), pullEnd(type = OTHER_END), nested(), extension()))
        refuses("no check right after the read", listOf(listener(), layout(), pullEnd(between = "move-object v1, v0"), nested(), extension()))
        refuses("no call right after the check", listOf(listener(), layout(), pullEnd(call = "invoke-virtual { v2 }, $LAYOUT->requestLayout()V"), nested(), extension()))
        refuses("its own register reused", listOf(listener(), layout(), pullEnd(entry = "move-object p0, p1"), nested(), extension()))
        refuses("registers out of reach", listOf(listener(), layout(), pullEnd(entry = "move-object/from16 v1, p0", owner = "v1", registers = 20), nested(), extension()))
        refuses("a branch onto the check", listOf(listener(), layout(), pullEnd(entry = "if-nez p1, :test"), nested(), extension()))
        refuses("no extension", listOf(listener(), layout(), pullEnd(), nested()))
        refuses("a setRefreshing that isn't public", listOf(listener(), layout(spinnerFlags = 0), pullEnd(), nested(), extension()))
        refuses("a layout class that isn't public", listOf(listener(), layout(classFlags = AccessFlags.ABSTRACT.value), pullEnd(), nested(), extension()))
        refuses("the listener read over the layout", listOf(listener(), layout(), pullEnd(held = "v2"), nested(), extension()))
    }

    @Test
    fun aNestedLayoutThatIsntTheOneFailsThePatchUnchanged() {
        val swipe = listOf(listener(), layout(), pullEnd())
        refuses("no nested layout", swipe + extension())
        refuses("a nested layout that isn't public", swipe + nested(classFlags = 0) + extension())
        refuses("a nested setRefreshing that isn't public", swipe + nested(spinnerFlags = 0) + extension())
        refuses("no nested setRefreshing", swipe + nested(spinner = false) + extension())
        refuses("no setListener", swipe + nested(setterName = "setOnPullListener") + extension())
        refuses("a setListener that does more than store",
            swipe + nested(setter = "iput-object p1, p0, $NESTED_LAYOUT->listener:$NESTED_LISTENER\ninvoke-virtual { p0 }, $NESTED_LAYOUT->requestLayout()V\nreturn-void") + extension())
        refuses("no call of the listener", swipe + nested(call = "invoke-virtual { p0 }, $NESTED_LAYOUT->requestLayout()V") + extension())
        refuses("no check right after the read", swipe + nested(between = "nop") + extension())
        refuses("a call taking something", swipe + nested(call = "invoke-interface { v0, p0 }, $NESTED_LISTENER->refresh(Ljava/lang/Object;)V") + extension())
        refuses("two calls of the listener", swipe + nested(second = true) + extension())
        refuses("the listener read over the layout", swipe + nested(held = "p0") + extension())
        refuses("a branch onto the check", swipe + nested(entry = "if-nez p1, :test") + extension())
        refuses("an extension without pull", swipe + nested() + extension(without = "pull"))
        refuses("an extension without the refresh stub", swipe + nested() + extension(without = REFRESH_NOW))
    }

    /**
     * On every declared build's fixture: the one refresh layout whose setOnRefreshListener only
     * stores the listener, and the one end-of-pull animation reading it, which asks once, right
     * after the read and ahead of the null check and the call.
     */
    @Test
    fun eachDeclaredBuildAsksBeforeARefresh() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.name == "setOnRefreshListener" } || ANIMATION_LISTENER in classDef.interfaces ||
                            classDef.type == NESTED_LAYOUT
                        ) {
                            classes += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(classes.distinctBy { it.type } + extension())
                context.askBeforeRefresh()

                val asked = classes.filter { ANIMATION_LISTENER in it.interfaces }.flatMap { end ->
                    context.mutableClassDefBy(end.type).methods.filter { it.name == "onAnimationEnd" }
                        .map { it.implementation!!.instructions.toList() }
                        .filter { body -> body.any { it.calls(REFRESH_LISTENER) } }
                }
                assertEquals("${bundle.name}: one end-of-pull animation asks", 1, asked.size)
                val end = asked.single()
                val at = end.indexOfFirst { it.calls(REFRESH_LISTENER) }
                assertEquals(bundle.name, Opcode.IGET_OBJECT, end[at - 1].opcode)
                assertEquals(bundle.name, listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE),
                    end.subList(at + 1, at + 5).map { it.opcode })
                val held = (end[at - 1] as OneRegisterInstruction).registerA
                assertEquals("${bundle.name}: the listener goes in", held, (end[at] as FiveRegisterInstruction).registerD)
                assertEquals("${bundle.name}: and the answer back in its place", held, (end[at + 1] as OneRegisterInstruction).registerA)
                assertEquals(bundle.name, 1, end.count { it.calls(REFRESH_LISTENER) })
                val layout = classes.single { it.methods.any { method -> method.name == "setOnRefreshListener" } &&
                    it.methods.any { method -> method.name == "setRefreshing" && method.parameterTypes.map(Any::toString) == listOf("Z") } }
                assertTrue("${bundle.name}: a public layout", AccessFlags.PUBLIC.isSet(layout.accessFlags))
                assertSpinnerOff(context, layout.type, bundle.name)

                val pulls = context.mutableClassDefBy(NESTED_LAYOUT).methods.mapNotNull { it.implementation?.instructions?.toList() }
                    .filter { body -> body.any { it.calls(PULL_LISTENER) } }
                assertEquals("${bundle.name}: one place in the nested layout asks", 1, pulls.size)
                val pull = pulls.single()
                val asks = pull.indexOfFirst { it.calls(PULL_LISTENER) }
                assertEquals(bundle.name, 1, pull.count { it.calls(PULL_LISTENER) })
                assertEquals(bundle.name, Opcode.IGET_OBJECT, pull[asks - 1].opcode)
                assertEquals(bundle.name, listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE),
                    pull.subList(asks + 1, asks + 5).map { it.opcode })
                val listenerIn = (pull[asks - 1] as OneRegisterInstruction).registerA
                assertEquals("${bundle.name}: the listener goes in", listenerIn, (pull[asks] as FiveRegisterInstruction).registerD)
                assertEquals("${bundle.name}: and the answer back in its place", listenerIn, (pull[asks + 1] as OneRegisterInstruction).registerA)
                assertRefresh(context, (pull[asks + 4] as ReferenceInstruction).reference.toString(), bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.pullEndCode()?.map(::shape)
        val trigger = context.triggerCode()?.map(::shape)
        val stubs = context.stubs()
        assertNotNull(case, assertThrows(case, PatchException::class.java) { context.askBeforeRefresh() })
        assertEquals(case, before, context.pullEndCode()?.map(::shape))
        assertEquals("$case: the nested layout untouched", trigger, context.triggerCode()?.map(::shape))
        assertEquals("$case: the stubs untouched", stubs, context.stubs())
    }

    private fun BytecodePatchContext.stubs(): List<List<Opcode>>? = classDefByOrNull(REFRESH_CONFIRM)?.let {
        mutableClassDefBy(REFRESH_CONFIRM).methods.filter { it.name == SPINNER_OFF || it.name == REFRESH_NOW }.sortedBy { it.name }
            .map { method -> method.implementation!!.instructions.map { it.opcode } }
    }

    private fun BytecodePatchContext.spinnerOff(): Method =
        mutableClassDefBy(REFRESH_CONFIRM).methods.single { it.name == SPINNER_OFF }

    private fun BytecodePatchContext.refreshStub(): Method =
        mutableClassDefBy(REFRESH_CONFIRM).methods.single { it.name == REFRESH_NOW }

    private fun BytecodePatchContext.triggerCode(): List<Instruction>? =
        classDefByOrNull(NESTED_LAYOUT)?.let { mutableClassDefBy(NESTED_LAYOUT).methods.singleOrNull { it.name == "settle" } }
            ?.implementation?.instructions?.toList()

    private fun shape(instruction: Instruction): String = when {
        instruction.calls(REFRESH_LISTENER) -> REFRESH_LISTENER
        instruction.calls(PULL_LISTENER) -> PULL_LISTENER
        else -> instruction.opcode.name
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.pullEndCode(): List<Instruction>? =
        classDefByOrNull(PULL_END)?.let { mutableClassDefBy(PULL_END).methods.single { it.name == "onAnimationEnd" }.implementation!!.instructions.toList() }

    private companion object {
        const val LISTENER = "Lfixture/RefreshListener;"
        const val LAYOUT = "Lfixture/RefreshLayout;"
        const val OTHER_LAYOUT = "Lfixture/OtherRefreshLayout;"
        const val PULL_END = "Lfixture/PullEnd;"
        const val OTHER_END = "Lfixture/OtherPullEnd;"
        const val NESTED_LISTENER = "Lfixture/PullListener;"
        const val SPRING = "Lfixture/Spring;"
        val PUBLIC = AccessFlags.PUBLIC.value
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        /** The extension's RefreshConfirm, less any method named [without]. */
        fun extension(without: String? = null): ClassDef = ExtensionDex.classDef(REFRESH_CONFIRM).let { built ->
            if (without == null) {
                built
            } else {
                ImmutableClassDef(built.type, built.accessFlags, built.superclass, built.interfaces, built.sourceFile,
                    built.annotations, built.fields, built.methods.filter { it.name != without })
            }
        }

        fun listener(): ClassDef = ImmutableClassDef(
            LISTENER, PUBLIC or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(LISTENER, "refresh", null, "V", PUBLIC or AccessFlags.ABSTRACT.value, null, null, null)),
        )

        /** A pull-down refresh layout: its listener setter only stores the listener, and it has setRefreshing(boolean). */
        fun layout(
            type: String = LAYOUT,
            spinner: Boolean = true,
            setter: String = "iput-object p1, p0, $type->listener:$LISTENER\nreturn-void",
            spinnerFlags: Int = PUBLIC,
            classFlags: Int = PUBLIC or AccessFlags.ABSTRACT.value,
        ): ClassDef {
            val methods = mutableListOf(method(type, "setOnRefreshListener", listOf(LISTENER), 2, PUBLIC, setter))
            if (spinner) methods += method(type, "setRefreshing", listOf("Z"), 2, spinnerFlags, "return-void")
            val fields = listOf(
                ImmutableField(type, "listener", LISTENER, 0, null, null, null),
                ImmutableField(type, "notify", "Z", 0, null, null, null),
            )
            return ImmutableClassDef(type, classFlags, "Landroid/view/ViewGroup;", null, null, null, fields, methods)
        }

        /**
         * The layout's end-of-pull animation as 450 has it: when the layout should tell its listener,
         * it reads the listener, checks it and calls it. v0 to v2 are its locals.
         */
        fun pullEnd(
            type: String = PULL_END,
            listens: Boolean = true,
            entry: String = "",
            between: String = "",
            held: String = "v0",
            call: String = "invoke-interface { $held }, $LISTENER->refresh()V",
            owner: String = "p0",
            registers: Int = 5,
        ): ClassDef {
            val body = """
                $entry
                iget-object v2, $owner, $type->layout:$LAYOUT
                iget-boolean v0, v2, $LAYOUT->notify:Z
                if-eqz v0, :done
                iget-object $held, v2, $LAYOUT->listener:$LISTENER
                $between
                ${if (":test" in entry) ":test" else ""}
                if-eqz $held, :done
                $call
                :done
                return-void
            """.lines().filter { it.isNotBlank() }.joinToString("\n")
            val methods = listOf(method(type, "onAnimationEnd", listOf("Landroid/view/animation/Animation;"), registers, PUBLIC_FINAL, body))
            val interfaces = if (listens) listOf(ANIMATION_LISTENER) else emptyList()
            val fields = listOf(ImmutableField(type, "layout", LAYOUT, PUBLIC_FINAL, null, null, null))
            return ImmutableClassDef(type, PUBLIC_FINAL, "Ljava/lang/Object;", interfaces, null, null, fields, methods)
        }

        /**
         * Instagram's nested-scrolling refresh layout as 450 has it: its spring's update reads the
         * listener once only to check it, and once a pull is far enough reads it again, checks it and
         * calls it. v0 is its local, p0 the layout and p1 the spring.
         */
        fun nested(
            classFlags: Int = PUBLIC,
            spinner: Boolean = true,
            spinnerFlags: Int = PUBLIC_FINAL,
            setterName: String = "setListener",
            setter: String = "iput-object p1, p0, $NESTED_LAYOUT->listener:$NESTED_LISTENER\nreturn-void",
            entry: String = "",
            between: String = "",
            held: String = "v0",
            call: String = "invoke-interface { $held }, $NESTED_LISTENER->refresh()V",
            second: Boolean = false,
        ): ClassDef {
            val body = """
                $entry
                iget-boolean v0, p0, $NESTED_LAYOUT->dragging:Z
                if-eqz v0, :done
                iget-object v0, p0, $NESTED_LAYOUT->listener:$NESTED_LISTENER
                if-eqz v0, :done
                iget-boolean v0, p0, $NESTED_LAYOUT->refreshing:Z
                if-eqz v0, :done
                iget-object $held, p0, $NESTED_LAYOUT->listener:$NESTED_LISTENER
                $between
                ${if (":test" in entry) ":test" else ""}
                if-eqz $held, :done
                $call
                :done
                return-void
            """.lines().filter { it.isNotBlank() }.joinToString("\n")
            val methods = mutableListOf(
                method(NESTED_LAYOUT, setterName, listOf(NESTED_LISTENER), 2, PUBLIC_FINAL, setter),
                method(NESTED_LAYOUT, "settle", listOf(SPRING), 3, PUBLIC_FINAL, body),
            )
            if (spinner) methods += method(NESTED_LAYOUT, "setRefreshing", listOf("Z"), 2, spinnerFlags, "return-void")
            if (second) {
                methods += method(NESTED_LAYOUT, "settleAgain", listOf(SPRING), 3, PUBLIC_FINAL,
                    "iget-object v0, p0, $NESTED_LAYOUT->listener:$NESTED_LISTENER\nif-eqz v0, :done\n" +
                        "invoke-interface { v0 }, $NESTED_LISTENER->refresh()V\n:done\nreturn-void")
            }
            val fields = listOf(
                ImmutableField(NESTED_LAYOUT, "listener", NESTED_LISTENER, PUBLIC, null, null, null),
                ImmutableField(NESTED_LAYOUT, "dragging", "Z", PUBLIC, null, null, null),
                ImmutableField(NESTED_LAYOUT, "refreshing", "Z", PUBLIC, null, null, null),
            )
            return ImmutableClassDef(NESTED_LAYOUT, classFlags, "Landroid/widget/FrameLayout;", null, null, null, fields, methods)
        }

        fun method(owner: String, name: String, parameters: List<String>, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
