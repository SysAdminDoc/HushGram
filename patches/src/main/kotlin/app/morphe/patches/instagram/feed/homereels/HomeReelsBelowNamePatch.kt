/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.homereels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.cleanup.loadsLiteral
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HOME_REELS_STATUS = "homeReelsBelowName"
internal const val BELOW_NAME = "$EXTENSION_PACKAGE/feed/HomeReelHeader;->belowName(Ljava/lang/String;)Z"
internal const val WHOLE_REEL = "$EXTENSION_PACKAGE/feed/HomeReelHeader;->wholeReel(FFLjava/lang/String;)F"

/** The narrowest shape the feed gives a post's media, 4:5, as its media state builder loads it. */
internal const val NARROWEST_POST = 0x3f4ccccdL

/** The post the media state builder works on, Instagram's own class name. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"

/** What the feed's full-height test compares a post's kind with before anything else: a live video Instagram recommends. */
internal const val LIVE_IN_FEED = "recommended_live_in_feed"

/** The name of the row kind a reel gets as a tall card with the name on top of it, loaded by the row kinds' enum. */
internal const val FULL_HEIGHT_ROW = "litho_full_height_single_media"

private const val STRING = "Ljava/lang/String;"
private const val MODULE_NAME = "getModuleName"

private fun refuse(detail: String): Nothing = throw PatchException("Reels on Home below the name: $detail")

/**
 * The two tests Home's row picker asks before it lays a reel out as a tall card. [feedTest] on
 * [feed] says the feed puts reels full height, given the feed's module name in v[module]; the hook
 * goes in at [afterLive], where its live video branch ends, borrowing v[feedScratch].
 * [tallTest] on [row] says this reel is tall enough; it reads the module name itself through
 * [host] on its own object and [moduleName], borrowing v[tallScratch] first thing.
 */
internal class HomeReelSites(
    val feed: String,
    val feedTest: String,
    val afterLive: Int,
    val module: Int,
    val feedScratch: Int,
    val row: String,
    val tallTest: String,
    val self: Int,
    val tallScratch: Int,
    val host: String,
    val moduleName: String,
)

/**
 * The row kinds are an enum whose names Instagram keeps ([FULL_HEIGHT_ROW] among them). The row
 * picker is the one static method returning that enum that calls a feed test: an instance method
 * of (object, String) returning a boolean that loads [LIVE_IN_FEED]. The picker hands the test the
 * module name it reads with [MODULE_NAME] from a field of its row maker, then asks the row maker's
 * own (object) boolean method, the tall test, with the same row maker.
 */
internal fun BytecodePatchContext.findHomeReels(): HomeReelSites {
    val rowKinds = classesLoadingString(FULL_HEIGHT_ROW).filter { it.superclass == "Ljava/lang/Enum;" }
        .singleOrNull()?.type ?: refuse("expected one row kind enum loading $FULL_HEIGHT_ROW")
    val tests = classesLoadingString(LIVE_IN_FEED).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isStatic() && method.returnType == "Z" && method.parameterTypes.size == 2 &&
                method.parameterTypes[0].toString().startsWith("L") && method.parameterTypes[1].toString() == STRING &&
                loadsString(method, LIVE_IN_FEED)
        }
    }
    val picked = tests.flatMap { test ->
        val text = test.text()
        methodsCalling(test.definingClass, test.name).filter { caller ->
            caller.isStatic() && caller.returnType == rowKinds && caller.code().any { it.call()?.toString() == text }
        }.map { test to it }
    }.distinctBy { (test, picker) -> test.text() + picker.text() }
    val (feedTest, picker) = picked.singleOrNull()
        ?: refuse("expected one static row picker returning $rowKinds that calls a feed test loading $LIVE_IN_FEED, found ${picked.map { it.second.text() }}")

    val code = picker.code()
    val tall = code.mapNotNull { instruction ->
        instruction.call()?.takeIf { called ->
            (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                called.definingClass == picker.definingClass && called.returnType == "Z" &&
                called.parameterTypes.size == 1 && called.parameterTypes[0].toString().startsWith("L")
        }?.toString()
    }.distinct().singleOrNull() ?: refuse("expected the picker ${picker.text()} to ask one (object) boolean method of its own class")
    val asks = code.indices.mapNotNull { at -> hostRead(code, at, picker.definingClass) }.distinct()
    val (host, moduleName) = asks.singleOrNull()
        ?: refuse("expected the picker to read the module name once through a field of ${picker.definingClass}, found $asks")

    val feedCode = feedTest.code()
    val afterLive = afterLiveBranch(feedCode) ?: refuse("no live video branch at the start of ${feedTest.text()}")
    val position = feedTest.parameterTypes.indexOfLast { it.toString() == STRING }
    val module = feedTest.localRegisterCount() + 1 + feedTest.parameterTypes.take(position).sumOf { it.width() }
    val overwritten = feedCode.take(afterLive).indexOfFirst { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == module }
    if (overwritten >= 0) refuse("${feedTest.text()} writes over the module name in v$module at $overwritten, before $afterLive")
    val feedScratch = feedTest.freeLocalsAt("Reels on Home below the name", afterLive, 1, highest = 15).single()

    val tallTest = classDefByOrNull(picker.definingClass)?.methods?.singleOrNull { it.text() == tall }
        ?: refuse("$tall is gone")
    if (tallTest.isStatic()) refuse("$tall is static")
    val tallScratch = tallTest.freeLocalsAt("Reels on Home below the name", 0, 1, highest = 15).single()
    return HomeReelSites(
        feed = feedTest.definingClass,
        feedTest = feedTest.signature(),
        afterLive = afterLive,
        module = module,
        feedScratch = feedScratch,
        row = picker.definingClass,
        tallTest = tallTest.signature(),
        self = tallTest.localRegisterCount(),
        tallScratch = tallScratch,
        host = host,
        moduleName = moduleName,
    )
}

