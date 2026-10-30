/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.time

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
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
 * Show a post's exact time: every call that writes a feed post's or a comment's time goes to the
 * extension instead, on the same registers, the stubs reach Instagram's own formatting, and
 * anything the patch can't pick out fails it before a change.
 */
class ShowPostTimeHookTest {
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(POST_TIME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (method in listOf(
            TIME_AS_DOUBLE.substringAfter("->"),
            TIME_AS_LONG.substringAfter("->"),
            TIME_FROM_RESOURCES.substringAfter("->"),
            "$INSTAGRAM_TIME($OBJECT${CONTEXT}D)$STRING",
            "$INSTAGRAM_TIME($OBJECT${CONTEXT}J)$STRING",
            "$INSTAGRAM_TIME($RESOURCES$OBJECT$OBJECT${INTEGER}DZ)$STRING",
        )) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
    }

    @Test
    fun everyPostAndCommentTimeGoesToTheExtension() {
        val context = PatchContexts.of(standIns() + ExtensionDex.classDef(POST_TIME))
        val found = context.findPostTimes()
        assertEquals(FORMATTER, found.formatter)
        assertEquals("the footer's time and the label's", 2, found.feed.size)
        assertEquals("the header's time, in the lambda its caller makes", 1, found.header.size)
        assertEquals("the row's two times, not the one that isn't the comment's", 2, found.row.size)

        val before = standIns().associate { it.type to it.methods.associate { method -> method.name to method.code() } }
        context.showPostTime(found)

        fun method(type: String, name: String) = context.mutableClassDefBy(type).methods.single { it.name == name }
        fun unchanged(type: String, name: String) =
            assertEquals("$type->$name keeps Instagram's code", before.getValue(type).getValue(name).map { it.text() }, method(type, name).code().map { it.text() })
        assertReplaced(method(FOOTER, "state"), before.getValue(FOOTER).getValue("state"), TIME_AS_DOUBLE, 1)
        assertReplaced(method(LABEL, "state"), before.getValue(LABEL).getValue("state"), TIME_AS_DOUBLE, 1)
        assertReplaced(method(HEADER_TIME, "invoke"), before.getValue(HEADER_TIME).getValue("invoke"), TIME_FROM_RESOURCES, 1)
        unchanged(HEADER, "compose")
        unchanged(HEADER_CALLER, "compose")
        // Asks the formatter for a time from the resources, but nothing the header's callers make.
        unchanged(ELSEWHERE, "invoke")
        assertReplaced(method(ROW_TIME, "relative"), before.getValue(ROW_TIME).getValue("relative"), TIME_AS_DOUBLE, 1)
        assertReplaced(method(ROW_TIME, "short"), before.getValue(ROW_TIME).getValue("short"), TIME_AS_LONG, 1)
        // Reads the comment's time, but writes another one.
        unchanged(ROW_TIME, "elsewhere")

        val stubs = context.mutableClassDefBy(POST_TIME).methods.filter { it.name == INSTAGRAM_TIME }
        fun stubCall(time: String, opcode: Opcode) = stubs.single { it.parameterTypes.last().toString() == time }.code()
            .single { it.opcode == opcode }.text()
        assertEquals("$FORMATTER->relative(${CONTEXT}D)$STRING", stubCall("D", Opcode.INVOKE_VIRTUAL))
        assertEquals("$FORMATTER->short(${CONTEXT}J)$STRING", stubCall("J", Opcode.INVOKE_VIRTUAL))
        assertEquals(FROM_RESOURCES.toString(), stubCall("Z", Opcode.INVOKE_STATIC_RANGE))
    }

    /** Up to 451 the header asked the formatter itself, and that call is the one replaced. */
    @Test
    fun aHeaderAskingTheFormatterItselfIsReplacedInPlace() {
        val context = PatchContexts.of(standIns(directHeader = true) + ExtensionDex.classDef(POST_TIME))
        val found = context.findPostTimes()
        assertEquals(HEADER, found.header.single().method.definingClass)
        val before = standIns(directHeader = true).single { it.type == HEADER }.methods.single().code()
        context.showPostTime(found)
        assertReplaced(context.mutableClassDefBy(HEADER).methods.single(), before, TIME_AS_LONG, 1)
    }

