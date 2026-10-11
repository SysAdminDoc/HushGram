/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.back

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.NeutralNativePath
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackLeavesHomeHookTest {
    private val home = "Lfixture/Home;"
    private val reason = "Lfixture/Reason;"

    /** The hook the patch writes is in the BackLeavesHome the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(LEAVE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$LEAVE is not in the extension: $declared", LEAVE.substringAfter("->") in declared)
    }

    /** The finder names Home's handler, the call that scrolls, and a free local up to v15. */
    @Test
    fun theFinderFindsTheScrollBackGives() {
        val site = PatchContexts.of(classes()).findBackOnHome()
        assertEquals("onBackPressed", site.handler.name)
        val call = site.handler.code()[site.scroll]
        assertEquals("scroll", (call as ReferenceInstruction).reference.toString().substringAfter("->").substringBefore("("))
        assertTrue("a local the hook can name: v${site.free}", site.free in 0..15)
    }

    /**
     * In front of the scroll the hook is asked: a 0 goes on to the scroll, and a 1 returns false from
     * the handler, the answer it gives at the top of the feed.
     */
    @Test
    fun theHookSitsInFrontOfTheScrollAndAOneReturnsFalse() {
        val context = PatchContexts.of(classes())
        val before = context.handler().code().map { it.opcode }
        val original = NeutralNativePath(context.handler())
        val site = context.findBackOnHome()

        context.backLeavesHome(site)

        val handler = context.handler()
        val code = handler.code()
        val at = site.scroll
        assertEquals(Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals(LEAVE, (code[at] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(site.free, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals(site.free, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("the branch lands on the scroll", at + 5, handler.targetOf(at + 2))
        assertEquals(Opcode.CONST_4, code[at + 3].opcode)
        assertEquals(Opcode.RETURN, code[at + 4].opcode)
        assertEquals(site.free, (code[at + 4] as OneRegisterInstruction).registerA)
        assertEquals("the scroll is still there, after the hook", "scroll",
            (code[at + 5] as ReferenceInstruction).reference.toString().substringAfter("->").substringBefore("("))
        assertEquals("one hook", 1, code.count { (it as? ReferenceInstruction)?.reference.toString() == LEAVE })
        assertEquals("every other opcode in order", before, code.filterIndexed { index, _ -> index !in at until at + 5 }.map { it.opcode })
        original.assertPreserved("Home's Back handler", handler, (at until at + 5).toSet())
    }

    /** A build the patch can't read fails at patch time, saying what it found, and the handler is left alone. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(title = false) to "expected one class loading \"$SCROLL_TITLE\", found 0",
            classes(twoHomes = true) to "expected one class loading \"$SCROLL_TITLE\", found 2",
            classes(titleInScroll = false) to "expected one scroll(reason) in $home that loads \"$SCROLL_TITLE\", found 0",
            classes(reasonIsEnum = false) to "expected one scroll(reason) in $home that loads \"$SCROLL_TITLE\", found 0",
            classes(handlerName = "onBack") to "expected one onBackPressed()Z in $home, found 0",
            classes(scrolls = 0) to "expected $home->onBackPressed to scroll to the top once, found 0",
            classes(scrolls = 2) to "expected $home->onBackPressed to scroll to the top once, found 2",
            classes(reasonName = "BACK_BUTTON_PRESS_AT_TOP") to "Back scrolls with the reason BACK_BUTTON_PRESS_AT_TOP, not $BACK_PRESS",
            classes(reasonField = "A03") to "Back scrolls with the reason BACK_BUTTON_PRESS_AT_TOP, not $BACK_PRESS",
            classes(nameless = true) to "Back scrolls with the reason of no name, not $BACK_PRESS",
            classes(gap = true) to "doesn't read a $reason constant right before scrolling",
            classes(jumpToCall = true) to "jumps to the scroll call",
            classes(thisReplaced = true) to "writes over this",
            classes(busyLocals = true) to "needs 1",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findBackOnHome() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    assertEquals(
                        "${original.type}->${method.name} changed while the patch refused",
                        method.code().map { it.opcode }, now.getValue(method.key()).code().map { it.opcode },
                    )
                }
            }
        }
    }

    private fun BytecodePatchContext.handler(): MutableMethod =
        mutableClassDefBy(home).methods.single { it.name == "onBackPressed" }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * Home's feed fragment: a scroll(reason) that loads the title Instagram traces it by, and a Back
     * handler that scrolls with a reason enum's static constant when the feed is ready. The reason
     * enum builds each constant as Instagram's does: its name in a register, the constructor, the store.
     */
    private fun classes(
        title: Boolean = true,
        twoHomes: Boolean = false,
        titleInScroll: Boolean = true,
        reasonIsEnum: Boolean = true,
        handlerName: String = "onBackPressed",
        scrolls: Int = 1,
        reasonName: String = BACK_PRESS,
        reasonField: String = "A02",
        nameless: Boolean = false,
        gap: Boolean = false,
        jumpToCall: Boolean = false,
        thisReplaced: Boolean = false,
        busyLocals: Boolean = false,
    ): List<ClassDef> {
        val scrollBody = if (title && titleInScroll) "const-string v0, \"$SCROLL_TITLE\"\nreturn-void" else "return-void"
        val calls = (1..scrolls).joinToString("\n") { "invoke-virtual { p0, v1 }, $home->scroll($reason)V" }
        val handler = method(
            home, handlerName, emptyList(), "Z", if (busyLocals) 2 else 3,
            body = """
                ${if (thisReplaced) "const/4 p0, 0x0" else ""}
                invoke-virtual { p0 }, $home->ready()Z
                move-result v0
                if-eqz v0, :none
                ${if (busyLocals) "const/4 v0, 0x1" else ""}
                ${if (jumpToCall) "if-eqz v0, :call" else ""}
                sget-object v1, $reason->$reasonField:$reason
                ${if (gap) "nop" else ""}
                ${if (jumpToCall) ":call" else ""}
                $calls
                ${if (busyLocals) "return v0" else "const/4 v0, 0x1\nreturn v0"}
                :none
                const/4 v0, 0x0
                return v0
            """,
        )
        val methods = listOf(
            handler,
            method(home, "scroll", listOf(if (reasonIsEnum) reason else "Lfixture/Other;"), "V", 1, body = scrollBody),
            method(home, "ready", emptyList(), "Z", 1, body = "const/4 v0, 0x1\nreturn v0"),
            method(home, "other", emptyList(), "V", 1, body = if (title && !titleInScroll) "const-string v0, \"$SCROLL_TITLE\"\nreturn-void" else "return-void"),
        )
        val name = if (nameless) "" else """
            const-string v1, "$reasonName"
            const/4 v0, 0x0
            new-instance v5, $reason
            invoke-direct { v5, v1, v0 }, $reason-><init>(Ljava/lang/String;I)V
            sput-object v5, $reason->A02:$reason
            const-string v2, "BACK_BUTTON_PRESS_AT_TOP"
            const/4 v1, 0x1
            new-instance v0, $reason
            invoke-direct { v0, v2, v1 }, $reason-><init>(Ljava/lang/String;I)V
            sput-object v0, $reason->A03:$reason
            return-void
        """
        val enum = classDef(
            reason, listOfNotNull(
                if (nameless) null else method(reason, "<clinit>", emptyList(), "V", 6, static = true, body = name),
                method(reason, "<init>", listOf("Ljava/lang/String;", "I"), "V", 0, body = "return-void"),
            ),
            fields = listOf("A02" to reason, "A03" to reason), superclass = "Ljava/lang/Enum;",
        )
        val result = mutableListOf(classDef(home, methods), enum)
        if (twoHomes) result += classDef("Lfixture/Twin;", listOf(method("Lfixture/Twin;", "x", emptyList(), "V", 1, body = "const-string v0, \"$SCROLL_TITLE\"\nreturn-void")))
        return result
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
        if (name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(
        type: String,
        methods: List<Method>,
        fields: List<Pair<String, String>> = emptyList(),
        superclass: String = "Ljava/lang/Object;",
    ): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null) },
            methods,
        )

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")
}
