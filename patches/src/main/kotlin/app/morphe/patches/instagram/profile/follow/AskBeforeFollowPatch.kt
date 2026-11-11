/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.follow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.confirm.publicStatic
import app.morphe.patches.instagram.feed.confirm.staticStub
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FOLLOW_PATCH = "Ask before a follow"

internal const val FOLLOW_CONFIRM = "$EXTENSION_PACKAGE/profile/FollowConfirm;"
internal const val HOLD_FOLLOW = "$FOLLOW_CONFIRM->hold(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
    "Ljava/lang/String;)Z"

/** The extension's stubs the patch fills: the button's view, the status, the username and the follow again. */
internal const val VIEW_OF_STUB = "viewOf"
internal const val STATUS_OF_STUB = "statusOf"
internal const val USERNAME_OF_STUB = "usernameOf"
internal const val FOLLOW_AGAIN_STUB = "followAgain"

/** Kept by name in every build: the Follow button every profile header and list row draws. */
internal const val FOLLOW_BUTTON_BASE = "Lcom/instagram/user/follow/FollowButtonBase;"

/** The status a Follow tap leaves, which only its enum's static setup loads. */
internal const val NOT_FOLLOWING = "FollowStatusNotFollowing"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val SEARCH_CONTEXT = "Lcom/instagram/search/common/analytics/SearchContext;"
private const val DOUBLE = "Ljava/lang/Double;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"

/**
 * A Follow or Follow back button on a profile or in a Followers or Following list waits for a
 * question while the switch is on. Included in the default selection with its switch off, so asking
 * is the user's pick.
 */
@Suppress("unused")
val askBeforeFollowPatch = bytecodePatch(
    name = "Ask before a follow",
    description = "Asks you to confirm before a Follow or Follow back button follows someone or sends them a " +
        "request, so a stray tap on a profile or in a Followers or Following list doesn't do it for you. " +
        "Starts off. Turn it on in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeFollow")
        askBeforeFollow(findFollowButton())
        enableStatus("askBeforeFollow")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$FOLLOW_PATCH: $why")

/**
 * The Follow button's helper and its tap action, the status read, the button field, the user
 * model's field tree with its string getter, and the extension's stubs.
 */
internal class FollowButtonTargets(
    val helper: ClassDef,
    val action: MutableMethod,
    val status: Method,
    val button: Field,
    val buttonIsInterface: Boolean,
    val tree: Field,
    val stringGetter: MethodReference,
    val viewOf: MutableMethod,
    val statusOf: MutableMethod,
    val usernameOf: MutableMethod,
    val followAgain: MutableMethod,
)

private fun isPublic(flags: Int) = AccessFlags.PUBLIC.isSet(flags)
private fun isStatic(flags: Int) = AccessFlags.STATIC.isSet(flags)

/**
 * Finds what the hook needs and checks it before anything changes. FollowButtonBase.getHelper()
 * names the helper every Follow button runs its tap through. The tap action is the helper's one
 * instance method taking (object, session, post, object, search context, object, user, Double,
 * String): each tap on a profile's Follow button and a Followers or Following row's Follow or
 * Follow back button comes here, before it reads the status, updates the button or sends anything.
 * The status read is the helper's one instance (session, user) method answering the enum whose setup
 * loads [NOT_FOLLOWING]. The button field is the helper's field whose type has getHelper() answering
 * the helper and getView(). The username comes from the user model's field tree: the tree's
 * (int) string getter the helper itself calls right after loading "username".hashCode().
 */
