/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden accounts' account hook: the session goes to HiddenAccounts.homeSession first thing in the
 * constructor of Home's cache source, and HiddenAccounts.accountOf reads the session's getUserId().
 * A build where either can't be found keeps one list for every account and changes nothing here.
 */
class HomeAccountHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val string = "Ljava/lang/String;"
    private val accountOf = "$HIDDEN_ACCOUNTS->accountOf(Ljava/lang/Object;)$string"

    /**
     * The cache source's constructor as 450 has it: 22 registers, the session last, in p2. It calls
     * its parent and copies the session, both past the registers a plain instruction can name.
     */
    private fun constructor(parameters: List<String> = listOf("Z", session), registers: Int = 22): Method {
        val self = registers - 1 - parameters.size
        return ImmutableMethod(
            MAIN_FEED_CACHE_SOURCE, "<init>", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(
                registers,
                listOf(
                    ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, self, 1,
                        ImmutableMethodReference("Ljava/lang/Object;", "<init>", emptyList(), "V")),
                    ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, registers - 1),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null, null,
            ),
        )
    }

    private fun source(vararg constructors: Method) =
        ImmutableClassDef(MAIN_FEED_CACHE_SOURCE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, constructors.toList())

    /** The session's class with its getUserId(), public unless [flags] says otherwise. */
    private fun session(classFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, getterFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, getter: Boolean = true) =
        ImmutableClassDef(
            session, classFlags, "Ljava/lang/Object;", null, null, null, null,
            if (!getter) emptyList() else listOf(ImmutableMethod(
                session, "getUserId", emptyList(), string, getterFlags, null, null,
                ImmutableMethodImplementation(2, listOf(
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, ImmutableFieldReference(session, "userId", string)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ), null, null),
            )),
        )

    private fun classes(source: ClassDef? = source(constructor()), session: ClassDef = session()): List<ClassDef> =
        listOfNotNull(source, session, ExtensionDex.classDef(HIDDEN_ACCOUNTS))

    private fun MethodReference.text() = toString()

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_ACCOUNTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HIDDEN_ACCOUNTS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HOME_SESSION, accountOf)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    /**
     * The session goes over first thing, through a range invoke naming the constructor's last
     * register, which is the session's, and the rest of the constructor is as it was.
     */
    @Test
    fun theSessionGoesOverFirstThingInTheConstructor() {
        val context = PatchContexts.of(classes())

        requireNotNull(context.homeAccountOrWarn()).write()

        val code = context.mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods.single().instructions()
        assertEquals(4, code.size)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals(HOME_SESSION, ((code[0] as ReferenceInstruction).reference as MethodReference).text())
        val range = code[0] as RegisterRangeInstruction
        assertEquals("the session's register, p2", 21, range.startRegister)
        assertEquals(1, range.registerCount)
        assertEquals(listOf(Opcode.INVOKE_DIRECT_RANGE, Opcode.MOVE_OBJECT_FROM16, Opcode.RETURN_VOID), code.drop(1).map { it.opcode })
        assertEquals("the constructor still reads the session where it was", 21, (code[2] as TwoRegisterInstruction).registerB)
    }

    /** The stub casts its argument to the session and answers getUserId(), using p0 alone. */
    @Test
    fun theStubAnswersTheSessionsUserId() {
        val context = PatchContexts.of(classes())

        requireNotNull(context.homeAccountOrWarn()).write()

        val code = context.mutableClassDefBy(HIDDEN_ACCOUNTS).methods.single { it.name == "accountOf" }.instructions()
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            code.take(4).map { it.opcode },
        )
        assertEquals(session, ((code[0] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$session->getUserId()$string", ((code[1] as ReferenceInstruction).reference as MethodReference).text())
        val p0 = (code[0] as OneRegisterInstruction).registerA
        assertTrue("the body uses p0 alone", code.take(4).filterIsInstance<OneRegisterInstruction>().all { it.registerA == p0 })
    }

    /** A source, constructor or getter that can't be found or reached leaves every class as it was. */
    @Test
    fun whatCantBeFoundLeavesEverythingUnchanged() {
        val private = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        for ((case, built) in listOf(
            "no cache source" to classes(source = null),
            "a constructor of another shape" to classes(source(constructor(listOf(session), 21))),
            "no constructor" to classes(source(constructor(listOf("Z", session), 22).let {
                ImmutableMethod(it.definingClass, "A00", it.parameters, it.returnType, AccessFlags.PUBLIC.value, null, null, it.implementation)
            })),
            "a session that isn't public" to classes(session = session(classFlags = AccessFlags.FINAL.value)),
            "a private getUserId" to classes(session = session(getterFlags = private)),
            "no getUserId" to classes(session = session(getter = false)),
        )) {
            val context = PatchContexts.of(built)
            val before = built.associate { it.type to it.methods.sumOf { m -> m.instructions().size } }

            assertNull(case, context.homeAccountOrWarn())

            val after = built.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.instructions().size } }
            assertEquals(case, before, after)
        }
    }

    /**
     * On every build of the declared version, the cache source has its one (boolean, UserSession)
     * constructor with the session in its last register, UserSession has its public getUserId(), the
     * hook goes in first, and the source is the one class holding the contract rule's two strings.
     */
    @Test
    fun everyBuildHandsTheSessionOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        var checked = 0
        for (bundle in bundles + others) {
            val where = "${bundle.parentFile.name}/${bundle.name}"
            val found = FixtureDex.classes(bundle, setOf(MAIN_FEED_CACHE_SOURCE, session))
            assertEquals(where, setOf(MAIN_FEED_CACHE_SOURCE, session), found.keys)
            val context = PatchContexts.of(found.values + ExtensionDex.classDef(HIDDEN_ACCOUNTS))

            val account = context.homeAccountOrWarn()
            assertNotNull("$where: the account hook wasn't found", account)
            account!!.write()

            val constructor = context.mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods.single { it.name == "<init>" }
            val code = constructor.instructions()
            assertEquals("$where: the hook first", HOME_SESSION, ((code[0] as ReferenceInstruction).reference as MethodReference).text())
            val registers = constructor.implementation!!.registerCount
            assertEquals("$where: the session is the last of p0, p1 and p2", registers - 1, (code[0] as RegisterRangeInstruction).startRegister)
            assertEquals("$where: the hook once", 1, code.count {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.text() == HOME_SESSION
            })

            val holding = FixtureDex.classesHolding(bundle, "MainFeedCacheDataSource.start").map { it.type }.toSet() intersect
                FixtureDex.classesHolding(bundle, "feed_schedule_initial_cache_load").map { it.type }.toSet()
            assertEquals("$where: the class the contract rule names", setOf(MAIN_FEED_CACHE_SOURCE), holding)
            checked++
        }
        assertEquals("every build was checked", bundles.size + others.size, checked)
    }
}
