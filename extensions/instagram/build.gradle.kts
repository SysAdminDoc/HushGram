/**
 * Writes NOTICE into a Java constant, so the Licenses row shows exactly the file the repository
 * ships. Morphe's section 7b notice has to reach the person using the app, and a hand-kept copy
 * would drift from NOTICE the first time either changed.
 */
abstract class GenerateLicenseNotice : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = notice.get().asFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val literal = buildString {
            for (ch in text) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> if (ch.code < 0x20 || ch.code > 0x7e) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
        val directory = outputDir.get().asFile.resolve("app/hushgram/extension/instagram/settings")
        directory.mkdirs()
        directory.resolve("LicenseNotice.java").writeText(
            "package app.hushgram.extension.instagram.settings;\n\n" +
                "/** Generated from NOTICE by :extensions:instagram:generateLicenseNotice. Do not edit. */\n" +
                "public final class LicenseNotice {\n" +
                "    public static final String TEXT = \"$literal\";\n\n" +
                "    private LicenseNotice() {\n    }\n}\n",
            Charsets.UTF_8,
        )
    }
}

val generateLicenseNotice = tasks.register<GenerateLicenseNotice>("generateLicenseNotice") {
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
    outputDir.set(layout.buildDirectory.dir("generated/source/licenseNotice"))
}

extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(generateLicenseNotice, GenerateLicenseNotice::outputDir)
}

// Robolectric 4.17 asks for Bouncy Castle 1.85 twice: by name, through the bc-jdk18on-bom it
// imports, and with no version of its own for the bcprov module that BOM governs. Every request
// in this module is rewritten to the reviewed release so related test libraries cannot resolve
// at mixed versions. No production configuration contains this group, so none of it reaches
// the MPE payload.
//
// Checking the resolved graph afterwards would prove nothing: the rewrite above guarantees the
// answer, so a "wrong resolved version" branch could never run. What the rewrite hides, and what
// is worth failing on, is the request underneath it. When Robolectric moves to a version nobody
// has looked at, this build stops instead of quietly rewriting it away.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// 1.85 was checked on 2026-09-15 against the six advisories the catalog names: CVE-2025-8916
// ends at 1.78, CVE-2026-5588 at 1.83, CVE-2025-14813 and CVE-2026-0636 at 1.84, and
// CVE-2026-8763 and CVE-2026-13506 at 1.85. It is rewritten
// all the same, because one reviewed release in the graph is easier to hold than two.
// Resolving every graph also reaches AGP's device-test tooling, which requests 1.79.
// Its known affected range is reviewed in settings.gradle.kts; the common override
// selects 1.86 here too. This records the request without permitting the old result.
val reviewedBouncyCastleRequests = setOf("1.79", "1.85", safeBouncyCastleVersion)
// Modules Robolectric declares with no version of its own, because the BOM it imports carries
// the version for them. Reviewing that BOM is what covers these, so they are named here rather
// than by a version: the BOM's own request is reviewed above, and a module that turns up here
// without one is a request nothing in this file chose the version for.
//
// Reading the constraint behind such a request instead was tried on 2026-09-15 and is worthless:
// the rewrite has already moved the BOM to the reviewed release by then, so every constraint it
// contributes names that release and the check can never fail. Same trap as the resolved-version
// check described above.
val reviewedVersionlessBouncyCastleModules = setOf("bcprov-jdk18on")
// Guarded by hand rather than by a synchronized wrapper: in a Kotlin build script `java` is the
// Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()
// Module names, kept apart from the versions above so a name can never end up in the reviewed
// version set by someone pasting it in.
val unversionedBouncyCastleRequests = sortedSetOf<String>()
// GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2, and Robolectric's test graph asks for
// 33.6.0. Rewritten to the catalog's release, as :patches does for the patcher's graph.
val safeGuavaVersion = libs.versions.guava.get()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded whatever it is, including a request that carries no version of its own.
            // A ?.let dropped those: one arriving through a platform or a BOM was rewritten to
            // the reviewed release like any other and then counted nowhere, so the unreviewed
            // set stayed empty for it and the gate below had nothing to fail on. A request with
            // no version is the one most worth reading, because nothing in this file chose
            // what it would otherwise have resolved to.
            // Blank as well as null. A declaration with no version at all reports "" rather
            // than null, so a plain null check counted it as a version and the failure read
            // "asks for Bouncy Castle , which nobody has reviewed": it stopped the build, which
            // is the point, but said nothing a reader could act on.
            val asked = requested.version?.takeIf { it.isNotBlank() }
            if (asked != null) {
                synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            } else {
                synchronized(unversionedBouncyCastleRequests) { unversionedBouncyCastleRequests.add(requested.name) }
            }
            useVersion(safeBouncyCastleVersion)
            because("The Robolectric test graph must use the reviewed security release.")
        }
        if (requested.group == "com.google.guava" && requested.name == "guava") {
            useVersion(safeGuavaVersion)
            because("GHSA-xxph-c9ww-hj94 covers every Guava before 33.7.2.")
        }
    }
}

val verifyBouncyCastleTestGraph = tasks.register("verifyBouncyCastleTestGraph") {
    group = "verification"
    description = "Checks the unit-test graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than being read from a set that nothing has filled yet.
        val classpaths = configurations
            .filter { it.name.endsWith("UnitTestRuntimeClasspath") && it.isCanBeResolved }
            .sortedBy { it.name }
        if (classpaths.isEmpty()) {
            throw GradleException("This module has no unit-test runtime classpath to inspect.")
        }

        for (classpath in classpaths) {
            val modules = classpath.incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.takeIf { it.group == "org.bouncycastle" }
                }
                .distinctBy { "${it.group}:${it.name}:${it.version}" }
                .sortedBy { it.name }

            if (modules.isEmpty()) {
                throw GradleException("${classpath.name} contains no Bouncy Castle module.")
            }
            logger.lifecycle(
                "Bouncy Castle in ${classpath.name}: " +
                    modules.joinToString(", ") { "${it.name}:${it.version}" }
            )
        }

        val requested = synchronized(requestedBouncyCastleVersions) {
            requestedBouncyCastleVersions.toSet()
        }
        val unversioned = synchronized(unversionedBouncyCastleRequests) {
            unversionedBouncyCastleRequests.toSet()
        }
        val unreviewed = (requested - reviewedBouncyCastleRequests).sorted() +
            (unversioned - reviewedVersionlessBouncyCastleModules).sorted()
                .map { "$it with no version of its own" }
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The test graph now asks for Bouncy Castle " + unreviewed.joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add a version to " +
                    "reviewedBouncyCastleRequests, or a module asking for no version of its own " +
                    "to reviewedVersionlessBouncyCastleModules once you have read what carries " +
                    "its version, or move the pin."
            )
        }
    }
}

// By type rather than by the one name. This module builds unit tests for debug only today, so
// testDebugUnitTest is the whole of it, but a second unit-test variant would otherwise start a
// test JVM on a graph nothing had looked at.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleTestGraph)
    // Robolectric otherwise omits the highest SDK from names. A bare name cannot prove API 37.
    systemProperty("robolectric.alwaysIncludeVariantMarkersInTestName", "true")
}

