/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.mentions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.accountBridges
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.time.STORY_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PATCH = "See who a story mentions"
internal const val STORY_MENTIONS = "$EXTENSION_PACKAGE/stories/StoryMentions;"
internal const val BIND = "$STORY_MENTIONS->bind(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val PILL_TOUCH = "$STORY_MENTIONS->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)Z"

/**
 * The trace name of the step where the story viewer's item binder gives one of its pages the story
 * it shows. Both of 450's story viewers load it, the main one and the catch-up one.
 */
internal const val BIND_MEDIA = "ReelViewerItemBinder.bindMedia"

/** The view a story viewer's page shows its story's media in. Instagram keeps its name. */
internal const val REEL_VIEW_GROUP = "Lcom/instagram/reels/viewer/common/ReelViewGroup;"

/** The key of a story's list of the accounts it mentions, whose getter on Media holds its hash. */
internal const val REEL_MENTIONS = "reel_mentions"
internal const val FULL_NAME = "full_name"

/** The stubs in StoryMentions the patch writes. */
internal val STUBS = listOf("itemView", "media", "mentions", "mentionUser", "fullName")

private const val LIST = "Ljava/util/List;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val STRING = "Ljava/lang/String;"

/**
 * See who a story mentions (#44): a pill under the name in a story's header saying how many
 * accounts the story mentions, from the story's own list, and a list of them on a tap. In the
 * default selection with its switch off, since it adds to Instagram's own header.
 */
