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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Like button's handler asks LikeConfirm.hold first and returns while it holds the tap, and the
 * extension's stubs reach the handler's screen and call it again.
 */
class AskBeforeLikeHookTest {
    @Test
    fun theLikeButtonAsksFirstAndItsStubsReachTheHandler() {
        val context = PatchContexts.of(listOf(handler(), position(), fragment(), extension()))
        context.askBeforeLike()

        val code = context.handlerCode()!!
        assertEquals(listOf(HOLD_LIKE, "move-result", "if-eqz", "return-void", "const-string"), code.take(5).map(::shape))
        val hold = code[0] as RegisterRangeInstruction
        assertEquals("hold takes the handler and its five values", 6, hold.registerCount)
        assertEquals("starting at the handler itself", HANDLER_REGISTERS - 6, hold.startRegister)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("once", 1, code.count { it.calls(HOLD_LIKE) })

        val screen = context.stub(CONTEXT_OF_STUB)
        assertEquals(listOf("check-cast", "iget-object", "if-nez", "const/4", "return-object", "invoke-virtual", "move-result-object", "return-object"),
            screen.take(8).map { it.opcode.name })
        assertEquals("$HANDLER->A00:$FRAGMENT", screen[1].referenced())
        assertEquals(GET_CONTEXT, screen[5].referenced())

        val again = context.stub(LIKE_AGAIN_STUB)
        assertEquals(listOf("check-cast", "check-cast", "check-cast", "check-cast", "invoke-virtual/range", "return-void"),
            again.take(6).map { it.opcode.name })
        assertEquals(listOf(HANDLER, MEDIA, POSITION, FUNCTION0), again.take(4).map { it.referenced() })
        assertEquals("$HANDLER->onLike($MEDIA${POSITION}Ljava/lang/String;${FUNCTION0}I)V", again[4].referenced())
        assertEquals(6, (again[4] as RegisterRangeInstruction).registerCount)
    }

