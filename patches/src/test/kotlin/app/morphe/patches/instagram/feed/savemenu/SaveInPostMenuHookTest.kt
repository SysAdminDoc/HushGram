/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.video.FEED_HELPER_NAME
import app.morphe.patches.instagram.download.video.WHY_OPTION
import app.morphe.patches.instagram.download.video.isShortMenuList
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save in the post menu (#569): the hooks are in the extension, and in every build of 454 the row's
 * call goes where anyone else's rows start, the short menu's list keeps Save and Unsave before each
 * return, and the row's stubs read the post, the options and the saved check Instagram's older menu uses.
 */
class SaveInPostMenuHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SAVE_ROW).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(OFFER_SAVE, ALLOW_SAVE, SETTLE_SAVE)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachBuildGetsTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            getsTheRow(bundle, bundle.name)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun getsTheRow(bundle: File, label: String) {
        val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(SAVE_ROW))
        FixtureDex.forEach(bundle) { dex ->
            val marked = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) == ELIGIBLE_MARKER }
            val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
            val named = dex.stringSection.any { it == FEED_HELPER_NAME }
            val options = dex.fieldSection.any { it.toString() == WHY_OPTION }
            val unsaves = dex.fieldSection.any { it.toString() == UNSAVE_OPTION }
            if (!marked && !loads && !named && !options && !unsaves && dex.classes.none { it.type == OPTION }) return@forEach
            for (classDef in dex.classes) {
                val wanted = classDef.type == OPTION || classDef.originalName() == FEED_HELPER_NAME || classDef.methods.any { method ->
                    ELIGIBLE_MARKER in method.markers() || method.isShortMenuList() ||
                        method.code().any { it.referenceText() == DOWNLOAD || it.referenceText() == UNSAVE_OPTION }
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        // The static methods R8 outlined `new ArrayList()` and getString into, and the static calls next
        // to an Unsave, among them the saved check, in a build that has them.
        val calls = classes.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        val outlined = calls.filter {
            (it.returnType == "Ljava/util/ArrayList;" && it.parameterTypes.isEmpty()) ||
                (it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/res/Resources;", "I"))
        }.map { it.definingClass }.toSet()
        val saved = classes.flatMap { it.methods }.filter { method -> method.code().any { it.referenceText() == UNSAVE_OPTION } }
            .flatMap { it.code() }.filter { it.opcode == Opcode.INVOKE_STATIC }
            .map { ((it as ReferenceInstruction).reference as MethodReference).definingClass }.toSet()
        val more = (outlined + saved).filter { type -> classes.none { it.type == type } }.toSet()
        if (more.isNotEmpty()) classes += FixtureDex.classes(bundle, more).values
        val context = PatchContexts.of(classes)

        val check = context.findSavedCheck()
        context.findFeedSaveRow().write()

        assertEquals("$label: the saved check reads a post", listOf(MEDIA), check.lookup.parameterTypes.map(Any::toString))
        val written = classes.map { it.type }.filter { it != SAVE_ROW }.distinct().flatMap { context.mutableClassDefBy(it).methods }
        val offers = written.filter { method -> method.code().any { it.referenceText() == OFFER_SAVE } }
        assertEquals("$label: the methods offering the row", 1, offers.size)
        val builder = offers.single().code()
        assertEquals("$label: one offer", 1, builder.count { it.referenceText() == OFFER_SAVE })
        // Instagram adds its own Save or Unsave to some posts after the offer, so the builder settles
        // its rows right before each return: the call sits there and hands on the register returned.
        val returns = builder.indices.filter { builder[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("$label: the builder returns its rows", returns.isNotEmpty())
        assertEquals("$label: one settle per return", returns.size, builder.count { it.referenceText() == SETTLE_SAVE })
        for (at in returns) {
            val settle = builder[at - 1]
            assertEquals("$label: settle right before the return at $at", SETTLE_SAVE, settle.referenceText())
            assertEquals(
                "$label: settle reads the list returned at $at",
                (builder[at] as OneRegisterInstruction).registerA,
                (settle as RegisterRangeInstruction).startRegister,
            )
        }
        val allows = written.filter { method -> method.code().any { it.referenceText() == ALLOW_SAVE } }
        assertEquals("$label: the short menu lists keeping the row", 1, allows.size)
        val short = allows.single().code()
        assertEquals(
            "$label: before each return of the short menu's list",
            short.count { it.opcode == Opcode.RETURN_OBJECT },
            short.count { it.referenceText() == ALLOW_SAVE },
        )

        val row = context.mutableClassDefBy(SAVE_ROW).methods
        fun stub(name: String) = row.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }.code()
        assertTrue("$label: saveOption", stub("saveOption").any { it.referenceText() == SAVE_OPTION })
        assertTrue("$label: unsaveOption", stub("unsaveOption").any { it.referenceText() == UNSAVE_OPTION })
        val savedStub = stub("saved").map { it.referenceText() }
        assertTrue("$label: saved asks the lookup", check.lookup.toString() in savedStub)
        assertTrue("$label: saved asks the test", check.test.toString() in savedStub)
        assertTrue("$label: menuMedia reads the post", stub("menuMedia").any { it.opcode == Opcode.IGET_OBJECT })
        assertTrue("$label: addRow is written", stub("addRow").size > 1)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
