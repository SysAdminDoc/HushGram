/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.*
import org.junit.Test

/**
 * #402: the comment box's gallery pick, shaped as 454's lambda case is. It reads the picked file's
 * path out of its Medium inside a try, scales the file, saves the still as comment_image_ and attaches
 * it to the screen, and the handler attaches whatever the still register holds. The hook goes right
 * after the path read and, on a 1, leaves through the shared Unit return with nothing attached.
 */
class CommentGifPickHookTest {
    private val public = AccessFlags.PUBLIC.value
    private val screen = "Ltest/PickScreen;"
    private val lambda = "Ltest/PickLambda;"
    private val medium = "Lcom/instagram/common/gallery/Medium;"
    private val unitExit = "sget-object v4, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;"
    private val screenCast = "check-cast v1, $screen"
    private val pathRead = "iget-object v0, v2, $medium->path:$STRING"
    private val attach = "invoke-virtual { v1, v3 }, $screen->attach($medium)V"

    // v0 the path, v1 the screen, v2 the Medium (read again after the scaling), v3 the still the
    // handler attaches. v3 is dead on the normal path at the hook but live through the handler.
    private val pick = """
        goto :start
        :exit
        $unitExit
        return-object v4
        :start
        iget-object v1, p0, $lambda->screen:$OBJECT
        $screenCast
        iget-object v2, p0, $lambda->medium:$OBJECT
        check-cast v2, $medium
        const/4 v3, 0x0
        :read
        $pathRead
        :scale
        const/16 v4, 0x2d0
        const/4 v5, 0x0
        invoke-static { v0, v4, v4, v5 }, Ltest/PickScaler;->scale(${STRING}III)Landroid/graphics/Bitmap;
        move-result-object v6
        const-string v4, "comment_image_"
        invoke-static { v4, v2, v6 }, Ltest/PickScaler;->still($STRING${medium}Landroid/graphics/Bitmap;)$medium
        move-result-object v3
        goto :attach
        move-exception v4
        :attach
        $attach
        goto :exit
    """

    /** The screen and the lambda, its try running from the path read to the handler. */
    private fun world(body: String = pick, attachFlags: Int = public, extension: Boolean = true): List<ClassDef> {
        val screenClass = CommentWorld.clazz(screen, methods = listOf(
            CommentWorld.method(screen, "attach", listOf(medium), "V", 2, attachFlags, "return-void")))
        val built = CommentWorld.method(lambda, "invoke", emptyList(), OBJECT, 8, public, body)
        val code = built.code()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val start = code.indexOfFirst { it.opcode == Opcode.CONST_4 && (it as OneRegisterInstruction).registerA == 3 } + 1
        val handler = code.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
        val tries = listOf(ImmutableTryBlock(addresses[start], addresses[handler] - addresses[start],
            listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", addresses[handler]))))
        val invoke = ImmutableMethod(lambda, "invoke", emptyList(), OBJECT, public, null, null,
            ImmutableMethodImplementation(8, code, tries, null))
        val fields = listOf("screen", "medium").map { ImmutableField(lambda, it, OBJECT, public, null, null, null) }
        val lambdaClass = CommentWorld.clazz(lambda, fields = fields, methods = listOf(invoke))
        val bridges = if (extension) listOf(COMMENT_GIF_PICK, GIF_REPLY_NATIVE) else listOf(GIF_REPLY_NATIVE)
        return listOf(screenClass, lambdaClass) + bridges.map(ExtensionDex::classDef)
    }

    /** Only the GIF reply's screen matters to the pick. */
    private fun reply(): GifReplyPlan {
        fun call(name: String) = ImmutableMethodReference(screen, name, emptyList(), "V")
        return GifReplyPlan(ImmutableMethodReference(screen, "select", listOf(STRING, STRING, "F", "Z"), "V"), 0,
            emptyList(), emptyList(), call("reply"), ImmutableFieldReference(screen, "composer", OBJECT), call("target"),
            call("toHelper"), call("toInfo"), false, call("send"), 0, 0)
    }

    private fun invoke(patch: BytecodePatchContext) = patch.mutableClassDefBy(lambda).methods.single { it.name == "invoke" }

    private fun kind(instruction: Instruction) = listOf(
        if (instruction.opcode == Opcode.GOTO_16 || instruction.opcode == Opcode.GOTO_32) Opcode.GOTO else instruction.opcode,
        instruction.reference()?.toString())

    @Test fun aKeptGifEndsThePickAtTheUnitReturnAndClearPickAttachesNothing() {
        val patch = PatchContexts.of(world())
        val stock = invoke(patch).code()
        val untouched = listOf(screen, COMMENT_GIF_PICK)
        val before = CommentWorld.snapshot(patch, untouched)
        var plan: GifPickPlan? = null
        val warnings = PatchLogCapture.warnings { plan = discovering(PHOTO_PATCH) { patch.gifPickOrNull(reply(), patch.classPool()) } }
        assertEquals(emptyList<String>(), warnings)
        val found = plan ?: error("the gallery pick wasn't found")
        val readAt = stock.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.field()?.name == "path" }
        assertEquals("right after the path read", readAt + 1, found.hookAt)
        assertEquals(listOf(0, 1, 1), listOf(found.path, found.screen, found.exit))
        assertEquals("$screen->attach($medium)V", found.pick.toString())
        assertEquals(2, found.spares.distinct().size)
        assertTrue("${found.spares}: the path and screen stay put", found.spares.none { it == 0 || it == 1 })
        assertFalse("${found.spares}: the handler still attaches v3", 3 in found.spares)
        assertTrue("${found.spares}: 4-bit, and dead at the hook", found.spares.all { it in 4..7 })

        discovering(PHOTO_PATCH) { patch.applyGifPick(found) }
        val method = invoke(patch)
        val code = method.code()
        val hook = code.subList(found.hookAt, found.hookAt + 5)
        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
            Opcode.IF_NEZ), hook.map { it.opcode })
        assertEquals(listOf(0, 1), hook.take(2).map { (it as TwoRegisterInstruction).registerB })
        assertEquals(found.spares, hook.take(2).map { (it as TwoRegisterInstruction).registerA })
        assertEquals(GIF_PICKED, hook[2].call().toString())
        assertEquals(found.spares, hook[2].arguments())
        assertEquals(found.spares[0], (hook[3] as OneRegisterInstruction).registerA)
        assertEquals(found.spares[0], (hook[4] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("a sent GIF leaves through the Unit return", addresses[found.exit],
            addresses[found.hookAt + 4] + (hook[4] as OffsetInstruction).codeOffset)
        assertEquals(Opcode.SGET_OBJECT, code[found.exit].opcode)
        assertEquals(stock.map(::kind), (code.take(found.hookAt) + code.drop(found.hookAt + 5)).map(::kind))
        val tries = method.implementation!!.tryBlocks.single()
        assertTrue("the hook sits in the stock try, as the read does",
            addresses[found.hookAt] in tries.startCodeAddress until tries.startCodeAddress + tries.codeUnitCount)

        val clear = patch.mutableClassDefBy(GIF_REPLY_NATIVE).methods.single { it.name == "clearPick" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), clear.map { it.opcode })
        assertEquals(screen, (clear[0].reference() as TypeReference).type)
        assertEquals(found.pick.toString(), clear[2].call().toString())
        assertEquals(before, CommentWorld.snapshot(patch, untouched))
    }