/**
 * At [at]: an iget-object of a field of [owner] whose value is the only argument of the next
 * instruction, an invoke-interface of [MODULE_NAME] returning a String. The field and the method, as text.
 */
private fun hostRead(code: List<Instruction>, at: Int, owner: String): Pair<String, String>? {
    val read = code[at]
    if (read.opcode != Opcode.IGET_OBJECT) return null
    val field = read.reference() as? FieldReference ?: return null
    if (field.definingClass != owner) return null
    val ask = code.getOrNull(at + 1) ?: return null
    val called = ask.call() ?: return null
    if (ask.opcode != Opcode.INVOKE_INTERFACE && ask.opcode != Opcode.INVOKE_INTERFACE_RANGE) return null
    if (called.name != MODULE_NAME || called.parameterTypes.isNotEmpty() || called.returnType != STRING) return null
    val passed = when (ask) {
        is Instruction35c -> ask.registerC.takeIf { ask.registerCount == 1 }
        is Instruction3rc -> ask.startRegister.takeIf { ask.registerCount == 1 }
        else -> null
    }
    if (passed != (read as TwoRegisterInstruction).registerA) return null
    return "${field.definingClass}->${field.name}:${field.type}" to called.toString()
}

/**
 * Where the feed test's first branch, the live video one, ends: the test loads [LIVE_IN_FEED],
 * compares it with equals, and on no jumps past the code that answers yes for a live video. That
 * jump's target, after a return.
 */
internal fun BytecodePatchContext.afterLiveBranch(code: List<Instruction>): Int? {
    val live = code.indices.firstOrNull { stringLoadedAt(code, it) == LIVE_IN_FEED } ?: return null
    val equals = (live + 1 until minOf(code.size, live + 4)).firstOrNull { at ->
        code[at].call()?.let { it.name == "equals" && it.returnType == "Z" } == true
    } ?: return null
    val result = code.getOrNull(equals + 1) ?: return null
    val branch = code.getOrNull(equals + 2) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_EQZ) return null
    if ((branch as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA) return null
    val target = targetOf(code, equals + 2) ?: return null
    if (target <= equals + 2 || (equals + 3 until target).none { code[it].opcode == Opcode.RETURN }) return null
    return target
}

/** The index a branch at [from] lands on. */
internal fun targetOf(code: List<Instruction>, from: Int): Int? {
    var unit = 0
    val units = code.map { instruction -> unit.also { unit += instruction.codeUnits } }
    val lands = units[from] + ((code[from] as? OffsetInstruction)?.codeOffset ?: return null)
    return units.indexOf(lands).takeIf { it >= 0 }
}

/**
 * Where the feed test's live branch ends, and first thing in the tall test, both on the label
 * Instagram's own jumps land on: with the switch on and Home's module name, each answers no, so
 * the row picker gives the reel the layout of every other post.
 */
