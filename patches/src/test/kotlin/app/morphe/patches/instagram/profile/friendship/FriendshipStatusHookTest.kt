/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendshipStatusHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FRIENDSHIP_STATUS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BESIDE_PRONOUNS, IN_PLACE_OF_PRONOUNS)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun bothPlacesTheSlotSettlesGetTheLabel() {
        val context = PatchContexts.of(standIns())

        val found = context.findProfileName()
        assertEquals(SLOT, found.slot)
        assertEquals(0, found.header)
        assertEquals(HEADER, found.headerType)
        context.labelProfileName(found)

        assertLabelled("stand-in", context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }, found.header)
    }

    @Test
    fun theStubsReachTheUserAndTheSlot() {
        val context = PatchContexts.of(standIns())
        val found = context.findProfileName()
        context.friendshipStubs().fill(found)

        val extension = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods
        fun calls(stub: String) = extension.single { it.name == stub }.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertTrue(calls("profileUser").containsAll(listOf("$HEADER->model:$VIEW_MODEL", "$VIEW_MODEL->user:$USER")))
        assertTrue(calls("friendshipFollowedBy").containsAll(
            listOf("$USER->friendship()$RELATIONSHIP", "$RELATIONSHIP->followedBy()Ljava/lang/Boolean;"),
        ))
        assertTrue(calls("friendshipFollowing").containsAll(
            listOf("$USER->friendship()$RELATIONSHIP", "$RELATIONSHIP->following()Ljava/lang/Boolean;"),
        ))
        assertTrue("$USER->follows()Ljava/lang/Boolean;" in calls("followedBy"))
        assertTrue("$USER->id()Ljava/lang/String;" in calls("userId"))
        assertTrue(calls("viewerId").containsAll(
            listOf("$HEADER->session:$USER_SESSION", "$USER_SESSION->getUserId()Ljava/lang/String;"),
        ))
        assertTrue("$SLOT->getView()Landroid/view/View;" in calls("slotView"))
        assertTrue("$SLOT->setVisibility(I)V" in calls("setSlotVisibility"))
    }

    /**
     * The label reads the profile screen's own answer the way Instagram's options sheet does to offer
     * Remove follower (#40): from the header's view model to the answer's tree, made the sheet's
     * fragment and typed for its client, then the friendship status in it, and followed_by in that.
     */
    @Test
    fun theScreenStubReadsTheAnswerAsTheOptionsSheetDoes() {
        val context = PatchContexts.of(standIns())
        val found = context.findProfileName()
        val screen = context.findScreenAnswer()
        assertEquals("$VIEW_MODEL->screen:$FLOW", screen.answer.toString())
        assertEquals("$FLOW->getValue()Ljava/lang/Object;", screen.value)
        assertEquals(TREE_HOLDER, screen.holder)
        assertEquals("$TREE_HOLDER->tree:$TREE", screen.tree.toString())
        assertEquals("$TREE->fragment(I)$TREE", screen.reinterpret)
        assertEquals("$TREE->typed(Ljava/lang/String;I)$TREE", screen.retype)
        assertEquals("$TREE->tree(I)$TREE", screen.subtree)
        assertEquals("$TREE->flag(I)Ljava/lang/Boolean;", screen.flag)
        assertEquals(SHEET_TYPE, screen.type)
        assertEquals(CLIENT, screen.client)

        context.friendshipStubs().apply {
            fill(found)
            fillScreen(found, screen)
        }

        val extension = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods
        val stub = extension.single { it.name == "screenFriendship" }
        assertEquals("two registers of its own and the header", 3, stub.implementation!!.registerCount)
        val code = stub.implementation!!.instructions.toList()
        val references = code.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals(
            listOf(
                HEADER, "$HEADER->model:$VIEW_MODEL", "$VIEW_MODEL->screen:$FLOW", "$FLOW->getValue()Ljava/lang/Object;",
                TREE_HOLDER, TREE_HOLDER, "$TREE_HOLDER->tree:$TREE", "$TREE->fragment(I)$TREE", CLIENT,
                "$TREE->typed(Ljava/lang/String;I)$TREE", "$TREE->tree(I)$TREE",
            ),
            references,
        )
        val literals = code.filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral }
        assertEquals(listOf(SHEET_TYPE, FRIENDSHIP_STATUS_KEY.hashCode()), literals)
        val flag = extension.single { it.name == "statusFlag" }.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals(listOf(TREE, "$TREE->flag(I)Ljava/lang/Boolean;"), flag)
    }

    /** Without the options sheet's read the label keeps the status kept on the account, with a warning. */
    @Test
    fun withoutTheOptionsSheetTheScreenIsLeftOut() {
        val context = PatchContexts.of(standIns(sheets = 0))
        assertThrows(PatchException::class.java) { context.findScreenAnswer() }
        var answer: ScreenAnswer? = null
        val warnings = PatchLogCapture.warnings { answer = context.screenAnswerOrWarn() }
        assertEquals(null, answer)
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().endsWith("The label goes by the follow status Instagram keeps on the account."))
    }

    /** Two places reading it alike can't be told apart, so neither is taken. */
    @Test
    fun twoSheetsReadingTheAnswerAreRefused() {
        val context = PatchContexts.of(standIns(sheets = 2))
        assertThrows(PatchException::class.java) { context.findScreenAnswer() }
    }

    /** A sheet whose client isn't a string the patch can tell is left out. */
    @Test
    fun aClientThatCantBeToldIsRefused() {
        val context = PatchContexts.of(standIns(clientKnown = false))
        assertThrows(PatchException::class.java) { context.findScreenAnswer() }
    }

    /**
     * A filled stub answering something narrower than Object returns on each way out by itself. Two
     * ways joined at one return-object hand it whatever they held merged, an Object, and ART turns
     * the whole extension class down for that (a status and a Boolean met that way on the S22).
     */
    @Test
    fun noNarrowStubJoinsTwoWaysAtOneReturn() {
        val context = PatchContexts.of(standIns())
        val found = context.findProfileName()
        context.friendshipStubs().apply {
            fill(found)
            fillScreen(found, context.findScreenAnswer())
        }

        val narrow = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods.filter {
            it.name in setOf(
                "friendshipFollowedBy", "friendshipFollowing", "followedBy", "userId", "viewerId", "slotView", "statusFlag",
                "screenFriendship",
            )
        }
        assertEquals(8, narrow.size)
        for (stub in narrow) {
            val code = stub.implementation!!.instructions.toList()
            val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val targets = code.withIndex().filter { it.value is OffsetInstruction }
                .map { addresses[it.index] + (it.value as OffsetInstruction).codeOffset }.toSet()
            code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.forEach { (at, _) ->
                assertTrue("${stub.name}: a branch lands on the return at $at", addresses[at] !in targets)
            }
        }
    }

    @Test
    fun noBinderFailsThePatch() {
        val context = PatchContexts.of(standIns(traceName = "bindBio"))
        assertThrows(PatchException::class.java) { context.findProfileName() }
    }

    /** A binder that never hides the slot is laid out some other way. */
    @Test
    fun aSlotThatIsNeverHiddenFailsThePatch() {
        val context = PatchContexts.of(standIns(hides = false))
        assertThrows(PatchException::class.java) { context.findProfileName() }
    }

    @Test
    fun aUserGetterNotLoadingTheKeyFailsThePatch() {
        val context = PatchContexts.of(standIns(key = "following"))
        assertThrows(PatchException::class.java) { context.findProfileName() }
    }

    /** Without the friendship status's dump there's no telling which of its getters is followed_by. */
    @Test
    fun aFriendshipStatusWithoutItsDumpFailsThePatch() {
        val context = PatchContexts.of(standIns(dumped = false))
        assertThrows(PatchException::class.java) { context.findProfileName() }
    }

    /**
     * A friendship status whose dump doesn't ask whether you follow the account still gets the label,
     * with a stub that never says so, so the chip can't claim you follow each other.
     */
    @Test
    fun aFriendshipStatusWithoutItsFollowingGetterLeavesThatStubUnknown() {
        val context = PatchContexts.of(standIns(dumpsFollowing = false))
        val found = context.findProfileName()
        assertEquals(null, found.relationshipFollowing)
        assertEquals("followedBy", found.relationshipFollowedBy)

        context.friendshipStubs().fill(found)

        val stub = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods.single { it.name == "friendshipFollowing" }
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), stub.implementation!!.instructions.map { it.opcode })
    }

    @Test
    fun aViewModelWithoutTheUserFailsThePatch() {
        val context = PatchContexts.of(standIns(keepsUser = false))
        assertThrows(PatchException::class.java) { context.findProfileName() }
    }

    /** In each declared build both places the pronouns slot settles in the name's binder get the label. */
    @Test
    fun eachDeclaredBuildLabelsTheName() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(profileClasses(bundle))

                val found = context.findProfileName()
                context.labelProfileName(found)
                context.friendshipStubs().fill(found)
                // Show it as a chip's Following each other reads the status's own getter of "following".
                val following = found.relationshipFollowing
                assertTrue("${bundle.name}: no following getter", following != null && following != found.relationshipFollowedBy)
                val asked = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods.single { it.name == "friendshipFollowing" }
                    .implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
                assertTrue("${bundle.name}: $asked", "$RELATIONSHIP->$following()Ljava/lang/Boolean;" in asked)
                val method = context.mutableClassDefBy(found.type).methods.single {
                    it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
                }
                assertLabelled("${bundle.name} ${found.type}->${found.name}", method, found.header)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /**
     * In each declared build the label reads the profile screen's answer as the options sheet does
     * (#40): one place reads it, its client and fragment type are Instagram's, and the filled stub
     * walks from the header's view model through the answer to the friendship status.
     */
    @Test
    fun eachDeclaredBuildReadsTheScreensAnswer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = (profileClasses(bundle) + screenClasses(bundle)).distinctBy { it.type }
                val context = PatchContexts.of(classes)

                val found = context.findProfileName()
                val screen = context.findScreenAnswer()
                assertScreenAnswer(bundle.name, screen)
                context.friendshipStubs().apply {
                    fill(found)
                    fillScreen(found, screen)
                }
                val references = context.mutableClassDefBy(FRIENDSHIP_STATUS).methods.single { it.name == "screenFriendship" }
                    .implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
                val walk = listOf(
                    found.viewModel.toString(), screen.answer.toString(), screen.value, screen.tree.toString(), screen.reinterpret,
                    CLIENT, screen.retype, screen.subtree,
                )
                assertEquals("${bundle.name}: $references", walk, references.filter { it in walk })
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** Instagram ships 450 as several builds compiled on their own; each one's sheet reads the answer alike (#77). */
    @Test
    fun everyOtherBuildReadsTheScreensAnswer() {
        for (apk in Fixtures.otherBuilds()) {
            val context = PatchContexts.of(screenClasses(apk))
            assertScreenAnswer(apk.parentFile.name, context.findScreenAnswer())
        }
    }

    private fun assertScreenAnswer(what: String, screen: ScreenAnswer) {
        assertEquals("$what: the screen's answer is the view model's", VIEW_MODEL, screen.answer.definingClass)
        assertTrue("$what: ${screen.value}", screen.value.startsWith("${screen.answer.type}->getValue()"))
        assertEquals("$what: the sheet's client", CLIENT, screen.client)
        assertEquals("$what: the sheet's fragment type", SHEET_TYPE, screen.type)
        assertEquals("$what: the answer keeps its tree", screen.holder, screen.tree.definingClass)
        for (call in listOf(screen.reinterpret, screen.retype, screen.subtree, screen.flag)) {
            assertTrue("$what: $call is the tree's", call.startsWith("${screen.tree.type}->"))
        }
        assertTrue("$what: ${screen.flag}", screen.flag.endsWith("(I)Ljava/lang/Boolean;"))
    }

    /** The binder of a bundle's profile name and the classes the label's finder reads from it. */
    private fun profileClasses(bundle: File): List<ClassDef> {
        val binders = FixtureDex.classesHolding(bundle, BIND_FULL_NAME) + FixtureDex.classesHolding(bundle, FOLLOWED_BY)
        val wanted = mutableSetOf(VIEW_MODEL, USER, RELATIONSHIP, USER_SESSION)
        for (method in binders.flatMap { it.methods }.filter { it.loads(BIND_FULL_NAME) }) {
            wanted += method.parameterTypes.map(CharSequence::toString)
            method.implementation?.instructions?.forEach { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                if (called?.name == "getView") wanted += called.definingClass
            }
        }
        return (binders + FixtureDex.classes(bundle, wanted).values + ExtensionDex.classDef(FRIENDSHIP_STATUS))
            .map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    /**
     * The classes the screen answer's finder reads in [bundle]: the profile fragment and its view
     * model, every class with a method asking the fragment for something and loading the friendship
     * status's key (the options sheet among them), then in rounds what those name: the answers the
     * fragment's getters cast to and the classes they extend, the view model's fields and the trees
     * the answers keep, with the string pools they ask.
     */
    private fun screenClasses(bundle: File): List<ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        val callers = mutableSetOf<String>()
        val key = FRIENDSHIP_STATUS_KEY.hashCode()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val asks = classDef.methods.any { method ->
                    val code = method.implementation?.instructions ?: return@any false
                    code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key && it.opcode == Opcode.CONST } &&
                        code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == PROFILE_FRAGMENT }
                }
                if (asks) callers += classDef.type
                if (asks || classDef.type == PROFILE_FRAGMENT || classDef.type == VIEW_MODEL) {
                    found.putIfAbsent(classDef.type, ImmutableClassDef.of(classDef))
                }
            }
        }
        repeat(3) {
            val wanted = found.values.flatMap { classDef ->
                when {
                    classDef.type == PROFILE_FRAGMENT -> classDef.methods.filter { method ->
                        method.parameterTypes.isEmpty() && method.implementation?.instructions?.firstOrNull()
                            ?.let { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == VIEW_MODEL } == true
                    }.map { it.returnType }
                    classDef.type in callers -> emptyList()
                    else -> listOfNotNull(classDef.superclass) + classDef.fields.map { it.type }
                }
            }.filter { it.startsWith("L") && it !in found }.toSet()
            if (wanted.isNotEmpty()) found += FixtureDex.classes(bundle, wanted)
        }
        return FixtureDex.withStringPools(bundle, found.values)
    }

    /**
     * Each hook once: a range call on two registers side by side, the first moved from the slot the
     * call three before shows or hides, the second from the header parameter. The beside hook follows
     * the slot being given its text, the other the slot's field being read. No branch lands on the
     * hook's code, so only the binder's own way through runs it.
     */
    private fun assertLabelled(what: String, method: Method, header: Int) {
        val code = method.implementation!!.instructions.toList()
        val locals = method.implementation!!.registerCount - method.parameterTypes.size -
            (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1)
        val headerRegister = locals + header
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for (hook in listOf(BESIDE_PRONOUNS, IN_PLACE_OF_PRONOUNS)) {
            val calls = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == hook }
            assertEquals("$what: calls of $hook", 1, calls.size)
            val (at, call) = calls.single()
            val range = call as RegisterRangeInstruction
            assertEquals("$what: $hook takes two registers", 2, range.registerCount)

            val shows = code[at - 3]
            val shown = (shows as ReferenceInstruction).reference as MethodReference
            assertEquals("$what: the slot settled three before $hook", "setVisibility", shown.name)
            val slot = (shows as FiveRegisterInstruction).registerC
            val slotMove = code[at - 2] as TwoRegisterInstruction
            val headerMove = code[at - 1] as TwoRegisterInstruction
            assertEquals("$what: slot moved", Opcode.MOVE_OBJECT_FROM16, code[at - 2].opcode)
            assertEquals("$what: slot into the first", range.startRegister, slotMove.registerA)
            assertEquals("$what: from the slot", slot, slotMove.registerB)
            assertEquals("$what: header into the second", range.startRegister + 1, headerMove.registerA)
            assertEquals("$what: from the header", headerRegister, headerMove.registerB)

            val before = code[at - 4]
            if (hook == BESIDE_PRONOUNS) {
                assertEquals("$what: the pronouns set before it's shown", "setText",
                    ((before as ReferenceInstruction).reference as MethodReference).name)
            } else {
                // 450 copies the slot through up to two plain moves between its read and the hide.
                val read = (at - 4 downTo maxOf(0, at - 6)).first { code[it].opcode !in PLAIN_MOVES }
                assertEquals("$what: the slot read right before it's hidden", Opcode.IGET_OBJECT, code[read].opcode)
            }
            for ((index, instruction) in code.withIndex()) {
                if (instruction !is OffsetInstruction) continue
                val target = addresses[index] + instruction.codeOffset
                assertTrue("$what: the branch at $index lands in $hook's code", target !in (addresses[at - 2]..addresses[at]))
            }
        }
    }

    private fun Method.loads(value: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

    internal companion object {
        val PLAIN_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

        const val BINDER = "Lfixture/ProfileBinder;"
        const val HOLDER = "Lfixture/ProfileHolder;"
        const val HEADER = "Lfixture/ProfileHeader;"
        const val SLOT = "Lfixture/Slot;"
        const val TRACE = "Lfixture/Trace;"
        const val DUMP = "Lfixture/RelationshipDump;"
        const val FLOW = "Lfixture/AnswerFlow;"
        const val ANSWER = "Lfixture/ScreenAnswer;"
        const val TREE_HOLDER = "Lfixture/TreeHolder;"
        const val TREE = "Lfixture/Tree;"
        const val CLIENT = "itas-android"

        /** The options sheet's fragment type on 450, 0xcd0b11e1. */
        const val SHEET_TYPE = -854912543

        /**
         * The classes the patch reads, shaped as on 449: a static binder that begins a trace section
         * named [traceName], shows the pronouns slot when there are pronouns and hides it otherwise, and
         * takes the header, whose view model keeps the user, whose getter loads [key]. As on 450, the
         * view model also keeps the screen's answer, which the profile fragment's getter answers and
         * [sheets] options sheets read as Instagram's does, with a client it loads as a string or,
         * without [clientKnown], gets from a call the patch can't read.
         */
        fun standIns(
            traceName: String = BIND_FULL_NAME,
            hides: Boolean = true,
            key: String = FOLLOWED_BY,
            keepsUser: Boolean = true,
            dumped: Boolean = true,
            dumpsFollowing: Boolean = true,
            sheets: Int = 1,
            clientKnown: Boolean = true,
        ): List<ClassDef> {
            val hide = if (hides) {
                """
                    const/16 v3, 0x8
                    iget-object v1, p1, $HOLDER->pronouns:$SLOT
                    invoke-interface { v1, v3 }, $SLOT->setVisibility(I)V
                """
            } else {
                ""
            }
            val bind = method(
                BINDER, "bind", listOf(HEADER, HOLDER, "Ljava/lang/String;"), "V", 8,
                """
                    const-string v0, "$traceName"
                    invoke-static { v0 }, $TRACE->begin(Ljava/lang/String;)V
                    if-eqz p2, :hide
                    const/4 v3, 0x0
                    iget-object v1, p1, $HOLDER->pronouns:$SLOT
                    invoke-interface { v1 }, $SLOT->getView()Landroid/view/View;
                    move-result-object v2
                    check-cast v2, Landroid/widget/TextView;
                    invoke-virtual { v2, p2 }, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
                    invoke-interface { v1, v3 }, $SLOT->setVisibility(I)V
                    goto :done
                    :hide
                    $hide
                    :done
                    invoke-static { v0 }, $TRACE->begin(Ljava/lang/String;)V
                    return-void
                """,
            )
            val getter = method(
                USER, "follows", emptyList(), "Ljava/lang/Boolean;", 2,
                """
                    const-string v0, "$key"
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            )
            val friendship = method(
                USER, "friendship", emptyList(), RELATIONSHIP, 2,
                """
                    const-string v0, "$FRIENDSHIP_STATUS_KEY"
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            )
            val id = method(
                USER, "id", emptyList(), "Ljava/lang/String;", 2,
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            )
            val hash = method(
                USER, "hashCode", emptyList(), "I", 2,
                """
                    invoke-virtual { p0 }, $USER->id()Ljava/lang/String;
                    move-result-object v0
                    invoke-virtual { v0 }, Ljava/lang/String;->hashCode()I
                    move-result v0
                    return v0
                """,
                static = false,
            )
            val viewer = method(
                USER_SESSION, "getUserId", emptyList(), "Ljava/lang/String;", 2,
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            )
            // The status's dump: each key, then the status asked for it.
            val dump = method(
                DUMP, "dump", listOf(RELATIONSHIP), "V", 2,
                """
                    const-string v0, "${if (dumpsFollowing) FOLLOWING else "is_bestie"}"
                    invoke-interface { p0 }, $RELATIONSHIP->following()Ljava/lang/Boolean;
                    move-result-object v0
                    const-string v0, "$FOLLOWED_BY"
                    invoke-interface { p0 }, $RELATIONSHIP->followedBy()Ljava/lang/Boolean;
                    move-result-object v0
                    return-void
                """,
            )
            // The profile fragment's getter of the screen's answer, as 450's UserDetailFragment.A0q().
            val answer = method(
                PROFILE_FRAGMENT, "answer", emptyList(), ANSWER, 2,
                """
                    iget-object v0, p0, $PROFILE_FRAGMENT->model:$VIEW_MODEL
                    if-eqz v0, :none
                    iget-object v0, v0, $VIEW_MODEL->screen:$FLOW
                    invoke-interface { v0 }, $FLOW->getValue()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $ANSWER
                    return-object v0
                    :none
                    const/4 v0, 0x0
                    return-object v0
                """,
                static = false,
            )
            // The options sheet's builder, as 450's 09D9.A02: Remove follower goes by what this reads.
            val client = if (clientKnown) {
                "const-string v3, \"$CLIENT\""
            } else {
                "invoke-static { }, Lfixture/Clients;->pick()Ljava/lang/String;\nmove-result-object v3"
            }
            val sheet = { type: String ->
                method(
                    type, "build", listOf(PROFILE_FRAGMENT), "V", 5,
                    """
                        $client
                        invoke-virtual { p0 }, $PROFILE_FRAGMENT->answer()$ANSWER
                        move-result-object v0
                        if-eqz v0, :done
                        iget-object v1, v0, $TREE_HOLDER->tree:$TREE
                        const v2, $SHEET_TYPE
                        invoke-interface { v1, v2 }, $TREE->fragment(I)$TREE
                        move-result-object v1
                        invoke-interface { v1, v3, v2 }, $TREE->typed(Ljava/lang/String;I)$TREE
                        move-result-object v1
                        const v2, ${FRIENDSHIP_STATUS_KEY.hashCode()}
                        invoke-interface { v1, v2 }, $TREE->tree(I)$TREE
                        move-result-object v1
                        if-eqz v1, :done
                        const v2, ${FOLLOWED_BY.hashCode()}
                        invoke-interface { v1, v2 }, $TREE->flag(I)Ljava/lang/Boolean;
                        move-result-object v1
                        :done
                        return-void
                    """,
                )
            }
            val sheetClasses = (1..sheets).map { index ->
                val type = "Lfixture/OptionsSheet$index;"
                classOf(type, emptyList(), listOf(sheet(type)))
            }
            val viewModelFields = (if (keepsUser) listOf(field(VIEW_MODEL, "user", USER)) else emptyList()) +
                field(VIEW_MODEL, "screen", FLOW)
            return listOf(
                classOf(BINDER, emptyList(), listOf(bind)),
                classOf(HOLDER, listOf(field(HOLDER, "pronouns", SLOT)), emptyList()),
                classOf(HEADER, listOf(field(HEADER, "model", VIEW_MODEL), field(HEADER, "session", USER_SESSION)), emptyList()),
                classOf(VIEW_MODEL, viewModelFields, emptyList()),
                classOf(USER, emptyList(), listOf(getter, friendship, id, hash)),
                classOf(USER_SESSION, emptyList(), listOf(viewer)),
                classOf(DUMP, emptyList(), if (dumped) listOf(dump) else emptyList()),
                interfaceOf(SLOT, abstract(SLOT, "getView", emptyList(), "Landroid/view/View;"), abstract(SLOT, "setVisibility", listOf("I"), "V")),
                interfaceOf(
                    RELATIONSHIP,
                    abstract(RELATIONSHIP, "following", emptyList(), "Ljava/lang/Boolean;"),
                    abstract(RELATIONSHIP, "followedBy", emptyList(), "Ljava/lang/Boolean;"),
                ),
                classOf(PROFILE_FRAGMENT, listOf(field(PROFILE_FRAGMENT, "model", VIEW_MODEL)), listOf(answer)),
                interfaceOf(FLOW, abstract(FLOW, "getValue", emptyList(), "Ljava/lang/Object;")),
                classOf(TREE_HOLDER, listOf(field(TREE_HOLDER, "tree", TREE)), emptyList()),
                classOf(ANSWER, emptyList(), emptyList(), superclass = TREE_HOLDER),
                interfaceOf(
                    TREE,
                    abstract(TREE, "fragment", listOf("I"), TREE),
                    abstract(TREE, "typed", listOf("Ljava/lang/String;", "I"), TREE),
                    abstract(TREE, "tree", listOf("I"), TREE),
                    abstract(TREE, "other", listOf("I"), TREE),
                    abstract(TREE, "flag", listOf("I"), "Ljava/lang/Boolean;"),
                ),
                ExtensionDex.classDef(FRIENDSHIP_STATUS),
            ) + sheetClasses
        }

        private fun interfaceOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            "Ljava/lang/Object;", null, null, null, null, methods.toList(),
        )

        private fun method(
            type: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
            static: Boolean = true,
        ): Method {
            val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else AccessFlags.FINAL.value)
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
            )
        }

        private fun abstract(type: String, name: String, parameters: List<String>, returns: String) = ImmutableMethod(
            type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )

        private fun field(type: String, name: String, of: String) =
            ImmutableField(type, name, of, AccessFlags.PUBLIC.value, null, null, null)

        private fun classOf(
            type: String, fields: List<ImmutableField>, methods: List<Method>, superclass: String = "Ljava/lang/Object;",
        ): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass,
            null, null, null, fields, methods,
        )
    }
}
