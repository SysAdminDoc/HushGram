/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.commentgifs

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.ViewIdFixture
import app.morphe.patches.instagram.misc.comment.GIPHY
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide GIFs in comments: on every build the row builder asks before it builds a row and loses a kept
 * comment's GIF and sticker, and the comment box's state hands over its picker kinds before keeping them.
 */
class HideCommentGifsPatchTest {
    /** The extension the bundle ships has the install the application hook calls. */
    @Test
    fun theExtensionHasTheInstall() {
        val install = ExtensionDex.classDef(COMMENT_GIFS).methods.single { it.name == "install" }
        assertTrue(AccessFlags.PUBLIC.isSet(install.accessFlags) && AccessFlags.STATIC.isSet(install.accessFlags))
    }

    /** The patch switches its status method on, and with nothing to hook goes in without failing. */
    @Test
    fun theStatusMethodIsSwitchedOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        hideCommentGifsPatch.execute(context)
        val status = context.method(SETTINGS_STATUS, COMMENT_GIFS_STATUS).code()
        assertEquals(1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
        val other = context.method(SETTINGS_STATUS, "hideComments").code()
        assertEquals("another patch's status is still off", 0, (other.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** Every hook the patch writes is in the CommentGifs the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(COMMENT_GIFS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HIDING, DROPS_COMMENT, KEPT_GIF, HIDES_MEDIA, COMPOSER_TYPES)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * On each build the builder's first instructions ask whether the comment gets a row, the GIF it
     * reads goes through the hook right after, a sticker's media and string are cleared right before
     * the media's constructor, and the box's state hands its kinds over before keeping them. The
     * older layout's button the view hider looks for is declared by each build too.
     */
    @Test
    fun eachBuildHooksTheRowAndTheBox() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every ${Fixtures.VERSION} build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            assertEquals("${bundle.name}: ids the build doesn't declare", emptyList<String>(),
                ViewIdFixture.missing(ViewIdFixture.resourceTable(bundle), listOf("layout_comment_thread_animated_image_picker_button")))

            val context = PatchContexts.of(slice(bundle))
            val row = context.findCommentRow()
            val composer = context.findComposer()
            context.hideCommentRowGifs(row)
            context.hideComposerGifs(composer)

            val builder = context.mutableClassDefBy(row.type).methods.single {
                it.name == row.name && it.returnType == row.returnType && it.parameterTypes.map(CharSequence::toString) == row.parameters
            }
            val code = builder.code()
            for (hook in listOf(HIDING, DROPS_COMMENT, KEPT_GIF, HIDES_MEDIA)) {
                assertEquals("${bundle.name}: $hook", 1, code.count { it.referenceText() == hook })
            }

            assertEquals("${bundle.name}: the row is asked for first", HIDING, code[0].referenceText())
            assertEquals("${bundle.name}: asks about the raw comment", Opcode.MOVE_OBJECT_FROM16, code[3].opcode)
            val drops = code.indexOfFirst { it.referenceText() == DROPS_COMMENT }
            assertEquals("${bundle.name}: its answer", Opcode.MOVE_RESULT, code[drops + 1].opcode)
            assertEquals("${bundle.name}: the way on", Opcode.IF_EQZ, code[drops + 2].opcode)
            assertEquals("${bundle.name}: no row", Opcode.CONST_4, code[drops + 3].opcode)
            assertEquals("${bundle.name}: no row's answer", Opcode.RETURN_OBJECT, code[drops + 4].opcode)

            val kept = code.indexOfFirst { it.referenceText() == KEPT_GIF }
            assertEquals("${bundle.name}: the GIF read", row.gif, code[kept - 2].referenceText())
            assertEquals("${bundle.name}: its answer", Opcode.MOVE_RESULT_OBJECT, code[kept - 1].opcode)
            assertEquals("${bundle.name}: what's handed over", row.gifRegister, (code[kept] as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: back in its register", row.gifRegister, (code[kept + 1] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: as a GIF", Opcode.CHECK_CAST, code[kept + 2].opcode)
            assertEquals("${bundle.name}: as a GIF", GIPHY, ((code[kept + 2] as ReferenceInstruction).reference as TypeReference).type)

            val media = code.indexOfFirst { it.referenceText() == HIDES_MEDIA }
            assertEquals("${bundle.name}: the sticker read", row.mediaGif, code[media - 2].referenceText())
            assertEquals("${bundle.name}: the media clears", row.mediaRegister, (code[media + 3] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: the string clears", row.stringRegister, (code[media + 4] as OneRegisterInstruction).registerA)
            val constructor = code[media + 5].methodReference()
            assertEquals("${bundle.name}: the constructor is next", "<init>", constructor?.name)
            assertTrue("${bundle.name}: ${constructor?.parameterTypes}", constructor?.parameterTypes.orEmpty().map(Any::toString).contains(MEDIA))

            val state = context.mutableClassDefBy(composer.type).methods.single {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == composer.parameters
            }
            val stateCode = state.code()
            val types = stateCode.indexOfFirst { it.referenceText() == COMPOSER_TYPES }
            assertEquals("${bundle.name}: hooks", 1, stateCode.count { it.referenceText() == COMPOSER_TYPES })
            assertEquals("${bundle.name}: what's handed over", composer.register, (stateCode[types] as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: back in its register", composer.register, (stateCode[types + 1] as OneRegisterInstruction).registerA)
            val write = stateCode[types + 2]
            assertEquals("${bundle.name}: the store", Opcode.IPUT_OBJECT, write.opcode)
            assertEquals("${bundle.name}: what's stored", composer.register, (write as TwoRegisterInstruction).registerA)
            val field = (write as ReferenceInstruction).reference as FieldReference
            assertEquals("${bundle.name}: where", KINDS_SET, field.type)
        }
    }

    /**
     * What the patch reads in [bundle]: the row builder's class and its parameters' classes, the
     * tree comment and its parents up to Pando's tree, the classes the comment's getters answer,
     * Media, the enum of the box's kinds, whatever reads its constants and the state those read.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val pool = linkedMapOf<String, ClassDef>()
        FixtureDex.classesLoading(bundle, ROW_MISSING_INFO).forEach { pool[it.type] = it }
        val parameters = pool.values.flatMap { it.methods }.flatMap { it.parameterTypes }.map(Any::toString).filter { it.startsWith("L") }.toSet()
        val params = FixtureDex.classes(bundle, parameters)
        pool.putAll(params)
        val raws = params.values.filter { type -> type.methods.any { it.parameterTypes.isEmpty() && it.returnType == GIPHY } }.map { it.type }.toSet()
        val kinds = FixtureDex.classesLoading(bundle, AVATAR_KIND).filter { type ->
            type.superclass == KINDS_ENUM_PARENT && type.fields.count { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type.type } == 2
        }
        kinds.forEach { pool[it.type] = it }
        val enums = kinds.mapTo(HashSet()) { it.type }

        val parents = mutableMapOf<String, String>()
        FixtureDex.forEach(bundle) { dex ->
            val holdsEnum = dex.typeSection.any { it in enums }
            for (type in dex.classes) {
                type.superclass?.let { parents[type.type] = it }
                val reads = holdsEnum && type.type !in enums && type.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.SGET_OBJECT && it.fieldReference()?.definingClass in enums
                    } == true
                }
                if (reads || type.interfaces.any(raws::contains)) pool[type.type] = ImmutableClassDef.of(type)
            }
        }
        val chain = mutableSetOf<String>()
        for (type in pool.values) {
            var parent = type.superclass
            while (parent != null && chain.add(parent)) parent = parents[parent]
        }
        val answers = pool.values.filter { it.type in raws }.flatMap { it.methods }.map { it.returnType }.filter { it.startsWith("L") }.toSet()
        val states = pool.values.flatMap { it.methods }.flatMap { it.code() }.mapNotNull { it.fieldReference() }
            .filter { it.type == KINDS_SET }.map { it.definingClass }.toSet()
        pool.putAll(FixtureDex.classes(bundle, chain + answers + states + MEDIA - pool.keys))
        val helpers = pool.values.flatMap { it.methods }.map { it.code() }.filter { it.size == 3 }
            .mapNotNull { code -> code[0].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.methodReference()?.definingClass }.toSet()
        pool.putAll(FixtureDex.classes(bundle, helpers - pool.keys))
        return FixtureDex.withStringPools(bundle, pool.values)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.referenceText(): String? = methodReference()?.toString()
}