internal fun BytecodePatchContext.findFollowButton(): FollowButtonTargets {
    val base = classDefByOrNull(FOLLOW_BUTTON_BASE) ?: refuse("the app has no $FOLLOW_BUTTON_BASE")
    val getHelper = base.methods.singleOrNull { it.name == "getHelper" && it.parameterTypes.isEmpty() }
        ?: refuse("$FOLLOW_BUTTON_BASE has no getHelper()")
    val helperType = getHelper.returnType
    val helper = classDefByOrNull(helperType) ?: refuse("getHelper() answers $helperType, which isn't in the app")
    if (!isPublic(helper.accessFlags)) refuse("the Follow button's helper $helperType isn't public")

    val actions = helper.methods.filter { method ->
        val types = method.parameterTypes.map(Any::toString)
        !isStatic(method.accessFlags) && method.returnType == "V" && types.size == 9 &&
            types[1] == USER_SESSION && types[2] == MEDIA && types[4] == SEARCH_CONTEXT &&
            types[7] == DOUBLE && types[8] == STRING && types.all { it.startsWith("L") }
    }
    val action = actions.singleOrNull()
        ?: refuse("expected one tap action in $helperType taking a session, post, search context, user, Double and String, found ${actions.size}")
    val where = "$helperType->${action.name}"
    if (!isPublic(action.accessFlags)) refuse("$where isn't public")
    val parameters = action.parameterTypes.map(Any::toString)
    for (type in parameters) {
        val classDef = classDefByOrNull(type)
        if (classDef != null && !isPublic(classDef.accessFlags)) refuse("$where takes $type, which isn't public")
        if (classDef == null && !type.startsWith("Ljava/")) refuse("$where takes $type, which isn't in the app")
    }
    val user = parameters[6]

    val statuses = helper.methods.filter { method ->
        !isStatic(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf(USER_SESSION, user) &&
            isFollowStatus(method.returnType)
    }
    val status = statuses.singleOrNull()
        ?: refuse("expected one (session, user) status read in $helperType answering the follow status, found ${statuses.size}")
    if (!isPublic(status.accessFlags)) refuse("$helperType->${status.name} isn't public")

    val buttons = helper.fields.filter { field ->
        if (isStatic(field.accessFlags)) return@filter false
        val type = classDefByOrNull(field.type) ?: return@filter false
        type.methods.any { it.name == "getHelper" && it.parameterTypes.isEmpty() && it.returnType == helperType } &&
            type.methods.any { it.name == "getView" && it.parameterTypes.isEmpty() && it.returnType == VIEW }
    }
    val button = buttons.singleOrNull() ?: refuse("expected one Follow button field in $helperType, found ${buttons.size}")
    if (!isPublic(button.accessFlags)) refuse("$helperType->${button.name} isn't public")
    val buttonClass = classDefByOrNull(button.type)!!
    if (!isPublic(buttonClass.accessFlags)) refuse("the Follow button type ${button.type} isn't public")

    val trees = userFields(user).filter { field ->
        !isStatic(field.accessFlags) && classDefByOrNull(field.type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }
    val getters = helper.methods.flatMap { usernameReads(it) }
        .filter { read -> trees.any { it.type == read.definingClass } }
        .distinctBy { "${it.definingClass}->${it.name}" }
    val getter = getters.singleOrNull()
        ?: refuse("expected one username read on the user's field tree in $helperType, found ${getters.size}")
    val tree = trees.filter { it.type == getter.definingClass }.singleOrNull()
        ?: refuse("$user keeps more than one ${getter.definingClass}")
    if (!isPublic(tree.accessFlags)) refuse("${tree.definingClass}->${tree.name} isn't public")
    if (classDefByOrNull(tree.definingClass)?.let { isPublic(it.accessFlags) } != true) refuse("${tree.definingClass} isn't public")
    if (classDefByOrNull(getter.definingClass)?.let { isPublic(it.accessFlags) } != true) refuse("${getter.definingClass} isn't public")

    val mutableAction = mutableClassDefBy(helperType).methods.single {
        it.name == action.name && it.parameterTypes.map(Any::toString) == parameters && it.returnType == "V"
    }
    if (mutableAction.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in mutableAction.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val extension = classDefByOrNull(FOLLOW_CONFIRM) ?: refuse("the extension has no $FOLLOW_CONFIRM")
    extension.publicStatic(HOLD_FOLLOW) ?: refuse("the extension has no public static $HOLD_FOLLOW")
    val stubs = mutableClassDefBy(FOLLOW_CONFIRM)
    val objects = List(9) { OBJECT }
    return FollowButtonTargets(
        helper = helper,
        action = mutableAction,
        status = status,
        button = button,
        buttonIsInterface = AccessFlags.INTERFACE.isSet(buttonClass.accessFlags),
        tree = tree,
        stringGetter = getter,
        viewOf = stubs.staticStub(VIEW_OF_STUB, listOf(OBJECT), VIEW, 1)
            ?: refuse("$FOLLOW_CONFIRM has no static View $VIEW_OF_STUB(Object)"),
        statusOf = stubs.staticStub(STATUS_OF_STUB, listOf(OBJECT, OBJECT, OBJECT), OBJECT, 3)
            ?: refuse("$FOLLOW_CONFIRM has no static Object $STATUS_OF_STUB(Object, Object, Object)"),
        usernameOf = stubs.staticStub(USERNAME_OF_STUB, listOf(OBJECT, "I"), STRING, 2)
            ?: refuse("$FOLLOW_CONFIRM has no static String $USERNAME_OF_STUB(Object, int)"),
        followAgain = stubs.staticStub(FOLLOW_AGAIN_STUB, objects + STRING, "V", 10)
            ?: refuse("$FOLLOW_CONFIRM has no static void $FOLLOW_AGAIN_STUB taking what the tap action takes"),
    )
}

/** Whether [type] is an enum whose own code loads [NOT_FOLLOWING]. */
private fun BytecodePatchContext.isFollowStatus(type: String): Boolean {
    val classDef = classDefByOrNull(type) ?: return false
    return classDef.superclass == "Ljava/lang/Enum;" && classDef.methods.any { loadsString(it, NOT_FOLLOWING) }
}

/** The fields of [type] and of the classes it extends inside the app. */
private fun BytecodePatchContext.userFields(type: String): List<Field> {
    val fields = mutableListOf<Field>()
    var at: String? = type
    while (at != null) {
        val classDef = classDefByOrNull(at) ?: break
        fields += classDef.fields
        at = classDef.superclass
    }
    return fields
}

/** The (int) string getters [method] calls on an interface right after loading "username".hashCode(). */
private fun usernameReads(method: Method): List<MethodReference> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val username = "username".hashCode()
    return code.zipWithNext().mapNotNull { (load, call) ->
        if (load !is NarrowLiteralInstruction || load.narrowLiteral != username) return@mapNotNull null
        if (call.opcode != Opcode.INVOKE_INTERFACE && call.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        reference.takeIf { it.returnType == STRING && it.parameterTypes.map(Any::toString) == listOf("I") }
    }
}

/**
 * Fills the stubs (the button's view, the status read, the username and a second call to the tap
 * action), then asks the extension first in the tap action, with its own arguments. A yes returns
 * before Instagram reads the status, touches the button or sends anything. Each stub works in its
 * parameters' registers alone, since a compiled `return null` with unused parameters may have no other.
 */
internal fun askBeforeFollow(found: FollowButtonTargets) {
    val helper = found.helper.type
    val action = found.action
    val parameters = action.parameterTypes.map(Any::toString)
    val user = parameters[6]
    val getView = "${found.button.type}->getView()$VIEW"
    found.viewOf.addInstructions(
        0,
        """
            check-cast p0, $helper
            iget-object p0, p0, $helper->${found.button.name}:${found.button.type}
            if-nez p0, :attached
            const/4 p0, 0x0
            return-object p0
            :attached
            ${if (found.buttonIsInterface) "invoke-interface" else "invoke-virtual"} { p0 }, $getView
            move-result-object p0
            return-object p0
        """,
    )
    found.statusOf.addInstructions(
        0,
        """
            check-cast p0, $helper
            check-cast p1, $USER_SESSION
            check-cast p2, $user
            invoke-virtual { p0, p1, p2 }, $helper->${found.status.name}($USER_SESSION$user)${found.status.returnType}
            move-result-object p0
            return-object p0
        """,
    )
    val getter = found.stringGetter
    found.usernameOf.addInstructions(
        0,
        """
            check-cast p0, $user
            iget-object p0, p0, ${found.tree.definingClass}->${found.tree.name}:${found.tree.type}
            if-eqz p0, :none
            invoke-interface { p0, p1 }, ${getter.definingClass}->${getter.name}(I)$STRING
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    val casts = (0 until 8).joinToString("\n") { "check-cast p${it + 1}, ${parameters[it]}" }
    found.followAgain.addInstructions(
        0,
        """
            check-cast p0, $helper
            $casts
            invoke-virtual/range { p0 .. p9 }, $helper->${action.name}(${parameters.joinToString("")})V
            return-void
        """,
    )
    action.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p9 }, $HOLD_FOLLOW
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", action.getInstruction(0)),
    )
}
