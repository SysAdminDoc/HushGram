/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
 * The story viewer's write of what you've watched into the store that greys a ring goes through
 * StorySeenRings, in the write's own place and with the story whose time it is, and nothing else that
 * writes a reel's seen time changes (#92).
 */
class StorySeenRingsHookTest {
    /** The hook the patch calls and the stub it fills are in the extension the bundle ships, static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_SEEN_RINGS).methods
        val hook = methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == RING_SEEN.substringAfter("->") }
        assertTrue("the hook isn't public and static", AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
        val stub = methods.single { it.name == "markSeen" }
        assertEquals(listOf(OBJECT, OBJECT, "J"), stub.parameterTypes.map(Any::toString))
        assertTrue("the stub isn't static", AccessFlags.STATIC.isSet(stub.accessFlags))
    }

    /**
     * The viewer's write becomes the hook, handed the reel, the account, the time and the story, at
     * the same place, so a branch that went to the write goes to the hook. The tray reads' writes and
     * the store's own stay as they are.
     */
    @Test
    fun theViewerHandsItsWriteAndTheStoryToTheHook() {
        val classes = standIns()
        val context = PatchContexts.of(classes)
        val found = context.findStoryRings()
        assertEquals(VIEWER, found.viewer)
        assertEquals(WRITE, found.write)
        assertEquals(5, found.at)

        context.keepStoriesNew(found)

        val before = classes.single { it.type == VIEWER }.methods.single { it.name == "A0A" }.code()
        val viewer = context.mutableClassDefBy(VIEWER).methods.single { it.name == "A0A" }
        assertHooked("stand-in", before, viewer.code(), found)
        assertEquals("stand-in: the hook's registers", listOf(2, 3, 0, 1, 5), (viewer.code()[5] as FiveRegisterInstruction).registers())
        assertEquals("the tray reads' writes", 2, context.calls(WRITE))
        assertEquals("the store's NUX reset", 1, context.mutableClassDefBy(STORE).methods.single { it.name == "A01" }.code().count { it.referenceText() == STATE_WRITE })
        assertEquals("one call of the hook", 1, context.calls(RING_SEEN))
        assertStubFilled(context, REEL, WRITE)
    }

    /** The filled stub has one way out, so nothing joins two paths at a return (see StorySeenHookTest). */
    @Test
    fun theStubHasOneWayOut() {
        val context = PatchContexts.of(standIns())
        context.keepStoriesNew(context.findStoryRings())

        val code = context.mutableClassDefBy(STORY_SEEN_RINGS).methods.single { it.name == "markSeen" }.code()
        val first = code.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        assertEquals(3, first)
        assertTrue("a branch before its return", code.take(first).none { it is OffsetInstruction })
    }

    @Test
    fun aBuildWithoutTheStoreFails() = assertRefused(standIns(store = false), "expected one class loading \"$LOCAL_SEEN_STORE\"")

    @Test
    fun aStateWithTwoWritesFails() = assertRefused(standIns(stateWrites = 2), "one static write of a reel's seen time, found 2")

    @Test
    fun anotherWriterOfTheStateFails() = assertRefused(standIns(otherStateWriter = true), "calling $STATE->A00, found 2")

    @Test
    fun aWriteTheExtensionCantCallFails() = assertRefused(standIns(writePublic = false), "isn't public for the extension to call")

    @Test
    fun aBuildWithoutTheViewersWriteFails() = assertRefused(standIns(viewers = 0), "as its reel's seen time, found 0")

    @Test
    fun twoViewerWritesFail() = assertRefused(standIns(viewers = 2), "as its reel's seen time, found 2")

    /** The story read into the time's own registers is gone by the write, so the hook can't be handed it. */
    @Test
    fun aTimeReadOverTheStoryFails() {
        assertRefused(standIns(storyIn = 0), "over the story (v0)")
        assertRefused(standIns(storyIn = 1), "over the story (v1)")
    }

    @Test
    fun aRangeWriteFails() = assertRefused(standIns(rangeWrite = true), "with a range call, which the hook can't take the story into")

