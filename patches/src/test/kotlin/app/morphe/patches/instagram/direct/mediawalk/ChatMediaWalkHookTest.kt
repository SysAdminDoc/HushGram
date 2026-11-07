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
import app.morphe.patches.instagram.direct.reels.MESSAGE_ID
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
        for (hook in listOf(WALK_OPEN, WALK_START, WALK_PAGER, WALK_HOLD, WALK_TAP)) assertTrue("$hook is not in the extension: $declared", hook in declared)
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
        val tap = { context.mutableClassDefBy(site.tap.definingClass).methods.single { it.name == site.tap.name && it.parameterTypes.map(Any::toString) == site.tap.parameterTypes.map(Any::toString) } }
        val openBefore = NeutralNativePath(open())
        val holdBefore = NeutralNativePath(hold())
        val tapBefore = NeutralNativePath(tap())
        val tapLocals = site.tap.localRegisterCount()
        val tapTypes = site.tap.parameterTypes.map(Any::toString)
        assertEquals("$label: where the tapped post sits", "Landroid/graphics/RectF;", tapTypes[site.tapRect.removePrefix("p").toInt() - 1])
        assertEquals("$label: the tapped post's view", "Landroid/view/View;", tapTypes[site.tapView.removePrefix("p").toInt() - 1])
        assertEquals("$label: the tapped post's message", MESSAGE_ID, tapTypes[site.tapIdentifier.removePrefix("p").toInt() - 1])
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

        // A tap on a shared post asks the extension first and ends there once the walk opened.
        val tapped = tap().code()
        assertEquals(
            "$label: the shared post's tap asks the extension first",
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            tapped.take(TAP_HOOK).map { it.opcode },
        )
        assertEquals("$label: tap", WALK_TAP, tapped[4].referenceText())
        assertEquals("$label: the delegate goes first", tapLocals, (tapped[0] as TwoRegisterInstruction).registerB)
        for ((at, register) in listOf(1 to site.tapRect, 2 to site.tapView, 3 to site.tapIdentifier)) {
            assertEquals("$label: the tap's argument $at", tapLocals + register.removePrefix("p").toInt(), (tapped[at] as TwoRegisterInstruction).registerB)
        }
        assertEquals("$label: tap once", 1, tapped.count { it.referenceText() == WALK_TAP })
        tapBefore.assertPreserved(label, tap(), (0 until TAP_HOOK).toSet())

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
        assertEquals("$label: forward turns the pager's reverse layout off", "$REBOUND_VIEW_PAGER->setReverseLayout(Z)V", calls("forward").last())
        assertEquals("$label: forward asks for false", listOf(0), stub("forward").code().filter { it.opcode == Opcode.CONST_4 }.map { (it as NarrowLiteralInstruction).narrowLiteral })
        assertTrue("$label: itemMedia reads the page's media", calls("itemMedia").last().endsWith(":$MEDIA"))

        // The shared post's page: the same builder the plain page goes through, in the same argument order.
        val shared = stub("sharedItem").code()
        val call = shared.single { it.opcode == Opcode.INVOKE_STATIC_RANGE } as RegisterRangeInstruction
        assertEquals("$label: every argument", 20, call.registerCount)
        assertEquals("$label: the range starts where the body builds it", 2, call.startRegister)
        val called = (call as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: the fifth argument is media", MEDIA, called.parameterTypes[4].toString())
        assertEquals("$label: the page", called.returnType, plain.substringAfterLast(")"))
        val flags = (18..21).map { register ->
            shared.filterIsInstance<OneRegisterInstruction>().last { it.registerA == register && it is NarrowLiteralInstruction }
                .let { (it as NarrowLiteralInstruction).narrowLiteral }
        }
        val round = shared.filterIsInstance<OneRegisterInstruction>().last { it.registerA == 17 && it is NarrowLiteralInstruction }
        assertEquals("$label: not the small round page", 0, (round as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: the flags of a page a tap makes", listOf(1, 0, 0, 1), flags)

        // A shared post's tap reads the delegate's own key, session and lookup, and opens through the delegate's photo opener.
        val delegate = site.tap.definingClass
        assertTrue("$label: mediaType reads the media's type", calls("mediaType")[1].startsWith("$MEDIA->") && calls("mediaType").last() == "Ljava/lang/Integer;->intValue()I")
        assertTrue("$label: tapSession reads the delegate's session", calls("tapSession").last().let { it.startsWith("$delegate->") && it.endsWith(":Lcom/instagram/common/session/UserSession;") })
        assertTrue("$label: tapKey reads the delegate's own field", calls("tapKey")[1].startsWith("$delegate->"))
        assertEquals("$label: tapKey answers a key", THREAD_KEY, calls("tapKey").last())
        assertTrue("$label: maker is the builder the viewer makes", calls("maker").last() in calls("factory"))
        assertTrue("$label: lookup makes its store of the session", calls("lookup")[1].contains("(Lcom/instagram/common/session/UserSession;)"))
        assertTrue("$label: lookup finds by key and id", calls("lookup").last().contains("(${THREAD_KEY}Ljava/lang/String;)"))
        assertTrue("$label: messageId reads the tapped message's id", calls("messageId").last().let { it.startsWith("$MESSAGE_ID->") && it.endsWith(":Ljava/lang/String;") })
        val shows = calls("showPage")
        assertTrue("$label: showPage opens through the delegate", shows.last().startsWith("$delegate->") && shows.last().contains("(Landroid/view/View;"))
        assertTrue("$label: showPage says where the post sits", shows.any { it.endsWith("-><init>(Landroid/graphics/RectF;F)V") })
        assertTrue("$label: showPage opens in album mode", site.albumMode in shows)
        assertTrue("$label: showPage hands the post alone", shows.any { it == "Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;" })
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
        // A shared post's tap: every photo opener of the chat's tap delegate, every lookup of a message by its id, and what they hand on.
        val tapping = FixtureDex.methodsWhere(bundle, { true }) { method ->
            val types = method.parameterTypes.map(Any::toString)
            val called = method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            (types.size == 7 && types[0] == "Landroid/view/View;" && types[3] == "Ljava/util/List;" && called.any { it.definingClass == MEDIA_VIEWER }) ||
                (called.any { it.parameterTypes.map(Any::toString) == listOf(THREAD_KEY, "Ljava/lang/String;") } &&
                    method.code().any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == MESSAGE_ID })
        }
        val tapOwners = FixtureDex.classes(bundle, tapping.map { it.definingClass }.toSet() + MESSAGE_ID)
        val handedOn = tapOwners.values.flatMap { classDef ->
            classDef.methods.flatMap { method -> method.typesTouched() + method.typesReturned() + method.parameterTypes.map(Any::toString) }
        }.filter { it.startsWith("L") }.toSet()
        val tapReached = FixtureDex.classes(bundle, handedOn)
        val opensShared = FixtureDex.classesLoading(bundle, SHARED_OPEN)
        val loaded = (named.values + held.values + anchored + reached.values + convertersOwners.values + fromAnchors.values + fieldsOwners.values +
            tapOwners.values + tapReached.values + opensShared).distinctBy { it.type }
        return FixtureDex.withStringPools(bundle, (loaded + supers(bundle, loaded)).distinctBy { it.type }).distinctBy { it.type }
    }

    /** The classes [loaded] extend that it doesn't hold, a few steps up, so a lookup's answer reaches the message class. */
    private fun supers(bundle: File, loaded: List<ClassDef>): List<ClassDef> {
        val have = loaded.mapTo(HashSet()) { it.type }
        val found = mutableListOf<ClassDef>()
        var wanted = loaded.mapNotNull { it.superclass }.filterTo(HashSet()) { it !in have }
        repeat(3) {
            if (wanted.isEmpty()) return found
            val next = FixtureDex.classes(bundle, wanted).values
            found += next
            next.mapTo(have) { it.type }
            wanted = next.mapNotNull { it.superclass }.filterTo(HashSet()) { it !in have }
        }
        return found
    }

    private fun Method.typesReturned(): List<String> = code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType }

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
        const val TAP_HOOK = 8
        val STUBS = setOf(
            "session", "factory", "messages", "kind", "time", "expiring", "plainItem", "legacyMedia", "attachment",
            "previewId", "preview", "previewMedia", "sharedItem", "itemMedia", "itemId", "scroll", "forward",
            "mediaType", "tapSession", "tapKey", "maker", "lookup", "messageId", "showPage",
        )
    }
}
