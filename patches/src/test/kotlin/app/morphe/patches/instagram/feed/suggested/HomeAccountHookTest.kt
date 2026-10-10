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
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
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

    private val startTrace = "MainFeedCacheDataSource.start"
    private val startLoad = "feed_schedule_initial_cache_load"
    private val sessionField = "A01"

    /** The source's start as 450 has it: 9 registers, the source in p0 (v7), one object parameter, both strings. */
    private fun start(name: String = "A0C", strings: List<String> = listOf(startTrace, startLoad), registers: Int = 9, static: Boolean = false): Method =
        ImmutableMethod(
            MAIN_FEED_CACHE_SOURCE, name, listOf(ImmutableMethodParameter("LX/04nm;", null, null)), "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(
                registers,
                strings.mapIndexed { i, text ->
                    ImmutableInstruction21c(Opcode.CONST_STRING, i, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(text))
                } + ImmutableInstruction10x(Opcode.RETURN_VOID),
                null, null,
            ),
        )

    /** A constructor that, like 450's, stores the session in [field] right away. */
    private fun storingConstructor(field: String = sessionField): Method {
        val base = constructor()
        return ImmutableMethod(
            MAIN_FEED_CACHE_SOURCE, "<init>", base.parameters, "V", base.accessFlags, null, null,
            ImmutableMethodImplementation(
                22,
                listOf(
                    ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 1, ImmutableFieldReference(MAIN_FEED_CACHE_SOURCE, field, session)),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null, null,
            ),
        )
    }

    private fun sourceWith(methods: List<Method>, fields: List<String> = listOf(sessionField)) =
        ImmutableClassDef(
            MAIN_FEED_CACHE_SOURCE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { ImmutableField(MAIN_FEED_CACHE_SOURCE, it, session, AccessFlags.PRIVATE.value, null, null, null) },
            methods,
        )

    /**
     * Instagram makes one source for each session and keeps it, so switching from account A to B and
     * back to A never runs A's constructor again. The source's start runs each time Home comes up, and
     * hands over the session the source keeps, so the account follows the switch.
     */
    @Test
    fun theStartHandsTheSessionOverEveryTimeHomeComesUp() {
        val context = PatchContexts.of(classes(sourceWith(listOf(storingConstructor(), start()))))

        requireNotNull(context.homeAccountOrWarn()).write()

        val code = context.mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods.single { it.name == "A0C" }.instructions()
        assertEquals(listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC), code.take(2).map { it.opcode })
        val read = code[0] as TwoRegisterInstruction
        assertEquals("v0 carries the session", 0, read.registerA)
        assertEquals("from the source in p0", 7, read.registerB)
        assertEquals("$MAIN_FEED_CACHE_SOURCE->$sessionField:$session", ((code[0] as ReferenceInstruction).reference).toString())
        assertEquals(HOME_SESSION, ((code[1] as ReferenceInstruction).reference as MethodReference).text())
        assertEquals(0, (code[1] as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction).registerC)
        assertEquals("the rest of the start is as it was", listOf(Opcode.CONST_STRING, Opcode.CONST_STRING, Opcode.RETURN_VOID), code.drop(2).map { it.opcode })
    }

    /** A source whose start can't be told for sure keeps the constructor hook and leaves every method as it was. */
    @Test
    fun aStartThatCantBeFoundIsLeftAlone() {
        val tooHigh = 18
        for ((case, built) in listOf(
            "no start" to sourceWith(listOf(storingConstructor())),
            "a start missing a string" to sourceWith(listOf(storingConstructor(), start(strings = listOf(startTrace)))),
            "two starts" to sourceWith(listOf(storingConstructor(), start(), start(name = "A0D"))),
            "a static start" to sourceWith(listOf(storingConstructor(), start(static = true))),
            "a source out of plain reach" to sourceWith(listOf(storingConstructor(), start(registers = tooHigh))),
            "no session field" to sourceWith(listOf(storingConstructor(), start()), fields = emptyList()),
            "two session fields" to sourceWith(listOf(storingConstructor(), start()), fields = listOf("A01", "A02")),
            "a field the constructor never writes" to sourceWith(listOf(storingConstructor("A02"), start())),
        )) {
            val everything = classes(built)
            val context = PatchContexts.of(everything)
            val account = context.homeAccountOrWarn()
            assertNotNull("$case: the constructor hook still goes in", account)
            account!!.write()

            val methods = context.mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods
            val starts = methods.filter { it.name.startsWith("A0") }
            for (method in starts) {
                val untouched = built.methods.single { it.name == method.name }.instructions().size
                assertEquals("$case: ${method.name} unchanged", untouched, method.instructions().size)
            }
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

            val starts = context.mutableClassDefBy(MAIN_FEED_CACHE_SOURCE).methods.filter { method ->
                method.instructions().any {
                    ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == startTrace
                }
            }
            assertEquals("$where: one start method", 1, starts.size)
            val startCode = starts.single().instructions()
            assertEquals("$where: the start reads the session first", Opcode.IGET_OBJECT, startCode[0].opcode)
            assertEquals("$where: from the source", starts.single().implementation!!.registerCount - 2, (startCode[0] as TwoRegisterInstruction).registerB)
            assertEquals("$where: the start hands it over", HOME_SESSION, ((startCode[1] as ReferenceInstruction).reference as MethodReference).text())
            assertEquals("$where: the start hook once", 1, startCode.count {
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