    @Test
    fun aRangeGetterFails() = assertRefused(standIns(rangeGetter = true), "reads the story's time with a range call")

    @Test
    fun aBranchToTheWriteFails() = assertRefused(standIns(branchToWrite = true), "a branch lands on")

    @Test
    fun aViewerNotHandedTheStoryFails() = assertRefused(standIns(viewerTakesStory = false), "isn't handed the story")

    @Test
    fun anExtensionWithoutStoryRingsFails() = assertRefused(standIns(extension = false), "$STORY_SEEN_RINGS isn't in the extension")

    /**
     * On each declared build the viewer's write is found and hooked, the declared build's viewer is
     * the method the contract rule picks (it holds nux_story), and a second copy of the viewer is refused.
     */
    @Test
    fun eachDeclaredBuildKeepsWatchedStoriesNew() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = ringClasses(bundle)
                val found = keepsWatchedStoriesNew(classes, bundle.name)
                val viewer = classes.single { it.type == found.viewer }
                val method = viewer.methods.single { it.name == found.viewerName && it.parameterTypes.map(Any::toString) == found.viewerParameters }
                assertTrue("${bundle.name}: the viewer doesn't hold nux_story, which the contract rule picks it by", method.code().any { it.string() == "nux_story" })
                assertEquals("${bundle.name}: the contract rule's shape", 2, found.viewerParameters.size)
                assertTrue("${bundle.name}: the contract rule's viewer is an instance method", !AccessFlags.STATIC.isSet(method.accessFlags))
                assertRefused(classes + copyOf(viewer, "Lfixture/SecondViewer;"), "as its reel's seen time, found 2")
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** 450's other builds compile the viewer on their own, one with far fewer registers (#77). */
    @Test
    fun eachOtherBuildKeepsWatchedStoriesNew() {
        for (bundle in Fixtures.otherBuilds()) keepsWatchedStoriesNew(ringClasses(bundle), bundle.parentFile.name)
    }

    private fun keepsWatchedStoriesNew(classes: List<ClassDef>, name: String): StoryRingTargets {
        val context = PatchContexts.of(classes)
        val found = context.findStoryRings()
        assertEquals("$name: the viewer is handed the story", REEL_ITEM, found.viewerParameters.first())
        assertTrue("$name: ${found.write} isn't a reel's write for an account", found.write.endsWith("(${USER_SESSION}J)V"))
        val before = classes.single { it.type == found.viewer }.methods.single {
            it.name == found.viewerName && it.parameterTypes.map(Any::toString) == found.viewerParameters
        }.code()
        val writes = classes.sumOf { classDef -> classDef.methods.sumOf { method -> method.code().count { it.referenceText() == found.write } } }

        context.keepStoriesNew(found)

        val viewer = context.mutableClassDefBy(found.viewer).methods.single {
            it.name == found.viewerName && it.parameterTypes.map(Any::toString) == found.viewerParameters
        }
        assertHooked(name, before, viewer.code(), found)
        assertEquals("$name: the other writes of a reel's seen time", writes - 1, context.calls(found.write))
        assertEquals("$name: one call of the hook", 1, context.calls(RING_SEEN))
        assertStubFilled(context, found.reelClass, found.write)
        return found
    }

