/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.typesMarked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Clean up Reels"

/*
 * Instagram 456 keeps only a few of the purge markers the parts used to be found by. A part whose
 * marker is gone is found by what its method still loads or does instead, which no other method of
 * its kind shares on any 456 build: a name string or a resource id its render loads, or the place the
 * part's builder has in a class found that way. A marker still held always wins.
 */

/** Parts whose render is the one loading a string of its own, by marker. */
internal val RENDER_STRINGS = mapOf(
    "ClipsBaselAttributionPillComponent_render" to "ClipsBaselAttributionPillComponent",
    "ClipsBaselCreativeToolAttributionPillComponent_render" to "ClipsBaselCreativeToolAttributionPillComponent",
    "ClipsBaselPlatformizedCreativeToolAttributionComponent_render" to "ClipsBaselPlatformizedCreativeToolAttributionPillComponent",
    "StoriesTemplatePillComponent_render" to "STORIES_TEMPLATE_PILL",
    "ClipsWearablesAttributionBluePillComponent_render" to "ClipsWearablesAttributionBluePillComponent",
    "ClipsWearablesAttributionSemiTransparentPillComponent_render" to "ClipsWearablesAttributionSemiTransparentPillComponent",
    "ClipsContentDeepDiveComponent_render" to "content_deep_dive_icon",
    "ProductTagCTAComponent_render" to "product_tag_cta_component",
    "RelatedAdsPivotCTAComponent_render" to "Shop with Muse",
    "ShopLensCtaComponent_render" to "ShopLensCta",
    // 457 dropped the bar's render marker; its render still names the card it opens.
    "ShopLensBarCtaComponent_render" to "ShopNowCardCta",
    "ShopLensPausePillComponent_render" to "ShopLensPausePill",
    "ClipsFriendlyViewerComponent_render" to "clips_friendly_viewer_component",
    // 457 dropped the comment preview's render marker; its render still names its text component.
    "ClipsInlineCommentSocialContextComponent_render" to "clips_inline_comment_text_component",
)

/** Parts whose render is the one loading a resource id or a flag of its own, by marker. */
internal val RENDER_LITERALS = mapOf(
    // 457 moved it from 456's 0x8112950000677b.
    "ClipsFollowButtonComponent_render" to 0x81126f00006726L,
    "ClipsMetaAiPillComponent_render" to 0x7f0801deL,
    "PollCTAComponent_render" to 0x7f1102f5L,
)

private const val SUBSCRIPTION = "SubscriptionCtaButtonComponent_render"
private const val AFFILIATE = "MetaAffiliateCTAComponent_render"
private const val RBM_BLACK = "ClipsWearablesAttributionShopRBMGlassesBlackPillComponent_render"
private const val RBM_SEMI = "ClipsWearablesAttributionShopRBMGlassesSemiTransparentPillComponent_render"
private const val SHOPPING_CTA = "ShoppingCtaComponent_render"
private const val BUBBLES_CHECK = "ClipsInfoOverlayUseCase_shouldShowFloatingBubblesAboveUsername"

/** The author-info component, which alone builds the Subscribe button. */
internal const val AUTHOR_INFO = "clips_author_info_component"

/** The affiliate pill's icon, which the product tag's render loads too; only the affiliate's reads the affiliate info. */
internal const val AFFILIATE_ICON = 0x7f08131fL
internal const val AFFILIATE_INFO = "Lcom/instagram/api/schemas/ProductAffiliateInformationDict;"

/** The Ray-Ban Meta glasses icon of the shop pills, and the semi-transparent pills' background. */
internal const val GLASSES_ICON = 0x7f080e0cL
internal const val SEMI_BACKGROUND = 0x7f080559L

/** The dwell CTA's text, which two renders load: the shopping CTA's keeps four fields, the other many more. */
internal const val DWELL_CTA = "trans_key_dwell_cta"
private const val SHOPPING_CTA_FIELDS = 4

