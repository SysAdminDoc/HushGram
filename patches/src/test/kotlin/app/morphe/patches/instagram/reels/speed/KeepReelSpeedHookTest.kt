/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.speed

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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

class KeepReelSpeedHookTest {
    private val controller = "Lfixture/ClipsVideoPlayerController;"
    private val player = "Lfixture/VideoPlayer;"
    private val item = "Lfixture/ClipsItem;"
    private val hold = "Lfixture/LongPressEnd;"
    private val feature = "Lfixture/LongPressToFeature;"
    private val nux = "Lfixture/FastPlayNux;"
    private val purge = "Lfixture/Purge;->mark(Ljava/lang/String;)V"
    private val log = "Lfixture/Log;->log(Ljava/lang/String;)V"
    private val setterRef = "$controller->A19(Lkotlin/jvm/functions/Function1;F)V"
    private val hooks = listOf(SPEED_SET, LOCKED_UP, LOCK_UP_ENDED, RESET_SPEED, HOLD_ENDED, ITEM, RESUMING)

    private fun marker(name: String, quarter: String = "q3") = "android_purge_26_${quarter}_$name"

    /** Every hook the patch writes is in the ReelSpeed the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SPEED).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf("$SET_PLAYER_SPEED_STUB(Ljava/lang/Object;F)V", "$AD_ITEM_STUB(Ljava/lang/Object;)Z")
        for (signature in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$signature is not in the extension: $declared", signature in declared)
        }
    }

    /**
     * The setter tells the extension each speed first thing; the hold handler tells it after its
     * lock-begin marker and after its "ended" event; the reset hands over the reason after its
     * lock-end marker and asks for the speed in front of its call to the setter; and maybeResumePlayer
     * hands over the reel and its player in front of the call that plays it, where a branch to that
     * call lands on the hooks. The stubs call the player's setter and read the reel's ad flag.
     */
    @Test
    fun everyHookGoesInAndBothStubsAreFilled() {
        val context = PatchContexts.of(classes())

        context.keepReelSpeed()

        val setter = context.method(controller, "A19")
        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE), setter.take(1).map { it.opcode })
        // Two locals, then this, the Function1 and the speed.
        assertCall("the setter's hook", setter[0], SPEED_SET, listOf(4))

        val resume = context.method(controller, "A0E")
        val itemHook = resume.indexOfFirst { it.referenceText() == ITEM }
        // Two locals, then this and the reel.
        assertCall("the reel handed over", resume[itemHook], ITEM, listOf(3))
        assertCall("the player handed over", resume[itemHook + 1], RESUMING, listOf(1))
        assertTrue("the play call doesn't follow", resume[itemHook + 2].referenceText()!!.startsWith("$player->play("))
        val branch = resume.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the branch to the play call skips the hooks", itemHook, resume.target(branch))

        val holdCode = context.method(hold, "end")
        val begun = holdCode.indexOfFirst { it.string()?.endsWith(LOCK_UP_BEGIN) == true }
        assertEquals("the lock hook isn't right after the marker", LOCKED_UP, holdCode[begun + 1].referenceText())
        val ended = holdCode.indexOfFirst { it.string() == HOLD_ENDED_EVENT }
        assertEquals("the hold-end hook isn't right after the event", HOLD_ENDED, holdCode[ended + 1].referenceText())

        val reset = context.method(feature, "reset")
        val endMarker = reset.indexOfFirst { it.string()?.endsWith(LOCK_UP_END) == true }
        assertCall("the lock-end reason", reset[endMarker + 1], LOCK_UP_ENDED, listOf(2))
        val asked = reset.indexOfFirst { it.referenceText() == RESET_SPEED }
        assertCall("the reset's speed", reset[asked], RESET_SPEED, listOf(0))
        assertEquals(Opcode.MOVE_RESULT, reset[asked + 1].opcode)
        assertEquals(0, (reset[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals("the setter call doesn't follow the answer", setterRef, reset[asked + 2].referenceText())
        val skip = reset.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertEquals("the branch around the reset doesn't land past it", asked + 3, reset.target(skip))

        val speed = context.method(REEL_SPEED, SET_PLAYER_SPEED_STUB)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID), speed.take(3).map { it.opcode })
        assertEquals("$player->setSpeed(F)V", speed[1].referenceText())
        val ad = context.method(REEL_SPEED, AD_ITEM_STUB)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_BOOLEAN, Opcode.RETURN), ad.take(3).map { it.opcode })
        assertEquals("$item->ad:Z", ad[1].referenceText())
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape(setters = 0) to "marked $SET_PLAYBACK_SPEED, found 0",
            Shape(setters = 2) to "marked $SET_PLAYBACK_SPEED, found 2",
            Shape(setterTakesInt = true) to "isn't an instance (Function1, float) method",
            Shape(speedCalls = 2) to "makes 2 interface calls taking a float",
            Shape(playerPublic = false) to "isn't a public interface",
            Shape(resumeElsewhere = true) to "$MAYBE_RESUME_PLAYER isn't in $controller",
            Shape(plays = 0) to "makes 0 (String, boolean) calls on the player",
            Shape(plays = 2) to "makes 2 (String, boolean) calls on the player",
            Shape(itemOverwritten = true) to "writes over parameter 0",
            Shape(holdIsLongPressEnd = false) to "isn't marked $LONG_PRESS_END",
            Shape(endedEvents = 2) to "loads \"$HOLD_ENDED_EVENT\" 2 times",
            Shape(branchAfterBegin = true) to "reaches the instruction after the lock-begin marker",
            Shape(lockEndMarked = false) to "isn't marked $LOCK_UP_END",
            Shape(reasonsInTwoRegisters = true) to "expected one register",
            Shape(reasonOverwritten = true) to "holds something other than the lock-end reason",
            Shape(resetCalls = 0) to "times, expected once",
            Shape(speedReadAfterReset = true) to "reads v0 again after its reset",
            Shape(adTested = false) to "doesn't test a flag just before",
            Shape(adFromAnotherClass = true) to "not the reel's",
            Shape(adFieldPublic = false) to "isn't a public instance field",
            Shape(itemPublic = false) to "the reel $item isn't public",
        )
        for ((shape, expected) in cases) {
            val classes = classes(shape)
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.keepReelSpeed() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.classDefByOrNull(type)?.methods?.toList().orEmpty() }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to ${written.map { it.name }}", written.isEmpty())
            val stubs = context.classDefBy(REEL_SPEED).methods.filter { it.name == SET_PLAYER_SPEED_STUB || it.name == AD_ITEM_STUB }
            assertTrue("$expected: a stub was filled", stubs.none { stub -> stub.code().any { it.opcode == Opcode.CHECK_CAST } })
        }
    }

    /**
     * In each declared build every hook goes where the stand-ins put it: the setter's first, the lock
     * and hold hooks right after their events, the reason after the lock-end marker and the speed in
     * front of the reset's setter call, and the reel and its player in front of the play call.
     */
    @Test
    fun eachDeclaredBuildGetsEveryHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsEveryHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val markers = setOf(SET_PLAYBACK_SPEED, MAYBE_RESUME_PLAYER, LOCK_UP_BEGIN, RESET, FAST_PLAY_NUX)
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val methods = holders.flatMap { it.methods }
        val setter = methods.single { SET_PLAYBACK_SPEED in it.markers() }
        val resume = methods.single { MAYBE_RESUME_PLAYER in it.markers() }
        val playerType = setter.code().mapNotNull { it.methodRef() }.single { it.parameterTypes.map(Any::toString) == listOf("F") }.definingClass
        val itemType = resume.parameterTypes.first().toString()
        val extra = FixtureDex.classes(bundle, setOf(playerType, itemType)).values
        val context = PatchContexts.of((holders + extra + ExtensionDex.classDef(REEL_SPEED)).distinctBy { it.type })

        context.keepReelSpeed()

        val name = label
        val after = { method: Method -> context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)) }
        val newSetter = after(setter)
        assertEquals("$name: the setter's first instruction", SPEED_SET, newSetter[0].referenceText())
        assertEquals("$name: the setter grew", setter.code().size + 1, newSetter.size)

        val newResume = after(resume)
        val itemHook = newResume.indexOfFirst { it.referenceText() == ITEM }
        assertEquals("$name: maybeResumePlayer grew", resume.code().size + 2, newResume.size)
        assertEquals("$name: the player follows the reel", RESUMING, newResume[itemHook + 1].referenceText())
        val playCall = newResume[itemHook + 2]
        assertTrue("$name: the play call follows", playCall.methodRef()?.definingClass == playerType && playCall.methodRef()?.returnType == "Z")
        assertEquals("$name: the player handed over is the one played", playCall.registers().first(), newResume[itemHook + 1].registers().single())

        val holdBefore = methods.single { LOCK_UP_BEGIN in it.markers() }
        val newHold = after(holdBefore)
        assertEquals("$name: the hold handler grew", holdBefore.code().size + 2, newHold.size)
        val begun = newHold.indexOfFirst { it.string()?.endsWith("_$LOCK_UP_BEGIN") == true }
        assertEquals("$name: the lock hook", LOCKED_UP, newHold[begun + 1].referenceText())
        val ended = newHold.indexOfFirst { it.string() == HOLD_ENDED_EVENT }
        assertEquals("$name: the hold-end hook", HOLD_ENDED, newHold[ended + 1].referenceText())

        val resetBefore = methods.single { RESET in it.markers() }
        val newReset = after(resetBefore)
        assertEquals("$name: the reset grew", resetBefore.code().size + 3, newReset.size)
        val endMarker = newReset.indexOfFirst { it.string()?.endsWith("_$LOCK_UP_END") == true }
        assertEquals("$name: the reason hook", LOCK_UP_ENDED, newReset[endMarker + 1].referenceText())
        val reasonRegister = newReset[endMarker + 1].registers().single()
        assertEquals(
            "$name: the reason handed over is the register the reasons go in",
            setOf(reasonRegister),
            newReset.filter { it.string() in LOCK_END_REASONS }.map { (it as OneRegisterInstruction).registerA }.toSet(),
        )
        val asked = newReset.indexOfFirst { it.referenceText() == RESET_SPEED }
        assertEquals("$name: the speed asked for is the one the setter gets", newReset[asked + 2].registers()[2], newReset[asked].registers().single())
        assertEquals("$name: the setter follows", setter.text(), newReset[asked + 2].methodRef()?.text())

        val ad = context.method(REEL_SPEED, AD_ITEM_STUB)
        assertEquals("$name: the ad flag is the reel's", itemType, (ad[1] as ReferenceInstruction).reference.let { (it as FieldReference).definingClass })
        val speed = context.method(REEL_SPEED, SET_PLAYER_SPEED_STUB)
        assertEquals("$name: the stub calls the player's setter", playerType, speed[1].methodRef()?.definingClass)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /** What each stand-in looks like; the defaults are 449's shape, and each case changes one thing. */
    private data class Shape(
        val setters: Int = 1,
        val setterTakesInt: Boolean = false,
        val speedCalls: Int = 1,
        val playerPublic: Boolean = true,
        val resumeElsewhere: Boolean = false,
        val plays: Int = 1,
        val itemOverwritten: Boolean = false,
        val holdIsLongPressEnd: Boolean = true,
        val endedEvents: Int = 1,
        val branchAfterBegin: Boolean = false,
        val lockEndMarked: Boolean = true,
        val reasonsInTwoRegisters: Boolean = false,
        val reasonOverwritten: Boolean = false,
        val resetCalls: Int = 1,
        val speedReadAfterReset: Boolean = false,
        val adTested: Boolean = true,
        val adFromAnotherClass: Boolean = false,
        val adFieldPublic: Boolean = true,
        val itemPublic: Boolean = true,
    )

    /**
     * The Reels player controller with its setPlaybackSpeed and maybeResumePlayer; the player
     * interface; the reel with its ad flag; fast play's hold handler, which logs a lock beginning
     * and a hold ending; the long-press feature's reset, which logs a lock ending with its reason and
     * sets the reel back to normal speed; and the fast-play hint, which picks its ads key by the
     * reel's ad flag.
     */
    private fun classes(shape: Shape = Shape()): List<ClassDef> {
        val speedParameter = if (shape.setterTakesInt) "I" else "F"
        val setters = (0 until shape.setters).map { copy ->
            method(controller, if (copy == 0) "A19" else "A1B", listOf("Lkotlin/jvm/functions/Function1;", speedParameter), "V", 5, """
                const-string v0, "${marker(SET_PLAYBACK_SPEED)}"
                iget-object v0, p0, $controller->player:$player
                if-eqz v0, :done
                ${"invoke-interface { v0, p2 }, $player->setSpeed(F)V\n".repeat(shape.speedCalls)}
                :done
                return-void
            """)
        }
        val plays = "invoke-interface { v1, p2, p3 }, $player->play(Ljava/lang/String;Z)Z\nmove-result v0\n".repeat(shape.plays)
        val resume = method(if (shape.resumeElsewhere) "Lfixture/Other;" else controller, "A0E",
            listOf(item, "Ljava/lang/String;", "Z"), "V", 6, """
                const-string v0, "${marker(MAYBE_RESUME_PLAYER)}"
                iget-object v1, p0, $controller->player:$player
                ${if (shape.itemOverwritten) "const/4 p1, 0x0" else ""}
                iget-boolean v0, p1, $item->ready:Z
                if-eqz v0, :play
                invoke-interface { v1 }, $player->prepare()V
                :play
                $plays
                return-void
            """, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value)
        val controllerClass = classDef(controller, setters + (if (shape.resumeElsewhere) emptyList() else listOf(resume)),
            fields = listOf(field(controller, "player", player)))
        val playerClass = ImmutableClassDef(
            player,
            (if (shape.playerPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(
                abstract(player, "setSpeed", listOf("F"), "V"),
                abstract(player, "play", listOf("Ljava/lang/String;", "Z"), "Z"),
                abstract(player, "prepare", emptyList(), "V"),
            ),
        )
        val itemClass = ImmutableClassDef(
            item, (if (shape.itemPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null,
            listOf(field(item, "ad", "Z", shape.adFieldPublic), field(item, "ready", "Z")),
            emptyList(),
        )

        val endedEvents = "const-string v1, \"$HOLD_ENDED_EVENT\"\ninvoke-static { v1 }, $log\n".repeat(shape.endedEvents)
        val holdMethod = method(hold, "end", listOf("Ljava/lang/Object;", "Z"), "Ljava/lang/Object;", 4, """
            const-string v0, "${marker(if (shape.holdIsLongPressEnd) LONG_PRESS_END else "ClipsLongPressController_onLongPressStart", "q2")}"
            if-eqz p1, :ended
            if-nez p1, :up
            const-string v0, "down"
            goto :log
            :up
            const-string v0, "up"
            :log
            const-string v1, "${marker(LOCK_UP_BEGIN, "q2")}"
            ${if (shape.branchAfterBegin) ":after" else ""}
            invoke-static { v1 }, $purge
            ${if (shape.branchAfterBegin) "if-eqz p1, :after" else ""}
            const-string v1, "locked"
            return-object v1
            :ended
            $endedEvents
            return-object v1
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

        val reasonInto = { reason: String -> if (shape.reasonsInTwoRegisters && reason == "switch_tab") 1 else 2 }
        val resetCalls = "invoke-virtual { p1, v1, v0 }, $setterRef\n".repeat(shape.resetCalls)
        val resetMethod = method(feature, "reset", listOf(item, controller, "Ljava/lang/Integer;", "Z"), "V", 7, """
            const-string v0, "${marker(RESET, "q2")}"
            if-eqz p0, :unlocked
            invoke-virtual { p2 }, Ljava/lang/Integer;->intValue()I
            move-result v1
            if-eqz v1, :scrolled
            const/4 v0, 0x1
            if-eq v1, v0, :tab
            const/4 v0, 0x2
            if-eq v1, v0, :slid
            const-string v2, "cancel_lock_up"
            :reason
            const-string v0, "${marker(if (shape.lockEndMarked) LOCK_UP_END else "ClipsFastPlayLogger_logFastPlayExposure", "q2")}"
            invoke-static { v0 }, $purge
            invoke-static { v2 }, $log
            :unlocked
            if-nez p3, :done
            const/4 v1, 0x0
            const/high16 v0, 0x3f800000
            $resetCalls
            ${if (shape.speedReadAfterReset) "invoke-static { v0 }, Lfixture/Log;->speed(F)V" else ""}
            :done
            return-void
            :slid
            const-string v2, "swipe_down"
            goto :reason
            :tab
            const-string v${reasonInto("switch_tab")}, "switch_tab"
            ${if (shape.reasonOverwritten) "const-string v2, \"elsewhere\"" else ""}
            goto :reason
            :scrolled
            const/16 v0, 0x106
            invoke-static { v0 }, Lfixture/Strings;->get(I)Ljava/lang/String;
            move-result-object v2
            goto :reason
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

        val flagOwner = if (shape.adFromAnotherClass) "Lfixture/OtherItem;" else item
        val nuxMethod = method(nux, "show", listOf(item, "Ljava/lang/String;"), "V", 4, """
            const-string v0, "${marker(FAST_PLAY_NUX, "q2")}"
            ${if (shape.adFromAnotherClass) "check-cast p0, $flagOwner" else ""}
            iget-boolean v1, p0, $flagOwner->ad:Z
            const-string v0, "${marker("ClipsFastPlayRepository_updateClipFastPlayNuxShown", "q2")}"
            ${if (shape.adTested) "if-eqz v1, :organic" else "if-eqz v1, :organic\nconst/4 v1, 0x0"}
            const-string v0, "$ADS_HINT_KEY"
            :shown
            invoke-static { v0 }, $log
            return-void
            :organic
            const-string v0, "key_clips_fast_play_ui_last_shown_timestamp_ms"
            goto :shown
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

        return listOfNotNull(
            controllerClass,
            if (shape.resumeElsewhere) classDef("Lfixture/Other;", listOf(resume)) else null,
            playerClass,
            itemClass,
            classDef(hold, listOf(holdMethod)),
            classDef(feature, listOf(resetMethod)),
            classDef(nux, listOf(nuxMethod)),
            ExtensionDex.classDef(REEL_SPEED),
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun abstract(owner: String, name: String, parameters: List<String>, returns: String): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

    private fun field(owner: String, name: String, type: String, public: Boolean = true) = ImmutableField(
        owner, name, type, (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or AccessFlags.FINAL.value,
        null, null, null,
    )

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun BytecodePatchContext.method(owner: String, name: String, parameters: List<String>? = null): List<Instruction> =
        mutableClassDefBy(owner).methods.single {
            it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters)
        }.code()

    private fun assertCall(what: String, instruction: Instruction, hook: String, registers: List<Int>) {
        assertEquals("$what: the hook", hook, instruction.referenceText())
        assertEquals("$what: the registers", registers, instruction.registers())
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        val landing = addresses[index] + (this[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(landing)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
}