// A filtered run or robolectric.enabledSdks can silently omit a platform. The matrix's actual
// behavioral cases must all have passed, including the dialog cases that assert SDK_INT itself.
tasks.register("verifyAndroidBoundaries") {
    group = "verification"
    description = "Requires passing Android 9/10/17 settings, recovery, storage, Cancel and provider caller cases."
    dependsOn("testDebugUnitTest")
    val results = layout.buildDirectory.dir("test-results/testDebugUnitTest")
    inputs.dir(results)
    doLast {
        val required = mapOf(
            "app.hushgram.extension.instagram.settings.OverrideDocumentsTest" to listOf(
                "installedDeveloperPatchOffersReadOnlyDocumentActions[28]", "installedDeveloperPatchOffersReadOnlyDocumentActions[37]",
                "missingDeveloperPatchHasNoOverrideDocumentActions[28]", "missingDeveloperPatchHasNoOverrideDocumentActions[37]",
                "exportUsesTheResolvedSessionStoreAndClosesTheDocumentBeforeSuccess[28]", "exportUsesTheResolvedSessionStoreAndClosesTheDocumentBeforeSuccess[37]",
                "changedValidFileIsValidatedOnlyAndLeavesNativeBytesUntouched[28]", "changedValidFileIsValidatedOnlyAndLeavesNativeBytesUntouched[37]",
                "cancellationWrongUriAndMissingPickerKeepControlsUsableWithoutNativeReads[28]", "cancellationWrongUriAndMissingPickerKeepControlsUsableWithoutNativeReads[37]",
                "malformedOversizedAndBuildMismatchedDocumentsAreRefusedWithoutWrites[28]", "malformedOversizedAndBuildMismatchedDocumentsAreRefusedWithoutWrites[37]",
                "inputReadAndCloseFailuresCannotReportValidatedSuccess[28]", "inputReadAndCloseFailuresCannotReportValidatedSuccess[37]",
                "outputWriteAndCloseFailuresCannotReportExportedSuccess[28]", "outputWriteAndCloseFailuresCannotReportExportedSuccess[37]",
                "unsupportedSessionNativeFailureAndRelativePathRefuseBeforeDocumentOutput[28]", "unsupportedSessionNativeFailureAndRelativePathRefuseBeforeDocumentOutput[37]",
                "absentNativeFileExportsEmptyValuesWithoutCreatingIt[28]", "absentNativeFileExportsEmptyValuesWithoutCreatingIt[37]"),
            "app.hushgram.extension.instagram.comment.CommentCopyTest" to listOf(
                "explicitTapCopiesTheOriginalVerbatimAndMarksItSensitive[28]", "explicitTapCopiesTheOriginalVerbatimAndMarksItSensitive[37]",
                "immutableAndRepeatedMenusKeepStockIdentityAndOnlyOneOwnedRow[28]", "immutableAndRepeatedMenusKeepStockIdentityAndOnlyOneOwnedRow[37]",
                "offPausedUnreadyAndUnsupportedMenusAreUntouched[28]", "offPausedUnreadyAndUnsupportedMenusAreUntouched[37]",
                "emptyTextGetsNoRowAndWhitespaceIsNeverTrimmed[28]", "emptyTextGetsNoRowAndWhitespaceIsNeverTrimmed[37]",
                "discoveryAndClipboardFailuresReturnToNativeDismissal[28]", "discoveryAndClipboardFailuresReturnToNativeDismissal[37]",
                "emptyOrNullCurrentTextRemovesStaleOwnedRowsFromImmutableMenus[28]", "emptyOrNullCurrentTextRemovesStaleOwnedRowsFromImmutableMenus[37]",
                "staleMenusAreUntouchedWhileOffPausedOrUnreadyAndFailuresRemainContained[28]", "staleMenusAreUntouchedWhileOffPausedOrUnreadyAndFailuresRemainContained[37]"),
            "app.hushgram.extension.instagram.comment.CommentPhotoTest" to listOf(
                "explicitTapSavesAnImmutableSnapshotAndReturnsForDismissal[28]", "explicitTapSavesAnImmutableSnapshotAndReturnsForDismissal[37]",
                "immutableRepeatedAndChangedMenusKeepStockRowsAndOneOwnedRow[28]", "immutableRepeatedAndChangedMenusKeepStockRowsAndOneOwnedRow[37]",
                "noPhotoNowRemovesOnlyStaleOwnedRows[28]", "noPhotoNowRemovesOnlyStaleOwnedRows[37]",
                "eachFamilyAloneAndTogetherKeepDistinctOwnedRows[28]", "eachFamilyAloneAndTogetherKeepDistinctOwnedRows[37]",
                "offPausedUnreadyAndMissingInputsLeaveStockUntouched[28]", "offPausedUnreadyAndMissingInputsLeaveStockUntouched[37]",
                "getterRowAndQueueFailuresStayContainedAndRespectALateSwitch[28]", "getterRowAndQueueFailuresStayContainedAndRespectALateSwitch[37]",
                "eachStepThatFindsNoPhotoCountsItsOwnReasonOnceAndReadsNoFurther[28]", "eachStepThatFindsNoPhotoCountsItsOwnReasonOnceAndReadsNoFurther[37]",
                "aPhotoIsReadInTheBridgesOldOrderAndLeavesItsCountToTheSizes[28]", "aPhotoIsReadInTheBridgesOldOrderAndLeavesItsCountToTheSizes[37]",
                "theUnpatchedBridgesCountAnUnselectedComment[28]", "theUnpatchedBridgesCountAnUnselectedComment[37]",
                "withoutMediaTypeOnlyAMediaWithNoVideoPassesAsAStillPhoto[28]", "withoutMediaTypeOnlyAMediaWithNoVideoPassesAsAStillPhoto[37]",
                "aMediaTypeThatIsntAPhotosNeverReadsTheVideo[28]", "aMediaTypeThatIsntAPhotosNeverReadsTheVideo[37]"),
            "app.hushgram.extension.instagram.download.CommentPhotoSaveTest" to listOf(
                "explicitCommentPhotoTapSavesTheLargestSuppliedRenditionAndCleansUp[28]", "explicitCommentPhotoTapSavesTheLargestSuppliedRenditionAndCleansUp[37]",
                "commentPhotoCancelUsesTheExistingControlAndRemovesAllTemporaryState[28]", "commentPhotoCancelUsesTheExistingControlAndRemovesAllTemporaryState[37]",
                "aCommentPhotoWithoutMediaTypeSaves[28]", "aCommentPhotoWithoutMediaTypeSaves[37]",
                "aCommentMediaWithoutMediaTypeButWithVideoGetsNoRow[28]", "aCommentMediaWithoutMediaTypeButWithVideoGetsNoRow[37]",
                "deniedLegacyStoragePermissionLeavesNoPendingPhoto[28]"),
            "app.hushgram.extension.instagram.download.CommentPhotoDownloadTest" to listOf(
                "onlySuppliedMetaPhotoAddressesAreCopiedVerbatim[28]", "onlySuppliedMetaPhotoAddressesAreCopiedVerbatim[37]",
                "missingModelsGetNoImageFallback[28]", "missingModelsGetNoImageFallback[37]",
                "copyIsDetachedAndUnmodifiable[28]", "copyIsDetachedAndUnmodifiable[37]",
                "aSaveThatCannotStartSaysSoAndNeverThrows[28]", "aSaveThatCannotStartSaysSoAndNeverThrows[37]",
                "eachWayTheSizesComeBackEmptyCountsItsOwnReasonOnce[28]", "eachWayTheSizesComeBackEmptyCountsItsOwnReasonOnce[37]",
                "aKeptSizeCountsAFoundPhotoAndNoRefusal[28]", "aKeptSizeCountsAFoundPhotoAndNoRefusal[37]",
                "everyReasonIsFixedTextTheReportKeepsAsWritten[28]", "everyReasonIsFixedTextTheReportKeepsAsWritten[37]"),
            "app.hushgram.extension.instagram.profile.ProfileSuggestionsTest" to listOf(
                "offPausedAndUnreadyLeaveInstagramsAnswers[28]", "offPausedAndUnreadyLeaveInstagramsAnswers[37]",
                "aButtonHiddenEarlierComesBackWhenTheSwitchGoesOff[28]", "aButtonHiddenEarlierComesBackWhenTheSwitchGoesOff[37]",
                "aButtonThatThrowsIsLeftAndReported[28]", "aButtonThatThrowsIsLeftAndReported[37]"),
            "app.hushgram.extension.instagram.profile.ProfileHighlightsTest" to listOf(
                "withTheSwitchOnTheRowIsLeftOut[28]", "withTheSwitchOnTheRowIsLeftOut[37]",
                "offToStartAndOffKeepsTheRow[28]", "offToStartAndOffKeepsTheRow[37]",
                "offPausedAndUnreadyKeepTheRow[28]", "offPausedAndUnreadyKeepTheRow[37]",
                "aThrowingSwitchKeepsTheRowAndIsReported[28]", "aThrowingSwitchKeepsTheRowAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.ProfileHighlightsSettingsTest" to listOf(
                "missingPatchHasNoHighlightsSwitch[28]", "missingPatchHasNoHighlightsSwitch[37]",
                "highlightsSwitchStartsOffPersistsAndHonorsPause[28]", "highlightsSwitchStartsOffPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.direct.NotesRowTest" to listOf(
                "withTheSwitchOnTheRowIsLeftOut[28]", "withTheSwitchOnTheRowIsLeftOut[37]",
                "offToStartAndOffKeepTheRow[28]", "offToStartAndOffKeepTheRow[37]",
                "offPausedAndUnreadyKeepTheRow[28]", "offPausedAndUnreadyKeepTheRow[37]",
                "aListWithoutTheRowGoesThroughAsItCame[28]", "aListWithoutTheRowGoesThroughAsItCame[37]",
                "aThrowingSwitchKeepsTheRowAndIsReported[28]", "aThrowingSwitchKeepsTheRowAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.NotesRowSettingsTest" to listOf(
                "missingPatchHasNoNotesRowSwitch[28]", "missingPatchHasNoNotesRowSwitch[37]",
                "notesRowSwitchStartsOffUnderMessagesPersistsAndHonorsPause[28]",
                "notesRowSwitchStartsOffUnderMessagesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.direct.InboxSuggestionsTest" to listOf(
                "withTheSwitchOnAccountsToFollowAreLeftOut[28]", "withTheSwitchOnAccountsToFollowAreLeftOut[37]",
                "offToStartAndOffKeepTheSection[28]", "offToStartAndOffKeepTheSection[37]",
                "offPausedAndUnreadyKeepTheSection[28]", "offPausedAndUnreadyKeepTheSection[37]",
                "aListLedByAnotherUnitGoesThroughAsItCame[28]", "aListLedByAnotherUnitGoesThroughAsItCame[37]",
                "aThrowingSwitchKeepsTheSectionAndIsReported[28]", "aThrowingSwitchKeepsTheSectionAndIsReported[37]",
                "aNameThatThrowsKeepsTheSectionAndIsReported[28]", "aNameThatThrowsKeepsTheSectionAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.InboxSuggestionsSettingsTest" to listOf(
                "missingPatchHasNoInboxSuggestionsSwitch[28]", "missingPatchHasNoInboxSuggestionsSwitch[37]",
                "inboxSuggestionsSwitchStartsOffUnderMessagesPersistsAndHonorsPause[28]",
                "inboxSuggestionsSwitchStartsOffUnderMessagesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.direct.InstantsTest" to listOf(
                "withTheSwitchOnTheCheckAnswersNo[28]", "withTheSwitchOnTheCheckAnswersNo[37]",
                "offToStartAndOffLeaveItToInstagram[28]", "offToStartAndOffLeaveItToInstagram[37]",
                "pausedAndUnreadyLeaveItToInstagram[28]", "pausedAndUnreadyLeaveItToInstagram[37]",
                "aThrowingSwitchLeavesItToInstagramAndIsReported[28]", "aThrowingSwitchLeavesItToInstagramAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.InstantsSettingsTest" to listOf(
                "missingPatchHasNoInstantsSwitch[28]", "missingPatchHasNoInstantsSwitch[37]",
                "instantsAloneStillGetsMessages[28]", "instantsAloneStillGetsMessages[37]",
                "instantsSwitchStartsOffUnderMessagesPersistsAndHonorsPause[28]",
                "instantsSwitchStartsOffUnderMessagesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.direct.ThreadSeenTest" to listOf(
                "withTheSwitchOnTheReceiptIsHeld[28]", "withTheSwitchOnTheReceiptIsHeld[37]",
                "offToStartAndOffSendTheReceipt[28]", "offToStartAndOffSendTheReceipt[37]",
                "viewOnceMediaIsAnIndependentChoice[28]", "viewOnceMediaIsAnIndependentChoice[37]",
                "pausedAndUnreadySendTheReceipt[28]", "pausedAndUnreadySendTheReceipt[37]",
                "aThrowingSwitchSendsTheReceiptAndIsReported[28]", "aThrowingSwitchSendsTheReceiptAndIsReported[37]",
                "aLongPressOffersMarkAsReadOnceWhileTheSwitchIsOn[28]", "aLongPressOffersMarkAsReadOnceWhileTheSwitchIsOn[37]",
                "offPausedAndUnreadyOfferNoRow[28]", "offPausedAndUnreadyOfferNoRow[37]",
                "aThrowingSwitchOffersNoRowAndIsReported[28]", "aThrowingSwitchOffersNoRowAndIsReported[37]",
                "markAsReadSendsThatChatsReceiptThroughInstagram[28]", "markAsReadSendsThatChatsReceiptThroughInstagram[37]",
                "onlyTheMarkedChatsReceiptGoesThrough[28]", "onlyTheMarkedChatsReceiptGoesThrough[37]",
                "aReceiptLetThroughGoesThroughAgainWhenInstagramRetriesIt[28]", "aReceiptLetThroughGoesThroughAgainWhenInstagramRetriesIt[37]",
                "aNewerMessageInAMarkedChatStaysHeld[28]", "aNewerMessageInAMarkedChatStaysHeld[37]",
                "aMarkCountsOnlyForTheAccountItWasMadeOn[28]", "aMarkCountsOnlyForTheAccountItWasMadeOn[37]",
                "aMarkOutlivesARestart[28]", "aMarkOutlivesARestart[37]",
                "aMarkLastsADay[28]", "aMarkLastsADay[37]",
                "onlyTheNewestMarksAreKept[28]", "onlyTheNewestMarksAreKept[37]",
                "aFileWithTooManyOrBrokenMarksIsCleanedWhenRead[28]", "aFileWithTooManyOrBrokenMarksIsCleanedWhenRead[37]",
                "markingAChatAgainKeepsItsMarkForAnotherDay[28]", "markingAChatAgainKeepsItsMarkForAnotherDay[37]",
                "otherRowsAndTheSwitchOffLeaveTheTapToInstagram[28]", "otherRowsAndTheSwitchOffLeaveTheTapToInstagram[37]",
                "offPausedAndUnreadyLeaveTheTapToInstagram[28]", "offPausedAndUnreadyLeaveTheTapToInstagram[37]",
                "aChatWithNothingToMarkIsToldSo[28]", "aChatWithNothingToMarkIsToldSo[37]",
                "unpatchedBridgesMarkNothing[28]", "unpatchedBridgesMarkNothing[37]",
                "aFailedSendTakesItsMarkBackAndIsReported[28]", "aFailedSendTakesItsMarkBackAndIsReported[37]",
                "aFailedSendKeepsAnEarlierMarkForTheSameMessage[28]", "aFailedSendKeepsAnEarlierMarkForTheSameMessage[37]",
                "aFailedUnreadClearStillSendsAndIsReported[28]", "aFailedUnreadClearStillSendsAndIsReported[37]",
                "anUnreadableReceiptStaysHeldWhileAMarkIsKept[28]", "anUnreadableReceiptStaysHeldWhileAMarkIsKept[37]",
                "withNoMarkKeptReceiptsAreNotRead[28]", "withNoMarkKeptReceiptsAreNotRead[37]",
                "theSwitchOffSendsEveryReceiptAndKeepsTheMark[28]", "theSwitchOffSendsEveryReceiptAndKeepsTheMark[37]",
                "chatsMarkedReadTogetherLetTheirReceiptsThrough[28]", "chatsMarkedReadTogetherLetTheirReceiptsThrough[37]",
                "offPausedAndUnreadyMarkNothingTogether[28]", "offPausedAndUnreadyMarkNothingTogether[37]",
                "aThrowingSwitchOrAccountMarksNothingTogetherAndIsReported[28]", "aThrowingSwitchOrAccountMarksNothingTogetherAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.ThreadSeenSettingsTest" to listOf(
                "missingPatchHasNoSeenReceiptSwitch[28]", "missingPatchHasNoSeenReceiptSwitch[37]",
                "seenReceiptAloneStillGetsMessages[28]", "seenReceiptAloneStillGetsMessages[37]",
                "seenReceiptSwitchStartsOffUnderMessagesPersistsAndHonorsPause[28]",
                "seenReceiptSwitchStartsOffUnderMessagesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.direct.TypingStatusTest" to listOf(
                "withTheSwitchOnTypingIsKeptBack[28]", "withTheSwitchOnTypingIsKeptBack[37]",
                "aStopAlwaysRunsInstagramsCode[28]", "aStopAlwaysRunsInstagramsCode[37]",
                "offToStartAndOffSendTheIndicator[28]", "offToStartAndOffSendTheIndicator[37]",
                "pausedAndUnreadySendTheIndicator[28]", "pausedAndUnreadySendTheIndicator[37]",
                "aThrowingSwitchSendsTheIndicatorAndIsReported[28]", "aThrowingSwitchSendsTheIndicatorAndIsReported[37]",
                "theSeenReceiptSwitchIsAnIndependentChoice[28]", "theSeenReceiptSwitchIsAnIndependentChoice[37]"),
            "app.hushgram.extension.instagram.stories.LiveSeenTest" to listOf(
                "withTheSwitchOnTheViewerHeartbeatIsHeld[28]", "withTheSwitchOnTheViewerHeartbeatIsHeld[37]",
                "offPausedUnreadyAndThrowingLeaveItToInstagram[28]", "offPausedUnreadyAndThrowingLeaveItToInstagram[37]"),
            "app.hushgram.extension.instagram.direct.KeepInChatTest" to listOf(
                "withTheSwitchOnViewOnceAndReplayableStayInChat[28]", "withTheSwitchOnViewOnceAndReplayableStayInChat[37]",
                "offPausedUnreadyAndThrowingLeaveItToInstagram[28]", "offPausedUnreadyAndThrowingLeaveItToInstagram[37]",
                "aViewOncePhotoYouSentKeepsItsModeWhicheverKeyComesFirst[28]", "aViewOncePhotoYouSentKeepsItsModeWhicheverKeyComesFirst[37]",
                "mediaYouReceiveAndMessagesWithNoFlagStayKept[28]", "mediaYouReceiveAndMessagesWithNoFlagStayKept[37]",
                "withTheSwitchOffNothingIsRememberedOrRestored[28]", "withTheSwitchOffNothingIsRememberedOrRestored[37]",
                "aMessageThatCantBeReadIsReportedAndLeftKept[28]", "aMessageThatCantBeReadIsReportedAndLeftKept[37]"),
            "app.hushgram.extension.instagram.direct.ScreenshotBlockTest" to listOf(
                "withTheSwitchOnWindowsStayCapturable[28]", "withTheSwitchOnWindowsStayCapturable[37]",
                "offPausedUnreadyAndThrowingLeaveItToInstagram[28]", "offPausedUnreadyAndThrowingLeaveItToInstagram[37]",
                "withTheSwitchOnInstagramCantMarkAWindowSecure[28]", "withTheSwitchOnInstagramCantMarkAWindowSecure[37]",
                "offOrClearingLeavesTheFlagsAsInstagramAsked[28]", "offOrClearingLeavesTheFlagsAsInstagramAsked[37]"),
            "app.hushgram.extension.instagram.direct.ScreenshotReportsTest" to listOf(
                "withTheSwitchOnScreenshotsAreKept[28]", "withTheSwitchOnScreenshotsAreKept[37]",
                "offPausedUnreadyAndThrowingLeaveItToInstagram[28]", "offPausedUnreadyAndThrowingLeaveItToInstagram[37]"),
            "app.hushgram.extension.instagram.direct.MessagesLockTest" to listOf(
                "lockedMessageNotificationsSayOnlyThatAMessageCame[28]", "lockedMessageNotificationsSayOnlyThatAMessageCame[37]",
                "offUnreadyAndUnlockedLeaveEverythingToInstagram[28]", "offUnreadyAndUnlockedLeaveEverythingToInstagram[37]",
                "theInboxIsCoveredAndThePhoneAskedOnce[28]", "theInboxIsCoveredAndThePhoneAskedOnce[37]",
                "confirmedOpensUntilInstagramLeavesTheScreen[28]", "confirmedOpensUntilInstagramLeavesTheScreen[37]",
                "aChatIsCoveredTooAndNothingElseIs[28]", "aChatIsCoveredTooAndNothingElseIs[37]",
                "lockAllOfInstagramCoversTheWholeScreen[28]", "lockAllOfInstagramCoversTheWholeScreen[37]",
                "lockAgainWaitsAsLongAsYouPicked[28]", "lockAgainWaitsAsLongAsYouPicked[37]",
                "turningALockOnWaitsUntilYouLeave[28]", "turningALockOnWaitsUntilYouLeave[37]",
                "aPhoneWithoutAScreenLockLeavesTheMessagesOpenAndSaysWhy[28]", "aPhoneWithoutAScreenLockLeavesTheMessagesOpenAndSaysWhy[37]",
                "pausedOrInSafeModeALockStillLocksAndCoversEverything[28]", "pausedOrInSafeModeALockStillLocksAndCoversEverything[37]",
                "eachWindowKeepsItsOwnCovers[28]", "eachWindowKeepsItsOwnCovers[37]",
                "screenReadersSkipWhatsCovered[28]", "screenReadersSkipWhatsCovered[37]",
                "theListShowingIsTheOneCovered[28]", "theListShowingIsTheOneCovered[37]",
                "openMessagesStayOutOfTheRecentAppsPicture[28]", "openMessagesStayOutOfTheRecentAppsPicture[37]",
                "lockingAgainHidesWhatTheShadeShows[28]", "lockingAgainHidesWhatTheShadeShows[37]",
                "onlyYourCancelEndsAnAsk[28]", "onlyYourCancelEndsAnAsk[37]",
                "aListedChatIsCoveredWhetherOrNotTheMessagesAreLocked[28]", "aListedChatIsCoveredWhetherOrNotTheMessagesAreLocked[37]",
                "otherChatsOpenAsBefore[28]", "otherChatsOpenAsBefore[37]",
                "cancellingShowsNothing[28]", "cancellingShowsNothing[37]",
                "removingAChatClearsItsLock[28]", "removingAChatClearsItsLock[37]",
                "aListedChatsNotificationsLoseTheirTextAndOthersDont[28]", "aListedChatsNotificationsLoseTheirTextAndOthersDont[37]",
                "aPushIsMarkedWithEveryIdItCarries[28]", "aPushIsMarkedWithEveryIdItCarries[37]",
                "aThreadIdFromThePushIsEnoughToMatchTheChat[28]", "aThreadIdFromThePushIsEnoughToMatchTheChat[37]",
                "aChatLeavingTheFrontIsForgottenButOnlyByItself[28]", "aChatLeavingTheFrontIsForgottenButOnlyByItself[37]",
                "lockingAgainHidesTheListedChatsInTheShade[28]", "lockingAgainHidesTheListedChatsInTheShade[37]",
                "aStartedAppHidesWhatAListedChatLeftInTheShade[28]", "aStartedAppHidesWhatAListedChatLeftInTheShade[37]",
                "theChatsNameIsFoundForTheList[28]", "theChatsNameIsFoundForTheList[37]",
                "theListReadsWhatWasSavedAndNothingElse[28]", "theListReadsWhatWasSavedAndNothingElse[37]",
                "aListedChatStaysLockedWhilePausedAndWithoutContext[28]", "aListedChatStaysLockedWhilePausedAndWithoutContext[37]"),
            "app.hushgram.extension.instagram.direct.HiddenChatsTest" to listOf(
                "aHiddenChatLeavesTheInboxAndTheOthersStay[28]", "aHiddenChatLeavesTheInboxAndTheOthersStay[37]",
                "anEmptyListChangesNothing[28]", "anEmptyListChangesNothing[37]",
                "showingAChatAgainBringsItBack[28]", "showingAChatAgainBringsItBack[37]",
                "aSummaryWhoseIdCantBeReadStaysInTheInbox[28]", "aSummaryWhoseIdCantBeReadStaysInTheInbox[37]",
                "aHiddenChatsPushIsDroppedAndOthersAreNot[28]", "aHiddenChatsPushIsDroppedAndOthersAreNot[37]",
                "aDroppedPushIsNotMarkedForTheLock[28]", "aDroppedPushIsNotMarkedForTheLock[37]",
                "pausedBringsEveryChatBackWhileTheListKeepsWhatWasChosen[28]", "pausedBringsEveryChatBackWhileTheListKeepsWhatWasChosen[37]",
                "nothingIsHiddenWithoutContextAndOtherSettingsDontMatter[28]", "nothingIsHiddenWithoutContextAndOtherSettingsDontMatter[37]",
                "theListReadsAndWritesTheSameLinesAsLockedChats[28]", "theListReadsAndWritesTheSameLinesAsLockedChats[37]",
                "theLastChatOpenedIsOfferedUntilItIsHidden[28]", "theLastChatOpenedIsOfferedUntilItIsHidden[37]",
                "theListOpensBehindThePhoneLockOnlyWhileAMessagesLockIsOn[28]", "theListOpensBehindThePhoneLockOnlyWhileAMessagesLockIsOn[37]"),
            "app.hushgram.extension.instagram.settings.TypingSettingsTest" to listOf(
                "missingPatchHasNoTypingSwitch[28]", "missingPatchHasNoTypingSwitch[37]",
                "typingAloneStillGetsMessages[28]", "typingAloneStillGetsMessages[37]",
                "typingSwitchStartsOffUnderMessagesPersistsAndHonorsPause[28]",
                "typingSwitchStartsOffUnderMessagesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.settings.GhostModeSettingsTest" to listOf(
                "theSwitchListFollowsTheBuild[28]", "theSwitchListFollowsTheBuild[37]",
                "oneGhostPatchGetsNoMasterSwitch[28]", "oneGhostPatchGetsNoMasterSwitch[37]",
                "itSitsFirstUnderAdsAndPrivacyAndStartsAsTheSwitchesAre[28]", "itSitsFirstUnderAdsAndPrivacyAndStartsAsTheSwitchesAre[37]",
                "aTapTurnsEverySwitchAndEachRowShowsIt[28]", "aTapTurnsEverySwitchAndEachRowShowsIt[37]",
                "itFollowsTheOwnSwitchesAndPauseStillWins[28]", "itFollowsTheOwnSwitchesAndPauseStillWins[37]",
                "searchFindsItByName[28]", "searchFindsItByName[37]"),
            "app.hushgram.extension.instagram.feed.SwipeToCreateTest" to listOf(
                "withTheSwitchOnASwipeTowardTheCameraIsHeld[28]", "withTheSwitchOnASwipeTowardTheCameraIsHeld[37]",
                "everyOtherMoveGoesOn[28]", "everyOtherMoveGoesOn[37]",
                "offPausedAndUnreadyLetTheSwipeGo[28]", "offPausedAndUnreadyLetTheSwipeGo[37]",
                "aThrowingSwitchLetsTheSwipeGoAndIsReported[28]", "aThrowingSwitchLetsTheSwipeGoAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.SwipeToCreateSettingsTest" to listOf(
                "missingPatchHasNoSwipeSwitch[28]", "missingPatchHasNoSwipeSwitch[37]",
                "swipeSwitchStartsOffPersistsAndHonorsPause[28]", "swipeSwitchStartsOffPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.feed.FullResolutionTest" to listOf(
                "withTheSwitchOnTheLargestSizeOfTheSameShapeLoads[28]", "withTheSwitchOnTheLargestSizeOfTheSameShapeLoads[37]",
                "eachOutcomeIsCountedUnderItsName[28]", "eachOutcomeIsCountedUnderItsName[37]",
                "thePickersSizesAreTriedWhenThePostsOwnDontListThePick[28]", "thePickersSizesAreTriedWhenThePostsOwnDontListThePick[37]",
                "theHookReadsThroughTheBridgesThePatchFills[28]", "theHookReadsThroughTheBridgesThePatchFills[37]",
                "aPickThatIsAlreadyTheLargestStays[28]", "aPickThatIsAlreadyTheLargestStays[37]",
                "aCropOrAnotherShapeIsNeverPicked[28]", "aCropOrAnotherShapeIsNeverPicked[37]",
                "aSizeRoundedByAPixelIsStillTheSameShape[28]", "aSizeRoundedByAPixelIsStillTheSameShape[37]",
                "nothingOverTheLargestSideLoads[28]", "nothingOverTheLargestSideLoads[37]",
                "aPickThatIsntOneOfThePostsSizesStays[28]", "aPickThatIsntOneOfThePostsSizesStays[37]",
                "aSizeOfAnotherClassIsPassedOver[28]", "aSizeOfAnotherClassIsPassedOver[37]",
                "unreadableSizesLeaveThePick[28]", "unreadableSizesLeaveThePick[37]",
                "offPausedAndUnreadyKeepInstagramsPick[28]", "offPausedAndUnreadyKeepInstagramsPick[37]",
                "aThrowingReadKeepsThePickAndIsReported[28]", "aThrowingReadKeepsThePickAndIsReported[37]",
                "theStockHookKeepsThePick[28]", "theStockHookKeepsThePick[37]"),
            "app.hushgram.extension.instagram.settings.FullResolutionSettingsTest" to listOf(
                "missingPatchHasNoPhotoSwitch[28]", "missingPatchHasNoPhotoSwitch[37]",
                "photoSwitchSitsUnderFeedStartsOffAndSaysItUsesMoreData[28]", "photoSwitchSitsUnderFeedStartsOffAndSaysItUsesMoreData[37]",
                "photoSwitchPersistsAndHonorsPause[28]", "photoSwitchPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.reels.ReelScrollingTest" to listOf(
                "withTheSwitchOnAReelsPagerStaysPut[28]", "withTheSwitchOnAReelsPagerStaysPut[37]",
                "otherPagersAreLeftAlone[28]", "otherPagersAreLeftAlone[37]",
                "aPagerSetUpWhileOffIsHeldOnceOn[28]", "aPagerSetUpWhileOffIsHeldOnceOn[37]",
                "offPausedAndUnreadyKeepReelsScrolling[28]", "offPausedAndUnreadyKeepReelsScrolling[37]",
                "aThrowingSwitchKeepsReelsScrollingAndIsReported[28]", "aThrowingSwitchKeepsReelsScrollingAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.ReelScrollingSettingsTest" to listOf(
                "missingPatchHasNoReelScrollingSwitch[28]", "missingPatchHasNoReelScrollingSwitch[37]",
                "reelScrollingSwitchStartsOffPersistsAndHonorsPause[28]", "reelScrollingSwitchStartsOffPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.profile.FollowingListTest" to listOf(
                "ownFollowingListMarksOnlyWhoDoesNotFollowBack[28]", "ownFollowingListMarksOnlyWhoDoesNotFollowBack[37]",
                "aRowWithNoNameShowsTheMarkOnItsOwn[28]", "aRowWithNoNameShowsTheMarkOnItsOwn[37]",
                "anUnknownAnswerStaysUnmarked[28]", "anUnknownAnswerStaysUnmarked[37]",
                "otherListsStayUnmarked[28]", "otherListsStayUnmarked[37]",
                "offPausedAndUnreadyKeepInstagramsRow[28]", "offPausedAndUnreadyKeepInstagramsRow[37]",
                "aThrowingReaderOrSwitchKeepsTheRowAndIsReported[28]", "aThrowingReaderOrSwitchKeepsTheRowAndIsReported[37]",
                "aRecycledRowLosesItsStaleMark[28]", "aRecycledRowLosesItsStaleMark[37]",
                "ownFollowingListPutsWhoDoesNotFollowBackFirstAndKeepsTheOrder[28]", "ownFollowingListPutsWhoDoesNotFollowBackFirstAndKeepsTheOrder[37]",
                "aListAlreadyInOrderComesBackAsItIs[28]", "aListAlreadyInOrderComesBackAsItIs[37]",
                "aLaterYesKeepsAnAnsweredNoInTheSecondGroup[28]", "aLaterYesKeepsAnAnsweredNoInTheSecondGroup[37]",
                "otherListsAndAnOffSwitchKeepInstagramsOrder[28]", "otherListsAndAnOffSwitchKeepInstagramsOrder[37]",
                "aThrowingReaderKeepsTheListAndIsReported[28]", "aThrowingReaderKeepsTheListAndIsReported[37]",
                "eitherSwitchHasTheListAskAboutEveryRow[28]", "eitherSwitchHasTheListAskAboutEveryRow[37]"),
            "app.hushgram.extension.instagram.stories.StoryMarksTest" to listOf(
                "unmarkedStoriesNeverReachTheRequest[28]", "unmarkedStoriesNeverReachTheRequest[37]",
                "aMarkedStoryGoesOnceAndOnlyOnce[28]", "aMarkedStoryGoesOnceAndOnlyOnce[37]",
                "severalMarksGoTogether[28]", "severalMarksGoTogether[37]",
                "aMarkForAStoryNotInTheBatchWaitsThenLapses[28]", "aMarkForAStoryNotInTheBatchWaitsThenLapses[37]",
                "aStoryHeldBackBeforeItsMarkGoesWithTheNextSend[28]", "aStoryHeldBackBeforeItsMarkGoesWithTheNextSend[37]",
                "anUndoneMarkSendsNothing[28]", "anUndoneMarkSendsNothing[37]",
                "keysOfAnotherShapeStayHeldBack[28]", "keysOfAnotherShapeStayHeldBack[37]",
                "offPausedUnreadyOrThrowingKeepsStockBehavior[28]", "offPausedUnreadyOrThrowingKeepsStockBehavior[37]",
                "theSwitchesFollowTheSettingsAndThePause[28]", "theSwitchesFollowTheSettingsAndThePause[37]",
                "throwingDiagnosticsNeverLetTheWholeBatchOut[28]", "throwingDiagnosticsNeverLetTheWholeBatchOut[37]",
                "aMarkBelongsToTheAccountItWasMadeOn[28]", "aMarkBelongsToTheAccountItWasMadeOn[37]",
                "withNoAccountNothingIsMarkedKeptOrSent[28]", "withNoAccountNothingIsMarkedKeptOrSent[37]",
                "aBatchThatCantStartEmptyKeepsTheMarkedStoriesForLater[28]", "aBatchThatCantStartEmptyKeepsTheMarkedStoriesForLater[37]",
                "aRetriedBatchHeldBackIsCanceled[28]", "aRetriedBatchHeldBackIsCanceled[37]"),
            "app.hushgram.extension.instagram.stories.StoryRetryTest" to listOf(
                "nullFactoryCancels", "throwingFactoryCancels", "allocationFailureCancels", "nonemptyFactoryCancels",
                "unreadableMapCancels", "failingMapCancels", "failingAccountCancels", "noMarksNeedsNoEmptyFactory",
                "offPreservesTheOriginalEvenWithBrokenAdapters", "failingMarkingSwitchCancels",
                "anonymityGateExceptionCannotReviveAnEarlierHeldRetry",
                "anonymityGateAllocationFailureCannotReviveAnEarlierHeldRetry",
                "diagnosticsCannotCancelAValidMarkedSubset", "aSecondAccountCannotUseTheFirstAccountsMark",
                "repeatedFactoryFailuresKeepUnmarkedReceiptsPrivateAndTheMarkEligible",
                "aRetryDelayedPastMarkExpiryCancelsInsteadOfSendingItsOriginal",
                "accountTeardownCannotReviveHeldReceiptsOrMarks"
            ).flatMap { listOf("$it[28]", "$it[37]") },
            "app.hushgram.extension.instagram.stories.StorySeenButtonTest" to listOf(
                "theButtonGoesBeforeTheMenuAndSaysWhatItDoes[28]", "theButtonGoesBeforeTheMenuAndSaysWhatItDoes[37]",
                "aTapMarksTheStoryAndASecondTapUndoesIt[28]", "aTapMarksTheStoryAndASecondTapUndoesIt[37]",
                "aTapOnAStoryAlreadyHeldBackSendsItRightAway[28]", "aTapOnAStoryAlreadyHeldBackSendsItRightAway[37]",
                "aRecycledHeaderFollowsItsNewStory[28]", "aRecycledHeaderFollowsItsNewStory[37]",
                "aStoryThatIsntAPostHasNoButton[28]", "aStoryThatIsntAPostHasNoButton[37]",
                "offPausedOrUnreadyShowsNoButton[28]", "offPausedOrUnreadyShowsNoButton[37]",
                "aHeaderWithoutTheRowIsReported[28]", "aHeaderWithoutTheRowIsReported[37]",
                "aThrowingReaderOrSwitchIsReportedAndLeavesTheHeader[28]", "aThrowingReaderOrSwitchIsReportedAndLeavesTheHeader[37]",
                "aMarkOnOneAccountIsNeverSentForAnother[28]", "aMarkOnOneAccountIsNeverSentForAnother[37]",
                "withoutTheAccountThereIsNoButton[28]", "withoutTheAccountThereIsNoButton[37]",
                "aHiddenButtonForgetsItsStory[28]", "aHiddenButtonForgetsItsStory[37]"),
            "app.hushgram.extension.instagram.reels.ReelsSuggestionsTest" to listOf(
                "offPausedAndUnreadyKeepEveryItem[28]", "offPausedAndUnreadyKeepEveryItem[37]",
                "aThrowingReaderOrSwitchKeepsTheItemAndIsReported[28]", "aThrowingReaderOrSwitchKeepsTheItemAndIsReported[37]"),
            "app.hushgram.extension.instagram.reels.ReelSeekBarTest" to listOf(
                "offPausedAndUnreadyLeaveInstagramsAnswers[28]", "offPausedAndUnreadyLeaveInstagramsAnswers[37]",
                "aThrowingSwitchLeavesInstagramsAnswersAndIsReported[28]", "aThrowingSwitchLeavesInstagramsAnswersAndIsReported[37]",
                "rightToLeftPutsTheLabelAtTheLeftEnd[28]", "rightToLeftPutsTheLabelAtTheLeftEnd[37]",
                "twiceTheTextSizeStillFits[28]", "twiceTheTextSizeStillFits[37]",
                "aContainerWithNoRoomPutsTheWholeLabelOnTheViewAboveIt[28]",
                "aContainerWithNoRoomPutsTheWholeLabelOnTheViewAboveIt[37]",
                "aBarSetBeforeItsLayoutGetsItsLabelOnceLaidOut[28]", "aBarSetBeforeItsLayoutGetsItsLabelOnceLaidOut[37]",
                "theLabelLeavesWithItsBar[28]", "theLabelLeavesWithItsBar[37]",
                "theLabelHidesWithItsBarOrAnyViewAbove[28]", "theLabelHidesWithItsBarOrAnyViewAbove[37]",
                "aHostRecycledForAnItemWithNoBarKeepsNoLabel[28]", "aHostRecycledForAnItemWithNoBarKeepsNoLabel[37]",
                "aReplacedBarLeavesOneLabelOnItsHost[28]", "aReplacedBarLeavesOneLabelOnItsHost[37]",
                "aBarWithNoRoomKeepsOneLabelAndOneListener[28]", "aBarWithNoRoomKeepsOneLabelAndOneListener[37]",
                "anOrdinaryReelBoundAgainKeepsItsLabelUp[28]", "anOrdinaryReelBoundAgainKeepsItsLabelUp[37]",
                "twoBarsSharingAHostKeepOneLabelAcrossAMove[28]", "twoBarsSharingAHostKeepOneLabelAcrossAMove[37]",
                "aBarFurtherDownItsContainerStillCounts[28]", "aBarFurtherDownItsContainerStillCounts[37]",
                "adsAndOtherScreensGetNoLabel[28]", "adsAndOtherScreensGetNoLabel[37]"),
            "app.hushgram.extension.instagram.settings.CommentCopySettingsTest" to listOf(
                "missingPatchHasNoCommentSwitch[28]", "missingPatchHasNoCommentSwitch[37]",
                "commentsSwitchStartsOffPersistsAndHonorsPause[28]", "commentsSwitchStartsOffPersistsAndHonorsPause[37]",
                "aBuildWithoutCopyUsernameKeepsCopyWithoutTheSecondSwitch[28]", "aBuildWithoutCopyUsernameKeepsCopyWithoutTheSecondSwitch[37]",
                "aBuildCopyingUsernamesReportsNothingMissing[28]", "aBuildCopyingUsernamesReportsNothingMissing[37]"),
            "app.hushgram.extension.instagram.settings.CommentPhotoSettingsTest" to listOf(
                "missingPatchHasNoCommentPhotoSwitch[28]", "missingPatchHasNoCommentPhotoSwitch[37]",
                "commentPhotoSwitchStartsOffPersistsAndHonorsPause[28]", "commentPhotoSwitchStartsOffPersistsAndHonorsPause[37]",
                "bothCommentSwitchesShareOneCategoryAndStayIndependent[28]", "bothCommentSwitchesShareOneCategoryAndStayIndependent[37]"),
            "app.hushgram.extension.instagram.settings.FollowingListSettingsTest" to listOf(
                "missingPatchHasNoFollowingListSwitch[28]", "missingPatchHasNoFollowingListSwitch[37]",
                "followingListSwitchStartsOffPersistsAndHonorsPause[28]", "followingListSwitchStartsOffPersistsAndHonorsPause[37]",
                "orderSwitchStartsOffUnderTheMarkAndHonorsPause[28]", "orderSwitchStartsOffUnderTheMarkAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.reels.ReelAutoScrollTest" to listOf(
                "anOnAnswerIsRememberedAndOutlivesInstagramsOff[28]", "anOnAnswerIsRememberedAndOutlivesInstagramsOff[37]",
                "turningItOffIsRemembered[28]", "turningItOffIsRemembered[37]",
                "turningItOnWaitsForInstagram[28]", "turningItOnWaitsForInstagram[37]",
                "aChoiceInstagramKeepsIsRememberedAtOnce[28]", "aChoiceInstagramKeepsIsRememberedAtOnce[37]",
                "aStaleSavedOnDoesNotTurnItBackOn[28]", "aStaleSavedOnDoesNotTurnItBackOn[37]",
                "theSavedPreferenceIsAnsweredButNeverRemembered[28]", "theSavedPreferenceIsAnsweredButNeverRemembered[37]",
                "nothingIsWrittenWhenTheChoiceIsUnchanged[28]", "nothingIsWrittenWhenTheChoiceIsUnchanged[37]",
                "offPausedAndUnreadyKeepInstagramsAnswer[28]", "offPausedAndUnreadyKeepInstagramsAnswer[37]",
                "aThrowingSwitchOrMemoryKeepsInstagramsAnswerAndIsReported[28]", "aThrowingSwitchOrMemoryKeepsInstagramsAnswerAndIsReported[37]"),
            "app.hushgram.extension.instagram.settings.ReelAutoScrollSettingsTest" to listOf(
                "missingPatchHasNoAutoScrollSwitch[28]", "missingPatchHasNoAutoScrollSwitch[37]",
                "autoScrollSwitchStartsOffPersistsAndHonorsPause[28]", "autoScrollSwitchStartsOffPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.media.TapToPlayTest" to listOf(
                "autoScrollStartsTheReelItMovesToAndArmsIt[28]", "autoScrollStartsTheReelItMovesToAndArmsIt[37]",
                "aMoveStartsOnePlayerOnly[28]", "aMoveStartsOnePlayerOnly[37]",
                "aStartLaterThanALoadAfterTheMoveIsHeld[28]", "aStartLaterThanALoadAfterTheMoveIsHeld[37]",
                "anArmedPlayerOrNoPlayerLeavesTheMoveToTheNextReel[28]", "anArmedPlayerOrNoPlayerLeavesTheMoveToTheNextReel[37]",
                "aSwipeEndsAMoveNoStartHasUsed[28]", "aSwipeEndsAMoveNoStartHasUsed[37]",
                "theAutoScrollHookStartsTheNextReelThroughTheGate[28]", "theAutoScrollHookStartsTheNextReelThroughTheGate[37]",
                "offPausedOrNotReadyAMoveRecordsNothing[28]", "offPausedOrNotReadyAMoveRecordsNothing[37]",
                "aFailingMoveRecordsNothingAndTheReportSaysSo[28]", "aFailingMoveRecordsNothingAndTheReportSaysSo[37]"),
            "app.hushgram.extension.instagram.settings.StorySeenSettingsTest" to listOf(
                "missingPatchHasNoMarkAsSeenSwitch[28]", "missingPatchHasNoMarkAsSeenSwitch[37]",
                "markAsSeenSwitchStartsOffPersistsAndHonorsPause[28]", "markAsSeenSwitchStartsOffPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.stories.StoryTimeTest" to listOf(
                "aTwelveHourPhoneGetsTheDateAndTimeWithPm[28]", "aTwelveHourPhoneGetsTheDateAndTimeWithPm[37]",
                "aTwentyFourHourPhoneGetsTheTwentyFourHourTime[28]", "aTwentyFourHourPhoneGetsTheTwentyFourHourTime[37]",
                "theLabelFollowsThePhonesLanguage[28]", "theLabelFollowsThePhonesLanguage[37]",
                "offPausedAndUnreadyKeepInstagramsLabel[28]", "offPausedAndUnreadyKeepInstagramsLabel[37]",
                "theChoiceStartsAtTheDateAndTime[28]", "theChoiceStartsAtTheDateAndTime[37]",
                "timeLeftCountsDownToADayAfterPosting[28]", "timeLeftCountsDownToADayAfterPosting[37]",
                "theHookCountsTimeLeftFromNow[28]", "theHookCountsTimeLeftFromNow[37]",
                "aStoryADayOldShowsTheDateAndTimeInsteadOfTimeLeft[28]", "aStoryADayOldShowsTheDateAndTimeInsteadOfTimeLeft[37]",
                "timePostedShowsOnlyTheTimeOfDay[28]", "timePostedShowsOnlyTheTimeOfDay[37]",
                "timePostedOnAnEarlierDayShowsTheDateToo[28]", "timePostedOnAnEarlierDayShowsTheDateToo[37]",
                "oneMinuteLeftTakesTheSingularInSpanish[28]", "oneMinuteLeftTakesTheSingularInSpanish[37]",
                "oneMinuteLeftTakesTheSingularInPortuguese[28]", "oneMinuteLeftTakesTheSingularInPortuguese[37]",
                "everyModeKeepsInstagramsLabelOffPausedAndUnready[28]", "everyModeKeepsInstagramsLabelOffPausedAndUnready[37]"),
            "app.hushgram.extension.instagram.settings.StoryTimeSettingsTest" to listOf(
                "missingPatchHasNoStoryTimeSwitch[28]", "missingPatchHasNoStoryTimeSwitch[37]",
                "storyTimeSwitchStartsOffUnderStoriesPersistsAndHonorsPause[28]", "storyTimeSwitchStartsOffUnderStoriesPersistsAndHonorsPause[37]",
                "theChoiceSitsBelowTheSwitchStartsAtTheDateAndTimeAndSaysWhatItShows[28]",
                "theChoiceSitsBelowTheSwitchStartsAtTheDateAndTimeAndSaysWhatItShows[37]"),
            "app.hushgram.extension.instagram.stories.StoryLoopTest" to listOf(
                "onEveryStoryLoops[28]", "onEveryStoryLoops[37]",
                "offPausedAndUnreadyKeepInstagramsAnswer[28]", "offPausedAndUnreadyKeepInstagramsAnswer[37]",
                "bothOnAStoryThatCanLoopLoops[28]", "bothOnAStoryThatCanLoopLoops[37]",
                "bothOnAStoryThatCantLoopIsStillHeld[28]", "bothOnAStoryThatCantLoopIsStillHeld[37]",
                "stopAloneHoldsEveryStory[28]", "stopAloneHoldsEveryStory[37]",
                "loopAloneLeavesStopOutOfIt[28]", "loopAloneLeavesStopOutOfIt[37]",
                "pausedAndUnreadyHoldNothing[28]", "pausedAndUnreadyHoldNothing[37]",
                "withoutTheLoopPatchStopHoldsAsBefore[28]", "withoutTheLoopPatchStopHoldsAsBefore[37]"),
            "app.hushgram.extension.instagram.settings.StoryLoopSettingsTest" to listOf(
                "missingPatchHasNoStoryLoopSwitch[28]", "missingPatchHasNoStoryLoopSwitch[37]",
                "storyLoopSwitchStartsOffUnderStoriesPersistsAndHonorsPause[28]", "storyLoopSwitchStartsOffUnderStoriesPersistsAndHonorsPause[37]"),
            "app.hushgram.extension.instagram.settings.OverrideNavigationTest" to listOf(
                "missingPatchHasNoNativeAction[28]", "missingPatchHasNoNativeAction[37]",
                "unavailableSessionOrNavigationKeepsTheDialogAndShowsRecovery[28]", "unavailableSessionOrNavigationKeepsTheDialogAndShowsRecovery[37]",
                "successfulNavigationClosesTheDialogWithoutEnablingLongPress[28]", "successfulNavigationClosesTheDialogWithoutEnablingLongPress[37]",
                "finishingDestroyedOrSavedHostsNeverCallNativeNavigation[28]", "finishingDestroyedOrSavedHostsNeverCallNativeNavigation[37]",
                "missingPatchHasNoWhitehatAction[28]", "missingPatchHasNoWhitehatAction[37]",
                "unavailableWhitehatKeepsTheDialogAndShowsRecovery[28]", "unavailableWhitehatKeepsTheDialogAndShowsRecovery[37]",
                "openedWhitehatClosesTheDialogWithoutEnablingLongPress[28]", "openedWhitehatClosesTheDialogWithoutEnablingLongPress[37]",
                "finishingDestroyedOrSavedHostsNeverOpenWhitehat[28]", "finishingDestroyedOrSavedHostsNeverOpenWhitehat[37]"),
            "app.hushgram.extension.instagram.settings.SettingsDialogBoundaryTest" to listOf(
                "sdk28DialogKeepsLegacyBarsOutsideLargeTextContent[28]", "sdk37DialogKeepsSystemBarsOutsideLargeTextContent[37]",
                "sdk28DialogMirrorsItsLargeTextHeader[28]", "sdk37DialogMirrorsItsLargeTextHeader[37]",
                "diagnosticChooserClosesWithItsSettingsPage[28]", "diagnosticChooserClosesWithItsSettingsPage[29]", "diagnosticChooserClosesWithItsSettingsPage[37]",
                "aLateReportTapCannotOpenAChooserAfterPageTeardown[28]", "aLateReportTapCannotOpenAChooserAfterPageTeardown[29]", "aLateReportTapCannotOpenAChooserAfterPageTeardown[37]"),
            "app.hushgram.extension.instagram.settings.SignInNoticeTest" to listOf(
                "aFailedDismissalKeepsTheNoticeAndExplainsTheFailure[28]", "aFailedDismissalKeepsTheNoticeAndExplainsTheFailure[29]",
                "aFailedDismissalKeepsTheNoticeAndExplainsTheFailure[30]", "aFailedDismissalKeepsTheNoticeAndExplainsTheFailure[37]",
                "aFailedDismissalDoesNotClaimAnUnprovenRollback[28]", "aFailedDismissalDoesNotClaimAnUnprovenRollback[29]",
                "aFailedDismissalDoesNotClaimAnUnprovenRollback[30]", "aFailedDismissalDoesNotClaimAnUnprovenRollback[37]"),
            "app.hushgram.extension.instagram.settings.PauseRecoveryTest" to listOf(
                "aWorkerCommitDisablesBothRecoveryInputsAndIgnoresDuplicateTaps[28]", "aWorkerCommitDisablesBothRecoveryInputsAndIgnoresDuplicateTaps[29]", "aWorkerCommitDisablesBothRecoveryInputsAndIgnoresDuplicateTaps[37]",
                "aFailedCommitKeepsTheSavedPauseAndRestoresTheRetryControls[28]", "aFailedCommitKeepsTheSavedPauseAndRestoresTheRetryControls[29]", "aFailedCommitKeepsTheSavedPauseAndRestoresTheRetryControls[37]",
                "aFailedRollbackShowsThePauseValueThatActuallySurvived[28]", "aFailedRollbackShowsThePauseValueThatActuallySurvived[29]", "aFailedRollbackShowsThePauseValueThatActuallySurvived[37]",
                "aFullWorkerQueueLeavesPauseSavedAndBothControlsReadyToRetry[28]", "aFullWorkerQueueLeavesPauseSavedAndBothControlsReadyToRetry[29]", "aFullWorkerQueueLeavesPauseSavedAndBothControlsReadyToRetry[37]",
                "recoveryFinishesAfterTeardownWithoutUpdatingDetachedControlsOrAcceptingALateTap[28]", "recoveryFinishesAfterTeardownWithoutUpdatingDetachedControlsOrAcceptingALateTap[29]", "recoveryFinishesAfterTeardownWithoutUpdatingDetachedControlsOrAcceptingALateTap[37]"),
            "app.hushgram.extension.instagram.settings.NavigationSettingsTest" to listOf(
                "selectedGestureOpensOnceWithoutCallingHomeDeveloperHandler[28]", "selectedGestureOpensOnceWithoutCallingHomeDeveloperHandler[37]",
                "everyNativeTabCanBeChosenWithoutInventingAButton[28]", "everyNativeTabCanBeChosenWithoutInventingAButton[37]",
                "offAndUnselectedReturnTheExactNativeListener[28]", "offAndUnselectedReturnTheExactNativeListener[37]",
                "pausedListenerDelegatesWithoutChangingSavedChoice[28]", "pausedListenerDelegatesWithoutChangingSavedChoice[37]",
                "aPausedStartKeepsTheNativeHandlerAndTheNextStartBindsTheSavedGesture[28]", "aPausedStartKeepsTheNativeHandlerAndTheNextStartBindsTheSavedGesture[37]",
                "rebindUsesNewestNativeHandlerAndDoesNotStackWrappers[28]", "rebindUsesNewestNativeHandlerAndDoesNotStackWrappers[37]",
                "nullTeardownStaysNullAndNormalTapStaysNative[28]", "nullTeardownStaysNullAndNormalTapStaysNative[37]",
                "selectedTabWithNoNativeLongPressStillOpensSettings[28]", "selectedTabWithNoNativeLongPressStillOpensSettings[37]",
                "detachedHiddenAndDisabledButtonsCannotOpenSettings[28]", "detachedHiddenAndDisabledButtonsCannotOpenSettings[37]",
                "offAndUnreadyDoNotAddALongClickAction[28]", "offAndUnreadyDoNotAddALongClickAction[37]",
                "retainedListenerUsesStockWhileSettingsBecomeUnready[28]", "retainedListenerUsesStockWhileSettingsBecomeUnready[37]",
                "reelsGestureKeepsItsNativeActionUnlessReelsIsChosen[28]", "reelsGestureKeepsItsNativeActionUnlessReelsIsChosen[37]",
                "accessibilityLongClickPreservesLabelAndKeyboardFocus[28]", "accessibilityLongClickPreservesLabelAndKeyboardFocus[37]",
                "stoppedOrDestroyedOwnerCannotReceiveADeferredGesture[28]", "stoppedOrDestroyedOwnerCannotReceiveADeferredGesture[37]",
                "gestureSettingsFollowSignedOutModalAndCloseStaysClosed[28]", "gestureSettingsFollowSignedOutModalAndCloseStaysClosed[37]",
                "inactiveWrapperKeepsTheNativeHapticDecision[37]"),
            "app.hushgram.extension.instagram.settings.NavigationChoiceTest" to listOf(
                "choiceStartsOffOffersEveryNativeNameAndPersistsOneSelection[28]", "choiceStartsOffOffersEveryNativeNameAndPersistsOneSelection[37]",
                "pauseShowsTheSavedChoiceWhileTheHookAnswersOff[28]", "pauseShowsTheSavedChoiceWhileTheHookAnswersOff[37]",
                "theChooserAndSelectedSummaryUseTranslatedLabels[28]", "theChooserAndSelectedSummaryUseTranslatedLabels[37]",
                "defaultAndSelectedSettingsRowsRenderWithoutTruncatingTheirText[28]", "defaultAndSelectedSettingsRowsRenderWithoutTruncatingTheirText[37]"),
            "app.hushgram.extension.instagram.settings.SettingsEntryOpenTest" to listOf(
                "movesToAScreenInstagramOpensOverIt[28]", "movesToAScreenInstagramOpensOverIt[37]",
                "staysClosedOnceThePersonClosedIt[28]", "staysClosedOnceThePersonClosedIt[37]"),
            "app.hushgram.extension.instagram.settings.SettingsThemeWaitTest" to listOf(
                "waitsForAThemeThatCanDrawText[28]", "waitsForAThemeThatCanDrawText[37]",
                "aScreenPutBackOverAnUnthemedHostClosesAndComesBack[28]", "aScreenPutBackOverAnUnthemedHostClosesAndComesBack[37]",
                "aFailedScreenPutBackOverAnUnthemedHostClosesAndComesBack[28]", "aFailedScreenPutBackOverAnUnthemedHostClosesAndComesBack[37]"),
            "app.hushgram.extension.instagram.settings.SettingsScreenRowLayoutTest" to listOf(
                "theTitleWrapsAtTwiceTheTextSize[28]", "theTitleWrapsAtTwiceTheTextSize[37]",
                "rightToLeftPutsTheMarkOnTheRight[28]", "rightToLeftPutsTheMarkOnTheRight[37]"),
            "app.hushgram.extension.shared.settings.HushgramPauseTest" to listOf(
                "android9CountsOnlyTheHandlersMark[28]", "android11CountsCrashesNativeCrashesAndHangsButNotBeingSwipedAway[37]"),
            "app.hushgram.extension.shared.settings.preference.LogBufferManagerExportTest" to listOf(
                "android9SavesTheReportInInstagramsOwnFolder[28]", "noExitOnRecordMeansNoLastExitSection[28]",
                "theReportSaysWhyTheProcessWentAwayLastTime[37]", "repeatedExportsEachGetTheirOwnDownloadsEntry[37]",
                "reportRowAndChooserNameTheFolderThatTheWriterUses[28]", "reportRowAndChooserNameTheFolderThatTheWriterUses[29]", "reportRowAndChooserNameTheFolderThatTheWriterUses[37]"),
            "app.hushgram.extension.instagram.download.MediaSaveTest" to listOf(
                "onAndroid9ASaveWritesTheFileIntoTheFolderItself[28]",
                "onAndroid9OverlappingSavesOwnSeparateWorkFilesAndLedgerRows[28]",
                "onAndroid9ARefusedLedgerRemovesOnlyItsReservedWorkFile[28]",
                "onAndroid9ConcurrentCommitsKeepEveryCompletedFile[28]"),
            "app.hushgram.extension.instagram.download.SaveInterruptionTest" to listOf(
                "sdk28InterruptedSaveRemovesItsHiddenStorageFileBeforeNotice[28]",
                "anInaccessibleAbsentRowRetiresAfterAnExactQuery[30]", "anInaccessibleAbsentRowRetiresAfterAnExactQuery[37]",
                "anInaccessiblePublishedRowIsKeptButItsStaleLedgerRetires[30]", "anInaccessiblePublishedRowIsKeptButItsStaleLedgerRetires[37]",
                "aDeleteExceptionNeverAcknowledgesAStillPendingRow[30]", "aDeleteExceptionNeverAcknowledgesAStillPendingRow[37]",
                "aDeleteExceptionWithUnknownQueryStateKeepsTheRetryRecord[30]", "aDeleteExceptionWithUnknownQueryStateKeepsTheRetryRecord[37]"),
            "app.hushgram.extension.instagram.settings.SettingsRowAccessibilityTest" to listOf(
                "offSwitchHasAReadableRoleStateAndOneWorkingClick",
                "disabledSwitchOffersNoClickAndCannotChangeItsSavedValue",
                "anActionRunsOnceAndRetainedRowsCannotRunAfterPageTeardown",
                "disablingAnAlreadyBoundSwitchImmediatelyRemovesItsAction",
                "hiddenAndNonselectableSwitchesNeverChangeSettings",
                "aFilteredRowCannotClickTheNewItemAtItsOldPosition",
                "aRetainedSwitchCannotMutateAfterItsListLeavesTheWindow",
                "pausedSwitchesDescribeSavedChoicesAndStillOfferDeliberateChanges",
                "translatedLabelsAndDisabledExplanationsAreExposedByTheNodes",
                "rtlLargeTextRetainsTheSwitchRoleAndReadableLabels",
                "focusedRowsKeepTheirIdentityAndCurrentStateDuringRebinding",
                "aDisabledActionCannotOpenItsDialog",
                "aRefusedSwitchChangeRunsItsListenerOnceAndKeepsTheOffState",
                "anAccessibleChoiceOpensItsNativeDialogAndUpdatesTheReadableValue",
                "anAccessibleNavigationChoiceSavesAndAnnouncesItsSelectedTab",
                "missingFamiliesAndInformationalRowsOfferNoFeatureAction",
                "accessibilitySearchAndClearKeepInputFocusAndRestoreTheSameControls",
                "searchFocusHoldsBalanceAcrossRepeatedActionsAndPageTeardown",
                "searchFocusNeverReleasesAnotherOwnersTransientState",
                "disablingAccessibilityThenClearingFocusReleasesSearchHold",
                "retainedClosedSearchClearCannotAdvertiseOrPerformClick",
                "aDisabledSettingsListCannotToggleItsChildSwitch",
                "aRemovedSectionCannotStillToggleItsChildBeforeRebinding",
                "aReplacedScreenCannotAcceptItsPreviousRowBeforeRebinding",
                "searchControlsRespectDisabledAncestorsAndRejectClosedPageEdits"
            ).flatMap { listOf("$it[28]", "$it[37]") },
            "app.hushgram.extension.instagram.settings.SettingsSearchTest" to listOf(
                "clearingRestoresTheOriginalObjectsOrderAndChoices[28]", "clearingRestoresTheOriginalObjectsOrderAndChoices[37]",
                "hiddenRowsStillSynchronizeStoredChangesAndParentAvailability[28]", "hiddenRowsStillSynchronizeStoredChangesAndParentAvailability[37]",
                "noMatchIsLocalizedWhilePauseAndRecoveryStayReachable[28]", "noMatchIsLocalizedWhilePauseAndRecoveryStayReachable[37]",
                "typingAndClearUseTheInlineAccessibleControls[28]", "typingAndClearUseTheInlineAccessibleControls[37]",
                "frameworkStateDoesNotRetainTheSearchQuery[28]", "frameworkStateDoesNotRetainTheSearchQuery[37]",
                "theLiveInlineControlsFitAndMirrorAtTwiceTheTextSize[28]", "theLiveInlineControlsFitAndMirrorAtTwiceTheTextSize[37]",
                "aSaveStartedDuringFilteringKeepsOneWorkingCancelRow[28]", "aSaveStartedDuringFilteringKeepsOneWorkingCancelRow[37]",
                "aCarouselKeepsItsCancelIdentityAcrossFilteredPageChanges[28]", "aCarouselKeepsItsCancelIdentityAcrossFilteredPageChanges[37]",
                "aCompleteCarouselOutcomeSurvivesFilteringAndReopeningSettings[28]", "aCompleteCarouselOutcomeSurvivesFilteringAndReopeningSettings[37]",
                "aCancelledCarouselShowsEveryCountAndQualityWarningAtLargeText[28]", "aCancelledCarouselShowsEveryCountAndQualityWarningAtLargeText[37]"),
            "app.hushgram.extension.instagram.download.CarouselSaveTest" to listOf(
                "aMixedBatchPreservesOrderSnapshotsAndQuality[30]", "failedAndDisabledPagesHaveExactCountsAndNoRetry[30]",
                "tooManyPagesAreRejectedAndTheExactLimitIsAccepted[30]", "oneCancelStopsTheTransferAndAllRemainingPages[30]",
                "cancelBeforeCommitRemovesThePendingRowAndKeepsEarlierFiles[30]", "aBatchUsesOneSlotAndNeverQueuesPastTheExistingLimit[30]",
                "currentPageDownloadAndSaveAllReadDifferentSnapshots[30]", "theSeparateMenuActionUsesItsLabelAndKeepsNativeOptionsIntact[30]",
                "exhaustedPreferenceRetirementDoesNotTurnTheBatchIntoAnInterruption[30]", "cancellationSurvivesExhaustedPreferenceRetirementToo[30]",
                "completeCountsArePublishedBeforeTheRowEndsAndRefusedStartsKeepThem[30]",
                "theSameNotificationCancelSurvivesEveryPageAndPhase[28]", "theSameNotificationCancelSurvivesEveryPageAndPhase[37]"),
            "app.hushgram.extension.instagram.download.SaveProgressTest" to listOf(
                "aSaveShowsItsProgressAtOnceAndCancelStopsIt[28]", "aSaveShowsItsProgressAtOnceAndCancelStopsIt[37]",
                "belowAndroid13TheCancelReceiverIsRegisteredWithNoFlag[30]", "onAndroid17TheCancelReceiverIsNotExported[37]",
                "aFinishedSaveTakesItsNotificationAwayAndLeavesNoRowPending[37]", "startingInstagramRemovesWhatAStoppedSaveLeft[37]",
                "aPendingRowSweepRetriesAfterTheGalleryThrows[37]"),
            "app.hushgram.extension.instagram.misc.OverrideImportTest" to listOf(
                "aMatchingFileMakesNoNativeCallAndKeepsTheStoreByteIdentical[28]", "aMatchingFileMakesNoNativeCallAndKeepsTheStoreByteIdentical[37]",
                "aValidImportMakesOnlyTypedWritesKeepsThePreviousCopyAndNeedsARestart[28]", "aValidImportMakesOnlyTypedWritesKeepsThePreviousCopyAndNeedsARestart[37]",
                "malformedOversizedDuplicatedMismatchedAndUnknownFilesChangeNothing[28]", "malformedOversizedDuplicatedMismatchedAndUnknownFilesChangeNothing[37]",
                "aTypeTheDecoderDisagreesWithOrAMissingNativeTableRefusesBeforeWriting[28]", "aTypeTheDecoderDisagreesWithOrAMissingNativeTableRefusesBeforeWriting[37]",
                "aSessionOrStoreChangeAfterTheFirstReadRefusesBeforeWriting[28]", "aSessionOrStoreChangeAfterTheFirstReadRefusesBeforeWriting[37]",
                "permissionRevokedWhileSettlingOrAtCommitRefusesBeforeAnyMutation[28]", "permissionRevokedWhileSettlingOrAtCommitRefusesBeforeAnyMutation[37]",
                "permissionRevokedDuringRestoreOrDiscardPreservesTheSavedCopy[28]", "permissionRevokedDuringRestoreOrDiscardPreservesTheSavedCopy[37]",
                "anEditInstagramHasntWrittenToTheFileYetIsSeenBeforeAnyWrite[28]", "anEditInstagramHasntWrittenToTheFileYetIsSeenBeforeAnyWrite[37]",
                "aWriteFailureOrAStoreThatDoesntKeepTheChangeIsPutBack[28]", "aWriteFailureOrAStoreThatDoesntKeepTheChangeIsPutBack[37]",
                "aFailedImportKeepsThePreviousRestorePoint[28]", "aFailedImportKeepsThePreviousRestorePoint[37]",
                "anImportThatStoppedPartwayRestoresFromItsOwnPendingCopy[28]", "anImportThatStoppedPartwayRestoresFromItsOwnPendingCopy[37]",
                "anUnconfirmedPutBackArmsRestoreAndBlocksImportsUntilItRuns[28]", "anUnconfirmedPutBackArmsRestoreAndBlocksImportsUntilItRuns[37]",
                "restoreReportsANullItCantPutBackAndDiscardUnblocksImports[28]", "restoreReportsANullItCantPutBackAndDiscardUnblocksImports[37]",
                "aMarkerFromAnotherInstagramBuildKeepsBlockingUntilDiscarded[28]", "aMarkerFromAnotherInstagramBuildKeepsBlockingUntilDiscarded[37]",
                "offPausedOrUnreadyNothingReadsOrWritesTheStore[28]", "offPausedOrUnreadyNothingReadsOrWritesTheStore[37]",
                "aRestoreThatRollsBackLeavesTheMarkerAsItWas[28]", "aRestoreThatRollsBackLeavesTheMarkerAsItWas[37]",
                "restoreSavesWhatItReplacesFirst[28]", "restoreSavesWhatItReplacesFirst[37]",
                "aPostPromotionSyncFailureKeepsRecoveryArmedAndReportsTheAppliedChanges[28]", "aPostPromotionSyncFailureKeepsRecoveryArmedAndReportsTheAppliedChanges[37]",
                "aBackupSelectedMarkerNeverRestoresADifferentStalePendingCopy[28]", "aBackupSelectedMarkerNeverRestoresADifferentStalePendingCopy[37]",
                "aLegacyMarkerRefusesTwoDifferentCopiesAndAcceptsOnlyAnUnambiguousCopy[28]", "aLegacyMarkerRefusesTwoDifferentCopiesAndAcceptsOnlyAnUnambiguousCopy[37]",
                "anInvalidSelectedCopyOrMarkerNeverFallsBackToAnotherCopy[28]", "anInvalidSelectedCopyOrMarkerNeverFallsBackToAnotherCopy[37]",
                "everyImportStorageBoundarySurvivesAFailedOperationAndAProcessRestart[28]", "everyImportStorageBoundarySurvivesAFailedOperationAndAProcessRestart[37]",
                "everyRestoreStorageBoundaryPreservesItsSelectedCopyUntilRecoveryFinishes[28]", "everyRestoreStorageBoundaryPreservesItsSelectedCopyUntilRecoveryFinishes[37]",
                "everyDiscardBoundaryIsRetryableWithoutChangingNativeOverrides[28]", "everyDiscardBoundaryIsRetryableWithoutChangingNativeOverrides[37]",
                "everyRollbackStorageBoundaryKeepsTheOlderBackupAndResumesItsJournal[28]", "everyRollbackStorageBoundaryKeepsTheOlderBackupAndResumesItsJournal[37]",
                "permissionRevokedAfterStagingRefusesTheFirstTypedWrite[28]", "permissionRevokedAfterStagingRefusesTheFirstTypedWrite[37]",
                "discardDoesNotParseAnyDamagedRecoveryRecord[28]", "discardDoesNotParseAnyDamagedRecoveryRecord[37]",
                "discardCanReplaceALoneDamagedTerminalRecordWithoutChangingOverrides[28]", "discardCanReplaceALoneDamagedTerminalRecordWithoutChangingOverrides[37]",
                "discardCanRecoverALoneTerminalAfterInspectionIoFailure[28]", "discardCanRecoverALoneTerminalAfterInspectionIoFailure[37]",
                "persistentIoFailureAfterTheFinalMarkerMoveKeepsImportsBlocked[28]", "persistentIoFailureAfterTheFinalMarkerMoveKeepsImportsBlocked[37]",
                "discardCanRemoveAnOversizedCorruptBackupWithoutReadingIt[28]", "discardCanRemoveAnOversizedCorruptBackupWithoutReadingIt[37]",
                "everyMoveIntoTheDirectoryIsFollowedByADirectorySync[28]", "everyMoveIntoTheDirectoryIsFollowedByADirectorySync[37]",
                "aDirectorySyncThatFailsRefusesBeforeAnyWrite[28]", "aDirectorySyncThatFailsRefusesBeforeAnyWrite[37]",
                "restoreNeedsASavedCopyFromThisSameStore[28]", "restoreNeedsASavedCopyFromThisSameStore[37]",
                "theSessionlessQceStoreIsImportedAndRestoredThroughItsOwnFile[28]", "theSessionlessQceStoreIsImportedAndRestoredThroughItsOwnFile[37]",
                "moreChangesThanTheLimitRefuseBeforeWriting[28]", "moreChangesThanTheLimitRefuseBeforeWriting[37]"),
            "app.hushgram.extension.instagram.settings.OverrideImportPageTest" to listOf(
                "importAndRestoreRowsExistOnlyWhileTheirSwitchIsOn[28]", "importAndRestoreRowsExistOnlyWhileTheirSwitchIsOn[37]",
                "anImportFromThePickerAppliesAndRestorePutsTheSavedCopyBack[28]", "anImportFromThePickerAppliesAndRestorePutsTheSavedCopyBack[37]",
                "anAppliedImportWithFailedCleanupReportsBothTheChangeAndItsRecovery[28]", "anAppliedImportWithFailedCleanupReportsBothTheChangeAndItsRecovery[37]",
                "aFailedDiscardReportsIncompleteCleanupAndItsRowCanRetry[28]", "aFailedDiscardReportsIncompleteCleanupAndItsRowCanRetry[37]",
                "cancelledMalformedAndUnsavedRequestsLeaveTheNativeStoreByteIdentical[28]", "cancelledMalformedAndUnsavedRequestsLeaveTheNativeStoreByteIdentical[37]",
                "anArmedStoreNamesBothWaysOutAndDiscardLetsImportsRunAgain[28]", "anArmedStoreNamesBothWaysOutAndDiscardLetsImportsRunAgain[37]",
                "aSwitchTurnedOffWhileThePickerIsOpenReadsNeitherTheFileNorTheStore[28]", "aSwitchTurnedOffWhileThePickerIsOpenReadsNeitherTheFileNorTheStore[37]",
                "resetTakesTheOverridesAwayAndRestorePutsThemBack[28]", "resetTakesTheOverridesAwayAndRestorePutsThemBack[37]"),
            "app.hushgram.extension.instagram.misc.InstagramSignatureTest" to listOf(
                "thisAppStillGetsInstagramsTwoCertificates[28]", "thisAppStillGetsInstagramsTwoCertificates[37]",
                "aRecordThatOnlyNamesThisAppSeedsNoSigners[28]", "aRecordThatOnlyNamesThisAppSeedsNoSigners[37]",
                "aSameKeyThreadsGetsThreadsMetaCertificate[28]", "aSameKeyThreadsGetsThreadsMetaCertificate[37]",
                "aSameKeyFacebookOrMessengerGetsFacebooksMetaCertificate[28]", "aSameKeyFacebookOrMessengerGetsFacebooksMetaCertificate[37]",
                "ownSignersAreReadFromPackageManagerWhenNotSeenYet[28]", "ownSignersAreReadFromPackageManagerWhenNotSeenYet[37]",
                "aMissingSigningInfoIsReadAgainFromPackageManager[28]", "aMissingSigningInfoIsReadAgainFromPackageManager[37]",
                "anOldSignaturesEntryDoesNotOutlastARotation[28]", "anOldSignaturesEntryDoesNotOutlastARotation[37]",
                "aMissingSigningInfoThatCantBeReadAgainGivesNoTrust[28]", "aMissingSigningInfoThatCantBeReadAgainGivesNoTrust[37]",
                "aRotatedFamilyAppIsJudgedByItsCurrentSigner[28]", "aRotatedFamilyAppIsJudgedByItsCurrentSigner[37]",
                "severalSignersHaveToMatchExactly[28]", "severalSignersHaveToMatchExactly[37]",
                "aFamilyAppWithAnotherKeyIsLeftToInstagram[28]", "aFamilyAppWithAnotherKeyIsLeftToInstagram[37]",
                "anyOtherPackageIsLeftToInstagram[28]", "anyOtherPackageIsLeftToInstagram[37]",
                "aMetaSignedBuildLeavesFamilyAppsToInstagram[28]", "aMetaSignedBuildLeavesFamilyAppsToInstagram[37]",
                "unknownOwnSignersGiveNoTrust[28]", "unknownOwnSignersGiveNoTrust[37]",
                "aThrowingSignerIsReportedAndLeftToInstagram[28]", "aThrowingSignerIsReportedAndLeftToInstagram[37]",
                "aThrowingPackageManagerIsReportedAndLeftToInstagram[28]", "aThrowingPackageManagerIsReportedAndLeftToInstagram[37]"),
            "app.hushgram.extension.instagram.misc.SameKeyProviderCallerTest" to listOf(
                "exactlyNamedFamilyCallersWithCurrentMatchingKeysAreAccepted[28]", "exactlyNamedFamilyCallersWithCurrentMatchingKeysAreAccepted[37]",
                "aColdProviderReadsItsOwnContextWithoutSettingsOrCachedSigners[28]", "aColdProviderReadsItsOwnContextWithoutSettingsOrCachedSigners[37]",
                "everyCurrentSignerMustMatchAndSigningHistoryEarnsNoTrust[28]", "everyCurrentSignerMustMatchAndSigningHistoryEarnsNoTrust[37]",
                "metaSignedBuildsKeepTheNativePolicy[28]", "metaSignedBuildsKeepTheNativePolicy[37]",
                "anotherKeyOrUnlistedPackageIsRefused[28]", "anotherKeyOrUnlistedPackageIsRefused[37]",
                "callerUidMustIdentifyOneUnambiguousNamedPackage[28]", "callerUidMustIdentifyOneUnambiguousNamedPackage[37]",
                "callerAndSelfPackageRecordsMustAgreeWithTheirUids[28]", "callerAndSelfPackageRecordsMustAgreeWithTheirUids[37]",
                "aCallerInAnotherAndroidUserIsRefused[28]", "aCallerInAnotherAndroidUserIsRefused[37]",
                "oldSignatureArraysAndUnreadableCurrentSignersEarnNoTrust[28]", "oldSignatureArraysAndUnreadableCurrentSignersEarnNoTrust[37]",
                "missingPackagesAndContextFailuresKeepTheNativeDecision[28]", "missingPackagesAndContextFailuresKeepTheNativeDecision[37]")
        )
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        for ((suiteName, names) in required) {
            val file = results.get().file("TEST-$suiteName.xml").asFile
            if (!file.isFile) throw GradleException("Missing Android boundary results: $suiteName. Run the unfiltered tests.")
            val suite = factory.newDocumentBuilder().parse(file).documentElement
            for (attribute in listOf("failures", "errors", "skipped")) {
                if (suite.getAttribute(attribute).toIntOrNull() != 0) {
                    throw GradleException("Android boundary suite $suiteName has $attribute=${suite.getAttribute(attribute)}.")
                }
            }
            val cases = suite.getElementsByTagName("testcase")
            for (name in names) {
                val matching = (0 until cases.length).map { cases.item(it) as org.w3c.dom.Element }
                    .filter { it.getAttribute("name") == name }
                if (matching.size != 1 || listOf("failure", "error", "skipped").any {
                        matching.singleOrNull()?.getElementsByTagName(it)?.length != 0
                    }) throw GradleException("Android boundary case did not pass exactly once: $suiteName.$name")
            }
        }
        logger.lifecycle("Verified Android API 28/29/37 boundary cases with the Instagram 450 target SDK (36).")
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(libs.annotation)
    testImplementation(project(":extensions:shared:library"))
    compileOnly(libs.kotlin.stdlib)
    testImplementation(libs.kotlin.stdlib)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

extension {
    name = "extensions/instagram.mpe"
}

android {
    namespace = "app.hushgram.extension.instagram"

    defaultConfig {
        // Instagram 450 declares minSdk 28, so nothing below it can run this code.
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The payload runs inside Instagram. javac and Robolectric run on a modern JDK, so a call above
    // Instagram's floor is green all the way to a phone, where it throws. NewApi reads the SDK_INT
    // guards; ObsoleteSdkInt catches a guard at or below the floor.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        checkOnly += "ObsoleteSdkInt"
        error += "ObsoleteSdkInt"
        abortOnError = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            // LicenseNoticeTest holds NOTICE against the copy generated into the payload, and
            // NOTICE is outside this module, so without this Gradle calls the task up to date
            // after NOTICE changes and the comparison never runs.
            it.inputs.file(rootProject.layout.projectDirectory.file("NOTICE"))
                .withPropertyName("licenseNotice")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // PatchStatusWiringTest reads each patch's enableStatus call from the patch sources, so
            // a patch-only change (a misspelled status name) has to rerun these tests too.
            it.inputs.dir(rootProject.layout.projectDirectory.dir("patches/src/main/kotlin"))
                .withPropertyName("patchSources")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            it.jvmArgs(
                "-XX:ActiveProcessorCount=2",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                // Robolectric's FileDescriptor interceptor reaches the fd through SharedSecrets.
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}