/** The reel's info overlay, whose render asks the floating bubbles check. */
internal const val INFO_OVERLAY = "clips_info_overlay_component"

/** The clips UFI's render loads this id, right before it builds the share count. */
internal const val UFI_ID = 0x7f0b3b57L
internal const val LIKE_BUTTON = "like_button"
internal const val COMMENT_BUTTON_NAME = "comment_button"
internal const val SAVE_BUTTON = "save_button"
internal const val SAVE_COUNT = "save_count"

internal const val PAUSE_ICON = "clips_pause_state_paused_icon_component"
internal const val PAUSE_SOUND = "clips_pause_state_audio_control_button_component"

/** The reel CTA component names its views with this; its product button's builder is in the same class. */
internal const val PAUSE_CTA = "ads_on_pause_cta_"

/** The floating bubbles use case's toString. */
internal const val BUBBLES_NAME = "FloatingBubblesUseCase(userSession="

/** What the comment bar controller's onViewCreated logs, and the flag its show reads. */
internal const val COMMENT_BAR_LAYOUT = "Failed to find inner layout for unified bottom bar margin adjustment"
internal const val COMMENT_BAR_SHOWN = 0x3b7a069dL

/** The name the direct reply bar controller's onViewCreated gives its bar. */
internal const val DIRECT_REPLY_BAR_NAME = "ReelsDirectReplyBar"

/**
 * The reply bar controllers' shared show and hide keep the Spotlight reply bar's markers on 456,
 * where the direct reply bar's are gone; they sit in the class the direct one extends.
 */
internal const val SPOTLIGHT_REPLY_BAR_SHOW = "ClipsViewerSpotlightReplyBarController_showReplyBar"
internal const val SPOTLIGHT_REPLY_BAR_HIDE = "ClipsViewerSpotlightReplyBarController_hideReplyBar"

/** Every string and literal an anchor looks for, so a test can slice a build by them. */
internal val ANCHOR_STRINGS = RENDER_STRINGS.values.toSet() + setOf(
    AUTHOR_INFO, DWELL_CTA, INFO_OVERLAY, LIKE_BUTTON, COMMENT_BUTTON_NAME, SAVE_BUTTON, SAVE_COUNT, PAUSE_ICON, PAUSE_SOUND,
    PAUSE_CTA, BUBBLES_NAME, COMMENT_BAR_LAYOUT, DIRECT_REPLY_BAR_NAME,
)
internal val ANCHOR_LITERALS = RENDER_LITERALS.values.toSet() + setOf(AFFILIATE_ICON, GLASSES_ICON, SEMI_BACKGROUND, UFI_ID, COMMENT_BAR_SHOWN)

/**
 * Finds each part's method: the one holding its marker, or, with none holding it, the one its
 * anchor names. Holding a marker twice fails the patch, and so does an anchor naming no method or
 * more than one.
 */
internal class ReelPartFinder(private val context: BytecodePatchContext) {
    /** The methods holding each of [CLEANUP_MARKERS], by marker. */
    val found: Map<String, List<Method>>

    /** The markers whose part was found by its anchor. */
    val anchored = mutableSetOf<String>()

    init {
        val wanted = CLEANUP_MARKERS.toSet()
        val holders = mutableMapOf<String, MutableList<Method>>()
        val marked = context.typesMarked(*CLEANUP_MARKERS.toTypedArray())
        context.classDefForEach { classDef ->
            if (classDef.type !in marked) return@classDefForEach
            // A string pool loads the markers Redex shares, the social context's among them on 456, for
            // the methods asking it; it holds no part.
            classDef.methods.filter { !it.isStringPool() }.forEach { method ->
                method.markers().filter { it in wanted }.distinct().forEach { holders.getOrPut(it) { mutableListOf() } += method }
            }
        }
        found = holders
    }

    private val cache = mutableMapOf<String, Method>()

