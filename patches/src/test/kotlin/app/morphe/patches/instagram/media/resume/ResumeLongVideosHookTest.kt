/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.resume

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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

class ResumeLongVideosHookTest {
    private val player = "Lfixture/VideoPlayer;"
    private val holder = "Lfixture/PlayerVideo;"
    private val source = "Lfixture/VideoSource;"
    private val dumper = "Lfixture/SourceLogger;"
    private val string = "Ljava/lang/String;"
    private val hooks = setOf(STARTED, STOPPED, REBOUND, ENDED, SEEKING, SESSION_ENDED)

    /** Every hook the patch writes is in the extension the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(RESUME_PLAYBACK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf(
            "position(Ljava/lang/Object;)I", "duration(Ljava/lang/Object;)I",
            "videoSource(Ljava/lang/Object;)Ljava/lang/Object;", "videoId(Ljava/lang/Object;)Ljava/lang/String;",
            "productType(Ljava/lang/Object;)Ljava/lang/Object;", "sponsored(Ljava/lang/Object;)Z",
            "seekPlayer(Ljava/lang/Object;IZZ)Z", "playerSession(Ljava/lang/Object;)Ljava/lang/Object;",
            "sessionUserId(Ljava/lang/Object;)Ljava/lang/String;", "sessionLoggedOut(Ljava/lang/Object;)Z",
        )
        for (member in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$member is not in the extension: $declared", member in declared)
        }
    }

    /**
     * Each hook goes first in its method with the player, and the seek's position, in the range
     * form; the pause the seek calls is hooked and the other (String) method isn't; each stub reads
     * what it's named for.
     */
    @Test
    fun eachHookGoesFirstAndEachStubIsFilled() {
        val context = PatchContexts.of(classes())

        context.resumeLongVideos()

        for ((name, hook) in listOf("GMo" to STARTED, "A0b" to REBOUND, "F9i" to ENDED, "FcE" to ENDED)) {
            val first = context.method(player, name).code()[0]
            assertEquals(name, hook, first.referenceText())
            assertEquals("$name: the player alone", 1, (first as RegisterRangeInstruction).registerCount)
        }
        for (name in listOf("A0c", "A0g")) {
            val first = context.method(player, name).code()[0]
            assertEquals(name, STOPPED, first.referenceText())
            assertEquals("$name: the player and the reason", 2, (first as RegisterRangeInstruction).registerCount)
        }
        val seek = context.method(player, "A0X").code()[0] as RegisterRangeInstruction
        assertEquals(SEEKING, (seek as Instruction).referenceText())
        assertEquals("the player and the position", 2, seek.registerCount)
        assertTrue("the seek's other (String) call", context.method(player, "A0K").code().none { it.referenceText() in hooks })
        assertTrue("a start with another log line", context.method(player, "GMp").code().none { it.referenceText() in hooks })

        // What the filled stub does before its first return; its own body stays behind it, unreached.
        fun stub(name: String) = context.method(RESUME_PLAYBACK, name).code()
            .let { code -> code.take(code.indexOfFirst { it.opcode.name.startsWith("return") } + 1) }
            .mapNotNull { it.referenceText() }
        assertEquals(listOf(player, "$player->BrK()I"), stub("position"))
        assertEquals(listOf(player, "$player->A0N()I"), stub("duration"))
        assertEquals(listOf(player, "$player->A0K:$holder", "$holder->A0A:$source"), stub("videoSource"))
        assertEquals(listOf(source, "$source->A0J:$string"), stub("videoId"))
        assertEquals(listOf(source, "$source->A0A:$PRODUCT_TYPE"), stub("productType"))
        assertEquals(listOf(source, "$source->A0e:Z"), stub("sponsored"))
        assertEquals(listOf(player, "$player->A0X(IZZ)V"), stub("seekPlayer"))
        assertEquals(listOf(player, "$player->A0t:$RESUME_SESSION"), stub("playerSession"))
        assertEquals(listOf(RESUME_SESSION, "$RESUME_SESSION->userId:$string"), stub("sessionUserId"))
        assertEquals(listOf(RESUME_SESSION, "$RESUME_SESSION->isLoggedOut:Z"), stub("sessionLoggedOut"))

        // The session's end tells the extension first, with the session alone.
        val ending = context.method(RESUME_SESSION, RESUME_END_SESSION).code()
        assertEquals(SESSION_ENDED, ending[0].referenceText())
        assertEquals("the session alone", 1, (ending[0] as RegisterRangeInstruction).registerCount)
        assertEquals("Instagram's own end follows", "$RESUME_SESSION->sessionState:$string", ending[1].referenceText())
        assertTrue("the other session method", context.method(RESUME_SESSION, "endSessionAndBroadcast").code()
            .none { it.referenceText() in hooks })
    }

