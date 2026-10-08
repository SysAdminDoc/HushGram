/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.doubletap

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapLikeHookTest {
    private val feed = "Lfixture/MediaHolderGestureDelegate;"
    private val carousel = "Lfixture/CarouselGestureDelegate;"
    private val video = "Lfixture/VideoGestureDelegate;"
    private val liker = "Lfixture/DoubleTapLiker;"
    private val tags = "Lfixture/ProductTags;"
    private val handler = "Lfixture/GestureActionHandler;"
    private val action = "Lfixture/LikeAction;"
    private val handleMarker = "android_purge_26_q3_$HANDLE_DOUBLE_TAP"
    private val setterMarker = "android_purge_26_q3_$SET_LIKE_ACTION"
    private val likeShape = "(Landroid/view/View;Lcom/instagram/feed/media/Media;Ljava/lang/Object;I)V"
    private val fbCommentRow = "Lfixture/FbCommentRowGestures;"
    private val commentRow = "Lfixture/CommentRowGestures;"
    private val otherRow = "Lfixture/LikeOnlyGestures;"
    private val messageTap = "Lfixture/MessageDoubleTap;"
    private val messageTip = "Lfixture/MessageTip;"
    private val messageId = "Lcom/instagram/model/direct/messageid/MessageIdentifier;"

    /** The hooks the patch writes are in the DoubleTapLike the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HOLD_BACK_POST, LIKE_ACTION, HOLD_BACK_COMMENT, HOLD_BACK_MESSAGE)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The feed's double-tap like asks first, where every kind of post reaches it, and the delegates
     * stay as they were. The Reels handler's like action is asked for as it's read.
     */
    @Test
    fun bothDoubleTapsAsk() {
        val context = PatchContexts.of(classes())

        context.turnOffDoubleTapLikes()

        assertGuardFirst("the feed's double-tap like", context.mutableClassDefBy(liker).methods.single { it.name == "like" }.code())
        for (delegate in listOf(feed, carousel, video)) {
            assertTrue(
                "$delegate was touched",
                context.mutableClassDefBy(delegate).methods.single().code().none { it.referenceText() == HOLD_BACK_POST },
            )
        }
        val reel = context.mutableClassDefBy(handler).methods.single { it.name == "handleDoubleTap" }.code()
        assertAskedAfterRead("the Reels double tap", reel, "$handler->likeAction:$action")
        for (row in listOf(fbCommentRow, commentRow)) {
            assertCommentGuardFirst(row, context.mutableClassDefBy(row).methods.single { it.name == "onDoubleTap" }.code())
        }
        assertTrue(
            "a listener that isn't a comment row was touched",
            context.mutableClassDefBy(otherRow).methods.single().code().none { it.referenceText() == HOLD_BACK_COMMENT },
        )
        assertTrue(
            "the setter was touched",
            context.mutableClassDefBy(handler).methods.single { it.name == "setLikeAction" }.code().none { it.referenceText() == LIKE_ACTION },
        )
        assertGuardFirst("the message double tap", context.mutableClassDefBy(messageTap).methods.single().code(), HOLD_BACK_MESSAGE)
        assertTrue(
            "the tip's own read of its count was touched",
            context.mutableClassDefBy(messageTip).methods.single().code().none { it.referenceText() == HOLD_BACK_MESSAGE },
        )
    }

    /** 450's x86 build (385611439) asks a pool of shared strings for like_comment in its comment row (#95). */
    @Test
    fun aCommentRowAskingAPoolForItsLikeIsGuarded() {
        val context = PatchContexts.of(classes(pooledLike = true) + pool())

        context.turnOffDoubleTapLikes()

        for (row in listOf(fbCommentRow, commentRow)) {
            assertCommentGuardFirst(row, context.mutableClassDefBy(row).methods.single { it.name == "onDoubleTap" }.code())
        }
        assertTrue(
            "a listener that isn't a comment row was touched",
            context.mutableClassDefBy(otherRow).methods.single().code().none { it.referenceText() == HOLD_BACK_COMMENT },
        )
    }

    /** Without the pool there's nothing to read the like's name from, so that row is no comment row. */
    @Test
    fun aRowAskingAMissingPoolIsNoCommentRow() {
        val context = PatchContexts.of(classes(pooledLike = true))

        context.turnOffDoubleTapLikes()

        assertCommentGuardFirst(fbCommentRow, context.mutableClassDefBy(fbCommentRow).methods.single { it.name == "onDoubleTap" }.code())
        assertTrue(
            "the row whose like can't be read was touched",
            context.mutableClassDefBy(commentRow).methods.single().code().none { it.referenceText() == HOLD_BACK_COMMENT },
        )
    }

    /** A pool answering another name for the row's number doesn't make it a comment row. */
    @Test
    fun aRowAskingAPoolForAnotherNameIsNoCommentRow() {
        val context = PatchContexts.of(classes(pooledLike = true) + pool(answer = "like_media"))

        context.turnOffDoubleTapLikes()

        assertTrue(
            "the row asking for another name was touched",
            context.mutableClassDefBy(commentRow).methods.single().code().none { it.referenceText() == HOLD_BACK_COMMENT },
        )
    }

    /**
     * Every build's three comment rows, two filing fb_comment_double_tap and one filing like_comment
     * or unlike_comment, are found, wherever Redex left their names: 385611439 asks a pool for
     * like_comment (#95).
     */
    @Test
    fun eachBuildFindsItsThreeCommentRows() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        for (bundle in bundles) {
            val label = "${bundle.parentFile.name}/${bundle.name}"
            val rows = (FixtureDex.classesHolding(bundle, FB_COMMENT_DOUBLE_TAP) + FixtureDex.classesHolding(bundle, UNLIKE_COMMENT))
                .distinctBy { it.type }
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, rows))
            val taps = rows.filter { it.superclass == "Landroid/view/GestureDetector\$SimpleOnGestureListener;" }.flatMap { it.methods }
                .filter { it.name == "onDoubleTap" && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/MotionEvent;") }
            assertEquals("$label: comment rows", 3, taps.count { context.isCommentDoubleTap(it) })
        }
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(feeds = 0) to "found 0",
            classes(feeds = 2) to "found 2",
            classes(likeCalls = 0) to "calls 0 methods with a view and the post",
            classes(likeCalls = 2) to "calls 2 methods with a view and the post",
            classes(likeMissing = true) to "isn't in the app",
            classes(likeStatic = true) to "isn't an instance method",
            classes(carouselCalls = false) to "is called by 2 posts' delegates",
            classes(carouselCalls = false, videoCalls = false, lambdaCalls = true) to "is called by 1 posts' delegates",
            classes(mapCallsAnotherLike = true) to "a second like shaped as",
            classes(likeLocals = 0) to "has no local register",
            classes(handlers = 0) to "marked $HANDLE_DOUBLE_TAP, found 0",
            classes(setterElsewhere = true) to "isn't in $handler",
            classes(setterWrites = 2) to "writes 2 fields",
            classes(reads = 2) to "2 times",
            classes(checkedLater = true) to "doesn't check its like action for null straight after",
            classes(commentRows = false) to "no comment row's double tap",
            classes(messages = 0) to "\"$MESSAGE_DOUBLE_TAP\", found 0",
            classes(messages = 2) to "\"$MESSAGE_DOUBLE_TAP\", found 2",
            classes(messageAnswers = true) to "doesn't return nothing",
            classes(messageReactions = 0) to "to 0 reactions",
            classes(messageReactions = 2) to "to 2 reactions",
            classes(messageLocals = 0) to "has no register of its own",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.turnOffDoubleTapLikes() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.classDefByOrNull(type)?.methods?.toList().orEmpty() }
                .filter { method ->
                    method.code().any { it.referenceText() in listOf(HOLD_BACK_POST, LIKE_ACTION, HOLD_BACK_COMMENT, HOLD_BACK_MESSAGE) }
                }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the feed's double-tap like and the Reels double tap are found and
     * hooked. The like is the one every kind of post's delegate calls, not only the photo's the
     * patch finds it through: on 2026-09-30 a carousel on the S22 still liked with the guard in the
     * photo's delegate.
     */
    @Test
    fun eachDeclaredBuildGetsBothHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val posts = FixtureDex.classesHolding(bundle, FEED_DOUBLE_TAP)
                val post = posts.flatMap { it.methods }.single { method -> method.code().any { it.string() == FEED_DOUBLE_TAP } }
                val like = post.code().mapNotNull { it.likeShapedCall() }.map { it.text() }.distinct().single()
                val messageHolders = FixtureDex.classesHolding(bundle, MESSAGE_TIP_COUNT)
                val messageTaps = messageHolders.flatMap { it.methods }.filter { method ->
                    method.code().mapNotNull { it.string() }.let { MESSAGE_TIP_COUNT in it && MESSAGE_DOUBLE_TAP in it }
                }.map { it.text() }
                val messageTap = messageTaps.singleOrNull() ?: throw AssertionError("${bundle.name}: message double taps $messageTaps")
                val callers = mutableSetOf<String>()
                val messageCallers = mutableSetOf<String>()
                val likeClasses = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    // A dex that neither calls nor declares a method has no reference to it.
                    val likes = dex.methodSection.any { it.text() == like }
                    val reacts = dex.methodSection.any { it.text() == messageTap }
                    if (!likes && !reacts) return@forEach
                    for (classDef in dex.classes) {
                        if (reacts) {
                            messageCallers += classDef.methods.filter { method -> method.code().any { it.methodText() == messageTap } }.map { it.text() }
                        }
                        if (!likes) continue
                        val calling = classDef.methods.filter { method -> method.code().any { it.methodText() == like } }
                        callers += calling.map { it.text() }
                        if (calling.isNotEmpty() || classDef.type == like.substringBefore("->")) likeClasses += ImmutableClassDef.of(classDef)
                    }
                }
                // 450 has two: the helper the chat's gesture listeners share, and the Compose message list's double tap.
                assertTrue("${bundle.name}: callers of $messageTap: $messageCallers", messageCallers.size >= 2)
                // 449 has eight: the single photo, the carousel and five more kinds of post, and a lambda.
                assertTrue("${bundle.name}: callers of $like: $callers", callers.size >= 7)

                val commentRows = FixtureDex.classesHolding(bundle, FB_COMMENT_DOUBLE_TAP) +
                    FixtureDex.classesHolding(bundle, UNLIKE_COMMENT)
                val holders = (posts + likeClasses + FixtureDex.classesHolding(bundle, handleMarker) + commentRows + messageHolders)
                    .distinctBy { it.type }
                val context = PatchContexts.of(holders)
                val reelType = holders.single { holder -> holder.methods.any { method -> method.code().any { it.string() == handleMarker } } }.type

                context.turnOffDoubleTapLikes()

                val guarded = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() == HOLD_BACK_POST } }
                assertEquals("${bundle.name}: the methods asking before a like", listOf(like), guarded.map { it.text() })
                assertGuardFirst("${bundle.name}: the feed's double-tap like", guarded.single().code())
                val reels = context.mutableClassDefBy(reelType).methods.filter { method -> method.code().any { it.referenceText() == LIKE_ACTION } }
                assertEquals("${bundle.name}: one Reels double tap asks", 1, reels.size)
                val reel = reels.single()
                assertTrue("${bundle.name}: the Reels double tap", handleMarker in reel.code().mapNotNull { it.string() })
                val asked = reel.code().indexOfFirst { it.referenceText() == LIKE_ACTION }
                val read = reel.code()[asked - 1]
                assertEquals("${bundle.name}: what comes before the ask", Opcode.IGET_OBJECT, read.opcode)
                assertAskedAfterRead("${bundle.name}: the Reels double tap", reel.code(), read.referenceText()!!)
                // 450 has three: two rows that file fb_comment_double_tap, and one that files like_comment.
                val comments = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() == HOLD_BACK_COMMENT } }
                assertEquals("${bundle.name}: comment double taps", 3, comments.size)
                comments.forEach { assertCommentGuardFirst("${bundle.name}: ${it.definingClass}", it.code()) }
                // The tip's own reads of its count stay as they were; only the double tap asks.
                val messages = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() == HOLD_BACK_MESSAGE } }
                assertEquals("${bundle.name}: message double taps", listOf(messageTap), messages.map { it.text() })
                val message = messages.single().code()
                assertGuardFirst("${bundle.name}: the message double tap", message, HOLD_BACK_MESSAGE)
                val reactions = message.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .filter { call -> call.returnType == "V" && call.parameterTypes.any { it.toString() == messageId } }
                assertEquals("${bundle.name}: the message double tap's reactions", 1, reactions.size)
                assertTrue("${bundle.name}: ${reactions.single()}", reactions.single().parameterTypes.map(CharSequence::toString).contains("Ljava/util/List;"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGuardFirst(what: String, code: List<Instruction>, hook: String = HOLD_BACK_POST) {
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
            code.take(4).map { it.opcode },
        )
        assertEquals("$what: the hook called", hook, code[0].referenceText())
    }

    private fun assertCommentGuardFirst(what: String, code: List<Instruction>) {
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", HOLD_BACK_COMMENT, code[0].referenceText())
    }

    /** The one read of [field] is followed by the ask, its answer back in the same register, a cast, then the null check. */
    private fun assertAskedAfterRead(what: String, code: List<Instruction>, field: String) {
        val reads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].referenceText() == field }
        assertEquals("$what: reads of the like action", 1, reads.size)
        val read = reads.single()
        val register = (code[read] as TwoRegisterInstruction).registerA
        val ask = code[read + 1] as RegisterRangeInstruction
        assertEquals("$what: the ask", LIKE_ACTION, code[read + 1].referenceText())
        assertEquals("$what: the ask hands over the read", listOf(register, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[read + 2].opcode)
        assertEquals("$what: the answer's register", register, (code[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[read + 3].opcode)
        assertEquals("$what: the cast's type", field.substringAfter(":"), ((code[read + 3] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the null check follows", Opcode.IF_EQZ, code[read + 4].opcode)
        assertEquals("$what: the null check's register", register, (code[read + 4] as OneRegisterInstruction).registerA)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The single photo's gesture delegate, whose onDoubleTapMedia files its report without an
     * activity, hands the double tap to the shared like and then shows the photo's product tags; the
     * carousel's and the video's delegates hand it to the same like. Each delegate is handed the
     * post and the like's other arguments beside a state of its own. A map's delegate likes nothing,
     * and a lambda takes Objects. The Reels gesture handler's double tap reads the like action and
     * skips the like without one, and its setter stores that action.
     */
    private fun classes(
        feeds: Int = 1,
        likeCalls: Int = 1,
        likeMissing: Boolean = false,
        likeStatic: Boolean = false,
        carouselCalls: Boolean = true,
        videoCalls: Boolean = true,
        lambdaCalls: Boolean = false,
        mapCallsAnotherLike: Boolean = false,
        likeLocals: Int = 1,
        handlers: Int = 1,
        setterElsewhere: Boolean = false,
        setterWrites: Int = 1,
        reads: Int = 1,
        checkedLater: Boolean = false,
        commentRows: Boolean = true,
        pooledLike: Boolean = false,
        messages: Int = 1,
        messageAnswers: Boolean = false,
        messageReactions: Int = 1,
        messageLocals: Int = 2,
    ): List<ClassDef> {
        val invoke = if (likeStatic) "invoke-static { v0, v0, p1, v2 }" else "invoke-virtual { v0, v0, v0, p1, v2 }"
        val likeCall = listOf("like", "likeAgain").take(likeCalls).joinToString("\n") { "$invoke, $liker->$it$likeShape" }
        val delegateOf = { state: String -> listOf("Lcom/instagram/feed/media/Media;", "Ljava/lang/Object;", state, "I") }
        val feedClass = classDef(
            feed,
            (0 until feeds).map { copy ->
                method(feed, if (copy == 0) "onDoubleTap" else "onDoubleTapAgain", delegateOf("Lfixture/PhotoState;"), "V", 8, """
                    const-string v0, "$FEED_DOUBLE_TAP"
                    const/4 v0, 0x0
                    const/4 v2, 0x0
                    $likeCall
                    invoke-static { v0, v0 }, $tags->show(Landroid/content/Context;Lcom/instagram/feed/media/Media;)V
                    return-void
                """)
            },
        )
        val delegate = { type: String, state: String, calls: String? ->
            classDef(
                type,
                listOf(
                    method(type, "onDoubleTap", delegateOf(state), "V", 8, """
                        const/4 v0, 0x0
                        const/4 v2, 0x0
                        ${if (calls != null) "$invoke, $liker->$calls$likeShape" else ""}
                        return-void
                    """),
                ),
            )
        }
        val carouselClass = delegate(carousel, "Lfixture/CarouselState;", if (carouselCalls) "like" else null)
        val videoClass = delegate(video, "Lfixture/VideoState;", if (videoCalls) "like" else null)
        val mapClass = delegate("Lfixture/MapDelegate;", "Lfixture/MapState;", if (mapCallsAnotherLike) "likeAgain" else null)
        val lambdaClass = classDef(
            "Lfixture/LikeLambda;",
            listOf(
                method("Lfixture/LikeLambda;", "invoke", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "V", 5, """
                    const/4 v0, 0x0
                    const/4 v2, 0x0
                    ${if (lambdaCalls) "$invoke, $liker->like$likeShape" else ""}
                    return-void
                """),
            ),
        )
        val likeFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (likeStatic) AccessFlags.STATIC.value else 0)
        val likeParameters = listOf("Landroid/view/View;", "Lcom/instagram/feed/media/Media;", "Ljava/lang/Object;", "I")
        val likeRegisters = likeLocals + likeParameters.size + (if (likeStatic) 0 else 1)
        val likerClass = classDef(
            liker,
            listOf("like", "likeAgain").map { name -> method(liker, name, likeParameters, "V", likeRegisters, "return-void", likeFlags) },
        )
        val readAndCheck = if (checkedLater) {
            """
                iget-object v1, p0, $handler->likeAction:$action
                const/4 v0, 0x0
                if-eqz v1, :done
            """
        } else {
            """
                iget-object v1, p0, $handler->likeAction:$action
                if-eqz v1, :done
            """
        }
        val secondRead = if (reads > 1) "iget-object v0, p0, $handler->likeAction:$action" else ""
        val handle = (0 until handlers).map { copy ->
            method(handler, if (copy == 0) "handleDoubleTap" else "handleDoubleTapAgain", emptyList(), "V", 3, """
                const-string v0, "$handleMarker"
                $secondRead
                $readAndCheck
                invoke-interface { v1 }, $action->invoke()V
                :done
                return-void
            """)
        }
        val setterBody = """
            const-string v0, "$setterMarker"
            iput-object p1, p0, $handler->likeAction:$action
            ${if (setterWrites > 1) "iput-object p1, p0, $handler->otherLikeAction:$action" else ""}
            return-void
        """
        val setterOwner = if (setterElsewhere) "Lfixture/OtherHandler;" else handler
        val setter = method(setterOwner, "setLikeAction", listOf(action), "V", 3, setterBody)
        val handlerClass = classDef(handler, handle + (if (setterElsewhere) emptyList() else listOf(setter)))
        // A comment row that files fb_comment_double_tap, one that files like_comment or
        // unlike_comment, and a listener that files only like_comment, which isn't a comment row.
        val rowOf = { type: String, strings: List<String> ->
            listenerDef(type, method(type, "onDoubleTap", listOf("Landroid/view/MotionEvent;"), "Z", 4,
                strings.joinToString("\n") { "const-string v1, \"$it\"" } + "\nconst/4 v0, 0x1\nreturn v0"))
        }
        // With [pooledLike] the second row asks [pool] for like_comment, as 450's 385611439 does.
        val askedLike = listOf(
            "const/16 v1, 0x$POOLED",
            "invoke-static { v1 }, $POOL->A00(I)Ljava/lang/String;",
            "move-result-object v1",
            "const-string v1, \"$UNLIKE_COMMENT\"",
            "const/4 v0, 0x1",
            "return v0",
        ).joinToString("\n")
        val rows = if (commentRows) listOf(
            rowOf(fbCommentRow, listOf("comment_row_component", FB_COMMENT_DOUBLE_TAP)),
            if (pooledLike) listenerDef(commentRow, method(commentRow, "onDoubleTap", listOf("Landroid/view/MotionEvent;"), "Z", 4, askedLike))
            else rowOf(commentRow, listOf(LIKE_COMMENT, UNLIKE_COMMENT)),
        ) else emptyList()
        // The chat's double tap on a message loads the tip count and its source, then hands the
        // message's id to a reaction. The tip's own read of the count loads no source.
        val react = "invoke-interface { v1, v1, v1 }, Lfixture/Reactions;->react(Ljava/lang/Object;$messageId)V"
        val messageParameters = listOf("Lfixture/Reactions;", "Ljava/util/List;")
        val messageFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val messageClass = classDef(
            messageTap,
            (0 until messages).map { copy ->
                method(messageTap, if (copy == 0) "react" else "reactAgain", messageParameters, if (messageAnswers) "Z" else "V",
                    messageLocals + messageParameters.size, """
                        const-string v0, "$MESSAGE_TIP_COUNT"
                        const-string v0, "$MESSAGE_DOUBLE_TAP"
                        const/4 v1, 0x0
                        ${List(messageReactions) { react }.joinToString("\n")}
                        ${if (messageAnswers) "return v1" else "return-void"}
                    """, messageFlags)
            },
        )
        val tipClass = classDef(
            messageTip,
            listOf(method(messageTip, "shouldShowTip", listOf("Ljava/lang/Object;"), "Z", 3, """
                const-string v0, "$MESSAGE_TIP_COUNT"
                const/4 v1, 0x0
                $react
                return v1
            """)),
        )
        return rows + rowOf(otherRow, listOf(LIKE_COMMENT)) + listOfNotNull(
            messageClass,
            tipClass,
            feedClass,
            carouselClass,
            videoClass,
            mapClass,
            lambdaClass,
            if (likeMissing) null else likerClass,
            handlerClass,
            if (setterElsewhere) classDef(setterOwner, listOf(setter)) else null,
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    /**
     * A pool of shared strings as 450's Redex writes it: a static (int)String switch answering
     * [answer] for [POOLED]. p0 is v1.
     */
    private fun pool(answer: String = LIKE_COMMENT): ClassDef = ImmutableClassDef(
        POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(ImmutableMethod(
            POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(answer)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
            ), null, null),
        )),
    )

    private fun listenerDef(type: String, method: Method): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Landroid/view/GestureDetector\$SimpleOnGestureListener;",
        null, null, null, emptyList(), listOf(method),
    )

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun Instruction.methodText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.text()

    private fun Instruction.likeShapedCall(): MethodReference? =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call ->
            call.returnType == "V" &&
                call.parameterTypes.take(2).map(CharSequence::toString) == listOf("Landroid/view/View;", "Lcom/instagram/feed/media/Media;")
        }

    private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private companion object {
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7
    }
}