    fun holding(marker: String): Method = cache.getOrPut(marker) {
        val holders = found[marker].orEmpty()
        if (holders.size > 1) {
            throw PatchException("$PATCH: expected one method holding the $marker marker, found ${holders.joinToString { it.at() }}")
        }
        holders.singleOrNull() ?: run {
            val byAnchor = try {
                anchor(marker)
            } catch (failure: PatchException) {
                throw PatchException("$PATCH: expected one method holding the $marker marker, found none, nor by its anchor: ${failure.message}")
            }
            byAnchor.singleOrNull()?.also { anchored += marker } ?: throw PatchException(
                "$PATCH: expected one method holding the $marker marker, found none, and its anchor names " +
                    if (byAnchor.isEmpty()) "none" else byAnchor.joinToString { it.at() },
            )
        }
    }

    /** The scope a render takes and the type it answers, read off the renders that still hold their markers. */
    private val renderShape: Pair<String, String> by lazy {
        val shapes = REEL_PARTS.filter { !it.check }.flatMap { found[it.marker].orEmpty() }
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 1 }
            .map { it.parameterTypes.single().toString() to it.returnType }.toSet()
        shapes.singleOrNull() ?: throw PatchException("the renders still holding their markers don't share one shape: $shapes")
    }

    private fun Method.isRender(): Boolean = !AccessFlags.STATIC.isSet(accessFlags) && implementation != null &&
        parameterTypes.size == 1 && parameterTypes.single().toString() == renderShape.first && returnType == renderShape.second

    private fun methodsLoading(value: String): List<Method> = with(context) {
        classesLoadingString(value).flatMap { classDef -> classDef.methods.filter { loadsString(it, value) } }
    }

    private fun methodsLoading(value: Long): List<Method> =
        context.classesLoading(value).flatMap { classDef -> classDef.methods.filter { it.loadsLiteral(value) } }

    private fun rendersLoading(value: String) = methodsLoading(value).filter { it.isRender() }

    private fun rendersLoading(value: Long) = methodsLoading(value).filter { it.isRender() }

    private fun classOf(method: Method): ClassDef = context.classDefBy(method.definingClass)

    private fun one(methods: List<Method>, what: String): Method = methods.singleOrNull()
        ?: throw PatchException("expected one $what, found ${if (methods.isEmpty()) "none" else methods.joinToString { it.at() }}")

    /** The clips UFI's render, the one loading [UFI_ID]. */
    private val ufiRender: Method by lazy { one(rendersLoading(UFI_ID), "render loading the clips UFI's id") }

    /** The clips UFI, the class of its render. */
    private val ufi: ClassDef by lazy { classOf(ufiRender) }

    /** The component whose render, the one loading [name], draws a button of the UFI. */
    private fun component(name: String): String = one(rendersLoading(name), "render loading \"$name\"").definingClass

    private fun anchor(marker: String): List<Method> {
        RENDER_STRINGS[marker]?.let { return rendersLoading(it) }
        RENDER_LITERALS[marker]?.let { return rendersLoading(it) }
        return when (marker) {
            SUBSCRIPTION -> subscription()
            AFFILIATE -> rendersLoading(AFFILIATE_ICON).filter { it.refersTo(AFFILIATE_INFO) }
            RBM_BLACK -> rendersLoading(GLASSES_ICON).filter { !it.loadsLiteral(SEMI_BACKGROUND) && it.unmarked() }
            RBM_SEMI -> rendersLoading(GLASSES_ICON).filter { it.loadsLiteral(SEMI_BACKGROUND) && it.unmarked() }
            SHOPPING_CTA -> rendersLoading(DWELL_CTA).filter { render ->
                classOf(render).fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) } == SHOPPING_CTA_FIELDS
            }
            BUBBLES_CHECK -> bubblesCheck()
            LIKE_PARTS[0].marker -> ufiBuilders().filter { it.returnType == component(LIKE_BUTTON) }
            LIKE_PARTS[1].marker -> ufiBuilders().filter {
                !it.isRender() && it.returnType == renderShape.second && context.loadsString(it, UFI_COUNT)
            }
            SAVE_PARTS[0].marker -> ufiBuilders().filter { it.returnType == component(SAVE_BUTTON) }
            SAVE_PARTS[1].marker -> ufiBuilders().filter { context.loadsString(it, SAVE_COUNT) }
            COMMENT_BUTTON_RENDER -> rendersLoading(COMMENT_BUTTON_NAME)
            SHARE_COUNT_MARKER -> listOf(ufiRender)
            PAUSE_PARTS[0].marker -> methodsLoading(PAUSE_ICON)
            PAUSE_PARTS[1].marker -> methodsLoading(PAUSE_SOUND)
            PRODUCT_CTA.marker -> context.classesLoadingString(PAUSE_CTA).filter { classDef -> classDef.methods.any { it.isRender() } }
                .flatMap { classDef ->
                    classDef.methods.filter {
                        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.parameterTypes.size == 2 &&
                            it.parameterTypes[1].toString() == "Ljava/lang/String;" && it.returnType.startsWith("L")
                    }
                }
            FLOATING_BUBBLES -> context.classesLoadingString(BUBBLES_NAME).flatMap { classDef ->
                classDef.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && noBubbleStates(it).size == 1 }
            }
            COMMENT_BAR_CREATED -> methodsLoading(COMMENT_BAR_LAYOUT).filter { it.name == "onViewCreated" }
            COMMENT_BAR_SHOW -> controllerMethods(holding(COMMENT_BAR_CREATED)).filter { it.loadsLiteral(COMMENT_BAR_SHOWN) }
            COMMENT_BAR_HIDE -> commentBarHide()
            DIRECT_REPLY_BAR_CREATED -> methodsLoading(DIRECT_REPLY_BAR_NAME).filter { it.name == "onViewCreated" }
            DIRECT_REPLY_BAR_SHOW -> directReplyBarOverride(found[SPOTLIGHT_REPLY_BAR_SHOW].orEmpty())
            DIRECT_REPLY_BAR_HIDE -> directReplyBarOverride(found[SPOTLIGHT_REPLY_BAR_HIDE].orEmpty())
            else -> throw PatchException("it has none")
        }
    }

    /**
     * The direct reply bar's show or hide, when Redex purged its marker: the controller's own override
     * of the Spotlight reply bar's [spotlight] method when it has one (456.1's armeabi-v7a build), or
     * else the Spotlight's, which Redex merged with the direct one's into the class both extend.
     */
    private fun directReplyBarOverride(spotlight: List<Method>): List<Method> {
        val controller = holding(DIRECT_REPLY_BAR_CREATED).definingClass
        return spotlight.map { merged ->
            context.classDefByOrNull(controller)?.methods?.firstOrNull {
                it.name == merged.name && it.returnType == merged.returnType && it.implementation != null &&
                    it.parameterTypes.map(Any::toString) == merged.parameterTypes.map(Any::toString)
            } ?: merged
        }
    }

    /** The UFI's instance builders other than its render. */
    private fun ufiBuilders(): List<Method> = ufi.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it != ufiRender
    }

    /**
     * The Subscribe button's render: the author-info component builds it, in a method of its own
     * making the button's component and answering it.
     */
    private fun subscription(): List<Method> {
        val author = one(rendersLoading(AUTHOR_INFO), "render loading \"$AUTHOR_INFO\"")
        return classOf(author).methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && !method.isRender() && method.returnType.startsWith("L") &&
                method.code().any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == method.returnType }
        }.map { it.returnType }.distinct().flatMap { type -> context.classDefByOrNull(type)?.methods?.filter { it.isRender() }.orEmpty() }
    }

    /**
     * The floating bubbles check: a static method the info overlay's render calls, taking three
     * objects and answering a boolean, that compares its first with a constant of that first one's type.
     */
    private fun bubblesCheck(): List<Method> = rendersLoading(INFO_OVERLAY).flatMap { render ->
        render.code().filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .map { (it as ReferenceInstruction).reference as MethodReference }
    }.distinctBy { it.toString() }.mapNotNull { called ->
        context.classDefByOrNull(called.definingClass)?.methods?.firstOrNull {
            it.name == called.name && it.returnType == called.returnType &&
                it.parameterTypes.map(Any::toString) == called.parameterTypes.map(Any::toString)
        }
    }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 3 &&
            method.comparesFirstWithItsConstant()
    }

    private fun Method.comparesFirstWithItsConstant(): Boolean {
        val type = parameterTypes.first().toString()
        val first = parameterRegisterNumber(0)
        val code = code()
        val constants = code.filter { instruction ->
            instruction.opcode == Opcode.SGET_OBJECT && ((instruction as ReferenceInstruction).reference as FieldReference)
                .let { it.definingClass == type && it.type == type }
        }.map { (it as OneRegisterInstruction).registerA }.toSet()
        return code.any { instruction ->
            (instruction.opcode == Opcode.IF_EQ || instruction.opcode == Opcode.IF_NE) && (instruction as TwoRegisterInstruction)
                .let { (it.registerA == first && it.registerB in constants) || (it.registerB == first && it.registerA in constants) }
        }
    }

    /** The comment bar controller's instance methods taking nothing and answering nothing, the controller holding [created]. */
    private fun controllerMethods(created: Method): List<Method> = classOf(created).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.parameterTypes.isEmpty() && it.returnType == "V"
    }

    /** The comment bar's hide, the one method of the controller its unVanish calls. */
    private fun commentBarHide(): List<Method> {
        val controller = holding(COMMENT_BAR_CREATED)
        val called = holding(COMMENT_BAR_UNVANISH).code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }
            .map { (it as ReferenceInstruction).reference as MethodReference }
            .filter { it.definingClass == controller.definingClass && it.parameterTypes.isEmpty() && it.returnType == "V" }
            .map { it.name }.toSet()
        return controllerMethods(controller).filter { it.name in called }
    }

    private fun Method.unmarked() = markers().none { it in CLEANUP_MARKERS }

    private fun Method.refersTo(type: String) = code().any { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is TypeReference -> reference.type == type
            is FieldReference -> reference.definingClass == type || reference.type == type
            is MethodReference -> reference.definingClass == type || reference.returnType == type ||
                reference.parameterTypes.any { it.toString() == type }
            else -> false
        }
    }
}