    @Test
    fun theLikeButtonOnAReelAsksFirstAndItsStubCallsItAgain() {
        val context = PatchContexts.of(listOf(reelHandler(), reelClass(ITEM), reelClass(STATE), reelClass(SOURCE), extension()))
        askBeforeReelLike(context.findReelLikeButton())

        val code = context.reelCode()!!
        assertTrue("hold first", code[0].calls(HOLD_REEL_LIKE))
        val hold = code[0] as RegisterRangeInstruction
        assertEquals("holdReel takes the handler and its eleven values", 12, hold.registerCount)
        assertEquals("starting at the handler itself", REEL_REGISTERS - 12, hold.startRegister)
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.CONST_STRING), code.subList(1, 5).map { it.opcode })
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("once", 1, code.count { it.calls(HOLD_REEL_LIKE) })

        val again = context.stub(LIKE_REEL_AGAIN_STUB)
        val base = context.stubRegisters(LIKE_REEL_AGAIN_STUB) - 12
        assertEquals(List(6) { Opcode.CHECK_CAST } + listOf(Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID), again.take(8).map { it.opcode })
        assertEquals(listOf(REEL_HANDLER, VIEW, ITEM, STATE, SOURCE, FUNCTION1), again.take(6).map { it.referenced() })
        assertEquals("the handler, button, three objects and callback", listOf(0, 1, 2, 3, 4, 9).map { base + it },
            again.take(6).map { (it as OneRegisterInstruction).registerA })
        assertEquals("$REEL_HANDLER->onTap(${REEL_PARAMETERS.joinToString("")})V", again[6].referenced())
        assertEquals(12, (again[6] as RegisterRangeInstruction).registerCount)
        assertEquals(base, (again[6] as RegisterRangeInstruction).startRegister)
    }

    @Test
    fun aCommentsLikeAsksFirstAndItsStubCallsItAgain() {
        val context = PatchContexts.of(listOf(commentHandler(), reelClass(COMMENT_STATE), repository(), extension()))
        askBeforeCommentLike(context.findCommentLikeButton())

        val code = context.commentCode()!!
        assertTrue("hold first", code[0].calls(HOLD_COMMENT_LIKE))
        val hold = code[0] as RegisterRangeInstruction
        assertEquals("holdComment takes the entry and its four values", 5, hold.registerCount)
        assertEquals("starting at the entry itself", COMMENT_REGISTERS - 5, hold.startRegister)
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.CONST_STRING), code.subList(1, 5).map { it.opcode })
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("once", 1, code.count { it.calls(HOLD_COMMENT_LIKE) })

        val again = context.stub(LIKE_COMMENT_AGAIN_STUB)
        val base = context.stubRegisters(LIKE_COMMENT_AGAIN_STUB) - 5
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID), again.take(4).map { it.opcode })
        assertEquals(listOf(COMMENT_HANDLER, COMMENT_STATE), again.take(2).map { it.referenced() })
        assertEquals(listOf(base, base + 1), again.take(2).map { (it as OneRegisterInstruction).registerA })
        assertEquals("$COMMENT_HANDLER->onLike(${COMMENT_PARAMETERS.joinToString("")})V", again[2].referenced())
        assertEquals(5, (again[2] as RegisterRangeInstruction).registerCount)
        assertEquals(base, (again[2] as RegisterRangeInstruction).startRegister)
    }

    @Test
    fun aCommentLikeThatIsntTheOneFailsUnchanged() {
        val others = listOf(reelClass(COMMENT_STATE), repository(), extension())
        refusesComment("no store kept", listOf(commentHandler(fields = emptyList())) + others)
        refusesComment("a static store", listOf(commentHandler(fieldFlags = PUBLIC_FINAL or AccessFlags.STATIC.value)) + others)
        refusesComment("a static entry", listOf(commentHandler(static = true)) + others)
        refusesComment("two entries", listOf(commentHandler(twice = true)) + others)
        refusesComment("an entry class that isn't public", listOf(commentHandler(classFlags = AccessFlags.FINAL.value)) + others)
        refusesComment("a boolean where the Boolean goes", listOf(commentHandler(parameters = listOf(COMMENT_STATE, "Z", STRING_TYPE, STRING_TYPE))) + others)
        refusesComment("a state that isn't in the app", listOf(commentHandler(), repository(), extension()))
        refusesComment("a state that isn't public", listOf(commentHandler(), reelClass(COMMENT_STATE, 0), repository(), extension()))
        refusesComment("no register of its own", listOf(commentHandler(registers = 5, body = "return-void")) + others)
        refusesComment("a jump onto its first instruction",
            listOf(commentHandler(body = ":top\nconst-string v0, \"like\"\nif-eqz p2, :top\nreturn-void")) + others)
        refusesComment("no extension", listOf(commentHandler(), reelClass(COMMENT_STATE), repository()))
    }

    @Test
    fun aReelLikeButtonThatIsntTheOneFailsUnchanged() {
        val others = listOf(reelClass(ITEM), reelClass(STATE), reelClass(SOURCE), extension())
        refusesReel("no handler filing the tap", listOf(reelHandler(body = "return-void")) + others)
        refusesReel("a static handler", listOf(reelHandler(static = true)) + others)
        refusesReel("two handlers", listOf(reelHandler(twice = true)) + others)
        refusesReel("a handler class that isn't public", listOf(reelHandler(classFlags = AccessFlags.FINAL.value)) + others)
        refusesReel("an int where a boolean goes", listOf(reelHandler(parameters = REEL_PARAMETERS.dropLast(1) + "I")) + others)
        refusesReel("no callback", listOf(reelHandler(parameters = REEL_PARAMETERS.take(8) + OBJECT_TYPE + REEL_PARAMETERS.drop(9))) + others)
        refusesReel("an item that isn't in the app", listOf(reelHandler(), reelClass(STATE), reelClass(SOURCE), extension()))
        refusesReel("a source that isn't public", listOf(reelHandler(), reelClass(ITEM), reelClass(STATE), reelClass(SOURCE, 0), extension()))
        refusesReel("no register of its own", listOf(reelHandler(registers = 12, body = "const-string p5, \"$REEL_LIKE_TAP\"\nreturn-void")) + others)
        refusesReel("a jump onto its first instruction",
            listOf(reelHandler(body = ":top\nconst-string v0, \"$REEL_LIKE_TAP\"\nif-eqz p10, :top\nreturn-void")) + others)
        refusesReel("no extension", listOf(reelHandler(), reelClass(ITEM), reelClass(STATE), reelClass(SOURCE)))
    }

    @Test
    fun aLikeButtonThatIsntTheOneFailsThePatchUnchanged() {
        refuses("no handler filing like_media", listOf(handler(body = "return-void"), position(), fragment(), extension()))
        refuses("a static handler", listOf(handler(static = true), position(), fragment(), extension()))
        refuses("two handlers", listOf(handler(twice = true), position(), fragment(), extension()))
        refuses("a handler class that isn't public", listOf(handler(classFlags = AccessFlags.FINAL.value), position(), fragment(), extension()))
        refuses("a position that isn't in the app", listOf(handler(), fragment(), extension()))
        refuses("a position that isn't public", listOf(handler(), position(flags = 0), fragment(), extension()))
        refuses("no fragment kept", listOf(handler(fragments = emptyList()), position(), fragment(), extension()))
        refuses("two fragments kept", listOf(handler(fragments = listOf("A00", "A01")), position(), fragment(), extension()))
        refuses("a fragment that isn't public", listOf(handler(fieldFlags = AccessFlags.FINAL.value), position(), fragment(), extension()))
        refuses("no Fragment in the app", listOf(handler(), position(), extension()))
        refuses("a Fragment without getContext", listOf(handler(), position(), fragment(getter = "requireContext"), extension()))
        refuses("no register of its own", listOf(handler(registers = 6, body = "const-string p3, \"like_media\"\nreturn-void"), position(), fragment(), extension()))
        refuses("a jump onto its first instruction", listOf(handler(body = ":top\nconst-string v0, \"like_media\"\nif-eqz p5, :top\nreturn-void"), position(), fragment(), extension()))
        refuses("no extension", listOf(handler(), position(), fragment()))
    }

    /**
     * On every declared build's fixture: the one Like button handler filing like_media takes the
     * hold first and once, and the stubs reach its fragment and call it again. The same for the
     * reel's handler filing the tap marker.
     */
    @Test
    fun eachDeclaredBuildAsksBeforeALike() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                reelBuilds(bundle, bundle.name)
                commentBuilds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksBeforeALikeToo() {
        for (base in Fixtures.otherBuilds()) {
            builds(base, base.parentFile.name)
            reelBuilds(base, base.parentFile.name)
            commentBuilds(base, base.parentFile.name)
        }
    }

    private fun builds(bundle: File, label: String) {
        val holding = FixtureDex.withStringPools(bundle, FixtureDex.classesHolding(bundle, "like_media"))
        val handler = holding.flatMap { it.methods }.single {
            it.likeShaped() && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.implementation?.instructions?.any { instruction -> instruction.referencedOrNull() == "like_media" } == true
        }
        val others = FixtureDex.classes(bundle, setOf(handler.parameterTypes[1].toString(), FRAGMENT)).values
        val context = PatchContexts.of((holding + others).distinctBy { it.type } + extension())
        context.askBeforeLike()

        val code = context.mutableClassDefBy(handler.definingClass).methods.single {
            it.name == handler.name && it.parameterTypes.map(Any::toString) == handler.parameterTypes.map(Any::toString)
        }.implementation!!.instructions.toList()
        assertTrue("$label: hold first", code[0].calls(HOLD_LIKE))
        assertEquals(label, handler.implementation!!.registerCount - 6, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals(label, 1, code.count { it.calls(HOLD_LIKE) })
        assertTrue("$label: the stub reaches the fragment", context.stub(CONTEXT_OF_STUB)[1].referenced().startsWith("${handler.definingClass}->"))
        assertTrue("$label: and calls the handler again", context.stub(LIKE_AGAIN_STUB)[4].referenced().startsWith("${handler.definingClass}->${handler.name}("))
    }

    private fun reelBuilds(bundle: File, label: String) {
        val loading = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, REEL_LIKE_TAP))
        val shaped = loading.flatMap { it.methods }.filter { it.reelShaped() }
        assertTrue("$label: a reel Like handler's shape", shaped.isNotEmpty())
        val taken = FixtureDex.classes(bundle, shaped.flatMap { it.parameterTypes.subList(1, 4).map(Any::toString) }.toSet()).values
        val context = PatchContexts.of((loading + taken).distinctBy { it.type } + extension())
        val found = context.findReelLikeButton()
        val stock = shaped.single { it.definingClass == found.handler.definingClass && it.name == found.handler.name }
        askBeforeReelLike(found)

        val code = found.handler.implementation!!.instructions.toList()
        assertTrue("$label: hold first", code[0].calls(HOLD_REEL_LIKE))
        assertEquals(label, stock.implementation!!.registerCount - 12, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals("$label: Instagram's own code follows", stock.implementation!!.instructions.first().opcode, code[4].opcode)
        assertEquals(label, 1, code.count { it.calls(HOLD_REEL_LIKE) })
        assertTrue("$label: the stub calls the handler again",
            context.stub(LIKE_REEL_AGAIN_STUB)[6].referenced().startsWith("${stock.definingClass}->${stock.name}("))
    }

    /**
     * Every class keeping the comment list's store, and the classes its like entry takes: the one
     * entry gets the hold first and once, Instagram's own code follows, and the stub calls it again.
     */
    private fun commentBuilds(bundle: File, label: String) {
        val keeping = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == COMMENT_LIST_REPOSITORY }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.fields.any { it.type == COMMENT_LIST_REPOSITORY }) keeping += ImmutableClassDef.of(classDef)
            }
        }
        val shaped = keeping.flatMap { it.methods }.filter { it.commentShaped() }
        assertEquals("$label: one comment like entry's shape beside the store", 1, shaped.size)
        val stock = shaped.single()
        val taken = FixtureDex.classes(bundle, setOf(stock.parameterTypes[0].toString())).values
        val context = PatchContexts.of((keeping + taken).distinctBy { it.type } + extension())
        val found = context.findCommentLikeButton()
        askBeforeCommentLike(found)

        val code = found.handler.implementation!!.instructions.toList()
        assertTrue("$label: hold first", code[0].calls(HOLD_COMMENT_LIKE))
        assertEquals(label, stock.implementation!!.registerCount - 5, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals("$label: Instagram's own code follows", stock.implementation!!.instructions.first().opcode, code[4].opcode)
        assertEquals(label, 1, code.count { it.calls(HOLD_COMMENT_LIKE) })
        assertTrue("$label: the stub calls the entry again",
            context.stub(LIKE_COMMENT_AGAIN_STUB)[2].referenced().startsWith("${stock.definingClass}->${stock.name}("))
    }

    private fun refusesComment(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.commentCode()?.map { it.opcode }
        val stub = context.classDefByOrNull(LIKE_CONFIRM)?.let { context.stub(LIKE_COMMENT_AGAIN_STUB).map { it.opcode } }
        assertThrows(case, PatchException::class.java) { askBeforeCommentLike(context.findCommentLikeButton()) }
        assertEquals(case, before, context.commentCode()?.map { it.opcode })
        assertEquals(case, stub, context.classDefByOrNull(LIKE_CONFIRM)?.let { context.stub(LIKE_COMMENT_AGAIN_STUB).map { it.opcode } })
    }

    private fun refusesReel(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.reelCode()?.map { it.opcode }
        val stub = context.classDefByOrNull(LIKE_CONFIRM)?.let { context.stub(LIKE_REEL_AGAIN_STUB).map { it.opcode } }
        assertThrows(case, PatchException::class.java) { askBeforeReelLike(context.findReelLikeButton()) }
        assertEquals(case, before, context.reelCode()?.map { it.opcode })
        assertEquals(case, stub, context.classDefByOrNull(LIKE_CONFIRM)?.let { context.stub(LIKE_REEL_AGAIN_STUB).map { it.opcode } })
    }

    private fun refuses(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.handlerCode()?.map(::shape)
        assertThrows(case, PatchException::class.java) { context.askBeforeLike() }
        assertEquals(case, before, context.handlerCode()?.map(::shape))
    }

    private fun shape(instruction: Instruction): String =
        if (instruction.calls(HOLD_LIKE)) HOLD_LIKE else instruction.opcode.name

    private fun Instruction.calls(reference: String) = referencedOrNull() == reference

    private fun Instruction.referencedOrNull(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        null -> null
        is StringReference -> reference.string
        else -> reference.toString()
    }

    private fun Instruction.referenced(): String = referencedOrNull()!!

    private fun Method.likeShaped(): Boolean = returnType == "V" &&
        parameterTypes.map(Any::toString).let { it.size == 5 && it[0] == MEDIA && it[2] == "Ljava/lang/String;" && it[3] == FUNCTION0 && it[4] == "I" }

    private fun BytecodePatchContext.handlerCode(): List<Instruction>? =
        classDefByOrNull(HANDLER)?.let { mutableClassDefBy(HANDLER).methods.first { it.name == "onLike" }.implementation!!.instructions.toList() }

    /** The reel handler's shape: the button, three objects, four strings, a callback and two booleans. */
    private fun Method.reelShaped(): Boolean = returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(Any::toString).let {
            it.size == 11 && it[0] == VIEW && it.subList(4, 8).all { type -> type == STRING_TYPE } && it[8] == FUNCTION1 &&
                it[9] == "Z" && it[10] == "Z"
        }

    /** The comment like entry's shape: one of Instagram's classes, a Boolean and two strings. */
    private fun Method.commentShaped(): Boolean = returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        implementation != null && parameterTypes.map(Any::toString).let {
            it.size == 4 && it[0].startsWith("L") && it[0] != BOOLEAN_TYPE && it[0] != STRING_TYPE && it[1] == BOOLEAN_TYPE &&
                it[2] == STRING_TYPE && it[3] == STRING_TYPE
        }

    private fun BytecodePatchContext.commentCode(): List<Instruction>? =
        classDefByOrNull(COMMENT_HANDLER)?.let { mutableClassDefBy(COMMENT_HANDLER).methods.first { it.name == "onLike" }.implementation!!.instructions.toList() }

    private fun BytecodePatchContext.reelCode(): List<Instruction>? =
        classDefByOrNull(REEL_HANDLER)?.let { mutableClassDefBy(REEL_HANDLER).methods.first { it.name == "onTap" }.implementation!!.instructions.toList() }

    private fun BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(LIKE_CONFIRM).methods.single { it.name == name }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.stubRegisters(name: String): Int =
        mutableClassDefBy(LIKE_CONFIRM).methods.single { it.name == name }.implementation!!.registerCount

    private companion object {
        const val HANDLER = "Lfixture/LikeHandler;"
        const val POSITION = "Lfixture/Position;"
        const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        const val HANDLER_REGISTERS = 8
        const val REEL_HANDLER = "Lfixture/ReelLikeHandler;"
        const val ITEM = "Lfixture/ReelItem;"
        const val STATE = "Lfixture/ReelState;"
        const val SOURCE = "Lfixture/ReelSource;"
        const val VIEW = "Landroid/view/View;"
        const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
        const val STRING_TYPE = "Ljava/lang/String;"
        const val OBJECT_TYPE = "Ljava/lang/Object;"
        const val REEL_REGISTERS = 14
        val REEL_PARAMETERS = listOf(VIEW, ITEM, STATE, SOURCE) + List(4) { STRING_TYPE } + listOf(FUNCTION1, "Z", "Z")
        const val COMMENT_HANDLER = "Lfixture/CommentLikeHandler;"
        const val COMMENT_STATE = "Lfixture/CommentState;"
        const val BOOLEAN_TYPE = "Ljava/lang/Boolean;"
        const val COMMENT_REGISTERS = 7
        val COMMENT_PARAMETERS = listOf(COMMENT_STATE, BOOLEAN_TYPE, STRING_TYPE, STRING_TYPE)
        val PUBLIC = AccessFlags.PUBLIC.value
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun extension(): ClassDef = ExtensionDex.classDef(LIKE_CONFIRM)

        /**
         * The Like button's handler as 450 has it: an instance method taking the post, its position,
         * the module name, a callback and an index, filing like_media, in a class keeping the screen's
         * fragment. v0 and v1 are its locals.
         */
        fun handler(
            body: String = "const-string v0, \"like_media\"\ninvoke-static { v0 }, Lfixture/Log;->file(Ljava/lang/String;)V\nreturn-void",
            static: Boolean = false,
            twice: Boolean = false,
            classFlags: Int = PUBLIC_FINAL,
            fragments: List<String> = listOf("A00"),
            fieldFlags: Int = PUBLIC_FINAL,
            registers: Int = HANDLER_REGISTERS,
        ): ClassDef {
            val parameters = listOf(MEDIA, POSITION, "Ljava/lang/String;", FUNCTION0, "I")
            val flags = if (static) PUBLIC_FINAL or AccessFlags.STATIC.value else PUBLIC_FINAL
            val methods = mutableListOf(method(HANDLER, "onLike", parameters, "V", registers, flags, body))
            if (twice) methods += method(HANDLER, "onLikeAgain", parameters, "V", registers, flags, body)
            val fields = fragments.map { ImmutableField(HANDLER, it, FRAGMENT, fieldFlags, null, null, null) }
            return ImmutableClassDef(HANDLER, classFlags, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        /**
         * The Like button's handler on a reel as 454 has it: an instance method taking the button,
         * three of Instagram's classes, four strings, a callback and two booleans, filing the tap
         * marker. v0 and v1 are its locals.
         */
        fun reelHandler(
            body: String = "const-string v0, \"$REEL_LIKE_TAP\"\ninvoke-static { v0 }, Lfixture/Log;->file(Ljava/lang/String;)V\nreturn-void",
            static: Boolean = false,
            twice: Boolean = false,
            classFlags: Int = PUBLIC_FINAL,
            parameters: List<String> = REEL_PARAMETERS,
            registers: Int = REEL_REGISTERS,
        ): ClassDef {
            val flags = if (static) PUBLIC_FINAL or AccessFlags.STATIC.value else PUBLIC_FINAL
            val methods = mutableListOf(method(REEL_HANDLER, "onTap", parameters, "V", registers, flags, body))
            if (twice) methods += method(REEL_HANDLER, "onTapAgain", parameters, "V", registers, flags, body)
            return ImmutableClassDef(REEL_HANDLER, classFlags, "Ljava/lang/Object;", null, null, null, null, methods)
        }

        /**
         * The comment list's like entry as 454 has it: an instance method taking the comment's state,
         * a Boolean and two strings, in a class keeping the comment list's store. v0 and v1 are its
         * locals.
         */
        fun commentHandler(
            body: String = "const-string v0, \"like\"\ninvoke-static { v0 }, Lfixture/Log;->file(Ljava/lang/String;)V\nreturn-void",
            static: Boolean = false,
            twice: Boolean = false,
            classFlags: Int = PUBLIC_FINAL,
            fields: List<String> = listOf("A0E"),
            fieldFlags: Int = PUBLIC_FINAL,
            parameters: List<String> = COMMENT_PARAMETERS,
            registers: Int = COMMENT_REGISTERS,
        ): ClassDef {
            val flags = if (static) PUBLIC_FINAL or AccessFlags.STATIC.value else PUBLIC_FINAL
            val methods = mutableListOf(method(COMMENT_HANDLER, "onLike", parameters, "V", registers, flags, body))
            if (twice) methods += method(COMMENT_HANDLER, "onLikeAgain", parameters, "V", registers, flags, body)
            val kept = fields.map { ImmutableField(COMMENT_HANDLER, it, COMMENT_LIST_REPOSITORY, fieldFlags, null, null, null) }
            return ImmutableClassDef(COMMENT_HANDLER, classFlags, "Ljava/lang/Object;", null, null, null, kept, methods)
        }

        fun repository(): ClassDef = reelClass(COMMENT_LIST_REPOSITORY)

        fun reelClass(type: String, flags: Int = PUBLIC_FINAL): ClassDef =
            ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, null, null)

        fun position(flags: Int = PUBLIC_FINAL): ClassDef =
            ImmutableClassDef(POSITION, flags, "Ljava/lang/Object;", null, null, null, null, null)

        fun fragment(getter: String = "getContext"): ClassDef = ImmutableClassDef(
            FRAGMENT, PUBLIC, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(FRAGMENT, getter, emptyList(), "Landroid/content/Context;", 2, PUBLIC, "const/4 v0, 0x0\nreturn-object v0")),
        )

        fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
