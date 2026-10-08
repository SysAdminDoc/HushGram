/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.misc.notifications.NOTIFICATION_GROUPS
import app.morphe.patches.instagram.misc.notifications.NOTIFICATION_MANAGER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock your messages: every notification goes through the extension first, at the poster and at
 * each post Instagram makes straight to Android, and the in-app banner asks it before it shows. Anything the patch can't tell apart fails it before an instruction changes.
 */
class LockMessagesHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(MESSAGES_LOCK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HIDE_NOTIFICATION, HOLD_BANNER)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun bothHooksGoFirst() {
        val context = PatchContexts.of(listOf(poster(), banner(), direct()))
        context.lock()

        assertHooked("stand-ins", context, POSTER, BANNER)
    }

    /**
     * Each direct post, five-register, range or Group notifications' stand-in, hands its
     * notification over first and posts what comes back, and a jump to a post lands on the hook.
     * The first two posts are followed by reads of the notification's register (the next post), so
     * the original waits in the spare v4 over each of them and comes back right after. The last post
     * is followed by nothing, so its copy simply stays in the register.
     */
    @Test
    fun aDirectPostHandsItsNotificationOverFirst() {
        val context = PatchContexts.of(listOf(poster(), banner(), direct()))
        context.lock()

        val method = context.mutableClassDefBy(DIRECT).methods.single()
        val code = method.instructions()
        assertEquals(
            listOf("if-eqz", MOVE_FROM16, HIDE, "move-result-object", "POST", MOVE_FROM16, MOVE_FROM16, HIDE,
                "move-result-object", "POST", MOVE_FROM16, HIDE, "move-result-object", "POST", "return-void"),
            code.map(::shape),
        )
        for (index in listOf(2, 7, 11)) {
            assertEquals("the hook at $index reads the notification", 3, (code[index] as RegisterRangeInstruction).startRegister)
            assertEquals("the copy at ${index + 1} goes back in its register", 3, (code[index + 1] as OneRegisterInstruction).registerA)
        }
        for (save in listOf(1, 6)) {
            assertEquals("the original at $save goes into the spare", listOf(4, 3), code[save].moved())
            assertEquals("and comes back right after the post", listOf(3, 4), code[save + 4].moved())
        }
        val posts = direct().methods.single().instructions().filter { it.postsNotification() }
        assertEquals("posts keep their calls and registers", posts.map { it.calls() }, code.filter { it.postsNotification() }.map { it.calls() })
        val jump = method.implementation!!.instructions.toList()[0]
        assertEquals("the jump lands on what goes first", 6, (jump as BuilderOffsetInstruction).target.location.index)
    }

    /** A post whose notification is read again with no local to keep the original in fails before any change. */
    @Test
    fun aReadAfterThePostWithNoSpareFailsThePatch() =
        refuses("nothing reads after instruction 1", listOf(poster(), banner(), direct(registers = 4)))

    /** The original can come back only when the post returns, so a handler of the post that reads it fails the patch. */
    @Test
    fun aHandlerReadingTheNotificationFailsThePatch() =
        refuses("when its post at instruction 0 throws", listOf(poster(), banner(), caught()))

    @Test
    fun aBuildWithNoDirectPostFailsThePatch() = refuses("found no notification", listOf(poster(), banner()))

    @Test
    fun twoPostersFailThePatch() =
        refuses("notification poster", listOf(poster(), poster("Lfixture/OtherPoster;"), banner()))

    @Test
    fun aMissingBannerFailsThePatch() = refuses("in-app banner", listOf(poster()))

    /** A static poster, or a banner taking something other than a Context first, is something else. */
    @Test
    fun methodsOfAnotherShapeFailThePatch() {
        refuses("notification poster", listOf(poster(static = true), banner()))
        refuses("in-app banner", listOf(poster(), banner(first = "Ljava/lang/Object;")))
    }

    @Test
    fun aJumpBackToTheStartFailsThePatch() {
        refuses("jumps back to the notification poster", listOf(poster(loop = true), banner()))
        refuses("jumps back to the in-app banner", listOf(poster(), banner(loop = true)))
    }

    @Test
    fun aBannerWithoutALocalFailsThePatch() = refuses("needs 1", listOf(poster(), banner(registers = 3)))

    /** In each declared build: the one poster and the one banner, each asking the extension first. */
    @Test
    fun eachDeclaredBuildLocksMessages() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val posting = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == NOTIFICATION_MANAGER && it.name == "notify" }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.postsNotification() } }) {
                            posting += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val classes = (FixtureDex.classesHolding(bundle, SIDE_CHANNEL) + FixtureDex.classesHolding(bundle, NO_BANNER_ACTIVITY) + posting)
                    .distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val targets = context.findLockTargets()
                val poster = targets.notify.definingClass
                val banner = targets.banner.definingClass
                val direct = context.findDirectPosts(targets.notify)
                hideNotificationText(targets.notify)
                hideDirectPosts(direct)
                holdBanner(targets.banner)

                assertHooked(bundle.name, context, poster, banner)
                // Every post outside the poster hands its notification over first, the inline reply's included.
                var posts = 0
                for (classDef in posting.distinctBy { it.type }) {
                    for (method in context.mutableClassDefBy(classDef.type).methods) {
                        val isPoster = method.definingClass == poster && method.parameterTypes.map(CharSequence::toString) == NOTIFY_PARAMETERS &&
                            !AccessFlags.STATIC.isSet(method.accessFlags) && method.instructions().any { text(it) == "\"$SIDE_CHANNEL\"" }
                        if (isPoster) continue
                        val code = method.instructions()
                        code.indices.filter { code[it].postsNotification() }.forEach { index ->
                            val notification = code[index].registerList().last()
                            val where = "${bundle.name}: ${method.definingClass}->${method.name} at $index"
                            assertEquals(where, HIDE_NOTIFICATION, (code[index - 2] as ReferenceInstruction).reference.toString())
                            assertEquals(where, notification, (code[index - 2] as RegisterRangeInstruction).startRegister)
                            assertEquals(where, notification, (code[index - 1] as OneRegisterInstruction).registerA)
                            posts++
                        }
                    }
                }
                assertTrue("${bundle.name}: no direct post", posts > 0)
                assertTrue("${bundle.name}: nothing reads a notification after its direct post",
                    direct.all { it.spares.isEmpty() })
                assertEquals("${bundle.name}: posts hooked", direct.sumOf { it.sites.size }, posts)
                assertTrue("${bundle.name}: the inline reply's post", direct.any { post ->
                    post.method.instructions().any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == REPLY_SERVICE }
                })
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun BytecodePatchContext.lock() {
        val targets = findLockTargets()
        val posts = findDirectPosts(targets.notify)
        hideNotificationText(targets.notify)
        hideDirectPosts(posts)
        holdBanner(targets.banner)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.lock() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun assertHooked(what: String, context: BytecodePatchContext, poster: String, banner: String) {
        val notify = context.mutableClassDefBy(poster).methods.single { it.parameterTypes.map(CharSequence::toString) == NOTIFY_PARAMETERS }
        val code = notify.instructions()
        val notification = notify.implementation!!.registerCount - 1
        assertEquals("$what: the poster's call", HIDE_NOTIFICATION, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the poster's call reads the notification", notification, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the copy", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: the copy goes back in the notification's register", notification, (code[1] as OneRegisterInstruction).registerA)

        val show = context.mutableClassDefBy(banner).methods.single { method -> method.instructions().any { text(it) == "\"$NO_BANNER_ACTIVITY\"" } }
        val shown = show.instructions()
        assertEquals("$what: the banner's call", HOLD_BANNER, (shown[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, shown[1].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, shown[2].opcode)
        assertEquals("$what: the branch's target", 4, (show.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, shown[3].opcode)
        for ((hook, method) in listOf(HIDE_NOTIFICATION to notify, HOLD_BANNER to show)) {
            assertEquals("$what: $hook calls", 1, method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == hook })
        }
    }

    /** A move's destination and source. */
    private fun Instruction.moved(): List<Int> = (this as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }

    private fun Instruction.registerList(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** A call as what it calls and the registers it reads. */
    private fun Instruction.calls(): String = "${opcode.name} ${(this as ReferenceInstruction).reference} ${registerList()}"

    private fun shape(instruction: Instruction): String = when {
        instruction.postsNotification() -> "POST"
        (instruction as? ReferenceInstruction)?.reference?.toString() == HIDE_NOTIFICATION -> HIDE
        else -> instruction.opcode.name
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val POSTER = "Lfixture/Poster;"
        const val BANNER = "Lfixture/Banner;"
        const val DIRECT = "Lfixture/DirectPost;"
        const val HIDE = "HIDE"
        const val MOVE_FROM16 = "move-object/from16"

        /** The service an inline reply runs in, which keeps its name: its update is posted straight to Android. */
        const val REPLY_SERVICE = "Linstagram/features/direct/notifications/impl/internal/DirectNotificationActionService;"

        fun post(definingClass: String, parameters: List<String>) =
            ImmutableMethodReference(definingClass, "notify", parameters, "V")

        /**
         * Three posts the way Instagram's code makes them, v0 the manager, v1 a tag, v2 an id and
         * v3 the notification: tagged as a five-register call, tagged as a range call a jump lands
         * on, and untagged through Group notifications' stand-in. v4, past what the posts use, is
         * free unless [registers] leaves it out.
         */
        fun direct(registers: Int = 5): ClassDef {
            val tagged = post(NOTIFICATION_MANAGER, NOTIFY_PARAMETERS)
            val code = listOf<Instruction>(
                ImmutableInstruction21t(Opcode.IF_EQZ, 2, 5),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 0, 1, 2, 3, 0, tagged),
                ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 4, tagged),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 3, 0, 2, 3, 0, 0,
                    post(NOTIFICATION_GROUPS, listOf(NOTIFICATION_MANAGER, "I", "Landroid/app/Notification;")),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return clazz(DIRECT, "post", emptyList(), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, registers, code)
        }

        /** A post in a try whose catch-all handler hands the notification on, as a log of the failure would. */
        fun caught(): ClassDef {
            val code = listOf<Instruction>(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 0, 1, 2, 3, 0, post(NOTIFICATION_MANAGER, NOTIFY_PARAMETERS)),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 3, 0, 0, 0, 0,
                    ImmutableMethodReference("Lfixture/Log;", "keep", listOf("Landroid/app/Notification;"), "V")),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            // The post's three code units are tried, and anything thrown goes to the handler at code unit 4.
            val tries = listOf(ImmutableTryBlock(0, 3, listOf(ImmutableExceptionHandler(null, 4))))
            return clazz(DIRECT, "post", emptyList(), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 5, code, tries)
        }

        /** Shaped like androidx's NotificationManagerCompat.notify(tag, id, notification). */
        fun poster(type: String = POSTER, static: Boolean = false, loop: Boolean = false): ClassDef {
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(SIDE_CHANNEL)),
                if (loop) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            return clazz(type, "A01", NOTIFY_PARAMETERS, access, 6, code)
        }

        /** Shaped like Instagram's in-app banner: static (Context, notification, owner). */
        fun banner(first: String = "Landroid/content/Context;", registers: Int = 6, loop: Boolean = false): ClassDef {
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(NO_BANNER_ACTIVITY)),
                if (loop) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val access = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            return clazz(BANNER, "A02", listOf(first, "Ljava/lang/Object;", "Ljava/lang/Object;"), access, registers, code)
        }

        fun clazz(
            type: String, name: String, parameters: List<String>, access: Int, registers: Int, code: List<Instruction>,
            tries: List<ImmutableTryBlock>? = null,
        ): ClassDef =
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", access, null, null,
                        ImmutableMethodImplementation(registers, code, tries, null),
                    ),
                ),
            )
    }
}
