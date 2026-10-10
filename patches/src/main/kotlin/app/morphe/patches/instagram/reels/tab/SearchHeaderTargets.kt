/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.comment.replace
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the Reels tab"

/** Instagram's main activity, which its manifest names, so every build keeps the name. */
internal const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"

internal const val SEARCH_BUTTON = "$EXTENSION_PACKAGE/reels/SearchHeaderButton;"
internal const val SEARCH_SELECT_STUB = "select"
internal const val SEARCH_PAGING_STUB = "paging"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"

/**
 * What the Search button on Home's header calls: the tab enum, the main activity's field holding the
 * tab host, the host's public switch to a tab, and the host's pager, which is set only where the tabs
 * swipe sideways.
 */
internal class SearchButtonHook(
    val tabType: String,
    val host: String,
    val select: String,
    val pager: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds what the Search button calls, failing before anything changes when any of it isn't there
 * exactly once. From the tab host [tabs] found (MainTabControllerImpl, the class of the tab switch):
 *
 *  - [MAIN_ACTIVITY] keeps the host in one instance field;
 *  - one public instance method of the host takes a tab, a String and a boolean, returns nothing,
 *    and calls the switch: InstagramMainActivity's switch to a tab picked from code calls it;
 *  - the host keeps a ViewPager2 in one instance field.
 *
 * The extension reaches all of them from its own class, so each class, field and method has to be
 * public.
 */
internal fun BytecodePatchContext.findSearchButton(tabs: ReelsTabHooks): SearchButtonHook {
    val host = classDefByOrNull(tabs.switch.definingClass) ?: refuse("the tab host ${tabs.switch.definingClass} isn't in the app")
    val tabClass = classDefByOrNull(tabs.tabType) ?: refuse("the tab enum ${tabs.tabType} isn't in the app")
    val activity = classDefByOrNull(MAIN_ACTIVITY) ?: refuse("this Instagram build has no $MAIN_ACTIVITY")
    for (owner in listOf(activity, host, tabClass)) {
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) refuse("${owner.type} isn't public, so the extension can't reach it")
    }

    val held = activity.fields.filter { it.type == host.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val field = held.singleOrNull() ?: refuse("expected $MAIN_ACTIVITY to keep one tab host ${host.type}, found ${held.size}")
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) refuse("$MAIN_ACTIVITY's tab host ${field.name} isn't public")

    val selects = host.methods.filter { method ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(tabs.tabType, STRING, "Z") &&
            method.code().any { it.methodReference()?.let { call -> call.definingClass == host.type && tabs.switch.sameAs(call) } == true }
    }
    val select = selects.singleOrNull()
        ?: refuse("expected one public method of ${host.type} taking a tab, a String and a boolean that calls ${tabs.switch.name}, found ${selects.size}")

    val pagers = host.fields.filter { it.type == VIEW_PAGER && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val pager = pagers.singleOrNull() ?: refuse("expected ${host.type} to keep one $VIEW_PAGER, found ${pagers.size}")
    if (!AccessFlags.PUBLIC.isSet(pager.accessFlags)) refuse("${host.type}'s pager ${pager.name} isn't public")

    val extension = classDefByOrNull(SEARCH_BUTTON) ?: refuse("the extension has no $SEARCH_BUTTON")
    if (extension.methods.none { it.isIntStub(SEARCH_SELECT_STUB, 2) }) refuse("$SEARCH_BUTTON has no public static I $SEARCH_SELECT_STUB($OBJECT$OBJECT)")
    if (extension.methods.none { it.isIntStub(SEARCH_PAGING_STUB, 1) }) refuse("$SEARCH_BUTTON has no public static I $SEARCH_PAGING_STUB($OBJECT)")

    return SearchButtonHook(
        tabs.tabType,
        "$MAIN_ACTIVITY->${field.name}:${host.type}",
        "${host.type}->${select.name}(${tabs.tabType}${STRING}Z)V",
        "${host.type}->${pager.name}:$VIEW_PAGER",
    )
}

/** Writes the Search button's two stubs. Only called once [findSearchButton] found everything. */
internal fun BytecodePatchContext.addSearchButton(hook: SearchButtonHook) {
    // Five registers: v0 to v2 are locals, and p0, the activity, and p1, the tab, are v3 and v4, so
    // nothing the stub writes lands on a parameter it reads after. Each way out returns on its own.
    replace(stub(SEARCH_SELECT_STUB, 2), 5, """
        instance-of v0, p0, $MAIN_ACTIVITY
        if-eqz v0, :none
        instance-of v0, p1, ${hook.tabType}
        if-eqz v0, :none
        check-cast p0, $MAIN_ACTIVITY
        iget-object v0, p0, ${hook.host}
        if-eqz v0, :none
        check-cast p1, ${hook.tabType}
        const/4 v1, 0x0
        const/4 v2, 0x0
        invoke-virtual { v0, p1, v1, v2 }, ${hook.select}
        const/4 v0, 0x1
        return v0
        :none
        const/4 v0, 0x0
        return v0
    """)
    // Two registers: v0 is a local and p0, the activity, is v1.
    replace(stub(SEARCH_PAGING_STUB, 1), 2, """
        instance-of v0, p0, $MAIN_ACTIVITY
        if-eqz v0, :none
        check-cast p0, $MAIN_ACTIVITY
        iget-object v0, p0, ${hook.host}
        if-eqz v0, :none
        iget-object v0, v0, ${hook.pager}
        if-eqz v0, :none
        const/4 v0, 0x1
        return v0
        :none
        const/4 v0, 0x0
        return v0
    """)
}

private fun BytecodePatchContext.stub(name: String, parameters: Int) =
    mutableClassDefBy(SEARCH_BUTTON).methods.single { it.isIntStub(name, parameters) }

private fun Method.isIntStub(name: String, parameters: Int) =
    this.name == name && parameterTypes.map(CharSequence::toString) == List(parameters) { OBJECT } && returnType == "I" &&
        AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun Method.sameAs(other: MethodReference): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
