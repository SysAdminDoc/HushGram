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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "$INSTAGRAM_TIME($OBJECT${CONTEXT}D)$STRING",
            "$INSTAGRAM_TIME($OBJECT${CONTEXT}J)$STRING",
        )) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
    }

    @Test
    fun everyPostAndCommentTimeGoesToTheExtension() {
        val context = PatchContexts.of(standIns() + ExtensionDex.classDef(POST_TIME))
        val found = context.findPostTimes()
        assertEquals(FORMATTER, found.formatter)
        assertEquals("the footer's two times", 2, found.feed.size)
        assertEquals("the header's time", 1, found.header.size)
        assertEquals("the row's two times, not the one that isn't the comment's", 2, found.row.size)

        val before = standIns().associate { it.type to it.methods.associate { method -> method.name to method.code() } }
        context.showPostTime(found)

        fun method(type: String, name: String) = context.mutableClassDefBy(type).methods.single { it.name == name }
        assertReplaced(method(FOOTER, "state"), before.getValue(FOOTER).getValue("state"), TIME_AS_DOUBLE, 2)
        assertReplaced(method(HEADER, "compose"), before.getValue(HEADER).getValue("compose"), TIME_AS_LONG, 1)
        assertReplaced(method(ROW_TIME, "relative"), before.getValue(ROW_TIME).getValue("relative"), TIME_AS_DOUBLE, 1)
        assertReplaced(method(ROW_TIME, "short"), before.getValue(ROW_TIME).getValue("short"), TIME_AS_LONG, 1)
        assertEquals(
            "a time that isn't the comment's keeps Instagram's call",
            before.getValue(ROW_TIME).getValue("elsewhere").map { it.text() },
            method(ROW_TIME, "elsewhere").code().map { it.text() },
        )

        val stubs = context.mutableClassDefBy(POST_TIME).methods.filter { it.name == INSTAGRAM_TIME }
        fun stubCall(time: String) = stubs.single { it.parameterTypes.last().toString() == time }.code()
            .single { it.opcode == Opcode.INVOKE_VIRTUAL }.text()
        assertEquals("$FORMATTER->relative(${CONTEXT}D)$STRING", stubCall("D"))
        assertEquals("$FORMATTER->short(${CONTEXT}J)$STRING", stubCall("J"))
    }

    /** Two formattings for one shape can't share a stub, so the patch refuses rather than pick one. */
    @Test
    fun twoFormattingsOfOneShapeFailThePatch() = refuses("reach 2 formatting methods") {
        PatchContexts.of(standIns(secondFormatting = true) + ExtensionDex.classDef(POST_TIME)).findPostTimes()
    }

    @Test
    fun aRowWithoutItsTimeFailsThePatch() = refuses("expected one comment row") {
        PatchContexts.of(standIns(rowTime = false) + ExtensionDex.classDef(POST_TIME)).findPostTimes()
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

    /** In each declared build: the feed footer's two times, the comment header's and the comment row's two. */
    @Test
    fun eachDeclaredBuildWritesExactTimes() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val kept = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.holds { string -> string == LONG_AGO_PATTERN || string == FEED_FOOTER ||
                                    string == COMMENT_ROW_TIME || string.startsWith(COMMENT_HEADER) } }
                        ) kept += ImmutableClassDef.of(classDef)
                    }
                }
                val row = kept.single { classDef ->
                    classDef.methods.any { it.holds { string -> string == COMMENT_ROW_TIME } } &&
                        classDef.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_BOX } == 1
                }
                val time = row.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_BOX }
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method ->
                                method.code().any { instruction ->
                                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                                    instruction.opcode == Opcode.IGET_OBJECT && field?.definingClass == row.type && field.name == time.name
                                }
                            }
                        ) kept += ImmutableClassDef.of(classDef)
                    }
                }
                val classes = kept.distinctBy { it.type }
                val context = PatchContexts.of(classes + ExtensionDex.classDef(POST_TIME))
                val found = context.findPostTimes()
                assertEquals("${bundle.name}: the footer's times", 2, found.feed.size)
                assertEquals("${bundle.name}: the header's time", 1, found.header.size)
                assertEquals("${bundle.name}: the row's times", 2, found.row.size)
                assertEquals("${bundle.name}: both shapes", setOf(true, false), found.sites.map { it.asLong }.toSet())

                val before = found.sites.groupBy { it.method }.mapValues { (method, _) -> method.code() }
                context.showPostTime(found)
                for ((method, sites) in found.sites.groupBy { it.method }) {
                    val patched = context.mutableClassDefBy(method.definingClass).methods.single {
                        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                    }
                    val code = patched.code()
                    for (site in sites) {
                        val hook = if (site.asLong) TIME_AS_LONG else TIME_AS_DOUBLE
                        assertEquals("${bundle.name} ${method.definingClass}->${method.name} @${site.index}", hook, code[site.index].text())
                        assertEquals("the same registers", before.getValue(method)[site.index].registers(), code[site.index].registers())
                    }
                    assertEquals("nothing else moved", before.getValue(method).size, code.size)
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
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
            assertEquals("${method.name} @$at: static", Opcode.INVOKE_STATIC, code[at].opcode)
            assertEquals("${method.name} @$at: the hook", hook, code[at].text())
            assertEquals("${method.name} @$at: Instagram's call", FORMATTER, before[at].text()?.substringBefore("->"))
            assertEquals("${method.name} @$at: the same registers", before[at].registers(), code[at].registers())
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.holds(accept: (String) -> Boolean) = code().any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.let(accept) == true
    }
    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.registers(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    private companion object {
        const val FORMATTER = "Lfixture/Time;"
        const val FOOTER = "Lfixture/Footer;"
        const val HEADER = "Lfixture/Header;"
        const val ROW = "Lfixture/CommentRow;"
        const val ROW_TIME = "Lfixture/CommentRowTime;"
        const val HEADER_TRACE = "${COMMENT_HEADER}CommentAuthorHeader.kt:77)"
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val PUBLIC_STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val ROW_FIELD = ImmutableFieldReference(ROW, "time", LONG_BOX)
        val UNBOX = ImmutableMethodReference("Ljava/lang/Number;", "longValue", emptyList(), "J")

        fun formatting(name: String, time: String) = ImmutableMethodReference(FORMATTER, name, listOf(CONTEXT, time), STRING)
        val RELATIVE = formatting("relative", "D")
        val RELATIVE_TOO = formatting("relativeToo", "D")
        val SHORT = formatting("short", "J")

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

        /** Shaped like 450's: the formatter, the footer, the Compose header, the row and the row's time lambda. */
        fun standIns(secondFormatting: Boolean = false, rowTime: Boolean = true): List<ClassDef> {
            val formatter = type(FORMATTER, listOf(
                method(FORMATTER, "core", listOf("Landroid/content/res/Resources;", "Lfixture/Unit;", FORMATTER, "Ljava/lang/Integer;", "D", "D", "Z", "Z", "Z", "Z"),
                    STRING, PUBLIC_STATIC, 13, listOf(string(0, LONG_AGO_PATTERN), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "relative", listOf(CONTEXT, "D"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "relativeToo", listOf(CONTEXT, "D"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(FORMATTER, "short", listOf(CONTEXT, "J"), STRING, AccessFlags.PUBLIC.value, 5,
                    listOf(string(0, ""), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
            ))
            // v3 the formatter, v4 the context, v5 and v6 the time; the second result lands on the double.
            val footer = type(FOOTER, listOf(method(FOOTER, "state", listOf(FORMATTER, CONTEXT, "J"), "V", PUBLIC_STATIC, 7, listOf(
                string(0, FEED_FOOTER),
                ImmutableInstruction12x(Opcode.LONG_TO_DOUBLE, 1, 5),
                call(RELATIVE, 3, 4, 1, 2),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction12x(Opcode.LONG_TO_DOUBLE, 1, 5),
                call(if (secondFormatting) RELATIVE_TOO else RELATIVE, 3, 4, 1, 2),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ))))
            val header = type(HEADER, listOf(method(HEADER, "compose", listOf(FORMATTER, CONTEXT, "J"), "V", PUBLIC_STATIC, 5, listOf(
                string(0, HEADER_TRACE),
                call(SHORT, 1, 2, 3, 4),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ))))
            val row = type(ROW,
                listOf(method(ROW, "render", emptyList(), "V", AccessFlags.PUBLIC.value, 2,
                    listOf(string(0, COMMENT_ROW_TIME), ImmutableInstruction10x(Opcode.RETURN_VOID)))),
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
            return listOf(formatter, footer, header, row, lambda)
        }
    }
}
