/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File

/**
 * Stand-ins for what Mark a story seen when you like or reply (#876) hooks, shaped like 457's
 * 386314341: the story like store and its request builder (02ps.A0S), the like button's handler
 * that loads both like names and calls the builder (07V6.Fqq), the quick reaction (069D.A00)
 * and the typed reply (020U.A06) both sending through the story reply send (02yd.A15), the viewer's
 * flush by count (06W8) and the flusher it's made with (06W7), Media and the extension's class.
 */
internal object StoryAnswerFixtures {
    const val LIKE_STORE = "Lfixture/StoryLikeStore;"
    const val LIKE_CONTEXT = "Lfixture/StoryLikeContext;"
    const val LIKE_ACTION = "Lfixture/StoryLikeAction;"
    const val LIKE_REQUEST = "Lfixture/StoryLikeRequest;"
    const val LIKE_BUTTON = "Lfixture/StoryLikeButton;"
    const val REACTION = "Lfixture/StoryQuickReactions;"
    const val COMPOSER = "Lfixture/StoryReplyComposer;"
    const val REPLY_SENDER = "Lfixture/StoryReplySender;"
    const val FLUSH_BY_COUNT = "Lfixture/StorySeenFlushByCount;"
    const val FLUSHER = "Lfixture/StorySeenFlusher;"
    const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"
    const val SHARE_TARGET = "Lcom/instagram/model/direct/DirectShareTarget;"
    const val OBJECT_TYPE = "Ljava/lang/Object;"
    const val STRING_TYPE = "Ljava/lang/String;"

    /** The like builder's parameters, as 457's: the request context, the action, a count, the story's id and four more Strings, an int. */
    val LIKE_PARAMETERS = listOf(
        LIKE_CONTEXT, LIKE_ACTION, "Ljava/lang/Integer;", STRING_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE, "I",
    )

    /** The story reply send's parameters: the account, the story, its target, a flag and one String. */
    val REPLY_PARAMETERS = listOf(USER_SESSION, MEDIA_TYPE, SHARE_TARGET, "Ljava/lang/Boolean;", STRING_TYPE)