    /** Two formattings for one shape can't share a stub, so the patch refuses rather than pick one. */
    @Test
    fun twoFormattingsOfOneShapeFailThePatch() = refuses("reach 2 formatting methods") {
        PatchContexts.of(standIns(secondFormatting = true) + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aHeaderWhoseCallersMakeNoTimeFailsThePatch() = refuses("nothing asks $FORMATTER for the time in $HEADER") {
        PatchContexts.of(standIns().filter { it.type != HEADER_TIME } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aMissingLabelFailsThePatch() = refuses("expected one feed label builder") {
        PatchContexts.of(standIns().filter { it.type != LABEL } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aRowWithoutItsTimeFailsThePatch() = refuses("expected one comment row") {
        PatchContexts.of(standIns(rowTime = false) + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    /** 450's 385611395 and 385611400 ask a pool of shared strings for the row's tag (#77). */
    @Test
    fun aRowAskingAPoolForItsTagIsFound() {
        val context = PatchContexts.of(standIns(pooledRow = true) + ExtensionDex.classDef(POST_TIME))
        val found = context.findPostTimes()
        assertEquals("the row's two times", 2, found.row.size)
        assertEquals(ROW_TIME, found.row.map { it.method.definingClass }.distinct().single())
        context.showPostTime(found)
        val relative = context.mutableClassDefBy(ROW_TIME).methods.single { it.name == "relative" }.code()
        assertEquals(TIME_AS_DOUBLE, relative.single { it.opcode == Opcode.INVOKE_STATIC }.text())
    }

    @Test
    fun aRowAskingThePoolForAnotherStringFailsThePatch() = refuses("expected one comment row") {
        PatchContexts.of(standIns(pooledRow = true).map { if (it.type == POOL) pool(number = POOLED + 1) else it } +
            ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aPooledRowWithoutItsPoolFailsThePatch() = refuses("expected one comment row") {
        PatchContexts.of(standIns(pooledRow = true).filter { it.type != POOL } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aRowWhoseTimeNothingWritesFailsThePatch() = refuses("nothing asks") {
        PatchContexts.of(standIns().filter { it.type != ROW_TIME } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aMissingHeaderFailsThePatch() = refuses("expected one comment header") {
        PatchContexts.of(standIns().filter { it.type != HEADER } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aMissingFooterFailsThePatch() = refuses("expected one feed footer builder") {
        PatchContexts.of(standIns().filter { it.type != FOOTER } + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    /** In each declared build: the feed footer's and label's times, the comment header's two and the comment row's two. */
    @Test
    fun eachDeclaredBuildWritesExactTimes() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                writesExactTimes(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the declared version's other builds, compiled on their own, like 452's two for armeabi-v7a (#77). */
    @Test
    fun eachOtherBuildWritesExactTimes() {
        for (bundle in Fixtures.otherBuilds()) writesExactTimes(bundle, bundle.parentFile.name)
    }

    private fun writesExactTimes(bundle: File, label: String) {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.holds { string -> string == LONG_AGO_PATTERN || string == FEED_FOOTER || string == FEED_LABEL ||
                            string == COMMENT_ROW_TIME || string.startsWith(COMMENT_HEADER) } }
                ) kept += ImmutableClassDef.of(classDef)
            }
        }
        // From 450's 385611395 the row asks a pool of shared strings for its tag (#77), so the
        // classes keeping one time that call a pool holding it come too.
        val pools = kept.flatMap { it.methods }.filter { it.isPool() && it.holds { string -> string == COMMENT_ROW_TIME } }
            .mapTo(HashSet()) { "${it.definingClass}->${it.name}" }
        if (pools.isNotEmpty()) FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.times() == 1 && classDef.methods.any { method ->
                        method.code().any { it.opcode == Opcode.INVOKE_STATIC && it.called()?.let { call -> "${call.definingClass}->${call.name}" } in pools }
                    }
                ) kept += ImmutableClassDef.of(classDef)
            }
        }
        val pooled = PatchContexts.of(kept.distinctBy { it.type })
        val row = kept.distinctBy { it.type }.single { classDef ->
            classDef.times() == 1 && classDef.methods.any { pooled.loadsString(it, COMMENT_ROW_TIME) }
        }
        val time = row.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_BOX }
        // From 452 the header's callers hand it the time as a lambda they make, so the callers come
        // too, and then what they make.
        val header = kept.flatMap { it.methods }.single { method -> method.holds { it.startsWith(COMMENT_HEADER) } }
        val made = HashSet<String>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val callers = classDef.methods.filter { method -> method.code().any { it.called()?.let { call -> call.definingClass == header.definingClass && call.name == header.name } == true } }
                callers.flatMapTo(made) { method -> method.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { (it.reference() as? TypeReference)?.type } }
                if (callers.isNotEmpty() || classDef.methods.any { method ->
                        method.code().any { instruction ->
                            val field = instruction.reference() as? FieldReference
                            instruction.opcode == Opcode.IGET_OBJECT && field?.definingClass == row.type && field.name == time.name
                        }
                    }
                ) kept += ImmutableClassDef.of(classDef)
            }
        }
        made.removeAll(kept.mapTo(HashSet()) { it.type })
        kept += FixtureDex.classes(bundle, made).values
        val classes = kept.distinctBy { it.type }
        val context = PatchContexts.of(classes + ExtensionDex.classDef(POST_TIME))
        val found = context.findPostTimes()
        assertEquals("$label: the footer's time and the label's", 2, found.feed.size)
        assertEquals("$label: the header's times, one in each lambda its callers make", 2, found.header.size)
        assertEquals("$label: the header's times are in two lambdas", 2, found.header.map { it.method.definingClass }.distinct().size)
        assertEquals("$label: the header's times come from the resources", setOf(TimeShape.RESOURCES), found.header.map { it.shape }.toSet())
        assertEquals("$label: the row's times", 2, found.row.size)
        assertEquals("$label: every shape", TimeShape.entries.toSet(), found.sites.map { it.shape }.toSet())

        val before = found.sites.groupBy { it.method }.mapValues { (method, _) -> method.code() }
        context.showPostTime(found)
        for ((method, sites) in found.sites.groupBy { it.method }) {
            val patched = context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            val code = patched.code()
            for (site in sites) {
                val hook = when (site.shape) {
                    TimeShape.DOUBLE -> TIME_AS_DOUBLE
                    TimeShape.LONG -> TIME_AS_LONG
                    TimeShape.RESOURCES -> TIME_FROM_RESOURCES
                }
                assertEquals("$label ${method.definingClass}->${method.name} @${site.index}", hook, code[site.index].text())
                assertEquals("the same registers", before.getValue(method)[site.index].registers(), code[site.index].registers())
            }
            assertEquals("nothing else moved", before.getValue(method).size, code.size)
        }
    }

    private fun refuses(reason: String, patch: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** Exactly [count] calls of [method] went to [hook], each on the registers its call had, and nothing else changed. */
    private fun assertReplaced(method: Method, before: List<Instruction>, hook: String, count: Int) {
        val code = method.code()
        assertEquals("${method.name}: same length", before.size, code.size)
        val changed = code.indices.filter { code[it].text() != before[it].text() }
        assertEquals("${method.name}: calls replaced", count, changed.size)
        for (at in changed) {
            val static = if (before[at] is RegisterRangeInstruction) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC
            assertEquals("${method.name} @$at: static", static, code[at].opcode)
            assertEquals("${method.name} @$at: the hook", hook, code[at].text())
            assertEquals("${method.name} @$at: Instagram's call", FORMATTER, before[at].text()?.substringBefore("->"))
            assertEquals("${method.name} @$at: the same registers", before[at].registers(), code[at].registers())
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.holds(accept: (String) -> Boolean) = code().any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.let(accept) == true
    }
    /** A static (int)String method, the shape of a Redex pool of shared strings. */
    private fun Method.isPool() = AccessFlags.STATIC.isSet(accessFlags) && returnType == STRING && parameterTypes.map(CharSequence::toString) == listOf("I")
    /** How many boxed Long instance fields the class keeps. */
    private fun ClassDef.times() = fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_BOX }
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun Instruction.called() = reference() as? MethodReference
    private fun Instruction.text(): String? = reference()?.toString()
    private fun Instruction.registers(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    private companion object {
        const val FORMATTER = "Lfixture/Time;"
        const val UNIT = "Lfixture/Unit;"
        const val FOOTER = "Lfixture/Footer;"
        const val LABEL = "Lfixture/Label;"
        const val HEADER = "Lfixture/Header;"
        const val HEADER_CALLER = "Lfixture/HeaderCaller;"
        const val HEADER_TIME = "Lfixture/HeaderTime;"
        const val ELSEWHERE = "Lfixture/ElsewhereTime;"
        const val FUNCTION = "Lkotlin/jvm/functions/Function1;"
        const val ROW = "Lfixture/CommentRow;"
        const val ROW_TIME = "Lfixture/CommentRowTime;"
        const val HEADER_TRACE = "${COMMENT_HEADER}CommentAuthorHeader.kt:77)"
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val PUBLIC_STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val ROW_FIELD = ImmutableFieldReference(ROW, "time", LONG_BOX)
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7
        val POOL_CALL = ImmutableMethodReference(POOL, "A00", listOf("I"), STRING)
        val UNBOX = ImmutableMethodReference("Ljava/lang/Number;", "longValue", emptyList(), "J")

        fun formatting(name: String, time: String) = ImmutableMethodReference(FORMATTER, name, listOf(CONTEXT, time), STRING)
        val RELATIVE = formatting("relative", "D")
        val RELATIVE_TOO = formatting("relativeToo", "D")
        val SHORT = formatting("short", "J")
        val FROM_RESOURCES = ImmutableMethodReference(FORMATTER, "fromResources", listOf(RESOURCES, UNIT, FORMATTER, INTEGER, "D", "Z"), STRING)
        val COMPOSE = ImmutableMethodReference(HEADER, "compose", listOf(FUNCTION), "V")

        fun method(type: String, name: String, parameters: List<String>, returns: String, access: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, access, null, null,
                ImmutableMethodImplementation(registers, code, null, null))

        fun type(type: String, methods: List<ImmutableMethod>, fields: List<ImmutableField> = emptyList()) =
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, fields, methods)

        fun string(register: Int, value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))
        fun call(reference: ImmutableMethodReference, vararg registers: Int) = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 }, registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, reference,
        )

        /** A use case's state builder loading [trace] that asks [formatting] for the post's time. */
        fun builder(owner: String, trace: String, formatting: ImmutableMethodReference) =
            // v3 the formatter, v4 the context, v5 and v6 the time; the result lands on the double.
            type(owner, listOf(method(owner, "state", listOf(FORMATTER, CONTEXT, "J"), "V", PUBLIC_STATIC, 7, listOf(
                string(0, trace),
                ImmutableInstruction12x(Opcode.LONG_TO_DOUBLE, 1, 5),
                call(formatting, 3, 4, 1, 2),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ))))

        /**
         * A Compose lambda writing the comment's time the way 452's do: the boxed time unboxed and made
         * a double, then the formatter's static resources overload on v0 to v6. this is v7, p1 v8.
         */
        fun headerTime(owner: String): ClassDef {
            val resources = ImmutableFieldReference(owner, "resources", RESOURCES)
            val formatter = ImmutableFieldReference(owner, "formatter", FORMATTER)
            return type(owner, listOf(method(owner, "invoke", listOf(OBJECT), OBJECT, AccessFlags.PUBLIC.value, 9, listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 7, resources),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 7, formatter),
                ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
                call(UNBOX, 8),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 4),
                ImmutableInstruction12x(Opcode.LONG_TO_DOUBLE, 4, 4),
                ImmutableInstruction11n(Opcode.CONST_4, 6, 0),
                ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 7, FROM_RESOURCES),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ))), listOf(resources, formatter).map { ImmutableField(owner, it.name, it.type, PUBLIC_FINAL, null, null, null) })
        }

        /**
         * Shaped like 452's: the formatter, the footer and the label under the comments, the Compose
         * header and the lambda its caller hands it, the row and the row's time lambda. With
         * [directHeader] the header asks the formatter itself, as up to 451. With [pooledRow] the row
         * asks [pool] for its tag, as 450's 385611395 and 385611400 do.
         */
        fun standIns(
            secondFormatting: Boolean = false,
            rowTime: Boolean = true,
            pooledRow: Boolean = false,
            directHeader: Boolean = false,
        ): List<ClassDef> {
            val formatter = type(FORMATTER, listOf(
                method(FORMATTER, "core", listOf(RESOURCES, UNIT, FORMATTER, INTEGER, "D", "D", "Z", "Z", "Z", "Z"),
                    STRING, PUBLIC_STATIC, 13, listOf(string(0, LONG_AGO_PATTERN), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, FROM_RESOURCES.name, FROM_RESOURCES.parameterTypes.map(CharSequence::toString), STRING, PUBLIC_STATIC, 8,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "relative", listOf(CONTEXT, "D"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "relativeToo", listOf(CONTEXT, "D"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "short", listOf(CONTEXT, "J"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
            ))
            val footer = builder(FOOTER, FEED_FOOTER, RELATIVE)
            val label = builder(LABEL, FEED_LABEL, if (secondFormatting) RELATIVE_TOO else RELATIVE)
            val header = if (directHeader) {
                type(HEADER, listOf(method(HEADER, "compose", listOf(FORMATTER, CONTEXT, "J"), "V", PUBLIC_STATIC, 5, listOf(
                    string(0, HEADER_TRACE),
                    call(SHORT, 1, 2, 3, 4),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ))))
            } else {
                type(HEADER, listOf(method(HEADER, COMPOSE.name, listOf(FUNCTION), "V", PUBLIC_STATIC, 2, listOf(
                    string(0, HEADER_TRACE),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ))))
            }
            // Makes a class that isn't in the app and the time lambda, and hands the header the lambda.
            val caller = type(HEADER_CALLER, listOf(method(HEADER_CALLER, "compose", emptyList(), "V", PUBLIC_STATIC, 2, listOf(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference("Ljava/lang/StringBuilder;")),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(HEADER_TIME)),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, COMPOSE),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ))))
            val tag = if (pooledRow) listOf(
                ImmutableInstruction21s(Opcode.CONST_16, 0, POOLED),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, POOL_CALL),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ) else listOf(string(0, COMMENT_ROW_TIME))
            val row = type(ROW,
                listOf(method(ROW, "render", emptyList(), "V", AccessFlags.PUBLIC.value, 2, tag + ImmutableInstruction10x(Opcode.RETURN_VOID))),
                if (rowTime) listOf(ImmutableField(ROW, "time", LONG_BOX, PUBLIC_FINAL, null, null, null)) else emptyList(),
            )
            fun own(name: String, fieldType: String) = ImmutableFieldReference(ROW_TIME, name, fieldType)
            val lambdaFields = listOf(own("row", ROW), own("formatter", FORMATTER), own("context", CONTEXT))
            // this is v6 in each.
            val lambda = type(ROW_TIME, listOf(
                method(ROW_TIME, "relative", emptyList(), OBJECT, AccessFlags.PUBLIC.value, 7, listOf(
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, lambdaFields[0]),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 1, ROW_FIELD),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 6, lambdaFields[2]),
                    call(UNBOX, 1),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 4),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 6, lambdaFields[1]),
                    ImmutableInstruction12x(Opcode.LONG_TO_DOUBLE, 0, 4),
                    call(RELATIVE, 3, 2, 0, 1),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                )),
                method(ROW_TIME, "short", emptyList(), OBJECT, AccessFlags.PUBLIC.value, 7, listOf(
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, lambdaFields[0]),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0, ROW_FIELD),
                    call(UNBOX, 0),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 2),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, lambdaFields[1]),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, lambdaFields[2]),
                    call(SHORT, 1, 0, 2, 3),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
                )),
                // Reads the comment's time, but writes another one.
                method(ROW_TIME, "elsewhere", emptyList(), OBJECT, AccessFlags.PUBLIC.value, 7, listOf(
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, lambdaFields[0]),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0, ROW_FIELD),
                    ImmutableInstruction21s(Opcode.CONST_WIDE_16, 2, 0),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, lambdaFields[1]),
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, lambdaFields[2]),
                    call(SHORT, 1, 0, 2, 3),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
                )),
            ), lambdaFields.map { ImmutableField(ROW_TIME, it.name, it.type, PUBLIC_FINAL, null, null, null) })
            return listOf(formatter, footer, label, header, caller, headerTime(HEADER_TIME), headerTime(ELSEWHERE), row, lambda) +
                if (pooledRow) listOf(pool()) else emptyList()
        }

        /**
         * A pool of shared strings as 450's Redex writes it: a static (int)String switch that
         * answers [COMMENT_ROW_TIME] for [number]. p0 is v1.
         */
        fun pool(number: Int = POOLED): ClassDef = type(POOL, listOf(method(POOL, POOL_CALL.name, listOf("I"), STRING, PUBLIC_STATIC, 2, listOf(
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            string(0, COMMENT_ROW_TIME),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(number, 5))),
        ))))
    }
}
