/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.tap

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.feed.sound.FEED_CONTROLLER_STRING
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TapFeedVideoToPauseHookTest {
    private val media = "Lcom/instagram/feed/media/Media;"
    private val objectType = "Ljava/lang/Object;"
    private val marker = "$FEED_TAP_PAUSE->NOT_PATCHED:$objectType"

    /** The instructions the tap and press hooks add: three moves, the call, its answer, the test and the return. */
    private val hookLength = 7

    /** The instructions the release hook adds: four moves and the call. */
    private val releaseLength = 5

    /** The instructions the launcher hook adds: four moves, the call, its answer, the test and the return. */
    private val launcherLength = 8

    /**
     * The hooks the patch writes are in the FeedTapPause the bundle ships, public and static: the tap's and the press's
     * take the handler, the post and the post's state as Objects, the release's those and whether it was a lift. Every
     * stub the patch fills is in the reader, static, answering an Object for its Objects, still answering the marker,
     * with no more registers than an invoke can name.
     */
    @Test
    fun theHookAndItsStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_TAP_PAUSE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FEED_TAPPED, FEED_PRESSED, FEED_RELEASED, FEED_OPENING)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        val reader = ExtensionDex.classDef(FEED_PLAYER_READER)
        for ((name, count) in FEED_PLAYER_STUBS) {
            val stub = reader.methods.singleOrNull { it.name == name }
            assertNotNull("$FEED_PLAYER_READER has no $name", stub)
            assertTrue("$name is static", AccessFlags.STATIC.isSet(stub!!.accessFlags))
            assertEquals("$name answers an Object", objectType, stub.returnType)
            assertEquals("$name takes Objects", List(count) { objectType }, stub.parameterTypes.map(Any::toString))
            assertTrue("$name has a register an invoke can't name", stub.implementation!!.registerCount <= 16)
            assertEquals("$name answers the marker until it's filled", marker, stub.code().first().referenceText())
        }
        assertEquals("every stub of the reader is one the patch fills", FEED_PLAYER_STUBS.map { it.first }.sorted(),
            reader.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<clinit>" }.map { it.name }.sorted())
    }

    /**
     * In each of the version's three builds the tap handler, the controller and the player are found by their strings, the
     * hook goes in once, first thing in the handler, reading the handler, the post and the state from their own registers,
     * and every stub is filled. The handler class's press entry and release are found by their shapes, and each gets its
     * own hook once, first thing, reading its own registers. Every other instruction of the handler's class and of the
     * controller stays in place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsFeedTap() {
        val bundles = bundles()
        assertEquals("every 452 build", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = name(apk)
            val parts = parts(apk)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, parts.wanted).values + ExtensionDex.classDef(FEED_PLAYER_READER)
                val context = PatchContexts.of(classes)
                val originals = listOf(parts.handler, parts.controller, parts.launcher).flatMap { context.mutableClassDefBy(it).methods }
                    .filter { it.implementation != null }.associateWith(::NeutralNativePath)

                val found = context.findFeedTap()
                assertEquals("$what: the handler's class", parts.handler, found.handler.definingClass)
                assertEquals("$what: the controller", parts.controller, found.controller)
                assertEquals("$what: the player", parts.player, found.isPlaying.definingClass)
                assertEquals("$what: the player's field", parts.player, found.playerField.type)
                assertEquals("$what: the playing item shows a post", media, found.shown.returnType)
                assertEquals("$what: the page lookup", listOf(objectType, "I"), found.pager.parameterTypes.map(Any::toString))
                assertEquals("$what: the state's note", listOf("Z"), found.mark.parameterTypes.map(Any::toString))
                assertEquals("$what: the state", found.handler.parameterTypes[4].toString(), found.mark.definingClass)
                assertEquals("$what: the press entry's class", parts.handler, found.press.definingClass)
                assertEquals("$what: the release's class", parts.handler, found.release.definingClass)
                val point = found.handler.parameterTypes[2].toString()
                val state = found.handler.parameterTypes[4].toString()
                // 453 hands the press entry the post's holder before the state; its (View, Media, point,
                // state, int) method is the double-tap like and must not be taken for the press.
                val holder = found.handler.parameterTypes[3].toString()
                assertEquals("$what: the press entry", listOf("Landroid/view/View;", media, point, state),
                    found.press.parameterTypes.map(Any::toString).filter { it != holder })
                assertEquals("$what: the press entry ends with the state", state, found.press.parameterTypes.last().toString())
                assertEquals("$what: the release", listOf(media, point, state, "Z"), found.release.parameterTypes.map(Any::toString))
                assertEquals("$what: the Reels launcher's class", parts.launcher, found.launcher.definingClass)
                assertEquals("$what: the launcher takes the post and its state", listOf(media, state),
                    found.launcher.parameterTypes.map(Any::toString).subList(4, 6))
                assertEquals("$what: the launcher's lazy field is its own", parts.launcher, found.launcherSupplier.definingClass)
                assertEquals("$what: and is asked as the handler's is", found.supplierValue.toString(), found.launcherValue.toString())
                if (apk.extension == "apks") {
                    // The declared build's own names, 385914557, the launcher's first, in one list so a
                    // run shows every name that moved. 451 paused with a string of its own, no Integer
                    // constant named.
                    assertEquals("$what: the pause reason", "tapped", found.reasonText)
                    assertEquals(
                        "$what: the names",
                        listOf(
                            "LX/01Jl;", "A00", "A03",
                            "A06", "A05", "A09", "A0C", "A0F", "A00", "A0m", "A01", "A00", "A0B", "A03", "A0F", "A0u", "A3G",
                        ),
                        listOf(
                            found.launcher.definingClass, found.launcher.name, found.launcherSupplier.name,
                            found.handler.name, found.press.name, found.release.name, found.moduleSupplier.name,
                            found.controllerField.name, found.playerField.name, found.isPlaying.name, found.itemField.name,
                            found.shown.name, found.pageField.name, found.play.name, found.pause.name,
                            found.mark.name, found.adDetails.name,
                        ),
                    )
                }

                context.apply { applyFeedTapPause(found) }

                // The handler's class, the controller and the reader are the classes the patch reads or writes.
                val asking = listOf(parts.handler, parts.controller, FEED_PLAYER_READER).flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { m -> m.code().any { it.referenceText() == FEED_TAPPED } }
                }
                assertEquals("$what: methods asking", listOf(parts.handler to found.handler.name), asking.map { it.definingClass to it.name })
                val handler = asking.single()
                val code = handler.code()
                assertEquals("$what: asks once", 1, code.count { it.referenceText() == FEED_TAPPED })
                assertEquals(
                    "$what: the hook comes first",
                    listOf(
                        Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                        Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
                    ),
                    code.take(hookLength).map { it.opcode },
                )
                val p0 = handler.implementation!!.registerCount - found.handler.parameterTypes.size - 1
                assertEquals("$what: the handler, the post and the state are read from their own registers",
                    listOf(p0, p0 + 2, p0 + 5), code.take(3).map { (it as TwoRegisterInstruction).registerB })
                assertEquals("$what: into the first three locals", listOf(0, 1, 2), code.take(3).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$what: which the call hands over", listOf(0, 1, 2), code[3].arguments())
                assertEquals("$what: the answer is tested where it lands",
                    (code[4] as OneRegisterInstruction).registerA, (code[5] as OneRegisterInstruction).registerA)
                assertEquals("$what: and lands in the first local", 0, (code[4] as OneRegisterInstruction).registerA)
                assertEquals("$what: a no goes on to the handler's first instruction", hookLength,
                    (code[5] as BuilderOffsetInstruction).target.location.index)

                // The press entry: the same guard, on the post in p2 and the state in its last parameter, in no other method.
                val pressing = listOf(parts.handler, parts.controller, FEED_PLAYER_READER).flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { m -> m.code().any { it.referenceText() == FEED_PRESSED } }
                }
                assertEquals("$what: methods asking about a press", listOf(parts.handler to found.press.name),
                    pressing.map { it.definingClass to it.name })
                val press = pressing.single()
                val pressCode = press.code()
                assertEquals("$what: the press is asked about once", 1, pressCode.count { it.referenceText() == FEED_PRESSED })
                assertEquals(
                    "$what: the press hook comes first",
                    listOf(
                        Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                        Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
                    ),
                    pressCode.take(hookLength).map { it.opcode },
                )
                val pressP0 = press.implementation!!.registerCount - found.press.parameterTypes.size - 1
                assertEquals("$what: the press's handler, post and state are read from their own registers",
                    listOf(pressP0, pressP0 + 2, pressP0 + found.press.parameterTypes.size), pressCode.take(3).map { (it as TwoRegisterInstruction).registerB })
                assertEquals("$what: into the press's first three locals", listOf(0, 1, 2),
                    pressCode.take(3).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$what: which the press call hands over", listOf(0, 1, 2), pressCode[3].arguments())
                assertEquals("$what: the press answer lands in the first local and is tested there", listOf(0, 0),
                    listOf((pressCode[4] as OneRegisterInstruction).registerA, (pressCode[5] as OneRegisterInstruction).registerA))
                assertEquals("$what: a no goes on to the press entry's first instruction", hookLength,
                    (pressCode[5] as BuilderOffsetInstruction).target.location.index)

                // The release: told first thing, with the post in p1, the state in p3 and the lift in p4, and goes on.
                val releasing = listOf(parts.handler, parts.controller, FEED_PLAYER_READER).flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { m -> m.code().any { it.referenceText() == FEED_RELEASED } }
                }
                assertEquals("$what: methods telling of a release", listOf(parts.handler to found.release.name),
                    releasing.map { it.definingClass to it.name })
                val release = releasing.single()
                val releaseCode = release.code()
                assertEquals("$what: the release is told once", 1, releaseCode.count { it.referenceText() == FEED_RELEASED })
                assertEquals(
                    "$what: the release hook comes first",
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16, Opcode.INVOKE_STATIC),
                    releaseCode.take(releaseLength).map { it.opcode },
                )
                val releaseP0 = release.implementation!!.registerCount - 5
                assertEquals("$what: the release's handler, post, state and lift are read from their own registers",
                    listOf(releaseP0, releaseP0 + 1, releaseP0 + 3, releaseP0 + 4),
                    releaseCode.take(4).map { (it as TwoRegisterInstruction).registerB })
                assertEquals("$what: into the release's first four locals", listOf(0, 1, 2, 3),
                    releaseCode.take(4).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$what: which the release call hands over", listOf(0, 1, 2, 3), releaseCode[4].arguments())

                // The Reels launcher: asked first thing, with the entry point in p2, the post in p5 and the state in p6.
                val hooked = listOf(parts.handler, parts.controller, parts.launcher, FEED_PLAYER_READER)
                val opening = hooked.flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { m -> m.code().any { it.referenceText() == FEED_OPENING } }
                }
                assertEquals("$what: methods asking about a Reels opening", listOf(parts.launcher to found.launcher.name),
                    opening.map { it.definingClass to it.name })
                assertEquals("$what: the launcher holds none of the other hooks", 0, context.mutableClassDefBy(parts.launcher).methods
                    .sumOf { m -> m.code().count { it.referenceText() in listOf(FEED_TAPPED, FEED_PRESSED, FEED_RELEASED) } })
                val launcher = opening.single()
                val launcherCode = launcher.code()
                assertEquals("$what: the launcher asks once", 1, launcherCode.count { it.referenceText() == FEED_OPENING })
                assertEquals(
                    "$what: the launcher hook comes first",
                    listOf(
                        Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16,
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
                    ),
                    launcherCode.take(launcherLength).map { it.opcode },
                )
                val launcherP0 = launcher.implementation!!.registerCount - found.launcher.parameterTypes.size - 1
                assertEquals("$what: the launcher, entry point, post and state are read from their own registers",
                    listOf(launcherP0, launcherP0 + 2, launcherP0 + 5, launcherP0 + 6),
                    launcherCode.take(4).map { (it as TwoRegisterInstruction).registerB })
                assertEquals("$what: into the launcher's first four locals", listOf(0, 1, 2, 3),
                    launcherCode.take(4).map { (it as OneRegisterInstruction).registerA })
                assertEquals("$what: which the launcher call hands over", listOf(0, 1, 2, 3), launcherCode[4].arguments())
                assertEquals("$what: the launcher answer lands in the first local and is tested there", listOf(0, 0),
                    listOf((launcherCode[5] as OneRegisterInstruction).registerA, (launcherCode[6] as OneRegisterInstruction).registerA))
                assertEquals("$what: a no goes on to the launcher's first instruction", launcherLength,
                    (launcherCode[6] as BuilderOffsetInstruction).target.location.index)

                for ((method, original) in originals) {
                    val params = method.parameterTypes.map(Any::toString)
                    fun isThe(hooked: Method) = method.definingClass == hooked.definingClass && method.name == hooked.name &&
                        params == hooked.parameterTypes.map(Any::toString)
                    val mine = when {
                        isThe(handler) || isThe(press) -> (0 until hookLength).toSet()
                        isThe(release) -> (0 until releaseLength).toSet()
                        isThe(launcher) -> (0 until launcherLength).toSet()
                        else -> emptySet()
                    }
                    original.assertPreserved("$what ${method.definingClass}->${method.name}", method, mine)
                }

                val reader = context.mutableClassDefBy(FEED_PLAYER_READER)
                for ((stubName, _) in FEED_PLAYER_STUBS) {
                    val filled = reader.methods.single { it.name == stubName }.code()
                    val body = filled.take(filled.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT } + 1)
                    assertEquals("$what: $stubName starts by casting what it's handed", Opcode.CHECK_CAST, body.first().opcode)
                    assertFalse("$what: $stubName still answers the marker", body.any { it.referenceText() == marker })
                    assertTrue("$what: $stubName reaches nothing outside the extension",
                        body.any { it.referenceText()?.startsWith("Lapp/hushgram/") == false })
                }
            }
        }
    }

    /** A build without the controller, the player or the reader stops the patch under its own name, before anything is written. */
    @Test
    fun aBuildMissingAPartStopsThePatch() {
        val apk = bundles().first()
        val parts = parts(apk)
        val classes = FixtureDex.classes(apk, parts.wanted).values + ExtensionDex.classDef(FEED_PLAYER_READER)
        val refusals = listOf(
            parts.controller to "expected one class loading \"$FEED_CONTROLLER_STRING\", found 0",
            parts.player to "expected one method holding \"$PLAY_INTERNAL\", found 0",
            parts.launcher to "expected one Reels launcher taking",
            parts.entryPoints to "expected one Reels launcher taking",
            FEED_PLAYER_READER to "the extension has no $FEED_PLAYER_READER",
        )
        for ((missing, said) in refusals) {
            val context = PatchContexts.of(classes.filter { it.type != missing })
            val failure = assertThrows(missing, PatchException::class.java) { context.findFeedTap() }
            val message = failure.message!!
            assertTrue(message, message.startsWith("Tap a feed video to pause it: ") && said in message)
            val handler = context.mutableClassDefBy(parts.handler)
            assertTrue("$missing: something was written", handler.methods.none { m ->
                m.code().any { it.referenceText() in listOf(FEED_TAPPED, FEED_PRESSED, FEED_RELEASED, FEED_OPENING) }
            })
        }
    }

    private class Parts(
        val handler: String,
        val controller: String,
        val player: String,
        val launcher: String,
        val entryPoints: String,
        val wanted: Set<String>,
    )

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
    }

    private fun name(apk: File): String = if (apk.extension == "apk") apk.parentFile.name else apk.name

    /**
     * The classes a build is read from: the handler's, the controller and the player, each the one class holding its string,
     * Media, and every class the handler's and the controller's code names, which takes in the module, its lazy holder,
     * the playing item, the post's state, the pause reason's two classes and the page lookup. Then the classes loading the
     * Reels launcher's string, what their code and their methods' parameters name, and the enum of entry points among
     * those with the classes its code names, its string pool among them. The launcher is the class of those with a method
     * taking a View and the entry points.
     */
    private fun parts(apk: File): Parts {
        val name = name(apk)
        val handlers = FixtureDex.classesHolding(apk, FEED_TAP_HANDLER_STRING)
        assertEquals("$name: classes loading the tap handler's string", 1, handlers.size)
        val controllers = FixtureDex.classesHolding(apk, FEED_CONTROLLER_STRING)
        assertEquals("$name: classes loading the controller's string", 1, controllers.size)
        val players = FixtureDex.classesHolding(apk, PLAY_INTERNAL)
        assertEquals("$name: classes loading the player's string", 1, players.size)
        val named = (handlers + controllers).flatMap { it.methods }.flatMap { m -> m.code().flatMap { it.typesNamed() } }
            .filter { it.startsWith("L") }.toSet()

        val launchers = FixtureDex.classesLoading(apk, REELS_LAUNCHER_STRING)
        val launcherNamed = launchers.flatMap { it.methods }
            .flatMap { m -> m.code().flatMap { it.typesNamed() } + m.parameterTypes.map(Any::toString) }
            .filter { it.startsWith("L") }.toSet()
        val enums = FixtureDex.classes(apk, launcherNamed).values.filter { classDef ->
            classDef.superclass == "Ljava/lang/Enum;" && classDef.methods.any { it.name == "<clinit>" }
        }
        val entryPoints = enums.filter { classDef -> classDef.methods.any { m -> m.code().any { it.referenceText() == VIDEO_TAP } } }
            .ifEmpty { enums }
        val entryNamed = entryPoints.flatMap { it.methods }.flatMap { m -> m.code().flatMap { it.typesNamed() } }
            .filter { it.startsWith("L") }.toSet()
        val launcher = launchers.filter { classDef ->
            classDef.methods.any { m ->
                val p = m.parameterTypes.map(Any::toString)
                p.size == 9 && p[0] == "Landroid/view/View;" && entryPoints.any { it.type == p[1] }
            }
        }
        assertEquals("$name: classes loading the Reels launcher's string with its shape", 1, launcher.size)
        val entryPoint = launcher.single().methods.first { it.parameterTypes.size == 9 }.parameterTypes[1].toString()
        return Parts(handlers.single().type, controllers.single().type, players.single().type, launcher.single().type, entryPoint,
            named + launcherNamed + entryNamed + launchers.map { it.type } + handlers.single().type + controllers.single().type +
                players.single().type + media)
    }

    private fun Instruction.typesNamed(): List<String> = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is MethodReference -> listOf(reference.definingClass)
        is FieldReference -> listOf(reference.definingClass, reference.type)
        is TypeReference -> listOf(reference.type)
        else -> emptyList()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