    /**
     * The stand-ins, sending the viewer's views through [store]'s [send] of a [batch]. Each option
     * breaks one thing the finder checks.
     */
    fun standIns(
        store: String,
        batch: String,
        send: String,
        like: Boolean = true,
        likeLoads: Int = 1,
        likeBranchesToSend: Boolean = false,
        likeOverwritesThis: Boolean = false,
        likeOverwritesId: Boolean = false,
        likeLocals: Int = 2,
        likeStatic: Boolean = false,
        likeButton: Boolean = true,
        likeButtonCalls: Boolean = true,
        reaction: Boolean = true,
        reactionSenders: Int = 1,
        typedCallsSender: Boolean = true,
        senderBranchesToStart: Boolean = false,
        flush: Boolean = true,
        flushIsConstructor: Boolean = true,
        flushReturns: Int = 1,
        flushOverwritesFlusher: Boolean = false,
        flusherSends: Boolean = true,
        flusherPublic: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
        val likeBody = """
            ${if (likeBranchesToSend) "const/4 v1, 0x0" else "nop"}
            ${if (likeBranchesToSend) "if-eqz v1, :send" else "nop"}
            sget-object v0, $LIKE_ACTION->LIKED:$LIKE_ACTION
            if-eqz v0, :unsend
            ${if (likeOverwritesThis) "const/4 p0, 0x0" else "nop"}
            ${if (likeOverwritesId) "const/4 p4, 0x0" else "nop"}
            :send
            const-string v0, "$STORY_LIKE_SEND"
            ${if (likeLoads > 1) "const-string v1, \"$STORY_LIKE_SEND\"" else "nop"}
            invoke-static { v0 }, $LIKE_STORE->request($STRING_TYPE)$LIKE_REQUEST
            move-result-object v0
            return-object v0
            :unsend
            const-string v0, "$STORY_UNLIKE_SEND"
            invoke-static { v0 }, $LIKE_STORE->request($STRING_TYPE)$LIKE_REQUEST
            move-result-object v0
            return-object v0
        """
        val likeStore = if (like) {
            answerClass(
                LIKE_STORE, emptyList(),
                listOf(
                    answerMethod(
                        LIKE_STORE, "A0S", LIKE_PARAMETERS, LIKE_REQUEST, likeLocals + 1 + LIKE_PARAMETERS.size, likeBody,
                        static = likeStatic,
                    ),
                    answerMethod(LIKE_STORE, "A0J", emptyList(), USER_SESSION, 2, """
                        const/4 v0, 0x0
                        return-object v0
                    """, static = false),
                    answerMethod(LIKE_STORE, "request", listOf(STRING_TYPE), LIKE_REQUEST, 2, """
                        const/4 v0, 0x0
                        return-object v0
                    """),
                ),
            )
        } else {
            null
        }
        // The like button's handler (07V6.Fqq on 386314341) loads both names too and calls the builder.
        val button = if (like && likeButton) {
            answerClass(
                LIKE_BUTTON, emptyList(),
                listOf(
                    answerMethod(LIKE_BUTTON, "Fqq", listOf(LIKE_STORE), "V", 12, """
                        const-string v1, "$STORY_LIKE_SEND"
                        const-string v2, "$STORY_UNLIKE_SEND"
                        move-object v0, p1
                        ${if (likeButtonCalls) "invoke-virtual/range { v0 .. v9 }, $LIKE_STORE->A0S(${LIKE_PARAMETERS.joinToString("")})$LIKE_REQUEST" else "nop"}
                        return-void
                    """, static = false),
                ),
            )
        } else {
            null
        }
        val sendCall ="invoke-static { p0, p1, p2, v1, v0 }, $REPLY_SENDER->A15(${REPLY_PARAMETERS.joinToString("")})V"
        val otherSendCall = "invoke-static { p0, p1, p2, v1, v0 }, $REPLY_SENDER->A16(${REPLY_PARAMETERS.joinToString("")})V"
        val reactions = if (reaction) {
            answerClass(
                REACTION, emptyList(),
                listOf(
                    answerMethod(REACTION, "A00", listOf(USER_SESSION, MEDIA_TYPE, SHARE_TARGET), "V", 5, """
                        const-string v0, "$STORY_QUICK_REACTION"
                        const/4 v1, 0x0
                        $sendCall
                        ${if (reactionSenders > 1) otherSendCall else "nop"}
                        return-void
                    """),
                ),
            )
        } else {
            null
        }
        val composer = answerClass(
            COMPOSER, emptyList(),
            listOf(
                answerMethod(COMPOSER, "A06", listOf(USER_SESSION, MEDIA_TYPE, SHARE_TARGET), "V", 5, """
                    const-string v0, "$STORY_TEXT_REPLY"
                    const/4 v1, 0x0
                    ${if (typedCallsSender) sendCall else "nop"}
                    return-void
                """),
            ),
        )
        val senderBody = """
            :start
            const-string v0, "reel"
            const/4 v1, 0x0
            ${if (senderBranchesToStart) "if-eqz v1, :start" else "nop"}
            return-void
        """
        val sender = answerClass(
            REPLY_SENDER, emptyList(),
            listOf(
                answerMethod(REPLY_SENDER, "A15", REPLY_PARAMETERS, "V", 3 + REPLY_PARAMETERS.size, senderBody),
                answerMethod(REPLY_SENDER, "A16", REPLY_PARAMETERS, "V", 3 + REPLY_PARAMETERS.size, senderBody),
            ),
        )
        val flushBody = """
            const-string v0, "$STORY_SEEN_FLUSH"
            invoke-direct { p0 }, $OBJECT_TYPE-><init>()V
            iput-object p2, p0, $FLUSH_BY_COUNT->flusher:$FLUSHER
            iput-object p1, p0, $FLUSH_BY_COUNT->session:$USER_SESSION
            ${if (flushReturns > 1) "if-nez p1, :kept" else "nop"}
            ${if (flushReturns > 1) "return-void" else "nop"}
            :kept
            ${if (flushOverwritesFlusher) "const/4 p2, 0x0" else "nop"}
            return-void
        """
        val flushByCount = if (flush) {
            answerClass(
                FLUSH_BY_COUNT,
                listOf(answerField(FLUSH_BY_COUNT, "flusher", FLUSHER), answerField(FLUSH_BY_COUNT, "session", USER_SESSION)),
                listOf(
                    answerMethod(
                        FLUSH_BY_COUNT, if (flushIsConstructor) "<init>" else "A01", listOf(USER_SESSION, FLUSHER), "V", 4, flushBody,
                        static = false, constructor = flushIsConstructor,
                    ),
                ),
            )
        } else {
            null
        }
        val flusher = answerClass(
            FLUSHER,
            listOf(answerField(FLUSHER, "session", USER_SESSION)),
            listOf(
                answerMethod(FLUSHER, "<init>", listOf(USER_SESSION, OBJECT_TYPE), "V", 3, """
                    invoke-direct { p0 }, $OBJECT_TYPE-><init>()V
                    iput-object p1, p0, $FLUSHER->session:$USER_SESSION
                    return-void
                """, static = false, constructor = true),
                answerMethod(FLUSHER, "A00", emptyList(), "V", 3, """
                    iget-object v0, p0, $FLUSHER->session:$USER_SESSION
                    new-instance v1, $batch
                    invoke-direct { v1 }, $batch-><init>()V
                    ${if (flusherSends) "invoke-static { v0 }, $store->A00($USER_SESSION)$store" else "nop"}
                    ${if (flusherSends) "move-result-object v0" else "nop"}
                    ${if (flusherSends) "invoke-virtual { v0, v1 }, $store->$send($batch)V" else "nop"}
                    return-void
                """, static = false, public = flusherPublic),
            ),
        )
        val media = answerClass(
            MEDIA_TYPE, emptyList(),
            listOf(
                answerMethod(MEDIA_TYPE, "getId", emptyList(), STRING_TYPE, 2, """
                    const/4 v0, 0x0
                    return-object v0
                """, static = false),
            ),
        )
        return listOfNotNull(
            likeStore, button, reactions, composer, sender, flushByCount, flusher, media,
            if (extension) ImmutableClassDef.of(ExtensionDex.classDef(STORY_ANSWER_SEEN)) else null,
        )
    }

