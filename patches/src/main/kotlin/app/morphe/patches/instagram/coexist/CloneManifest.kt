/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val CLONE_LABEL_RESOURCE = "hushgram_clone_label"

private const val NAME = "android:name"
private const val LABEL = "android:label"
private const val AUTHORITIES = "android:authorities"
private const val AFFINITY = "android:taskAffinity"
private const val PROCESS = "android:process"
private const val HOST = "android:host"

/** The custom tab return host, which Instagram's code builds from its package name at run time. */
private const val TAB_HOST = "cct.$INSTAGRAM"

private val GUARDS = listOf("android:permission", "android:readPermission", "android:writePermission")
private val PERMISSION_REQUESTS = setOf("uses-permission", "uses-permission-sdk-23")

/** The attributes that name a class, by tag. Android reads one starting with a dot as under the package. */
private val CLASS_NAMES = mapOf(
    "application" to listOf(NAME, "android:backupAgent", "android:manageSpaceActivity", "android:appComponentFactory"),
    "activity" to listOf(NAME, "android:parentActivityName"),
    "activity-alias" to listOf(NAME, "android:targetActivity", "android:parentActivityName"),
    "service" to listOf(NAME),
    "receiver" to listOf(NAME),
    "provider" to listOf(NAME),
    "instrumentation" to listOf(NAME),
)

/** What the manifest half reads before it changes anything: what the code half needs, and the app's label. */
internal class CloneManifestFacts(
    /** Every authority Instagram's application declares, each once, in the manifest's order. */
    val authorities: List<String>,
    /** The permissions Instagram declares by full name. Each is under its package. */
    val permissions: Set<String>,
    /** The application's label, which the launcher entries share. */
    val label: String,
)

/** How many of each thing a rename changed. */
internal data class ManifestRename(
    val labels: Int,
    val authorities: Int,
    val permissions: Int,
    val permissionUses: Int,
    val affinities: Int,
    val categories: Int,
    val hosts: Int,
    val processes: Int,
    val classNames: Int,
)

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private fun Element.all(tag: String = "*"): List<Element> =
    getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

private fun Element.authorityList(): List<String> =
    getAttribute(AUTHORITIES).split(';').map(String::trim).filter(String::isNotEmpty)

/**
 * Reads what the rename needs and refuses a manifest it can't rename whole: one that isn't
 * Instagram's, has no application, label or provider, or declares a permission, group or tree
 * outside Instagram's package, which would clash with the installed Instagram.
 */
internal fun readCloneManifest(manifest: Document): CloneManifestFacts {
    val root = manifest.documentElement
    if (root == null || root.tagName != "manifest") refuse("AndroidManifest.xml has no manifest element")
    val named = root.getAttribute("package")
    if (named != INSTAGRAM) refuse("AndroidManifest.xml names the package \"$named\", not $INSTAGRAM")
    val application = root.children("application").singleOrNull() ?: refuse("AndroidManifest.xml has no single application element")
    val label = application.getAttribute(LABEL)
    if (label.isEmpty()) refuse("Instagram's application has no label")
    val authorities = application.all("provider").flatMap { provider ->
        provider.authorityList().ifEmpty { refuse("the provider ${provider.getAttribute(NAME)} declares no authority") }
    }.distinct()
    if (authorities.isEmpty()) refuse("Instagram's application declares no provider")
    for (tag in listOf("permission-group", "permission-tree")) {
        root.children(tag).firstOrNull()?.let { refuse("AndroidManifest.xml declares the $tag ${it.getAttribute(NAME)}, which this can't move") }
    }
    val permissions = root.children("permission").map { it.getAttribute(NAME) }.filterNot { it.isEmpty() || it.startsWith(".") }
    permissions.firstOrNull { underClone(it, INSTAGRAM) == null }?.let {
        refuse("Instagram declares the permission $it, which isn't under its package and would clash with the installed Instagram")
    }
    return CloneManifestFacts(authorities, permissions.toSet(), label)
}