/** Where [this] method is, for a message. */
private fun Method.at() = "$definingClass->$name"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** A static `(I)String` method: the shape of Redex's pools of shared strings. */
internal fun Method.isStringPool() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" &&
    parameterTypes.map(Any::toString) == listOf("I")

internal val LITERAL_LOADS = setOf(
    Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16,
    Opcode.CONST_WIDE, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_HIGH16,
)

/** Whether [this] method loads [value] as a constant. */
internal fun Method.loadsLiteral(value: Long): Boolean = code().any { it.loadsLiteral(value) }

internal fun Instruction.loadsLiteral(value: Long): Boolean = opcode in LITERAL_LOADS && (this as WideLiteralInstruction).wideLiteral == value

/**
 * The states the floating bubbles use case [method] answers as they are: each a field it reads and
 * answers straight away, a single instance of its own class.
 */
internal fun noBubbleStates(method: Method): List<String> = method.code().zipWithNext().mapNotNull { (read, answer) ->
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
    val returned = read.opcode == Opcode.SGET_OBJECT && answer.opcode == Opcode.RETURN_OBJECT &&
        (read as OneRegisterInstruction).registerA == (answer as OneRegisterInstruction).registerA
    if (returned && field.type == field.definingClass) "${field.definingClass}->${field.name}:${field.type}" else null
}.distinct()