    /** The strings the classes Mark a story seen when you like or reply reads hold, directly or through a Redex pool. */
    val STRINGS = setOf(STORY_LIKE_SEND, STORY_UNLIKE_SEND, STORY_QUICK_REACTION, STORY_TEXT_REPLY, STORY_SEEN_FLUSH)

    /**
     * The classes Mark a story seen when you like or reply reads on [bundle], past those in [have]:
     * every class holding or asking a pool for one of [STRINGS], the story reply send's class they
     * call, the flusher the viewer's flush by count is made with, the superclasses of them all (where
     * the like store's account getter may sit), Media, the pools, and the extension's class.
     */
    fun fixtureClasses(bundle: File, have: Set<String>): List<ClassDef> {
        val found = LinkedHashMap<String, ClassDef>()
        for (classDef in FixtureDex.classesLoadingAny(bundle, STRINGS)) if (classDef.type !in have) found[classDef.type] = classDef
        val methods = found.values.flatMap { it.methods }
        val senders = methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.returnType == "V" && it.parameterTypes.map(Any::toString).take(STORY_REPLY_SHAPE.size) == STORY_REPLY_SHAPE }
            .map { it.definingClass }
        val flushers = methods.filter { it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == USER_SESSION }
            .map { it.parameterTypes[1].toString() }
        fun unseen(types: Collection<String>) = types.filter { it.startsWith("L") && it !in have && it !in found && it != OBJECT_TYPE }.toSet()
        var wanted = unseen(senders + flushers + MEDIA_TYPE + found.values.mapNotNull { it.superclass })
        repeat(4) {
            if (wanted.isEmpty()) return@repeat
            val loaded = FixtureDex.classes(bundle, wanted)
            found += loaded
            wanted = unseen(loaded.values.mapNotNull { it.superclass })
        }
        return FixtureDex.withStringPools(bundle, found.values) + ImmutableClassDef.of(ExtensionDex.classDef(STORY_ANSWER_SEEN))
    }

    private fun answerMethod(
        owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        static: Boolean = true, constructor: Boolean = false, public: Boolean = true,
    ): Method {
        val flags = (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
            (if (static) AccessFlags.STATIC.value else 0) or
            (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun answerField(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)

    private fun answerClass(type: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT_TYPE, emptyList(), null, null, fields, methods)
}