    @Test
    fun twoFieldsReadBeforeALabelFailBeforeAnythingChanges() {
        assertFailsUntouched(classes(sponsoredReadTwice = true), "before \"$IS_SPONSORED\"")
    }

    @Test
    fun aFieldTheExtensionCantReadFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(privateSponsored = true), "isn't a public instance field")
    }

    @Test
    fun aSecondWayToTheVideoFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(secondPath = true), "one way from $player")
    }

    @Test
    fun aPlayerWithTwoSessionsFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(secondSession = true), "one $RESUME_SESSION field")
    }

    @Test
    fun aSessionWithoutAUserIdFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(userId = false), "has no userId")
    }

    @Test
    fun aSessionWithoutItsEndFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(sessionEnd = false), "has no completeEndSession()V")
    }

    @Test
    fun aSessionWithoutItsSignOutFlagFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(loggedOut = false), "has no isLoggedOut:Z")
    }

    @Test
    fun aSeekThatPausesNothingFailsBeforeAnythingChanges() {
        assertFailsUntouched(classes(seekPauses = false), "pause the seek calls")
    }

    /**
     * In each declared build, IgVideoPlayerImpl's hooked methods, its readers and seek, and
     * IgVideoSource's fields are each found once, and each hook goes in first.
     */
    @Test
    fun eachDeclaredBuildHooksItsPlayer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val players = FixtureDex.classesHolding(bundle, PLAY_INTERNAL)
                val dumps = FixtureDex.classesHolding(bundle, IS_SPONSORED)
                val sources = dumps.flatMap { it.methods }
                    .filter { method -> method.implementation?.instructions?.any { it.stringText() == MEDIA_ID } == true }
                    .mapNotNull { it.parameterTypes.firstOrNull()?.toString() }
                val fieldTypes = players.flatMap { it.fields }.map { it.type }
                val extra = FixtureDex.classes(bundle, (sources + fieldTypes).toSet()).values
                val context = PatchContexts.of(
                    (players + dumps + extra + ExtensionDex.classDef(RESUME_PLAYBACK)).distinctBy { it.type },
                )
                val found = context.findResumePlayer()
                assertEquals("${bundle.name}: the holder is the player's", found.player.type, found.holder.definingClass)
                assertEquals("${bundle.name}: the source is the holder's", found.holder.type, found.source.definingClass)
                assertEquals("${bundle.name}: the product type", PRODUCT_TYPE, found.productType.type)
                assertTrue("${bundle.name}: the position and length readers differ", found.position != found.length)
                assertEquals("${bundle.name}: the player's session", found.player.type, found.session.definingClass)
                assertEquals("${bundle.name}: the session's user ID", "$RESUME_SESSION->userId:$string", found.userId.toString())
                assertEquals("${bundle.name}: the session's end", RESUME_SESSION, found.sessionEnd.definingClass)
                assertEquals("${bundle.name}: the sign-out flag", "$RESUME_SESSION->isLoggedOut:Z", found.loggedOut.toString())

                context.resumeLongVideos()

                fun after(method: Method) = context.classDefBy(method.definingClass).methods.first {
                    it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
                }.code()
                for ((method, hook) in listOf(found.started to STARTED, found.pause to STOPPED, found.stop to STOPPED,
                    found.bind to REBOUND, found.seek to SEEKING, found.completed to ENDED, found.looping to ENDED)) {
                    assertEquals("${bundle.name}: ${method.name}", hook, after(method)[0].referenceText())
                }
                val ending = after(found.sessionEnd)
                assertEquals("${bundle.name}: the session's end", SESSION_ENDED, ending[0].referenceText())
                assertEquals("${bundle.name}: Instagram's own end follows", found.sessionEnd.implementation!!.instructions.count(),
                    ending.size - 1)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertFailsUntouched(classes: List<ClassDef>, message: String) {
        val context = PatchContexts.of(classes)
        val failure = assertThrows(PatchException::class.java) { context.resumeLongVideos() }
        assertTrue(failure.message!!, failure.message!!.contains(message))
        for (method in context.classDefBy(player).methods + context.classDefBy(RESUME_SESSION).methods) {
            assertTrue("${method.name} changed", method.code().none { it.referenceText() in hooks })
        }
        for (method in context.classDefBy(RESUME_PLAYBACK).methods) {
            assertTrue("the stub ${method.name} was filled", method.code().none { reference ->
                reference.referenceText()?.let { it.startsWith(player) || it.startsWith(source) || it.startsWith(RESUME_SESSION) } == true
            })
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.stringText(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(
        sponsoredReadTwice: Boolean = false,
        privateSponsored: Boolean = false,
        secondPath: Boolean = false,
        seekPauses: Boolean = true,
        secondSession: Boolean = false,
        userId: Boolean = true,
        sessionEnd: Boolean = true,
        loggedOut: Boolean = true,
    ): List<ClassDef> {
        val open = AccessFlags.PUBLIC.value
        val videoPlayer = classDef(
            player,
            listOfNotNull(
                method(player, "A0J", listOf(player, string, "Z", "Z"), "V", 5, static = true, body = """
                    const-string v0, "$PLAY_INTERNAL"
                    return-void
                """),
                method(player, "GMo", listOf("J", "J"), "V", 6, body = """
                    const-string v0, "$PLAYBACK_STARTED"
                    return-void
                """),
                method(player, "GMp", listOf("J", "J"), "V", 6, body = """
                    const-string v0, "Playback stalled "
                    return-void
                """),
                method(player, "F9i", listOf("Ljava/lang/Object;"), "V", 3, body = """
                    const-string v0, "$PLAYBACK_COMPLETED"
                    return-void
                """),
                method(player, "FcE", emptyList(), "V", 2, body = """
                    const-string v0, "$PLAYBACK_LOOPING"
                    return-void
                """),
                method(player, "A0b", listOf("Ljava/lang/Object;"), "V", 3, body = """
                    const-string v0, "$PREPARE_VIDEO"
                    return-void
                """),
                method(player, "A0X", listOf("I", "Z", "Z"), "V", 6, body = """
                    const-string v0, "clips_viewer"
                    const-string v0, "resume"
                    ${if (seekPauses) "invoke-virtual { p0, v0 }, $player->A0c(Ljava/lang/String;)V" else ""}
                    return-void
                """),
                method(player, "A0c", listOf(string), "V", 3, body = "return-void"),
                method(player, "A0K", listOf(string), "V", 3, body = "return-void"),
                method(player, "A0g", listOf(string, "Z"), "V", 4, body = """
                    const-string v0, "clips_pip"
                    const-string v0, "ig_text"
                    return-void
                """),
                method(player, "BrK", emptyList(), "I", 2, body = """
                    const v0, 0x5265c00
                    return v0
                """),
                method(player, "A0N", emptyList(), "I", 3, body = """
                    const-wide/16 v0, 0x0
                    long-to-int v0, v0
                    return v0
                """),
                idReader("DrU"),
                if (secondPath) idReader("DrV", field = "A0L") else null,
            ),
            listOfNotNull(
                ImmutableField(player, "A0K", holder, open, null, null, null),
                if (secondPath) ImmutableField(player, "A0L", holder, open, null, null, null) else null,
                ImmutableField(player, "A0t", RESUME_SESSION, open or AccessFlags.FINAL.value, null, null, null),
                if (secondSession) ImmutableField(player, "A0u", RESUME_SESSION, open, null, null, null) else null,
            ),
        )
        // Shaped like 450's: the end checks the state, tells each listener the sign-out flag, and
        // moves the state on; endSessionAndBroadcast starts the end and isn't hooked.
        val session = classDef(
            RESUME_SESSION,
            listOfNotNull(
                if (sessionEnd) {
                    method(RESUME_SESSION, RESUME_END_SESSION, emptyList(), "V", 4, body = """
                        iget-object v1, p0, $RESUME_SESSION->sessionState:$string
                        iget-boolean v0, p0, $RESUME_SESSION->isLoggedOut:Z
                        return-void
                    """)
                } else {
                    null
                },
                method(RESUME_SESSION, "endSessionAndBroadcast", listOf(string), "V", 3, body = """
                    iget-boolean v0, p0, $RESUME_SESSION->isLoggedOut:Z
                    return-void
                """),
            ),
            listOfNotNull(
                if (userId) ImmutableField(RESUME_SESSION, "userId", string, open or AccessFlags.FINAL.value, null, null, null) else null,
                ImmutableField(RESUME_SESSION, "token", string, open or AccessFlags.FINAL.value, null, null, null),
                if (loggedOut) ImmutableField(RESUME_SESSION, "isLoggedOut", "Z", open, null, null, null) else null,
                ImmutableField(RESUME_SESSION, "sessionState", string, open, null, null, null),
            ),
        )
        val playerVideo = classDef(holder, emptyList(), listOf(ImmutableField(holder, "A0A", source, open, null, null, null)))
        val videoSource = classDef(
            source,
            emptyList(),
            listOf(
                ImmutableField(source, "A0J", string, open, null, null, null),
                ImmutableField(source, "A0A", PRODUCT_TYPE, open, null, null, null),
                ImmutableField(source, "A0e", "Z", if (privateSponsored) AccessFlags.PRIVATE.value else open, null, null, null),
                ImmutableField(source, "A0f", "Z", open, null, null, null),
            ),
        )
        val sourceLogger = classDef(
            dumper,
            listOf(
                method(dumper, "Ggr", listOf(source, string), "V", 4, body = """
                    const-string v0, "unrelated"
                    iget-object v1, p1, $source->A0J:$string
                    const-string v0, "$MEDIA_ID"
                    iget-object v1, p1, $source->A0A:$PRODUCT_TYPE
                    const-string v0, "$PRODUCT_TYPE_LABEL"
                    const-string v0, "LOOPING"
                    ${if (sponsoredReadTwice) "iget-boolean v1, p1, $source->A0f:Z" else ""}
                    iget-boolean v1, p1, $source->A0e:Z
                    const-string v0, "$IS_SPONSORED"
                    return-void
                """),
            ),
        )
        return listOf(videoPlayer, playerVideo, videoSource, sourceLogger, session, ExtensionDex.classDef(RESUME_PLAYBACK))
    }

    /** A media ID reader: the player's field, its IgVideoSource, the ID, each checked for null. */
    private fun idReader(name: String, field: String = "A0K") = method(player, name, emptyList(), string, 2, body = """
        iget-object v0, p0, $player->$field:$holder
        if-eqz v0, :none
        iget-object v0, v0, $holder->A0A:$source
        if-eqz v0, :none
        iget-object v0, v0, $source->A0J:$string
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)
}