    /**
     * The viewer's code after the hook: the same instructions but one, where the write was, now the
     * hook taking the write's registers and the story the time was just read from.
     */
    private fun assertHooked(name: String, before: List<Instruction>, after: List<Instruction>, found: StoryRingTargets) {
        assertEquals("$name: the viewer's size", before.size, after.size)
        val hook = after[found.at]
        assertEquals("$name: the hook's opcode", Opcode.INVOKE_STATIC, hook.opcode)
        assertEquals("$name: the hook", RING_SEEN, hook.referenceText())
        assertEquals(
            "$name: the hook's registers",
            listOf(found.reel, found.session, found.time, found.time + 1, found.item),
            (hook as FiveRegisterInstruction).registers(),
        )
        val write = before[found.at] as FiveRegisterInstruction
        assertEquals("$name: the write's registers", listOf(found.reel, found.session, found.time, found.time + 1), write.registers())
        assertEquals("$name: the time", found.time, (after[found.at - 1] as OneRegisterInstruction).registerA)
        val getter = after[found.at - 2]
        assertEquals("$name: the time is the story's", REEL_ITEM, ((getter as ReferenceInstruction).reference as MethodReference).definingClass)
        assertEquals("$name: the story", found.item, (getter as FiveRegisterInstruction).registerC)
        assertEquals("$name: the viewer still writes the seen time", 0, after.count { it.referenceText() == found.write })
        fun rest(code: List<Instruction>) = code.filterIndexed { index, _ -> index != found.at }.map { it.opcode to it.referenceText() }
        assertEquals("$name: the rest of the viewer", rest(before), rest(after))
    }

    /** The stub makes the reel's own write, with the reel, the account and the time it's handed. */
    private fun assertStubFilled(context: BytecodePatchContext, reel: String, write: String) {
        val stub = context.mutableClassDefBy(STORY_SEEN_RINGS).methods.single { it.name == "markSeen" }
        val code = stub.code()
        assertEquals(
            listOf(Opcode.CHECK_CAST to reel, Opcode.CHECK_CAST to USER_SESSION, Opcode.INVOKE_VIRTUAL to write, Opcode.RETURN_VOID to null),
            code.take(4).map { it.opcode to it.referenceText() },
        )
        val first = stub.implementation!!.registerCount - 4
        assertEquals("the stub's parameters, p0 to p3", (first until first + 4).toList(), (code[2] as FiveRegisterInstruction).registers())
    }

