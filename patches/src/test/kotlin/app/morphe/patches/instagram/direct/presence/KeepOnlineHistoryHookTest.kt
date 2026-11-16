/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep online history (#590): the presence store's merge hands its session and the map of updates
 * to the extension before anything else, and the extension's two bridges read an update's own
 * description and an account from Instagram's cache. Anything the patch can't tell apart fails it
 * before an instruction changes.
 */
class KeepOnlineHistoryHookTest {
    @Test
    fun theHookAndBridgesAreInTheExtension() {
        val methods = ExtensionDex.classDef(ONLINE_HISTORY).methods
        fun one(name: String, vararg parameters: String) = methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
        }.also { assertTrue(name, AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)) }
        assertEquals("V", one("received", "Ljava/lang/Object;", "Ljava/util/Map;").returnType)
        assertEquals("Ljava/lang/String;", one("presence", "Ljava/lang/Object;").returnType)
        assertEquals("Ljava/lang/Object;", one("user", "Ljava/lang/Object;", "J").returnType)
        assertEquals("$ONLINE_HISTORY->received(Ljava/lang/Object;Ljava/util/Map;)V", ONLINE_HISTORY_RECEIVED)
    }

    @Test
    fun theStandInMergeAsksFirst() {
        val context = PatchContexts.of(standIns())
        val site = context.findOnlineHistory()
        assertEquals(STORE, site.store)
        assertEquals("A05", site.merge.name)
        assertEquals("$STORE->A10:$ONLINE_SESSION", site.session)
        assertEquals(MODEL, site.model)
        assertEquals("A00", site.describe.name)
        assertHooked(context, "stand-in", site)
    }

    @Test
    fun anythingAmbiguousFailsThePatch() {
        refuses("presence model describing itself, found none", standIns(describes = false))
        refuses("presence merge casting to $MODEL, found none", standIns(merges = 0))
        refuses("presence merge casting to $MODEL, found $STORE->A0", standIns(merges = 2))
        refuses("session field on $STORE, found 0", standIns(sessions = 0))
        refuses("session field on $STORE, found 2", standIns(sessions = 2))
        refuses("jumps back to its start", standIns(loops = true))
        refuses("fewer than two locals", standIns(locals = 1))
        refuses("for a session", standIns(caches = 0))
        refuses("account by id", standIns(byIds = 2))
    }

    /** In each declared build: the one merge, asking the extension first, and both bridges written. */
    @Test
    fun eachDeclaredBuildAsks() {
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
    fun eachOtherBuildAsksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val sliced = (FixtureDex.classesLoading(bundle, PRESENCE_MODEL_KEYS.last()) + FixtureDex.classesLoading(bundle, PRESENCE_POLL))
            .distinctBy { it.type }
        val classes = FixtureDex.withStringPools(bundle, sliced) + FixtureDex.classes(bundle, setOf(USER_CACHE)).values +
            ExtensionDex.classDef(ONLINE_HISTORY)
        val context = PatchContexts.of(classes.distinctBy { it.type })
        val site = context.findOnlineHistory()
        assertEquals("$label: the merge takes the store and a map", listOf(site.store, "Ljava/util/Map;"),
            site.merge.parameterTypes.map(Any::toString).take(2))
        assertHooked(context, label, site)
    }

    /**
     * The merge hands the store's session and its map to [ONLINE_HISTORY_RECEIVED] in two locals
     * before its own first instruction, which follows unchanged; the presence bridge describes the
     * model and the user bridge asks the session's cache for the id.
     */
    private fun assertHooked(context: BytecodePatchContext, what: String, site: OnlineHistorySite) {
        val stock = site.merge.code().kept()
        val registers = site.merge.implementation!!.registerCount
        context.keepOnlineHistory(site)
        val merge = context.mutableClassDefBy(site.store).methods.single {
            it.name == site.merge.name && it.parameterTypes.map(Any::toString) == site.merge.parameterTypes.map(Any::toString)
        }
        val code = merge.code()
        val firstParameter = registers - site.merge.parameterTypes.size
        assertEquals("$what: the store into v0", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: the store into v0", listOf(0, firstParameter), code[0].twoRegisters())
        assertEquals("$what: its session", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals("$what: its session", site.session, (code[1] as ReferenceInstruction).reference.toString())
        assertEquals("$what: its session", listOf(0, 0), code[1].twoRegisters())
        assertEquals("$what: the map into v1", Opcode.MOVE_OBJECT_FROM16, code[2].opcode)
        assertEquals("$what: the map into v1", listOf(1, firstParameter + 1), code[2].twoRegisters())
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[3].opcode)
        assertEquals("$what: the call", ONLINE_HISTORY_RECEIVED, (code[3] as ReferenceInstruction).reference.toString())
        val call = code[3] as FiveRegisterInstruction
        assertEquals("$what: the call takes both", listOf(0, 1), listOf(call.registerC, call.registerD).take(call.registerCount))
        assertEquals("$what: the rest stays", stock, code.drop(4).kept())

        val stubs = context.mutableClassDefBy(ONLINE_HISTORY).methods
        val presence = stubs.single { it.name == "presence" }.code()
        assertEquals("$what: presence casts to the model", Opcode.CHECK_CAST, presence[0].opcode)
        assertEquals("$what: presence casts to the model", site.model, (presence[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: and describes it", Opcode.INVOKE_VIRTUAL, presence[1].opcode)
        assertEquals("$what: and describes it", "${site.model}->${site.describe.name}()Ljava/lang/String;",
            (presence[1] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), presence.subList(2, 4).map { it.opcode })
        val user = stubs.single { it.name == "user" }.code()
        assertEquals("$what: user casts to the session", Opcode.CHECK_CAST, user[0].opcode)
        assertEquals("$what: user casts to the session", ONLINE_SESSION, (user[0] as ReferenceInstruction).reference.toString())
        val cache = (user[1] as ReferenceInstruction).reference.toString()
        assertEquals("$what: asks for the session's cache", Opcode.INVOKE_STATIC, user[1].opcode)
        assertTrue("$what: asks for the session's cache, not $cache",
            cache.startsWith("$USER_CACHE->") && cache.endsWith("($ONLINE_SESSION)$USER_CACHE"))
        val account = (user[3] as ReferenceInstruction).reference.toString()
        assertEquals("$what: then the account by id", Opcode.INVOKE_VIRTUAL, user[3].opcode)
        assertTrue("$what: then the account by id, not $account", account.startsWith("$USER_CACHE->") && account.endsWith("(J)$USER"))
        assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), user.subList(4, 6).map { it.opcode })
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.keepOnlineHistory(context.findOnlineHistory()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun Instruction.twoRegisters(): List<Int> = (this as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }

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
        const val STORE = "Lfixture/PresenceStore;"
        const val MODEL = "Lfixture/Presence;"

        /**
         * Shaped like 454's: the model describing itself with the keys, the store polling with the
         * request, holding [sessions] session fields and [merges] static merges of (store, Map, Z, Z)
         * each casting to the model, and Instagram's cache of accounts with [caches] ways to get it
         * for a session and [byIds] ways to get an account by id.
         */
        fun standIns(
            describes: Boolean = true,
            merges: Int = 1,
            sessions: Int = 1,
            loops: Boolean = false,
            locals: Int = 4,
            caches: Int = 1,
            byIds: Int = 1,
        ): List<ClassDef> = listOf(model(describes), store(merges, sessions, loops, locals), cache(caches, byIds),
            ExtensionDex.classDef(ONLINE_HISTORY))

        fun model(describes: Boolean): ClassDef {
            val keys = if (describes) PRESENCE_MODEL_KEYS else PRESENCE_MODEL_KEYS.drop(1)
            val code = keys.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            val describe = ImmutableMethod(MODEL, "A00", emptyList(), "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, code, null, null))
            return ImmutableClassDef(MODEL, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(describe))
        }

        fun store(merges: Int, sessions: Int, loops: Boolean, locals: Int): ClassDef {
            val poll = ImmutableMethod(STORE, "A06", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(PRESENCE_POLL)),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null))
            val parameters = listOf(STORE, "Ljava/util/Map;", "Z", "Z").map { ImmutableMethodParameter(it, null, null) }
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(MODEL)),
            )
            if (loops) code += ImmutableInstruction21t(Opcode.IF_EQZ, locals + 2, -3)
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val merge = List(merges) { n ->
                ImmutableMethod(STORE, "A0${5 + 2 * n}", parameters, "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(locals + 4, code, null, null))
            }
            val fields = List(sessions) { n ->
                ImmutableField(STORE, "A1$n", ONLINE_SESSION, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, null)
            }
            return ImmutableClassDef(STORE, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, merge + poll)
        }

        fun cache(caches: Int, byIds: Int): ClassDef {
            fun returnsNull(name: String, parameters: List<String>, returns: String, static: Boolean) = ImmutableMethod(
                USER_CACHE, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(4, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null),
            )
            val methods = List(caches) { returnsNull("A0$it", listOf(ONLINE_SESSION), USER_CACHE, true) } +
                List(byIds) { returnsNull("A1$it", listOf("J"), USER, false) }
            return ImmutableClassDef(USER_CACHE, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
        }
    }
}
