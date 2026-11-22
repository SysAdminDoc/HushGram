/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.feed.commentgifs.DROPS_COMMENT
import app.morphe.patches.instagram.feed.commentgifs.HIDING
import app.morphe.patches.instagram.feed.commentgifs.ROW_MISSING_INFO
import app.morphe.patches.instagram.feed.commentgifs.findCommentRow
import app.morphe.patches.instagram.feed.commentgifs.hideCommentRowGifs
import app.morphe.patches.instagram.misc.comment.GIPHY
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Also in comments (#807): on every build the comment row builder asks whether comments are read, hands
 * the comment's words and author to the extension and answers no row for one it drops, beside Hide GIFs
 * in comments' hook in the same builder, whichever patch goes in first.
 */
class CaptionWordCommentsTest {
    /** Both hooks the comment part writes are in the CaptionWordReels the bundle ships, public and static. */
    @Test
    fun theCommentHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CAPTION_WORD_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(COMMENTS_ON, DROPS_WORDS_COMMENT)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachBuildDropsCommentsByWordsBesideTheGifHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every ${Fixtures.VERSION} build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val classes = slice(bundle)
            for (wordsFirst in listOf(true, false)) {
                val name = "${bundle.name}, ${if (wordsFirst) "words" else "GIFs"} first"
                val context = PatchContexts.of(classes)
                val plan = if (wordsFirst) {
                    context.findCommentWords().also { context.hideCommentWords(it) }.also { context.hideCommentRowGifs(context.findCommentRow()) }
                } else {
                    context.hideCommentRowGifs(context.findCommentRow())
                    context.findCommentWords().also { context.hideCommentWords(it) }
                }
                val builder = context.mutableClassDefBy(plan.type).methods.single {
                    it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(CharSequence::toString) == plan.parameters
                }
                val code = builder.code()
                for (hook in listOf(COMMENTS_ON, DROPS_WORDS_COMMENT, HIDING, DROPS_COMMENT)) {
                    assertEquals("$name: $hook", 1, code.count { it.referenceText() == hook })
                }
                assertEquals("$name: the last one in asks first", if (wordsFirst) HIDING else COMMENTS_ON, code[0].referenceText())

                val on = code.indexOfFirst { it.referenceText() == COMMENTS_ON }
                assertEquals("$name: its answer", Opcode.MOVE_RESULT, code[on + 1].opcode)
                assertEquals("$name: off goes on to the builder", Opcode.IF_EQZ, code[on + 2].opcode)
                assertEquals("$name: the raw comment", Opcode.MOVE_OBJECT_FROM16, code[on + 3].opcode)
                assertEquals("$name: the raw comment's register", builder.parameterRegisterNumber(plan.comment),
                    (code[on + 3] as TwoRegisterInstruction).registerB)
                assertEquals("$name: its words", plan.text, code[on + 4].referenceText())
                assertEquals("$name: as text", "Ljava/lang/String;", code[on + 4].methodReference()?.returnType)
                assertEquals("$name: its author", plan.user, code[on + 6].referenceText())
                assertEquals("$name: as a user", USER, code[on + 6].methodReference()?.returnType)
                assertEquals("$name: handed over", DROPS_WORDS_COMMENT, code[on + 8].referenceText())
                assertEquals("$name: its answer", Opcode.MOVE_RESULT, code[on + 9].opcode)
                assertEquals("$name: kept goes on", Opcode.IF_EQZ, code[on + 10].opcode)
                assertEquals("$name: no row", Opcode.CONST_4, code[on + 11].opcode)
                assertEquals("$name: no row's answer", Opcode.RETURN_OBJECT, code[on + 12].opcode)
            }
        }
    }

    /**
     * What both patches read in [bundle]: the row builder's class and its parameters' classes, the
     * comment classes and their parents up to Pando's tree, the classes the comment's getters answer,
     * Media and the one-call helpers the tree's getters go through.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val pool = linkedMapOf<String, ClassDef>()
        FixtureDex.classesLoading(bundle, ROW_MISSING_INFO).forEach { pool[it.type] = it }
        val parameters = pool.values.flatMap { it.methods }.flatMap { it.parameterTypes }.map(Any::toString).filter { it.startsWith("L") }.toSet()
        val params = FixtureDex.classes(bundle, parameters)
        pool.putAll(params)
        val raws = params.values.filter { type -> type.methods.any { it.parameterTypes.isEmpty() && it.returnType == GIPHY } }.map { it.type }.toSet()
        val parents = mutableMapOf<String, String>()
        FixtureDex.forEach(bundle) { dex ->
            for (type in dex.classes) {
                type.superclass?.let { parents[type.type] = it }
                if (type.interfaces.any(raws::contains)) pool[type.type] = ImmutableClassDef.of(type)
            }
        }
        val chain = mutableSetOf<String>()
        for (type in pool.values) {
            var parent = type.superclass
            while (parent != null && chain.add(parent)) parent = parents[parent]
        }
        val answers = pool.values.filter { it.type in raws }.flatMap { it.methods }.map { it.returnType }.filter { it.startsWith("L") }.toSet()
        pool.putAll(FixtureDex.classes(bundle, chain + answers + MEDIA - pool.keys))
        val helpers = pool.values.flatMap { it.methods }.map { it.code() }.filter { it.size == 3 }
            .mapNotNull { code -> code[0].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.methodReference()?.definingClass }.toSet()
        pool.putAll(FixtureDex.classes(bundle, helpers - pool.keys))
        return FixtureDex.withStringPools(bundle, pool.values)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.referenceText(): String? = methodReference()?.toString()
}
