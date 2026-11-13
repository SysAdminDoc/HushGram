/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.links

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.reels.sound.VIEWER_CONFIG
import app.morphe.patches.instagram.reels.sound.VIEWER_SOURCE
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.patch.BytecodePatchContext
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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenReelLinksHookTest {
    /** The hook: read the source, ask with it, take the answer, OR it into the config's flag. */
    private val hookLength = 4

    /** The hooks the patch writes are in the ReelLinks the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_LINKS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(REEL_LINKS_SKIP, REEL_LINKS_HOLD, REEL_LINKS_IN, REEL_LINKS_COMMENT)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        return bundles
    }

    /**
     * In each build of the version the method building the viewer's kept-reels controller is found by
     * its flag name, and the hook goes in once, right in front of the config's check: it reads the
     * viewer's source from the config the check read, asks with it, and ORs the answer into the
     * flag the check branches on. Every other instruction keeps its place, and the check still goes
     * where it went. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsTheConfigCheck() {
        for (apk in bundles()) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val loading = FixtureDex.classesLoading(apk, FLASH_UI_STATE).map { it.type }
            assertTrue("$name: classes loading \"$FLASH_UI_STATE\"", loading.isNotEmpty())
            val wanted = loading.toSet() + VIEWER_CONFIG
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val site = context.findReelLinksSite()
                val stockCode = site.method.code()
                val check = stockCode[site.index]
                assertEquals("$what: the config's check", Opcode.IF_NEZ, check.opcode)
                assertEquals("$what: the check reads the flag", site.flag, (check as OneRegisterInstruction).registerA)
                val flagRead = stockCode[site.index - 1]
                assertEquals("$what: the flag is the config's", VIEWER_CONFIG, (flagRead.reference() as FieldReference).definingClass)
                assertEquals("$what: read from the config register", site.config, (flagRead as TwoRegisterInstruction).registerB)
                assertEquals("$what: the source field", VIEWER_SOURCE, site.source.type)
                assertTrue("$what: the registers fit four-bit forms", listOf(site.flag, site.config, site.scratch).all { it in 0..15 })
                assertTrue("$what: the borrowed local is neither the flag nor the config", site.scratch != site.flag && site.scratch != site.config)
                val original = context.mutableClassDefBy(site.method.definingClass).methods.single {
                    it.name == site.method.name && it.returnType == site.method.returnType &&
                        it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
                }
                val stock = NeutralNativePath(original)
                context.apply { applyReelLinks(site) }

                val asking = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == REEL_LINKS_SKIP } }
                }
                assertEquals("$what: methods asking", listOf(site.method.definingClass to site.method.name),
                    asking.map { it.definingClass to it.name })
                val method = asking.single()
                val code = method.code()
                val at = site.index
                assertEquals(
                    "$what: the hook",
                    listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.OR_INT_2ADDR, Opcode.IF_NEZ),
                    code.subList(at, at + hookLength + 1).map { it.opcode },
                )
                assertEquals("$what: right after the flag is read", Opcode.IGET_BOOLEAN, code[at - 1].opcode)
                assertEquals("$what: its source", site.source.toString(), code[at].referenceText())
                assertEquals("$what: the source is read from the config", site.config, (code[at] as TwoRegisterInstruction).registerB)
                assertEquals("$what: asked with the source", listOf(site.scratch), (code[at + 1] as FiveRegisterInstruction).let {
                    listOf(it.registerC).take(it.registerCount)
                })
                val or = code[at + 3] as TwoRegisterInstruction
                assertEquals("$what: the answer goes into the flag", listOf(site.flag, site.scratch), listOf(or.registerA, or.registerB))
                assertEquals("$what: the check still reads the flag", site.flag, (code[at + hookLength] as OneRegisterInstruction).registerA)
                assertTrue("$what: a jump lands inside the hook", (at..at + hookLength).none { it in method.jumpTargets() })
                val written = code.subList(at, at + hookLength).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                    .filter { it != site.scratch && it != site.flag }
                assertTrue("$what: the hook wrote a register it didn't borrow: $written", written.isEmpty())
                stock.assertPreserved(what, method, (at until at + hookLength).toSet())
            }
        }
    }

    /**
     * In each build the call handing a chunk of reels to the viewer is found by the method's log line,
     * and the hold goes in once right in front of it: ask with the call's own eight registers, and
     * return on a yes, while a no goes on to the call. The adapter's insert, found by its flag name,
     * tells the extension first thing, with `this`. Every other instruction of both keeps its place.
     */
    @Test
    fun eachDeclaredBuildHoldsTheChunkForTheLinkedReel() {
        for (apk in bundles()) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val chunks = FixtureDex.classesLoading(apk, CHUNK_ADDED).map { it.type }
            val inserts = FixtureDex.classesLoading(apk, ITEMS_INSERTED).map { it.type }
            assertTrue("$name: classes loading \"$CHUNK_ADDED\"", chunks.isNotEmpty())
            assertTrue("$name: classes loading \"$ITEMS_INSERTED\"", inserts.isNotEmpty())
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, (chunks + inserts).toSet()).values
                val context = PatchContexts.of(classes)
                val site = context.findReelChunkSite()
                val insert = context.findLinkInsertSite(site.adapter)
                val stockCall = site.method.code()[site.index] as RegisterRangeInstruction
                assertEquals("$what: the call's adapter, head, reels and five flags", 8, stockCall.registerCount)
                assertEquals("$what: the call's first register", site.first, stockCall.startRegister)
                assertTrue("$what: the borrowed local isn't an argument", site.scratch !in site.first until site.first + 8)
                val self = insert.selfRegister()
                assertTrue("$what: the insert reads the adapter's fields from v$self", insert.code().any {
                    it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerB == self &&
                        (it.reference() as FieldReference).definingClass == site.adapter
                })
                val chunkStock = NeutralNativePath(context.mutableOf(site.method))
                val insertStock = NeutralNativePath(context.mutableOf(insert))
                context.apply {
                    applyReelChunk(site)
                    applyLinkInsert(insert)
                }

                val holding = context.methodsCalling(classes, REEL_LINKS_HOLD)
                assertEquals("$what: methods asking to hold", listOf(site.method.definingClass to site.method.name),
                    holding.map { it.definingClass to it.name })
                val method = holding.single()
                val code = method.code()
                val at = site.index
                assertEquals(
                    "$what: the hold",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID, Opcode.INVOKE_VIRTUAL_RANGE),
                    code.subList(at, at + 5).map { it.opcode },
                )
                val ask = code[at] as RegisterRangeInstruction
                val call = code[at + 4] as RegisterRangeInstruction
                assertEquals("$what: asked with the call's registers", listOf(call.startRegister, call.registerCount),
                    listOf(ask.startRegister, ask.registerCount))
                assertEquals("$what: Instagram's call", stockCall.reference().toString(), call.referenceText())
                assertEquals("$what: the answer", site.scratch, (code[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$what: the answer is checked", site.scratch, (code[at + 2] as OneRegisterInstruction).registerA)
                val branch = method.implementation!!.instructions[at + 2] as BuilderOffsetInstruction
                assertEquals("$what: a no goes on to Instagram's call", at + 4, branch.target.location.index)
                assertTrue("$what: a jump lands inside the hold", (at..at + 3).none { it in method.jumpTargets() })
                chunkStock.assertPreserved(what, method, (at until at + 4).toSet())

                val telling = context.methodsCalling(classes, REEL_LINKS_IN)
                assertEquals("$what: methods telling the linked reel is in", listOf(site.adapter to insert.name),
                    telling.map { it.definingClass to it.name })
                val told = telling.single().code()
                assertEquals("$what: told first thing", Opcode.INVOKE_STATIC_RANGE, told[0].opcode)
                val tell = told[0] as RegisterRangeInstruction
                assertEquals("$what: told with this", listOf(self, 1), listOf(tell.startRegister, tell.registerCount))
                insertStock.assertPreserved(what, telling.single(), setOf(0))
            }
        }
    }

    /**
     * In each build the tap on a comment shared in a chat is found by the comment's query parameters,
     * and the hook goes in once, right after it reads what the comment was on: it hands the extension
     * the register the read put it in, and the comparison with a reel's type comes next, as before
     * (#662). Every other instruction keeps its place.
     */
    @Test
    fun eachDeclaredBuildTellsOfSharedComments() {
        for (apk in bundles()) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val loading = FixtureDex.classesLoading(apk, COMMENT_ID).map { it.type }
            assertTrue("$name: classes loading \"$COMMENT_ID\"", loading.isNotEmpty())
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, loading.toSet()).values
                val context = PatchContexts.of(classes)
                val site = context.findSharedCommentSite()
                val stockCode = site.method.code()
                val typeRead = stockCode[site.index - 2]
                assertEquals("$what: the read", "Landroid/net/Uri;->getQueryParameter(Ljava/lang/String;)Ljava/lang/String;",
                    typeRead.referenceText())
                assertEquals("$what: its result", Opcode.MOVE_RESULT_OBJECT, stockCode[site.index - 1].opcode)
                assertEquals("$what: the result's register", site.type, (stockCode[site.index - 1] as OneRegisterInstruction).registerA)
                val stock = NeutralNativePath(context.mutableOf(site.method))
                context.apply { applySharedComment(site) }

                val telling = context.methodsCalling(classes, REEL_LINKS_COMMENT)
                assertEquals("$what: methods telling", listOf(site.method.definingClass to site.method.name),
                    telling.map { it.definingClass to it.name })
                val method = telling.single()
                val code = method.code()
                val at = site.index
                assertEquals("$what: the hook right after the read's result",
                    listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE),
                    code.subList(at - 1, at + 1).map { it.opcode })
                val tell = code[at] as RegisterRangeInstruction
                assertEquals("$what: told with what the comment was on", listOf(site.type, 1), listOf(tell.startRegister, tell.registerCount))
                assertTrue("$what: compared with a reel's type next",
                    code[at + 1].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) && code[at + 1].referenceText() == COMMENT_ON_REEL)
                assertTrue("$what: a jump lands on the hook", at !in method.jumpTargets())
                stock.assertPreserved(what, method, setOf(at))
            }
        }
    }

    private fun BytecodePatchContext.mutableOf(method: Method): MutableMethod =
        mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }

    private fun BytecodePatchContext.methodsCalling(classes: Collection<ClassDef>, hook: String): List<MutableMethod> =
        classes.flatMap { c -> mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == hook } } }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()
}