@Suppress("unused")
val storyMentionsPatch = bytecodePatch(
    name = "See who a story mentions",
    description = "Adds a small label in a story's header showing how many accounts it mentions. Tap it for a " +
        "list and tap an account to open their profile. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyMentions")
        val found = findStoryMentions()
        val touch = findPillTouch()
        val stubs = storyMentionStubs()
        val pictures = accountBridges(PATCH)
        stubs.fill(found)
        pictures()
        hookStoryBinds(found)
        hookPillTouch(touch)
        enableStatus("storyMentions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * One of the story viewer's binders: in [type]'s method [name], the instruction at [index] puts
 * the story, in register [item], into the page, in register [page].
 */
internal class StoryBind(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val index: Int,
    val page: Int,
    val item: Int,
)

/**
 * What the patch found: the binders, the page's type and its field holding its [REEL_VIEW_GROUP],
 * the story item's field holding its Media, Media's getter of [REEL_MENTIONS], the public interface
 * every mention implements and its getter of the account, and the account's getter of [FULL_NAME]. All of them
 * public, since the stubs reach them from the extension's package.
 */
internal class StoryMentionSites(
    val binds: List<StoryBind>,
    val page: String,
    val view: String,
    val media: String,
    val mentions: String,
    val mention: String,
    val mentionUser: String,
    val fullName: String,
)

/**
 * Finds every method that loads [BIND_MEDIA] and, after it, puts a story item into a field of
 * exactly one type, the viewer's page. The page keeps its media view in one public field, and the
 * binders read the story's Media out of one public field of the story item. Media's getter of
 * [REEL_MENTIONS] names the type of each mention with a const-class, and that type implements one public
 * interface with one abstract getter answering the account. Fails before anything changes when any of it isn't there exactly once,
 * since that's an Instagram this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryMentions(): StoryMentionSites {
    val binders = classesHolding(BIND_MEDIA).flatMap { classDef ->
        classDef.methods.filter { method -> method.code().any { it.loadsString(BIND_MEDIA) } }.map { classDef.type to it }
    }
    if (binders.isEmpty()) refuse("no method loads $BIND_MEDIA")

    val binds = binders.map { (type, method) ->
        val code = method.code()
        val named = code.indexOfFirst { it.loadsString(BIND_MEDIA) }
        val puts = (named + 1 until code.size).filter { index ->
            code[index].opcode == Opcode.IPUT_OBJECT && code[index].fieldReference()?.type == STORY_ITEM
        }
        val put = puts.singleOrNull()
            ?: refuse("expected $type->${method.name} to put one story item into its page after $BIND_MEDIA, found ${puts.size}")
        val registers = code[put] as TwoRegisterInstruction
        StoryBind(type, method.name, method.parameterTypes.map(CharSequence::toString), put, registers.registerB, registers.registerA) to
            code[put].fieldReference()!!.definingClass
    }
    val pages = binds.map { it.second }.distinct()
    val page = pages.singleOrNull() ?: refuse("the binders put the story into more than one type of page: ${pages.joinToString()}")

    val pageClass = classDefBy(page)
    val views = pageClass.fields.filter { it.type == REEL_VIEW_GROUP && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val view = views.singleOrNull() ?: refuse("expected one $REEL_VIEW_GROUP field on $page, found ${views.size}")
    if (!AccessFlags.PUBLIC.isSet(pageClass.accessFlags) || !AccessFlags.PUBLIC.isSet(view.accessFlags)) {
        refuse("$page or its field ${view.name} isn't public")
    }

    val reads = binders.flatMap { (_, method) ->
        method.code().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.fieldReference() }
            .filter { it.definingClass == STORY_ITEM && it.type == MEDIA }.map { it.name }
    }.distinct()
    val media = reads.singleOrNull() ?: refuse("expected the binders to read one Media field of $STORY_ITEM, found ${reads.size}")
    if (classDefBy(STORY_ITEM).fields.none { it.name == media && it.type == MEDIA && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        refuse("$STORY_ITEM->$media isn't a public field")
    }

    val mentions = pandoGetter(PATCH, MEDIA, REEL_MENTIONS, LIST)
    val types = mentions.code().filter { it.opcode == Opcode.CONST_CLASS }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
    val mention = types.singleOrNull()
        ?: refuse("expected $MEDIA->${mentions.name} to name one type of mention, found ${types.size}")
    val mentionClass = classDefBy(mention)
    // Media's getter answers its cached list when one is set, and a story Instagram parsed from JSON
    // fills that list with a different class than the one named here. Both implement the same public
    // interface, whose one abstract getter answers the account, so the stubs read it through that.
    val holders = mentionClass.interfaces.mapNotNull { name ->
        val declared = classDefByOrNull(name) ?: return@mapNotNull null
        if (!AccessFlags.INTERFACE.isSet(declared.accessFlags)) return@mapNotNull null
        val getters = declared.methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == USER && AccessFlags.ABSTRACT.isSet(it.accessFlags) &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        getters.singleOrNull()?.let { declared to it }
    }
    val (shared, mentionUser) = holders.singleOrNull()
        ?: refuse("expected one interface of $mention with one getter of the account, found ${holders.size}: ${holders.joinToString { it.first.type }}")
    val mentionInterface = shared.type
    val fullName = pandoGetter(PATCH, USER, FULL_NAME, STRING)

    // The stubs cast to these and call these from the extension's package, so a private one would
    // throw IllegalAccessError on every bind.
    val closed = listOfNotNull(
        STORY_ITEM.takeUnless { AccessFlags.PUBLIC.isSet(classDefBy(STORY_ITEM).accessFlags) },
        MEDIA.takeUnless { AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) },
        "$MEDIA->${mentions.name}".takeUnless { AccessFlags.PUBLIC.isSet(mentions.accessFlags) },
        mentionInterface.takeUnless { AccessFlags.PUBLIC.isSet(shared.accessFlags) },
        "$mentionInterface->${mentionUser.name}".takeUnless { AccessFlags.PUBLIC.isSet(mentionUser.accessFlags) },
        USER.takeUnless { AccessFlags.PUBLIC.isSet(classDefBy(USER).accessFlags) },
        "$USER->${fullName.name}".takeUnless { AccessFlags.PUBLIC.isSet(fullName.accessFlags) },
    )
    if (closed.isNotEmpty()) refuse("${closed.joinToString()} isn't public, so the extension can't reach it")

    return StoryMentionSites(
        binds.map { it.first }, page, view.name, media, mentions.name, mentionInterface, mentionUser.name, fullName.name,
    )
}

/** StoryMentions' stubs, which the patch writes once everything is found. */
internal class StoryMentionStubs(private val stubs: Map<String, MutableMethod>) {
    fun fill(found: StoryMentionSites) {
        write("itemView", "check-cast p0, ${found.page}", "iget-object p0, p0, ${found.page}->${found.view}:$REEL_VIEW_GROUP")
        write("media", "check-cast p0, $STORY_ITEM", "iget-object p0, p0, $STORY_ITEM->${found.media}:$MEDIA")
        write("mentions", "check-cast p0, $MEDIA", "invoke-virtual { p0 }, $MEDIA->${found.mentions}()$LIST", "move-result-object p0")
        write("mentionUser", "check-cast p0, ${found.mention}", "invoke-interface { p0 }, ${found.mention}->${found.mentionUser}()$USER", "move-result-object p0")
        write("fullName", "check-cast p0, $USER", "invoke-virtual { p0 }, $USER->${found.fullName}()$STRING", "move-result-object p0")
    }

    /** Puts [lines] and a return of p0 in front of the stub's own `return null`, which is then never reached. */
    private fun write(name: String, vararg lines: String) {
        stubs.getValue(name).addInstructions(0, (lines.toList() + "return-object p0").joinToString("\n"))
    }
}

/** Finds each of [STUBS] in StoryMentions: static, taking one Object. */
internal fun BytecodePatchContext.storyMentionStubs(): StoryMentionStubs {
    val methods = mutableClassDefBy(STORY_MENTIONS).methods
    return StoryMentionStubs(STUBS.associateWith { name ->
        methods.singleOrNull {
            it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        } ?: refuse("$STORY_MENTIONS has no static $name(Object)")
    })
}

/**
 * Right after each binder puts the story into its page, hands both to [BIND]. The put is an
 * `iput-object`, whose two registers fit in four bits each, so the invoke names them as they are:
 * nothing is copied, no local is borrowed, and nothing here can refuse after [findStoryMentions] passes.
 */
internal fun BytecodePatchContext.hookStoryBinds(found: StoryMentionSites) {
    for (bind in found.binds) {
        val method = mutableClassDefBy(bind.type).methods.single {
            it.name == bind.name && it.parameterTypes.map(CharSequence::toString) == bind.parameters
        }
        method.addInstructions(bind.index + 1, "invoke-static { v${bind.page}, v${bind.item} }, $BIND")
    }
}

/**
 * IgFragmentActivity's own dispatchTouchEvent, which every touch on an Instagram screen passes
 * through before any of its views. The story viewer takes each touch on a story for its own taps
 * and swipes before the header sees it (#125), so a tap on the pill is caught here instead. The
 * hook's answer goes in the method's first local, which is written before it's read.
 */
internal fun BytecodePatchContext.findPillTouch(): MutableMethod {
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val touches = activity.methods.filter {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) &&
            it.implementation != null
    }
    val touch = touches.singleOrNull() ?: refuse("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")
    if (touch.localRegisterCount() < 1) refuse("$FRAGMENT_ACTIVITY->dispatchTouchEvent has no local register for the hook's answer")
    return mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single {
        it.name == touch.name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
    }
}

/**
 * First thing in [touch], hands the activity and the event to [PILL_TOUCH]. When it answers true
 * the touch was on the pill and is answered as handled. Otherwise the code after it runs as it was.
 * Tap to play reads every touch here too and is held first, so when its call is already in, this
 * one goes right after it. When Tap to play comes later, it goes in front of this one.
 */
internal fun hookPillTouch(touch: MutableMethod) {
    val first = (touch.getInstruction(0) as? ReferenceInstruction)?.reference as? MethodReference
    val at = if (first != null && "${first.definingClass}->${first.name}(${first.parameterTypes.joinToString("")})${first.returnType}" == TAP_CLOCK) 1 else 0
    touch.addInstructionsWithLabels(
        at,
        """
            invoke-static/range { p0 .. p1 }, $PILL_TOUCH
            move-result v0
            if-eqz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", touch.getInstruction(at)),
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