internal fun BytecodePatchContext.keepHomeReelsBelowName(sites: HomeReelSites) {
    val feedTest = mutableClassDefBy(sites.feed).methods.singleOrNull { it.signature() == sites.feedTest }
        ?: refuse("${sites.feed}->${sites.feedTest} is gone")
    val f = "v${sites.feedScratch}"
    feedTest.addInstructionsAtControlFlowLabel(
        sites.afterLive,
        """
            invoke-static/range { v${sites.module} .. v${sites.module} }, $BELOW_NAME
            move-result $f
            if-eqz $f, :instagram
            const/4 $f, 0x0
            return $f
            :instagram
            nop
        """,
    )
    val tallTest = mutableClassDefBy(sites.row).methods.singleOrNull { it.signature() == sites.tallTest }
        ?: refuse("${sites.row}->${sites.tallTest} is gone")
    val t = "v${sites.tallScratch}"
    tallTest.addInstructionsAtControlFlowLabel(
        0,
        """
            move-object/from16 $t, v${sites.self}
            iget-object $t, $t, ${sites.host}
            if-eqz $t, :instagram
            invoke-interface/range { $t .. $t }, ${sites.moduleName}
            move-result-object $t
            invoke-static/range { $t .. $t }, $BELOW_NAME
            move-result $t
            if-eqz $t, :instagram
            const/4 $t, 0x0
            return $t
            :instagram
            nop
        """,
    )
}

/**
 * Home's media state builder (#788): [method] on [owner] works out the shape a post's media is shown
 * at, a reel laid out like a post held to 4:5 at its narrowest, and hands that and the media's own
 * shape to the state's constructor. At [at] the shape shown, just read into v[shown], moves on toward
 * the constructor. The media's own shape is in v[own], written once before, and the feed's analytics
 * host in the parameter v[host], whose module name [moduleName] reads. v[scratch] are free there.
 */
internal class PostShapeSite(
    val owner: String,
    val method: String,
    val at: Int,
    val shown: Int,
    val own: Int,
    val host: Int,
    val moduleName: String,
    val scratch: List<Int>,
)

/**
 * The one instance method taking the [MEDIA] first and returning an object that loads [NARROWEST_POST]
 * and calls a private (float, float, int, boolean) float method of its own class, the clamp. It builds
 * the state once, with a constructor taking two floats: the media's own shape, then the shape shown.
 * The shown one comes out of the float field of a (boolean, float) holder, read once and moved on to
 * the register the constructor takes it from. It asks the analytics host one module name.
 */
