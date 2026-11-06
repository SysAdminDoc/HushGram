/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.mediawalk

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.direct.reels.MEDIA
import app.morphe.patches.instagram.direct.reels.THREAD_KEY
import app.morphe.patches.instagram.feed.carousel.REBOUND_VIEW_PAGER
import app.morphe.patches.instagram.misc.extension.localRegisterCount
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Swipe through a chat's photos and posts (#350): the viewer's opener takes the chat's pages from the
 * extension first thing, its pager goes through the extension as it's readied, the drag delegate asks
 * the extension before a swipe starts a dismiss, and each stub reaches what Instagram's own code does.
 */
class ChatMediaWalkHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(MEDIA_WALK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$MEDIA_WALK->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(WALK_OPEN, WALK_START, WALK_PAGER, WALK_HOLD)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(MEDIA_WALK))).findMediaWalk() }
        assertTrue(refused.message, refused.message!!.contains(MEDIA_VIEWER))
    }

    @Test
    fun eachDeclaredBuildHooksTheOpenerThePagerAndTheDrag() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksThemToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun hooks(bundle: File, label: String) {
        val context = PatchContexts.of(load(bundle) + ExtensionDex.classDef(MEDIA_WALK))
        val site = context.findMediaWalk()

        val open = { context.mutableClassDefBy(MEDIA_VIEWER).methods.single { it.name == site.open.name && it.parameterTypes.map(Any::toString) == site.open.parameterTypes.map(Any::toString) } }
        val hold = { context.mutableClassDefBy(site.hold.definingClass).methods.single { it.name == site.hold.name && it.parameterTypes.map(Any::toString) == site.hold.parameterTypes.map(Any::toString) } }
        val openBefore = NeutralNativePath(open())
        val holdBefore = NeutralNativePath(hold())
        val locals = site.open.localRegisterCount()
        val modeNumber = locals + site.mode.removePrefix("p").toInt()
        val listNumber = locals + site.list.removePrefix("p").toInt()
        val indexNumber = locals + site.index.removePrefix("p").toInt()
        val keyNumber = locals + site.key.removePrefix("p").toInt()
        val types = site.open.parameterTypes.map(Any::toString)
        assertEquals("$label: the key", THREAD_KEY, types[site.key.removePrefix("p").toInt() - 1])
        assertEquals("$label: the list", "Ljava/util/List;", types[site.list.removePrefix("p").toInt() - 1])
        assertEquals("$label: the index", "I", types[site.index.removePrefix("p").toInt() - 1])
        assertEquals("$label: the mode", "Ljava/lang/Integer;", types[site.mode.removePrefix("p").toInt() - 1])
        assertTrue("$label: the mode a walk hands the viewer", site.albumMode.endsWith(":Ljava/lang/Integer;"))
        assertTrue("$label: the pager's readied in the opener", site.open.code()[site.pagerWrite].fieldText()!!.startsWith("$REBOUND_VIEW_PAGER->"))
        assertTrue("$label: the opener reads that mode itself", site.open.code().any { it.opcode == Opcode.SGET_OBJECT && it.fieldText() == site.albumMode })

        context.applyMediaWalk(site)

        // The opener: the chat's pages go in the list, the tapped slot in the index, the album mode in the mode.
        val steps = open().code()
        assertEquals(
            "$label: the opener asks the extension first",
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQ, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.MOVE_FROM16,
                Opcode.SGET_OBJECT, Opcode.MOVE_OBJECT_FROM16,
            ),
            steps.take(ENTRY_HOOK).map { it.opcode },
        )
        assertEquals("$label: open", WALK_OPEN, steps[4].referenceText())
        assertEquals("$label: this goes first", locals, (steps[0] as TwoRegisterInstruction).registerB)
        assertEquals("$label: then the key", keyNumber, (steps[1] as TwoRegisterInstruction).registerB)
        assertEquals("$label: then the list", listNumber, (steps[2] as TwoRegisterInstruction).registerB)
        assertEquals("$label: then the index", indexNumber, (steps[3] as TwoRegisterInstruction).registerB)
        assertEquals("$label: nothing changes on the list it was given", listOf(0, 2), (steps[6] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$label: the pages replace the list", listNumber, (steps[7] as OneRegisterInstruction).registerA)
        assertEquals("$label: startIndex", WALK_START, steps[8].referenceText())
        assertEquals("$label: the slot replaces the index", indexNumber, (steps[10] as OneRegisterInstruction).registerA)
        assertEquals("$label: album mode", site.albumMode, steps[11].referenceText())
        assertEquals("$label: album mode replaces the mode", modeNumber, (steps[12] as OneRegisterInstruction).registerA)
        assertEquals("$label: open once", 1, steps.count { it.referenceText() == WALK_OPEN })

        val pagerHook = steps.indices.single { steps[it].referenceText() == WALK_PAGER }
        assertEquals("$label: the pager goes to the extension right after the opener readies it", site.pagerWrite + ENTRY_HOOK + 1, pagerHook)
        assertEquals("$label: the pager register", site.pagerRegister, (steps[pagerHook] as RegisterRangeInstruction).startRegister)
        assertEquals("$label: the viewer's own pager write stays", REBOUND_VIEW_PAGER, (steps[pagerHook - 1].fieldText() ?: "").substringBefore("->"))
        openBefore.assertPreserved(label, open(), (0 until ENTRY_HOOK).toSet() + pagerHook)

        // A drag answers no while the extension says so, and otherwise runs Instagram's own check.
        val drag = hold().code()
        assertEquals(
            "$label: the drag asks the extension first",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
            drag.take(HOLD_HOOK).map { it.opcode },
        )
        assertEquals("$label: holdDismiss", WALK_HOLD, drag[0].referenceText())
        holdBefore.assertPreserved(label, hold(), (0 until HOLD_HOOK).toSet())

        // Each stub's body is what its name says, with no leftover of the extension's own stand-in.
        val walk = context.mutableClassDefBy(MEDIA_WALK)
        fun stub(name: String) = walk.methods.single { it.name == name }
        fun calls(name: String) = stub(name).code().mapNotNull { it.referenceText() }
        for (written in site.stubs) {
            val body = stub(written.name)
            assertEquals("$label: ${written.name}'s registers", written.registers, body.implementation!!.registerCount)
            assertTrue("$label: ${written.name} ends in a return", body.code().last().opcode.let { it == Opcode.RETURN_OBJECT || it == Opcode.RETURN || it == Opcode.RETURN_WIDE || it == Opcode.RETURN_VOID })
        }
        assertEquals("$label: every stub is written", STUBS, site.stubs.map { it.name }.toSet())

        val message = calls("kind").first()
        assertTrue("$label: kind reads the message's own field, by name", calls("kind").last().endsWith("Ljava/lang/Enum;->name()Ljava/lang/String;"))
        assertTrue("$label: time is long", calls("time").last().endsWith("()J"))
        assertTrue("$label: expiring is the message's own", calls("expiring").last().endsWith("()Z"))
        assertEquals("$label: the three message stubs read the same class", 1, listOf(calls("time").first(), calls("expiring").first(), message).distinct().size)

        val plain = calls("plainItem").last()
        assertTrue("$label: plainItem takes a message and a Long", plain.contains("(${message}Ljava/lang/Long;)"))
        assertTrue("$label: legacyMedia reads media", calls("legacyMedia").last().endsWith("()$MEDIA"))
        assertTrue("$label: attachment is static on the message", calls("attachment").last().contains("(${message})"))
        assertTrue("$label: previewId is a long", calls("previewId").last().endsWith(":J"))
        assertTrue("$label: preview is an image", calls("preview").last().endsWith(":Lcom/instagram/model/mediasize/ExtendedImageUrl;"))
        assertTrue("$label: previewMedia makes media of an attachment", calls("previewMedia").last().endsWith(")$MEDIA"))
        assertTrue("$label: messages asks for the unsorted list", stub("messages").code().any { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 })
        assertTrue("$label: messages reads the store", calls("messages").last().endsWith("($THREAD_KEY" + "Z)Ljava/util/List;"))
        assertEquals("$label: the viewer's session", 1, calls("session").count { it.endsWith(":Lcom/instagram/common/session/UserSession;") })
        assertTrue("$label: factory takes the viewer's activity", calls("factory").any { it.endsWith(":Landroidx/fragment/app/FragmentActivity;") })
        assertTrue("$label: itemId reads the page's media id", calls("itemId").last() == "$MEDIA->getId()Ljava/lang/String;")
        assertTrue("$label: scroll names a direction", calls("scroll").any { it.contains("->valueOf(Ljava/lang/String;)") })
        assertTrue("$label: scroll sets it", calls("scroll").last().startsWith("$REBOUND_VIEW_PAGER->setScrollDirection("))

        // The shared post's page: the same builder the plain page goes through, in the same argument order.
        val shared = stub("sharedItem").code()
        val call = shared.single { it.opcode == Opcode.INVOKE_STATIC_RANGE } as RegisterRangeInstruction
        assertEquals("$label: every argument", 20, call.registerCount)
        assertEquals("$label: the range starts where the body builds it", 2, call.startRegister)
        val called = (call as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: the fifth argument is media", MEDIA, called.parameterTypes[4].toString())
        assertEquals("$label: the page", called.returnType, plain.substringAfterLast(")"))
        assertTrue("$label: stubs fit the frames they use", site.stubs.all { (stub(it.name).implementation?.registerCount ?: 99) <= 25 })
    }

    /** Everything the finders walk through, with every class a finder picks among so a second match refuses here too. */
    private fun load(bundle: File): List<ClassDef> {
        val named = FixtureDex.classes(bundle, setOf(MEDIA_VIEWER, REBOUND_VIEW_PAGER, MEDIA))
        val viewer = named.getValue(MEDIA_VIEWER)
        val held = FixtureDex.classes(bundle, viewer.fields.map { it.type }.filter { it.startsWith("L") }.toSet())
        val pager = named.getValue(REBOUND_VIEW_PAGER)
        val direction = pager.methods.single { it.name == "setScrollDirection" }.parameterTypes.single().toString()
        val anchored = listOf(DRAG_NAN, ATTACHMENT_CAST, THREAD_ENTRY).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        val builderTouches = held.values.flatMap { classDef -> classDef.methods.flatMap { it.typesTouched() } }.toSet()
        val viewerTouches = viewer.methods.flatMap { it.typesTouched() }.toSet()
        val reached = FixtureDex.classes(bundle, builderTouches + viewerTouches + direction)
        val converters = FixtureDex.methodsWhere(bundle, { true }) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == MEDIA && method.parameterTypes.size == 2 &&
                method.parameterTypes[0].toString() == "Lcom/instagram/common/session/UserSession;" &&
                method.code().any { it.referenceText()?.startsWith("$MEDIA_CACHE->") == true }
        }
        val convertersOwners = FixtureDex.classes(bundle, converters.map { it.definingClass }.toSet())
        val fromAnchors = FixtureDex.classes(bundle, anchored.flatMap { classDef -> classDef.methods.flatMap { it.typesTouched() } }.toSet())
        val fieldsOwners = FixtureDex.classes(bundle, convertersOwners.values.flatMap { classDef -> classDef.methods.flatMap { it.typesTouched() } }.toSet())
        return FixtureDex.withStringPools(
            bundle,
            (named.values + held.values + anchored + reached.values + convertersOwners.values + fromAnchors.values + fieldsOwners.values).distinctBy { it.type },
        ).distinctBy { it.type }
    }

    private fun Method.typesTouched(): List<String> = code().mapNotNull { step ->
        when (val reference = (step as? ReferenceInstruction)?.reference) {
            is FieldReference -> reference.definingClass
            is MethodReference -> reference.definingClass
            is TypeReference -> reference.type
            else -> null
        }
    }.filter { it.startsWith("L") }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.fieldText(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()

    private companion object {
        const val ENTRY_HOOK = 13
        const val HOLD_HOOK = 5
        val STUBS = setOf(
            "session", "factory", "messages", "kind", "time", "expiring", "plainItem", "legacyMedia", "attachment",
            "previewId", "preview", "previewMedia", "sharedItem", "itemId", "scroll",
        )
    }
}
