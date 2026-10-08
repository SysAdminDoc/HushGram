/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.notifications

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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every notification Instagram posts goes through NotificationGroups, which puts it in HushGram's
 * group while the switch is on and posts it exactly as built while it's off. Every cancel goes
 * there too, so a group's summary is counted again when one of its notifications goes.
 */
class GroupNotificationsHookTest {
    private val untagged = NOTIFY_SHAPES[0]
    private val tagged = NOTIFY_SHAPES[1]
    private val cancelUntagged = CANCEL_SHAPES[0]
    private val cancelTagged = CANCEL_SHAPES[1]

    private fun call(shape: String, definingClass: String = NOTIFICATION_MANAGER, name: String = "notify") =
        ImmutableMethodReference(
            definingClass, name,
            Regex("""L[^;]+;|I""").findAll(shape.substringBefore(')').drop(1)).map { it.value }.toList(),
            shape.substringAfter(')'),
        )

    /**
     * A class of [type] whose one static method posts twice the way Instagram's code does: v0 the
     * manager, v1 a tag, v2 an id and v3 the notification, untagged as a five-register call and
     * tagged as a range call. An untagged cancel sits between them, and with [cancels] a tagged
     * cancel as a range call follows. A compat class's notify and cancel stay.
     */
    private fun poster(type: String, cancels: Boolean = true): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "post", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    listOfNotNull(
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 3, 0, 0, call(untagged)),
                        if (cancels) ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0, call(cancelUntagged, name = "cancel")) else null,
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 3, 0, 0,
                            call(untagged, "Landroidx/core/app/NotificationManagerCompat;")),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0,
                            call(cancelUntagged, "Landroidx/core/app/NotificationManagerCompat;", "cancel")),
                        ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 4, call(tagged)),
                        if (cancels) ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 3, call(cancelTagged, name = "cancel")) else null,
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    @Test
    fun eachPostAndCancelGoesToItsStandInWithTheSameRegisters() {
        val instagram = "Lfixture/NotificationPoster;"
        val context = PatchContexts.of(listOf(poster(instagram)))

        assertEquals(Sent(posts = 2, cancels = 2), context.groupNotifications())

        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals(
            "$NOTIFICATION_GROUPS->notify(Landroid/app/NotificationManager;ILandroid/app/Notification;)V",
            (code[0] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(0, 2, 3), code[0].registers())
        assertEquals(Opcode.INVOKE_STATIC, code[1].opcode)
        assertEquals("$NOTIFICATION_GROUPS->cancel(Landroid/app/NotificationManager;I)V", (code[1] as ReferenceInstruction).reference.toString())
        assertEquals(listOf(0, 2), code[1].registers())
        assertEquals("another class's notify stays", Opcode.INVOKE_VIRTUAL, code[2].opcode)
        assertEquals("another class's cancel stays", Opcode.INVOKE_VIRTUAL, code[3].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[4].opcode)
        assertEquals(
            "$NOTIFICATION_GROUPS->notify(Landroid/app/NotificationManager;Ljava/lang/String;ILandroid/app/Notification;)V",
            (code[4] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(0, 1, 2, 3), code[4].registers())
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[5].opcode)
        assertEquals(
            "$NOTIFICATION_GROUPS->cancel(Landroid/app/NotificationManager;Ljava/lang/String;I)V",
            (code[5] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(0, 1, 2), code[5].registers())
    }

    /** A build that never cancels still groups: its summaries are only counted again at the next post. */
    @Test
    fun aBuildCancellingNothingStillGroups() {
        val instagram = "Lfixture/NotificationPoster;"
        val context = PatchContexts.of(listOf(poster(instagram, cancels = false)))

        assertEquals(Sent(posts = 2, cancels = 0), context.groupNotifications())
    }

    /** The extension's own posts are the real ones the stand-ins make. Sent, each would call itself. */
    @Test
    fun theExtensionsOwnPostsStay() {
        val instagram = "Lfixture/NotificationPoster;"
        val context = PatchContexts.of(listOf(poster(instagram), poster(NOTIFICATION_GROUPS)))

        assertEquals(Sent(posts = 2, cancels = 2), context.groupNotifications())

        val kept = context.mutableClassDefBy(NOTIFICATION_GROUPS).methods.single().instructions()
        assertEquals(listOf(untagged, tagged), kept.mapNotNull { it.notifyCall() })
        assertEquals(listOf(cancelUntagged, cancelTagged), kept.mapNotNull { it.cancelCall() })
    }

    @Test
    fun aBuildPostingNothingFailsThePatch() {
        val quiet = ImmutableClassDef(
            "Lfixture/Quiet;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, emptyList<Method>(),
        )
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(quiet)).groupNotifications() }
    }

    /**
     * Each stand-in the rewrite writes is a public static method of the NotificationGroups the
     * bundle ships, read from the compiled extension, so a parameter that compiles to another type
     * fails here and not in Instagram's notification code.
     */
    @Test
    fun everyCallSentHasAStandIn() {
        val declared = ExtensionDex.classDef(NOTIFICATION_GROUPS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$NOTIFICATION_GROUPS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        NOTIFY_SHAPES.forEach { shape ->
            assertTrue("NotificationGroups declares no ${notifyStandIn(shape)}: $declared", notifyStandIn(shape) in declared)
        }
        CANCEL_SHAPES.forEach { shape ->
            assertTrue("NotificationGroups declares no ${cancelStandIn(shape)}: $declared", cancelStandIn(shape) in declared)
        }
    }

    /**
     * The declared build posts from nine methods without a tag and seven with one (Firebase's
     * display notifications among them), and cancels from four without a tag and twenty-three
     * with one (the message notifications' withdraw and trim among them). Every one of those calls
     * goes to its stand-in on the same registers, with the instruction count unchanged, and none is
     * left behind.
     */
    @Test
    fun eachDeclaredBuildSendsEveryPostAndCancel() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == NOTIFICATION_MANAGER && it.name in setOf("notify", "cancel") }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.sent() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val methods = callers.flatMap { it.methods }
                val byShape = NOTIFY_SHAPES.associateWith { shape ->
                    methods.count { method -> method.instructions().any { it.notifyCall() == shape } }
                }
                assertEquals("${bundle.name}: methods posting by shape", mapOf(untagged to 9, tagged to 7), byShape)
                val cancelsByShape = CANCEL_SHAPES.associateWith { shape ->
                    methods.count { method -> method.instructions().any { it.cancelCall() == shape } }
                }
                assertEquals("${bundle.name}: methods cancelling by shape", mapOf(cancelUntagged to 4, cancelTagged to 23), cancelsByShape)

                val context = PatchContexts.of(callers)
                val sent = context.groupNotifications()

                var posts = 0
                var cancels = 0
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.sent() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: instruction count", was.size, now.size)
                        assertEquals("$where: calls left", emptyList<String>(), now.mapNotNull { it.sent() })
                        was.forEachIndexed { index, instruction ->
                            val standIn = instruction.sent() ?: return@forEachIndexed
                            assertEquals("$where at $index", standIn, (now[index] as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: registers", instruction.registers(), now[index].registers())
                            if (instruction.notifyCall() != null) posts++ else cancels++
                        }
                    }
                }
                assertEquals("${bundle.name}: calls sent", Sent(posts, cancels), sent)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** The stand-in a notify or cancel call goes to, or null for any other instruction. */
    private fun Instruction.sent(): String? = notifyCall()?.let(::notifyStandIn) ?: cancelCall()?.let(::cancelStandIn)

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