/** Adds the clone's name to the default strings; quotes preserve spaces when resources compile. */
internal fun addCloneLabel(strings: Document, label: String) {
    cloneLabelProblem(label)?.let(::refuse)
    val resource = strings.createElement("string")
    resource.setAttribute("name", CLONE_LABEL_RESOURCE)
    resource.setAttribute("translatable", "false")
    resource.textContent = "\"$label\""
    strings.documentElement.appendChild(resource)
}

/**
 * Moves Instagram's manifest to [clone] and names it [label] on the home screen. Everything is
 * checked first, so a refusal leaves the manifest as it was.
 *
 * Android tells two apps apart by package, and it refuses an app that declares a provider
 * authority or a permission another app holds, so those move with the package: an authority or
 * permission under Instagram's package moves to the copy's, and any other authority gets the
 * copy's package in front. A relative permission name follows the package by itself. Task
 * affinities move the same way as authorities, so the copy's screens never join a task of the
 * installed Instagram's. The push category and the custom tab return host name the package too.
 * Labels equal to the application's are the launcher entries and share targets, and they take
 * [label] through a string resource, since WaitingForStringsActivity reads ApplicationInfo.labelRes.
 * A class name starting with a dot is written out in full first, since it would
 * otherwise name a class under the copy's package. Actions, meta-data names and the provider
 * Instagram asks the Pages app for stay as they are.
 */
internal fun renameManifest(manifest: Document, clone: String, label: String): ManifestRename {
    clonePackageProblem(clone)?.let(::refuse)
    cloneLabelProblem(label)?.let(::refuse)
    val facts = readCloneManifest(manifest)
    val root = manifest.documentElement
    val application = root.children("application").single()
    val everything = listOf(root) + root.all()

    var classNames = 0
    for (element in everything) for (attribute in CLASS_NAMES[element.tagName].orEmpty()) {
        val value = element.getAttribute(attribute)
        if (value.startsWith(".")) {
            element.setAttribute(attribute, INSTAGRAM + value)
            classNames++
        }
    }
    root.setAttribute("package", clone)

    var labels = 0
    for (element in everything) {
        if (element.getAttribute(LABEL) == facts.label) {
            element.setAttribute(LABEL, "@string/$CLONE_LABEL_RESOURCE")
            labels++
        }
    }

    var authorities = 0
    for (provider in application.all("provider")) {
        val moved = provider.authorityList().map { cloneAuthority(it, clone) }
        provider.setAttribute(AUTHORITIES, moved.joinToString(";"))
        authorities += moved.size
    }

    var permissions = 0
    for (permission in root.children("permission")) {
        val name = permission.getAttribute(NAME)
        if (name in facts.permissions) {
            permission.setAttribute(NAME, underClone(name, clone)!!)
            permissions++
        }
    }
    var permissionUses = 0
    for (element in everything) {
        val attributes = GUARDS + if (element.tagName in PERMISSION_REQUESTS) listOf(NAME) else emptyList()
        for (attribute in attributes) {
            val name = element.getAttribute(attribute)
            if (name in facts.permissions) {
                element.setAttribute(attribute, underClone(name, clone)!!)
                permissionUses++
            }
        }
    }

    var affinities = 0
    var processes = 0
    var categories = 0
    var hosts = 0
    for (element in everything) {
        val affinity = element.getAttribute(AFFINITY)
        if (affinity.isNotEmpty()) {
            element.setAttribute(AFFINITY, underClone(affinity, clone) ?: "$clone.$affinity")
            affinities++
        }
        underClone(element.getAttribute(PROCESS), clone)?.let {
            element.setAttribute(PROCESS, it)
            processes++
        }
        if (element.tagName == "category" && element.getAttribute(NAME) == INSTAGRAM) {
            element.setAttribute(NAME, clone)
            categories++
        }
        if (element.tagName == "data" && element.getAttribute(HOST) == TAB_HOST) {
            element.setAttribute(HOST, "cct.$clone")
            hosts++
        }
    }
    return ManifestRename(labels, authorities, permissions, permissionUses, affinities, categories, hosts, processes, classNames)
}