    /** The finder refuses [classes] saying [why] and nothing is changed. */
    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.keepStoriesNew(context.findStoryRings()) }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        assertEquals("a hook was written", 0, context.calls(RING_SEEN))
        context.classDefByOrNull(STORY_SEEN_RINGS)?.let {
            val stock = ExtensionDex.classDef(STORY_SEEN_RINGS).methods.single { method -> method.name == "markSeen" }.code().size
            assertEquals("the stub was filled", stock, context.mutableClassDefBy(STORY_SEEN_RINGS).methods.single { method -> method.name == "markSeen" }.code().size)
        }
    }

    /**
     * The classes the finder reads on [bundle]: the store holding its trace section, its state, every
     * class calling the state's write and every class calling the reel's, their string pools, and the
     * extension's class.
     */
    private fun ringClasses(bundle: File): List<ClassDef> {
        val stores = FixtureDex.classesHolding(bundle, LOCAL_SEEN_STORE)
        val state = stores.flatMap { it.methods }.single {
            it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == USER_SESSION
        }.parameterTypes[1].toString()
        val stateClass = FixtureDex.classes(bundle, setOf(state)).getValue(state)
        val stateWrite = stateClass.methods.single {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(state, STRING, "J")
        }.key()
        val stateWriters = callersOf(bundle, stateWrite)
        val reelWrite = stateWriters.single { it.definingClass != state && stores.none { store -> store.type == it.definingClass } }.key()
        val reelWriters = callersOf(bundle, reelWrite)
        val types = (stores.map { it.type } + state + stateWriters.map { it.definingClass } + reelWriters.map { it.definingClass }).toSet()
        return (FixtureDex.withStringPools(bundle, FixtureDex.classes(bundle, types).values) + ExtensionDex.classDef(STORY_SEEN_RINGS))
            .map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    private fun callersOf(bundle: File, key: String): List<Method> =
        FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == key } }) { method ->
            method.code().any { it.referenceText() == key }
        }

    /**
     * Instagram's seen state as a handful of classes: the store, made from the account and its state
     * and holding its trace section, which resets a NUX through the state's write; the state and its
     * write; the reel, whose write for an account calls it, and whose own tray read calls that; a
     * tray parser calling it with a number the server sent; and the viewer logger calling it with the
     * time of the story it's showing.
     */
    private fun standIns(
        store: Boolean = true,
        stateWrites: Int = 1,
        otherStateWriter: Boolean = false,
        writePublic: Boolean = true,
        viewers: Int = 1,
        storyIn: Int? = null,
        rangeWrite: Boolean = false,
        rangeGetter: Boolean = false,
        branchToWrite: Boolean = false,
        viewerTakesStory: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
        val storeClass = classOf(
            STORE, listOf(field(STORE, "state", STATE)),
            listOf(
                method(
                    STORE, "<init>", listOf(USER_SESSION, STATE), "V", 4, """
                        invoke-direct { p0 }, $OBJECT-><init>()V
                        const-string v0, "$LOCAL_SEEN_STORE"
                        iput-object p2, p0, $STORE->state:$STATE
                        return-void
                    """, static = false, constructor = true,
                ),
                method(
                    STORE, "A01", listOf(STRING), "V", 5, """
                        iget-object v0, p0, $STORE->state:$STATE
                        const-wide/16 v1, 0x0
                        invoke-static { v0, p1, v1, v2 }, $STATE_WRITE
                        return-void
                    """, static = false,
                ),
            ),
        )
        val stateBody = """
            iget-object v0, p0, $STATE->seen:Ljava/util/HashMap;
            invoke-static { p2, p3 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v1
            invoke-virtual { v0, p1, v1 }, Ljava/util/HashMap;->put(${OBJECT}${OBJECT})$OBJECT
            return-void
        """
        val stateClass = classOf(
            STATE, listOf(field(STATE, "seen", "Ljava/util/HashMap;")),
            (0 until stateWrites).map { method(STATE, "A0$it", listOf(STATE, STRING, "J"), "V", 6, stateBody) },
        )
        val reelClass = classOf(
            REEL, listOf(field(REEL, "state", STATE), field(REEL, "id", STRING)),
            listOf(
                method(
                    REEL, "A0e", listOf(USER_SESSION, "J"), "V", 6, """
                        iget-object v0, p0, $REEL->state:$STATE
                        iget-object v1, p0, $REEL->id:$STRING
                        invoke-static { v0, v1, p2, p3 }, $STATE_WRITE
                        return-void
                    """, static = false, public = writePublic,
                ),
                method(
                    REEL, "A0f", listOf(USER_SESSION, "I"), "V", 5, """
                        int-to-long v0, p2
                        invoke-virtual { p0, p1, v0, v1 }, $WRITE
                        return-void
                    """, static = false,
                ),
            ),
        )
        val parser = classOf(
            PARSER, emptyList(),
            listOf(
                method(
                    PARSER, "A00", listOf(REEL, USER_SESSION, "Ljava/lang/Number;"), "V", 5, """
                        invoke-virtual { p2 }, Ljava/lang/Number;->longValue()J
                        move-result-wide v0
                        invoke-virtual { p0, p1, v0, v1 }, $WRITE
                        return-void
                    """,
                ),
            ),
        )
        val elsewhere = if (otherStateWriter) {
            classOf(
                ELSEWHERE, emptyList(),
                listOf(
                    method(
                        ELSEWHERE, "write", listOf(STATE, STRING), "V", 4, """
                            const-wide/16 v0, 0x1
                            invoke-static { p0, p1, v0, v1 }, $STATE_WRITE
                            return-void
                        """,
                    ),
                ),
            )
        } else {
            null
        }
        val story = if (storyIn == null) "p1" else "v$storyIn"
        val viewerBody = when {
            rangeWrite -> """
                iget-object v0, p2, $VIEWER_STATE->reel:$REEL
                iget-object v1, p0, $VIEWER->session:$USER_SESSION
                invoke-virtual { p1 }, $REEL_ITEM->A07()J
                move-result-wide v2
                invoke-virtual/range { v0 .. v3 }, $WRITE
                return-void
            """
            rangeGetter -> """
                iget-object v2, p2, $VIEWER_STATE->reel:$REEL
                iget-object v3, p0, $VIEWER->session:$USER_SESSION
                invoke-virtual/range { p1 .. p1 }, $REEL_ITEM->A07()J
                move-result-wide v0
                invoke-virtual { v2, v3, v0, v1 }, $WRITE
                return-void
            """
            branchToWrite -> """
                iget-object v2, p2, $VIEWER_STATE->reel:$REEL
                iget-object v3, p0, $VIEWER->session:$USER_SESSION
                invoke-virtual { p1 }, $REEL_ITEM->A07()J
                move-result-wide v0
                :write
                invoke-virtual { v2, v3, v0, v1 }, $WRITE
                if-eqz v2, :write
                return-void
            """
            !viewerTakesStory -> """
                iget-object v2, p2, $VIEWER_STATE->reel:$REEL
                iget-object v3, p0, $VIEWER->session:$USER_SESSION
                iget-object v5, p0, $VIEWER->story:$REEL_ITEM
                invoke-virtual { v5 }, $REEL_ITEM->A07()J
                move-result-wide v0
                invoke-virtual { v2, v3, v0, v1 }, $WRITE
                return-void
            """
            else -> """
                iget-object v2, p2, $VIEWER_STATE->reel:$REEL
                iget-object v3, p0, $VIEWER->session:$USER_SESSION
                ${if (storyIn == null) "const-string v0, \"nux_story\"" else "move-object v$storyIn, p1"}
                invoke-virtual { $story }, $REEL_ITEM->A07()J
                move-result-wide v0
                invoke-virtual { v2, v3, v0, v1 }, $WRITE
                return-void
            """
        }
        val viewerParameters = if (viewerTakesStory) listOf(REEL_ITEM, VIEWER_STATE) else listOf(OBJECT, VIEWER_STATE)
        val viewerClass = classOf(
            VIEWER, listOf(field(VIEWER, "session", USER_SESSION), field(VIEWER, "story", REEL_ITEM)),
            listOf("A0A", "A0B").take(viewers).map { name ->
                method(VIEWER, name, viewerParameters, "V", 7, viewerBody, static = false)
            },
        )
        return listOfNotNull(
            if (store) storeClass else null, stateClass, reelClass, parser, elsewhere, viewerClass,
            classOf(VIEWER_STATE, listOf(field(VIEWER_STATE, "reel", REEL)), emptyList()),
            if (extension) ImmutableClassDef.of(ExtensionDex.classDef(STORY_SEEN_RINGS)) else null,
        )
    }

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        static: Boolean = true, constructor: Boolean = false, public: Boolean = true,
    ): Method {
        val flags = (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
            (if (static) AccessFlags.STATIC.value else 0) or
            (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    private fun classOf(type: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, emptyList(), null, null, fields, methods)

    /** [classDef] under another name, its members moved with it. */
    private fun copyOf(classDef: ClassDef, type: String): ClassDef = ImmutableClassDef(
        type, classDef.accessFlags, classDef.superclass, classDef.interfaces, null, null,
        classDef.fields.map { ImmutableField(type, it.name, it.type, it.accessFlags, null, null, null) },
        classDef.methods.map { ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, it.implementation) },
    )

    private fun BytecodePatchContext.calls(reference: String): Int {
        var count = 0
        classDefForEach { classDef -> classDef.methods.forEach { method -> count += method.code().count { it.referenceText() == reference } } }
        return count
    }

    private fun FiveRegisterInstruction.registers(): List<Int> =
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        const val STORE = "Lfixture/LocalReelSeenStateStore;"
        const val STATE = "Lfixture/LocalReelSeenState;"
        const val STATE_WRITE = "$STATE->A00(${STATE}Ljava/lang/String;J)V"
        const val REEL = "Lfixture/Reel;"
        const val WRITE = "$REEL->A0e(${USER_SESSION}J)V"
        const val PARSER = "Lfixture/ReelParser;"
        const val ELSEWHERE = "Lfixture/Elsewhere;"
        const val VIEWER = "Lfixture/ReelViewerLogger;"
        const val VIEWER_STATE = "Lfixture/ReelViewerState;"
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
    }
}
