/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.viewers

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep story viewers (#710): the success method of the request for a story's viewers hands its
 * callback and the answer to the extension before anything else, and the extension's bridges read
 * the viewers, the total, the story and each viewer's account. Anything the patch can't tell apart
 * fails it before an instruction changes.
 */
class KeepStoryViewersHookTest {
    @Test
    fun theHookAndBridgesAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_VIEWERS).methods
        fun one(name: String, vararg parameters: String) = methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
        }.also { assertTrue(name, AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)) }
        assertEquals("V", one("listed", OBJECT, OBJECT).returnType)
        assertEquals("$STORY_VIEWERS->listed(Ljava/lang/Object;Ljava/lang/Object;)V", STORY_VIEWERS_LISTED)
        val returns = mapOf(
            "listViewers" to "Ljava/util/List;", "listTotal" to "I", "listMedia" to OBJECT, "viewerUser" to OBJECT,
            "listStory" to TEXT, "userId" to TEXT, "username" to TEXT, "mediaId" to TEXT, "takenAt" to "Ljava/lang/Long;",
        )
        for ((name, type) in returns) assertEquals(name, type, one(name, OBJECT).returnType)
    }

    @Test
    fun theStandInCallbackHandsOverItsAnswer() {
        val context = PatchContexts.of(standIns())
        val site = context.findStoryViewers()
        assertEquals(CALLBACK, site.callback)
        assertEquals("A0h", site.success.name)
        assertEquals("$CALLBACK->A02:$TEXT", site.story)
        assertEquals(ANSWER, site.answer)
        assertEquals("$ANSWER->A0I:Ljava/util/List;", site.viewers)
        assertEquals("$ANSWER->A03:I", site.total)
        assertEquals("$ANSWER->A04:$MEDIA", site.media)
        assertEquals(VIEWER, site.viewer)
        assertEquals("$VIEWER->A03:$USER", site.account)
        assertHooked(context, "stand-in", site)
    }

    @Test
    fun anythingAmbiguousFailsThePatch() {
        refuses("logging $VIEWERS_FETCHED, found none", standIns(callbacks = 0))
        refuses("logging $VIEWERS_FETCHED, found $CALLBACK->A0h, ${CALLBACK.dropLast(1)}2;->A0h", standIns(callbacks = 2))
        refuses("to read one list of $ANSWER before its count", standIns(lists = 2))
        refuses("its parser writes for $VIEWERS_TOTAL, found []", standIns(totals = false))
        refuses("expected one viewer with an account", standIns(viewers = 0))
        refuses("the answer $ANSWER isn't public", standIns(publicAnswer = false))
        refuses("expected one $MEDIA field on $ANSWER, found 0", standIns(media = false))
        refuses("$ANSWER->A0I:Ljava/util/List; isn't public", standIns(publicViewers = false))
        refuses("jumps back to its start", standIns(loops = true))
    }

    /** In each declared build: the one callback, handing over first, and every bridge written. */
    @Test
    fun eachDeclaredBuildHandsOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildHandsOverToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val sliced = listOf(VIEWER_LIST_REQUEST, VIEWERS_FETCHED, VIEWERS_TOTAL, STORY_VIEWER_MODEL)
            .flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        val have = sliced.mapTo(HashSet()) { it.type }
        val named = sliced.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .filter { it.opcode == Opcode.CHECK_CAST || it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            .filter { it.startsWith("LX/") && it !in have }.toSet()
        val classes = FixtureDex.withStringPools(bundle, sliced) + FixtureDex.classes(bundle, named + setOf(USER, MEDIA)).values +
            ExtensionDex.classDef(STORY_VIEWERS)
        val context = PatchContexts.of(classes.distinctBy { it.type })
        val site = context.findStoryViewers()
        assertTrue("$label: the viewers are a list", site.viewers.endsWith(":Ljava/util/List;"))
        assertTrue("$label: the total is an int", site.total.endsWith(":I"))
        assertTrue("$label: the story id is text", site.story.endsWith(":$TEXT"))
        assertHooked(context, label, site)
    }

    /**
     * The success method's first instruction hands its own two parameter registers to
     * [STORY_VIEWERS_LISTED], the rest following unchanged, and each bridge casts to the class it
     * reads and reads the field or asks the getter.
     */
    private fun assertHooked(context: BytecodePatchContext, what: String, site: StoryViewersSite) {
        val stock = site.success.code().kept()
        val registers = site.success.implementation!!.registerCount
        context.keepStoryViewers(site)
        val success = context.mutableClassDefBy(site.callback).methods.single {
            it.name == site.success.name && it.parameterTypes.map(Any::toString) == listOf(OBJECT) && it.returnType == "V"
        }
        val code = success.code()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the call", STORY_VIEWERS_LISTED, (code[0] as ReferenceInstruction).reference.toString())
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the call takes the callback and the answer", listOf(registers - 2, 2), listOf(call.startRegister, call.registerCount))
        assertEquals("$what: the rest stays", stock, code.drop(1).kept())

        val stubs = context.mutableClassDefBy(STORY_VIEWERS).methods
        fun bridge(name: String, cast: String, read: Opcode, reference: String) {
            val body = stubs.single { it.name == name }.code()
            assertEquals("$what: $name casts", Opcode.CHECK_CAST, body[0].opcode)
            assertEquals("$what: $name casts", cast, (body[0] as ReferenceInstruction).reference.toString())
            assertEquals("$what: $name reads", read, body[1].opcode)
            val actual = (body[1] as ReferenceInstruction).reference.toString()
            assertTrue("$what: $name reads $reference, not $actual", actual == reference || (reference.endsWith(")") && actual.startsWith(reference.dropLast(1))))
        }
        bridge("listViewers", site.answer, Opcode.IGET_OBJECT, site.viewers)
        bridge("listTotal", site.answer, Opcode.IGET, site.total)
        bridge("listMedia", site.answer, Opcode.IGET_OBJECT, site.media)
        bridge("viewerUser", site.viewer, Opcode.IGET_OBJECT, site.account)
        bridge("listStory", site.callback, Opcode.IGET_OBJECT, site.story)
        bridge("mediaId", MEDIA, Opcode.INVOKE_VIRTUAL, "$MEDIA->getId()$TEXT")
        for (name in listOf("userId", "username", "takenAt")) {
            val body = stubs.single { it.name == name }.code()
            val cast = if (name == "takenAt") MEDIA else USER
            assertEquals("$what: $name casts to $cast", cast, (body[0] as ReferenceInstruction).reference.toString())
            assertEquals("$what: $name asks a getter", Opcode.INVOKE_VIRTUAL, body[1].opcode)
            val getter = (body[1] as ReferenceInstruction).reference.toString()
            assertTrue("$what: $name asks $cast, not $getter", getter.startsWith("$cast->") && getter.contains("()"))
            assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), body.subList(2, 4).map { it.opcode })
        }
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.keepStoryViewers(context.findStoryViewers()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name // opcode-name-ok
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference" // opcode-name-ok
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    /** The body as text without NOPs, any goto read as goto, since an insert can widen one. */
    private fun List<Instruction>.kept(): List<String> = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) "goto" else text(it)
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val TEXT = "Ljava/lang/String;"
        const val LIST = "Ljava/util/List;"
        const val DASHBOARD = "Lfixture/ReelDashboard;"
        const val CALLBACK = "Lfixture/ViewersCallback;"
        const val ANSWER = "Lfixture/ViewersAnswer;"
        const val PARSER = "Lfixture/ViewersParser;"
        const val VIEWER_PARSER = "Lfixture/ViewerParser;"
        const val VIEWER = "Lfixture/Viewer;"
        val PUBLIC = AccessFlags.PUBLIC.value
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun method(owner: String, name: String, returns: String, registers: Int, code: List<ImmutableInstruction>,
                   parameters: List<String> = emptyList(), flags: Int = PUBLIC) =
            ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null))

        fun field(owner: String, name: String, type: String, flags: Int = PUBLIC_FINAL) = ImmutableField(owner, name, type, flags, null, null, null)

        fun type(opcode: Opcode, register: Int, type: String) = ImmutableInstruction21c(opcode, register, ImmutableTypeReference(type))

        fun string(register: Int, value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

        fun read(opcode: Opcode, into: Int, from: Int, owner: String, name: String, type: String) =
            ImmutableInstruction22c(opcode, into, from, ImmutableFieldReference(owner, name, type))

        fun virtual(owner: String, name: String, returns: String, register: Int) =
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, register, 0, 0, 0, 0, ImmutableMethodReference(owner, name, emptyList(), returns))

        val returnNull: List<ImmutableInstruction> = listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))

        /**
         * Shaped like 454's: the dashboard building the request and creating [callbacks] callbacks
         * whose success method casts the answer, reads [lists] lists of it and logs the count and
         * total; the answer's parser writing the total when [totals]; the viewer's parser creating
         * [viewers] viewers with an account; and the account and story models with their getters.
         */
        fun standIns(
            callbacks: Int = 1,
            lists: Int = 1,
            totals: Boolean = true,
            viewers: Int = 1,
            publicAnswer: Boolean = true,
            media: Boolean = true,
            publicViewers: Boolean = true,
            loops: Boolean = false,
        ): List<ClassDef> {
            val callbackTypes = List(callbacks) { n -> if (n == 0) CALLBACK else "${CALLBACK.dropLast(1)}${n + 1};" }
            val dashboard = ImmutableClassDef(DASHBOARD, PUBLIC, OBJECT, null, null, null, null, listOf(
                method(DASHBOARD, "A0G", "V", 2, listOf(string(0, VIEWER_LIST_REQUEST)) +
                    callbackTypes.map { type(Opcode.NEW_INSTANCE, 1, it) } + ImmutableInstruction10x(Opcode.RETURN_VOID),
                    flags = PUBLIC or AccessFlags.STATIC.value),
            ))
            val success = mutableListOf<ImmutableInstruction>(type(Opcode.CHECK_CAST, 3, ANSWER))
            success += read(Opcode.IGET_OBJECT, 0, 3, ANSWER, "A0I", LIST)
            if (lists > 1) success += read(Opcode.IGET_OBJECT, 0, 3, ANSWER, "A0H", LIST)
            success += string(1, VIEWERS_FETCHED)
            success += string(1, VIEWERS_TOTAL)
            if (loops) success += ImmutableInstruction21t(Opcode.IF_EQZ, 0, -(success.sumOf { it.codeUnits }))
            success += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val callbackClasses = callbackTypes.map { type ->
                ImmutableClassDef(type, PUBLIC_FINAL, OBJECT, null, null, null,
                    listOf(field(type, "A01", OBJECT), field(type, "A02", TEXT)),
                    listOf(method(type, "A0h", "V", 4, success, listOf(OBJECT), PUBLIC_FINAL)))
            }
            val answerFields = listOf(
                field(ANSWER, "A0I", LIST, if (publicViewers) PUBLIC_FINAL else AccessFlags.PRIVATE.value),
                field(ANSWER, "A0H", LIST), field(ANSWER, "A03", "I"), field(ANSWER, "A01", "I"),
            ) + if (media) listOf(field(ANSWER, "A04", MEDIA)) else emptyList()
            val answer = ImmutableClassDef(ANSWER, if (publicAnswer) PUBLIC_FINAL else AccessFlags.FINAL.value, OBJECT,
                null, null, null, answerFields, null)
            val parse = mutableListOf<ImmutableInstruction>(string(0, VIEWERS_TOTAL))
            if (totals) parse += read(Opcode.IPUT, 1, 2, ANSWER, "A03", "I")
            parse += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val parser = ImmutableClassDef(PARSER, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(
                method(PARSER, "unsafeParseFromJson", "V", 3, parse, flags = PUBLIC or AccessFlags.STATIC.value),
            ))
            val viewerTypes = List(viewers) { n -> if (n == 0) VIEWER else "${VIEWER.dropLast(1)}${n + 1};" }
            val viewerParser = ImmutableClassDef(VIEWER_PARSER, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(
                method(VIEWER_PARSER, "A00", "V", 2, listOf(string(0, STORY_VIEWER_MODEL)) +
                    viewerTypes.map { type(Opcode.NEW_INSTANCE, 1, it) } + ImmutableInstruction10x(Opcode.RETURN_VOID)),
            ))
            val viewerClasses = viewerTypes.map { type ->
                ImmutableClassDef(type, PUBLIC_FINAL, OBJECT, null, null, null, listOf(field(type, "A03", USER), field(type, "A04", OBJECT)), null)
            }
            return listOf(dashboard, answer, parser, viewerParser, user(), media(), ExtensionDex.classDef(STORY_VIEWERS)) +
                callbackClasses + viewerClasses
        }

        /** The account: hashed by its id getter, and a username getter holding its key's hash. */
        fun user(): ClassDef = ImmutableClassDef(USER, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(
            method(USER, "hashCode", "I", 2, listOf(
                virtual(USER, "A0X", TEXT, 1),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                virtual(TEXT, "hashCode", "I", 0),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            )),
            method(USER, "A0X", TEXT, 2, returnNull),
            method(USER, "A0Y", TEXT, 2, listOf(ImmutableInstruction31i(Opcode.CONST, 0, "username".hashCode())) + returnNull),
        ))

        /** The story: its kept id getter and a posted-time getter holding its key's hash. */
        fun media(): ClassDef = ImmutableClassDef(MEDIA, PUBLIC_FINAL, OBJECT, null, null, null, null, listOf(
            method(MEDIA, "getId", TEXT, 2, returnNull),
            method(MEDIA, "A0Z", "Ljava/lang/Long;", 2, listOf(ImmutableInstruction31i(Opcode.CONST, 0, "taken_at".hashCode())) + returnNull),
        ))
    }
}