    /** 454's armeabi-v7a build reads its Unit as LX/00h7;->A00, a static holding its own class. */
    @Test fun aUnitRedexRenamedIsStillThePicksReturn() {
        val renamed = "sget-object v4, LX/00h7;->A00:LX/00h7;"
        val patch = PatchContexts.of(world(pick.replace(unitExit, renamed)))
        var plan: GifPickPlan? = null
        val warnings = PatchLogCapture.warnings { plan = discovering(PHOTO_PATCH) { patch.gifPickOrNull(reply(), patch.classPool()) } }
        assertEquals(emptyList<String>(), warnings)
        val found = plan ?: error("the gallery pick wasn't found")
        assertEquals(1, found.exit)
        discovering(PHOTO_PATCH) { patch.applyGifPick(found) }
        val code = invoke(patch).code()
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("a sent GIF leaves through the renamed Unit", addresses[found.exit],
            addresses[found.hookAt + 4] + (code[found.hookAt + 4] as OffsetInstruction).codeOffset)
        assertEquals("LX/00h7;->A00:LX/00h7;", code[found.exit].field().toString())
    }

    /** Any shape the hook can't prove leaves the pick and both bridges as they were, and says why. */
    @Test fun aPickItCantProveIsLeftAloneAndTheLogSaysWhy() {
        val cases = listOf(
            Triple("no CommentGifPick", world(extension = false), "missing extension boundary $COMMENT_GIF_PICK"),
            Triple("no comment_image_", world(pick.replace("\"comment_image_\"", "\"comment_still_\"")),
                "expected one comment box's gallery pick, found 0"),
            Triple("two attaches", world(pick.replace("goto :attach", "$attach\ngoto :attach")),
                "expected one gallery pick's attach call, found 2"),
            Triple("a private attach", world(attachFlags = AccessFlags.PRIVATE.value),
                "expected one comment screen's public attach, found 0"),
            Triple("a path from a string", world(pick.replace(pathRead, "const-string v0, \"/sdcard/kept.gif\"")),
                "gallery pick scales something other than the picked file's path"),
            Triple("a branch into the scaling", world(pick.replace(screenCast, "$screenCast\nif-eqz v1, :scale")),
                "a branch enters the gallery pick between its path read and its scaling"),
            Triple("an uncast screen", world(pick.replace(screenCast, "")),
                "gallery pick doesn't cast its screen before it reads the path"),
            Triple("a branch around the cast", world(pick.replace(screenCast, "if-eqz v1, :read\n$screenCast")),
                "gallery pick can reach its path read without casting its screen"),
            Triple("a return after the attach", world(pick.replace("goto :exit", "$unitExit\nreturn-object v4")),
                "gallery pick doesn't leave right after it attaches"),
            Triple("a null exit", world(pick.replace(unitExit, "const/4 v4, 0x0")),
                "gallery pick leaves somewhere other than its Unit return"),
            Triple("a static of another type", world(pick.replace(unitExit, "sget-object v4, Ltest/PickHolder;->A00:$OBJECT")),
                "gallery pick leaves somewhere other than its Unit return"),
            Triple("the Unit read but another register returned", world(pick.replace("return-object v4", "return-object v5")),
                "gallery pick leaves somewhere other than its Unit return"),
        )
        for ((case, classes, why) in cases) {
            val patch = PatchContexts.of(classes)
            val types = classes.map { it.type }
            val before = CommentWorld.snapshot(patch, types)
            var plan: GifPickPlan? = null
            val warnings = PatchLogCapture.warnings { plan = discovering(PHOTO_PATCH) { patch.gifPickOrNull(reply(), patch.classPool()) } }
            assertNull(case, plan)
            assertEquals(case, listOf("$PHOTO_PATCH: $why. Save comment photo goes in without Send saved GIFs as GIFs."), warnings)
            assertEquals(case, before, CommentWorld.snapshot(patch, types))
        }
    }
}
