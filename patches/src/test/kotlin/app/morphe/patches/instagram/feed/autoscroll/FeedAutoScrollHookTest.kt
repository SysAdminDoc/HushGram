/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.autoscroll

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.seekbar.COVER_REPORT
import app.morphe.patches.instagram.media.resume.PLAYBACK_LOOPING
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Auto scroll in Home (#660): the feed player's two callbacks, Home's list constructors and the touch dispatch. */
class FeedAutoScrollHookTest {
    /** The calls the patch writes are in the FeedAutoScroll the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_AUTO_SCROLL).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(LOOPED_HOOK, ENDED_HOOK, ATTACH_HOOK, TOUCH_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * On 454.0.0.54.77's arm64 build 386014758 the patch finds the feed player 07jT (a 0JAM, through
     * 07Jd) by its progress callback, and its Foo(I)V and FLO()V, the ones IgVideoPlayerImpl (04Te)
     * calls on its listener from Foq, which loads "Playback looping ", and FLQ, which loads "Playback
     * completed ". Each build has its own names.
     */
    @Test
    fun eachDeclaredBuildHooksThePlayerTheListAndTheTouch() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = builds(classes(bundle), bundle.name)
                if (bundle.name.contains("386014758")) {
                    assertEquals(
                        "${bundle.name}: the player and its callbacks for a video starting over and ending",
                        listOf("LX/07jT;", "Foo", "FLO"),
                        listOf(sites.player, sites.looped, sites.ended),
                    )
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildFindsItToo() {
        val bases = Fixtures.otherBuilds()
        assertEquals("every other build is read", Fixtures.OTHER_BUILDS, bases.size)
        for (base in bases) builds(classes(base), base.parentFile.name)
    }

    /** With Tap to play's call first in the touch dispatch, the touch goes in right after it. */
    @Test
    fun withTapToPlayInTheTouchGoesRightAfterIt() {
        val context = PatchContexts.of(bundled())
        val dispatch = context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single { it.isDispatch() }
        dispatch.addInstructions(0, "invoke-static/range { p0 .. p1 }, $TAP_CLOCK")
        val sites = context.findFeedAutoScroll()
        assertEquals(1, sites.touchAt)
        sites.write(context)
        val code = context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single { it.isDispatch() }.code()
        assertEquals("Tap to play's call stays first", TAP_CLOCK, code[0].method()?.toString())
        assertEquals(TOUCH_HOOK, code[1].method()?.toString())
        assertEquals("the activity and the event", code[0].range(), code[1].range())
    }

    /** The patch itself hooks everything in and switches its status method on, and nobody else's. */
    @Test
    fun thePatchHooksInAndSwitchesItsStatusOn() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)) + bundled())
        feedAutoScrollPatch.execute(context)
        fun status(name: String) = context.mutableClassDefBy(SETTINGS_STATUS).methods.first { it.name == name }.code()
        assertEquals(1, (status(FEED_AUTO_SCROLL_STATUS).first() as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("another patch's status is still off", 0, (status("hideComments").first() as NarrowLiteralInstruction).narrowLiteral)
        val list = context.mutableClassDefBy(HOME_LIST).methods.filter { it.name == "<init>" }
        assertTrue("each of Home's list constructors hands the list over", list.all { m -> m.code().any { it.method()?.toString() == ATTACH_HOOK } })
    }

    /** A second run finds its own calls and refuses instead of handing everything over twice. */
    @Test
    fun aSecondRunRefuses() {
        val context = PatchContexts.of(bundled())
        context.findFeedAutoScroll().write(context)
        val failure = assertThrows(PatchException::class.java) { context.findFeedAutoScroll() }
        assertTrue(failure.message, failure.message!!.contains("already calls $FEED_AUTO_SCROLL"))
    }

    /** A build missing any of what the patch reads fails before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val all = bundled()
        val sites = PatchContexts.of(all).findFeedAutoScroll()
        val looks = PatchContexts.of(all)
        val implementations = all.filter { classDef ->
            classDef.methods.any { !AccessFlags.STATIC.isSet(it.accessFlags) && looks.loadsString(it, PLAYBACK_LOOPING) }
        }.map { it.type }
        val above = typesAbove(all, sites.player)
        val cases = listOf(
            all.filter { it.type != HOME_LIST } to "this build has no $HOME_LIST",
            all.filter { it.type != FRAGMENT_ACTIVITY } to "this build has no $FRAGMENT_ACTIVITY",
            all.filter { it.type != sites.player } to "loading \"$COVER_REPORT\", found 0",
            all.filter { it.type !in implementations } to "loading \"$PLAYBACK_LOOPING\", found 0",
            all.filter { it.type !in above } to "the feed video player, isn't a ",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findFeedAutoScroll() }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Auto scroll in Home: "))
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (type in listOf(sites.player, HOME_LIST, FRAGMENT_ACTIVITY).filter { type -> classes.any { it.type == type } }) {
                val methods = context.mutableClassDefBy(type).methods
                assertTrue("$expected: something was written in $type", methods.none { m -> m.code().any { it.method()?.definingClass == FEED_AUTO_SCROLL } })
            }
        }
    }

    /** A callback something jumps back to the start of would skip the hook on that jump, so it refuses. */
    @Test
    fun aCallbackSomethingJumpsBackIntoRefuses() {
        val context = PatchContexts.of(bundled())
        val sites = context.findFeedAutoScroll()
        context.mutableClassDefBy(sites.player).methods.single { it.name == sites.ended && it.shape() == "()V" }
            .addInstructionsWithLabels(
                0,
                """
                    :top
                    nop
                    goto :top
                """,
            )
        val failure = assertThrows(PatchException::class.java) { context.findFeedAutoScroll() }
        assertTrue(failure.message, failure.message!!.contains("jumps back to its start"))
    }

    /** A branch onto the return of Home's list constructor lands on the hook, so the list is handed over that way too. */
    @Test
    fun aBranchToTheListsReturnRunsTheHookToo() {
        val made = constructor(
            """
                invoke-direct { p0, p1 }, $RECYCLER_VIEW-><init>($CONTEXT)V
                if-eqz p1, :done
                const/4 v0, 0x1
                :done
                return-void
            """,
        )
        val context = PatchContexts.of(bundled().filter { it.type != HOME_LIST } + list(made))
        val sites = context.findFeedAutoScroll()
        assertEquals(listOf(listOf(CONTEXT)), sites.constructors)
        sites.write(context)
        val code = context.mutableClassDefBy(HOME_LIST).methods.single().code()
        assertEquals(ATTACH_HOOK, code[3].method()?.toString())
        assertEquals("this", listOf(1, 1), code[3].range())
        assertEquals(Opcode.RETURN_VOID, code[4].opcode)
        assertEquals("the branch to the return goes to the hook", 3, (code[1] as BuilderOffsetInstruction).target.location.index)
    }

    /** A constructor that writes over this before its return has no list to hand over, and refuses. */
    @Test
    fun aListThatLosesThisBeforeItsReturnRefuses() {
        val made = constructor(
            """
                invoke-direct { p0, p1 }, $RECYCLER_VIEW-><init>($CONTEXT)V
                const/4 p0, 0x0
                return-void
            """,
        )
        val context = PatchContexts.of(bundled().filter { it.type != HOME_LIST } + list(made))
        val failure = assertThrows(PatchException::class.java) { context.findFeedAutoScroll() }
        assertTrue(failure.message, failure.message!!.contains("writes over this"))
    }

    /**
     * Finds, writes and checks one build: each callback hands the player over first thing, each of
     * Home's list constructors hands the list over before each return, and the touch dispatch hands
     * the activity and the event over first, all as range calls on the method's own parameters, with
     * Instagram's code kept as it was around them.
     */
    private fun builds(stock: List<ClassDef>, label: String): FeedAutoScrollSites {
        val context = PatchContexts.of(stock)
        val sites = context.findFeedAutoScroll()
        assertEquals("$label: Home's list has its three constructors", SHAPES, sites.constructors.toSet())
        assertEquals("$label: with nothing of Tap to play's in, the touch goes first", 0, sites.touchAt)
        sites.write(context)

        fun stockOf(type: String, pick: (Method) -> Boolean) = stock.single { it.type == type }.methods.single(pick)
        fun hookedOf(type: String, pick: (Method) -> Boolean) = context.mutableClassDefBy(type).methods.single(pick)
        val looped: (Method) -> Boolean = { it.name == sites.looped && it.shape() == "(I)V" }
        val ended: (Method) -> Boolean = { it.name == sites.ended && it.shape() == "()V" }
        val dispatch: (Method) -> Boolean = { it.isDispatch() }
        firstThing("$label: a video starting over", stockOf(sites.player, looped), hookedOf(sites.player, looped), LOOPED_HOOK, 2)
        firstThing("$label: a video ending", stockOf(sites.player, ended), hookedOf(sites.player, ended), ENDED_HOOK, 1)
        firstThing("$label: a touch", stockOf(FRAGMENT_ACTIVITY, dispatch), hookedOf(FRAGMENT_ACTIVITY, dispatch), TOUCH_HOOK, 2)

        for (parameters in sites.constructors) {
            val made: (Method) -> Boolean = { it.name == "<init>" && it.parameterTypes.map(Any::toString) == parameters }
            val before = stockOf(HOME_LIST, made)
            val after = hookedOf(HOME_LIST, made).code()
            val what = "$label: Home's list made from $parameters"
            val hooks = after.indices.filter { after[it].method()?.toString() == ATTACH_HOOK }
            assertEquals("$what hands itself over before each return", before.code().count { it.opcode == Opcode.RETURN_VOID }, hooks.size)
            for (at in hooks) {
                assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
                assertEquals("$what: on this", listOf(before.thisRegister(), 1), after[at].range())
                assertEquals("$what: right before the return", Opcode.RETURN_VOID, after[at + 1].opcode)
            }
            assertStockKept(what, before.code(), after.filterIndexed { at, _ -> at !in hooks })
        }
        return sites
    }

    private fun firstThing(what: String, stock: Method, hooked: Method, hook: String, registers: Int) {
        val code = hooked.code()
        assertEquals("$what reaches the extension first thing", hook, code.first().method()?.toString())
        assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, code.first().opcode)
        assertEquals("$what: on this and the parameters", listOf(stock.thisRegister(), registers), code.first().range())
        assertEquals("$what reaches it once", 1, code.count { it.method()?.toString() == hook })
        assertStockKept(what, stock.code(), code.drop(1))
    }

    private fun assertStockKept(what: String, stock: List<Instruction>, kept: List<Instruction>) {
        fun kinds(code: List<Instruction>) = code.map { it.opcode.kind() }.filter { it != Opcode.NOP }
        assertEquals("$what: Instagram's own code is kept", kinds(stock), kinds(kept))
    }

    /** The declared bundle's classes, read once for the tests that only need one build. */
    private fun bundled(): List<ClassDef> = BUNDLED

    /** Every type above [type] among [classes], through superclasses and interfaces. */
    private fun typesAbove(classes: List<ClassDef>, type: String): Set<String> {
        val byType = classes.associateBy { it.type }
        val seen = HashSet<String>()
        val pending = ArrayDeque(byType.getValue(type).above())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) byType[next]?.let { pending += it.above() }
        }
        return seen
    }

    private fun list(vararg constructors: Method): ClassDef =
        ImmutableClassDef(HOME_LIST, AccessFlags.PUBLIC.value, RECYCLER_VIEW, null, null, null, null, constructors.toList())

    /** A constructor of Home's list taking a Context, with one local. */
    private fun constructor(body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                HOME_LIST, "<init>", listOf(ImmutableMethodParameter(CONTEXT, null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun Method.isDispatch(): Boolean =
        name == "dispatchTouchEvent" && shape() == "(Landroid/view/MotionEvent;)Z" && implementation != null

    private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

    private fun Method.thisRegister(): Int =
        implementation!!.registerCount - 1 - parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

    private fun Opcode.kind(): Opcode = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this

    private fun Instruction.range(): List<Int> = (this as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

        /** Home's list's three constructors, the same in every build. */
        val SHAPES = setOf(
            listOf(CONTEXT),
            listOf(CONTEXT, "Landroid/util/AttributeSet;"),
            listOf(CONTEXT, "Landroid/util/AttributeSet;", "I"),
        )

        val BUNDLED: List<ClassDef> by lazy { classes(Fixtures.files { it.extension == "apks" }.first()) }

        /**
         * The classes loading the looping callback's string and the feed player's progress string,
         * every class and interface above the player, Home's list and the activity, and the string
         * pools they ask.
         */
        fun classes(bundle: File): List<ClassDef> {
            val found = LinkedHashMap<String, ClassDef>()
            FixtureDex.classesLoading(bundle, PLAYBACK_LOOPING).forEach { found.putIfAbsent(it.type, it) }
            val players = FixtureDex.classesLoading(bundle, COVER_REPORT)
            players.forEach { found.putIfAbsent(it.type, it) }
            val asked = HashSet<String>()
            var wanted = setOf(HOME_LIST, FRAGMENT_ACTIVITY) + players.flatMap { it.above() }
            while (true) {
                val next = wanted.filterTo(HashSet()) {
                    it !in found && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") && asked.add(it)
                }
                if (next.isEmpty()) break
                val read = FixtureDex.classes(bundle, next).values
                read.forEach { found.putIfAbsent(it.type, it) }
                wanted = read.flatMap { it.above() }.toSet()
            }
            return FixtureDex.withStringPools(bundle, found.values)
        }

        fun ClassDef.above(): List<String> = listOfNotNull(superclass) + interfaces
    }
}