internal fun BytecodePatchContext.findPostShape(): PostShapeSite {
    val found = classesLoading(NARROWEST_POST).flatMap { it.methods }.filter { method ->
        !method.isStatic() && method.returnType.startsWith("L") &&
            method.parameterTypes.firstOrNull()?.toString() == MEDIA && method.loadsLiteral(NARROWEST_POST) &&
            method.code().any { call -> call.opcode == Opcode.INVOKE_DIRECT && call.call()?.isClampOf(method.definingClass) == true }
    }
    val builder = found.singleOrNull()
        ?: refuse("expected one media state builder calling a (float, float, int, boolean) clamp, found ${found.map { it.text() }}")
    val code = builder.code()
    val text = builder.text()

    val makes = code.indices.filter { at ->
        val called = code[at].call()
        code[at].opcode == Opcode.INVOKE_DIRECT_RANGE && called?.definingClass == builder.returnType && called.name == "<init>"
    }
    val make = makes.singleOrNull() ?: refuse("expected $text to build ${builder.returnType} once, found ${makes.size}")
    val types = code[make].call()!!.parameterTypes.map { it.toString() }
    val floats = types.indices.filter { types[it] == "F" }
    if (floats.size != 2) refuse("${builder.returnType}'s constructor takes ${floats.size} floats, not the media's own shape and the one shown")
    val start = (code[make] as Instruction3rc).startRegister
    val own = start + 1 + types.take(floats[0]).sumOf { it.width() }
    val kept = start + 1 + types.take(floats[1]).sumOf { it.width() }

    val holders = code.mapNotNull { call ->
        call.call()?.takeIf { called ->
            call.opcode == Opcode.INVOKE_DIRECT && called.name == "<init>" && called.parameterTypes.map { it.toString() } == listOf("Z", "F")
        }?.definingClass
    }.toSet()
    val reads = code.indices.filter { at ->
        val field = code[at].reference() as? FieldReference
        code[at].opcode == Opcode.IGET && field != null && field.definingClass in holders && field.type == "F"
    }
    val read = reads.singleOrNull() ?: refuse("expected $text to read one (boolean, float) holder's float, found ${reads.size}")
    val shown = (code[read] as TwoRegisterInstruction).registerA
    val at = read + 1
    val move = code.getOrNull(at) as? TwoRegisterInstruction
    if (move == null || move.opcode !in FLOAT_MOVES || move.registerB != shown) refuse("$text doesn't move the shape shown on after reading it")
    val handed = code.any { it.opcode in FLOAT_MOVES && (it as TwoRegisterInstruction).registerA == kept && it.registerB == move.registerA }
    if (!handed) refuse("$text doesn't hand v${move.registerA} to the constructor's shape shown in v$kept")
    if (shown > 15) refuse("$text reads the shape shown into v$shown, past v15")
    if (at in builder.jumpTargets()) refuse("something in $text jumps onto $at")

    val ownWrites = code.indices.filter { code[it].writesRegister(own) }
    if (ownWrites.size != 1 || ownWrites.single() > read) refuse("$text writes the media's own shape in v$own at $ownWrites")
    val asks = code.mapNotNull { it.call() }
        .filter { it.name == MODULE_NAME && it.parameterTypes.isEmpty() && it.returnType == STRING }.map { it.toString() }.distinct()
    val moduleName = asks.singleOrNull() ?: refuse("expected $text to ask one module name, found $asks")
    val module = moduleName.substringBefore("->")
    val position = builder.parameterTypes.indexOfFirst { type ->
        type.toString() == module || classDefByOrNull(type.toString())?.interfaces?.contains(module) == true
    }
    if (position < 0) refuse("$text takes no $module")
    val host = builder.localRegisterCount() + 1 + builder.parameterTypes.take(position).sumOf { it.width() }
    if (code.any { it.writesRegister(host) }) refuse("$text writes over its analytics host in v$host")
    val scratch = builder.freeLocalsAt("Reels on Home below the name", at, 2, except = listOf(shown))
    return PostShapeSite(builder.definingClass, builder.signature(), at, shown, own, host, moduleName, scratch)
}

/** The moves a float takes from one register to another. */
private val FLOAT_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/** Whether [this] is a private (float, float, int, boolean) float method of [owner]. */
private fun MethodReference.isClampOf(owner: String): Boolean =
    definingClass == owner && returnType == "F" && parameterTypes.map { it.toString() } == listOf("F", "F", "I", "Z")

/** Whether [this] instruction puts a value in v[register]. */
private fun Instruction.writesRegister(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA == register ||
        opcode.setsWideRegister() && (this as? OneRegisterInstruction)?.registerA == register - 1

/**
 * Right after the builder reads the shape shown, it goes through the extension with the media's own
 * shape and the feed's module name, so a reel laid out below the name on Home keeps its own shape.
 */
internal fun BytecodePatchContext.keepHomeReelsWhole(site: PostShapeSite) {
    val builder = mutableClassDefBy(site.owner).methods.singleOrNull { it.signature() == site.method }
        ?: refuse("${site.owner}->${site.method} is gone")
    val (module, own) = site.scratch.map { "v$it" }
    builder.addInstructions(
        site.at,
        """
            move-object/from16 $module, v${site.host}
            invoke-interface { $module }, ${site.moduleName}
            move-result-object $module
            move/from16 $own, v${site.own}
            invoke-static { v${site.shown}, $own, $module }, $WHOLE_REEL
            move-result v${site.shown}
        """,
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.text() = "$definingClass->${signature()}"
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun CharSequence.width() = if (toString() == "J" || toString() == "D") 2 else 1

/**
 * Lays reels on Home out like every other post, the name and the three-dot menu above the video
 * instead of on top of it, and the whole video, not cut to 4:5 (#788). See HomeReelHeader in the
 * extension. Off to start.
 */
@Suppress("unused")
val homeReelsBelowNamePatch = bytecodePatch(
    name = "Reels on Home below the name",
    description = "Shows reels on Home like other posts, with the name and the three-dot menu above the " +
        "whole video instead of on top of it. Starts off. Turn it on in HushGram settings > Feed and Reels.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(HOME_REELS_STATUS)
        val sites = findHomeReels()
        keepHomeReelsBelowName(sites)
        keepHomeReelsWhole(findPostShape())
        enableStatus(HOME_REELS_STATUS)
    }
}
