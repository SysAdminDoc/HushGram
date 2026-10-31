/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.changePinRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.lockDelayRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.lockToggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.unlockWithRow;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Messages category of HushGram settings. */
final class MessagesCategory {
    private MessagesCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.NOTES_ROW) || build.contains(PatchFamily.INBOX_SEARCH)
                || build.contains(PatchFamily.INBOX_SUGGESTIONS) || build.contains(PatchFamily.INSTANTS)
                || build.contains(PatchFamily.THREAD_SEEN) || build.contains(PatchFamily.TYPING)
                || build.contains(PatchFamily.DM_MEDIA_SEEN)
                || build.contains(PatchFamily.ACTIVE_STATUS)
                || build.contains(PatchFamily.LAST_ACTIVE)
                || build.contains(PatchFamily.MESSAGES_LOCK)
                || build.contains(PatchFamily.SCREENSHOT_REPORTS)
                || build.contains(PatchFamily.SCREENSHOT_BLOCK)
                || build.contains(PatchFamily.KEEP_IN_CHAT)
                || build.contains(PatchFamily.VIEW_ONCE_MARK)
                || build.contains(PatchFamily.CHAT_REELS)
                || build.contains(PatchFamily.CHAT_HISTORY)
                || build.contains(PatchFamily.ASK_BEFORE_CALL)
                || build.contains(PatchFamily.MESSAGE_BOX)
                || build.contains(PatchFamily.FULL_SCREEN_BARS)) {
            PreferenceCategory messages = category(screen, L10n.t("Messages"), SettingsIcons.CHATS,
                    L10n.t("Privacy and controls for your chats."));
            if (build.contains(PatchFamily.NOTES_ROW)) {
                messages.addPreference(toggle(context, Settings.HIDE_NOTES_ROW, L10n.t("Hide the notes row"),
                        L10n.t("Takes the row of notes off the top of your messages, the Map bubble in it too. "
                                + "Your chats, search and requests stay.")));
            }
            if (build.contains(PatchFamily.INBOX_SEARCH)) {
                messages.addPreference(toggle(context, Settings.HIDE_INBOX_SEARCH,
                        L10n.t("Hide the search bar in Messages"),
                        L10n.t("Takes the search bar and its Filter button off the top of your messages, the "
                                + "Meta AI bar too. Your chats, notes and requests stay.")));
            }
            if (build.contains(PatchFamily.INBOX_SUGGESTIONS)) {
                messages.addPreference(toggle(context, Settings.HIDE_INBOX_SUGGESTIONS,
                        L10n.t("Hide suggested accounts in Messages"),
                        L10n.t("Takes the accounts Instagram suggests off the bottom of your messages. Your chats "
                                + "and follow requests stay. Restart Instagram to see the change.")));
            }
            if (build.contains(PatchFamily.INSTANTS)) {
                messages.addPreference(toggle(context, Settings.HIDE_INSTANTS, L10n.t("Hide Instants"),
                        L10n.t("Removes the stack of Instants (quick photos from friends) from your messages. "
                                + "Restart Instagram to see the change.")));
            }
            if (build.contains(PatchFamily.THREAD_SEEN)) {
                messages.addPreference(toggle(context, Settings.READ_WITHOUT_SEEN_RECEIPT,
                        L10n.t("Read messages without the seen receipt"),
                        L10n.t("Opening a chat doesn't tell people you've seen their messages, and you still see "
                                + "when they've seen yours. To let one chat know, long press it in your messages and "
                                + "tap Mark as read.")));
                messages.addPreference(toggle(context, Settings.KEEP_CHATS_UNREAD, L10n.t("Keep chats unread here"),
                        L10n.t("A chat you open stays unread in your messages on this phone until you long press it "
                                + "and tap Mark as read. A newer message in it stays unread too. Turned off, a chat you "
                                + "open stays read on this phone until a newer message comes in, even after your "
                                + "messages refresh.")));
                messages.addPreference(toggle(context, Settings.MARK_SEEN_ON_REPLY,
                        L10n.t("Mark a chat seen when you reply"),
                        L10n.t("Sending a message in a chat lets the other person see that you've read what they "
                                + "sent before it. Chats you don't answer stay unseen to them.")));
            }
            if (build.contains(PatchFamily.TYPING)) {
                messages.addPreference(toggle(context, Settings.HIDE_TYPING, L10n.t("Hide that you're typing"),
                        L10n.t("People you're chatting with don't see the typing dots while you write, and you "
                                + "still see theirs.")));
            }
            // Under Ads and privacy until 2026-10-26, and a search for that still finds it.
            if (build.contains(PatchFamily.DM_MEDIA_SEEN)) {
                messages.addPreference(toggle(context, Settings.VIEW_DM_MEDIA_ANONYMOUSLY,
                        L10n.t("View DM photos and videos anonymously"),
                        L10n.t("Stops people seeing that you opened their view once photos and videos. They still "
                                + "disappear after you view them. This is a test feature and starts off.")));
            }
            if (build.contains(PatchFamily.ACTIVE_STATUS)) {
                messages.addPreference(toggle(context, Settings.HIDE_ACTIVE_STATUS, L10n.t("Hide your active status"),
                        L10n.t("People don't see Active now for you while you use Instagram, and you still see when "
                                + "they're active. It takes effect the next time you come back to Instagram.")));
            }
            if (build.contains(PatchFamily.LAST_ACTIVE)) {
                messages.addPreference(toggle(context, Settings.SHOW_LAST_ACTIVE,
                        L10n.t("Show when someone was last active"),
                        L10n.t("Under a name in your messages and in a chat, the exact time they were last on, like "
                                + "Active at 3:42 PM, replaces Active 3h ago. Active now stays as it is.")));
            }
            if (build.contains(PatchFamily.SCREENSHOT_REPORTS)) {
                messages.addPreference(toggle(context, Settings.HIDE_SCREENSHOTS, L10n.t("Don't report screenshots"),
                        L10n.t("Instagram doesn't notice when you take a screenshot, so whoever sent you a disappearing photo or video isn't told. Your screenshots save as usual.")));
            }
            if (build.contains(PatchFamily.SCREENSHOT_BLOCK)) {
                messages.addPreference(toggle(context, Settings.ALLOW_SCREENSHOTS, L10n.t("Allow screenshots"),
                        L10n.t("Screenshots and screen recordings work wherever Instagram blocks them, like disappearing photos and videos. Turn on Don't report screenshots too if the sender shouldn't hear about it.")));
            }
            if (build.contains(PatchFamily.KEEP_IN_CHAT)) {
                messages.addPreference(toggle(context, Settings.KEEP_IN_CHAT, L10n.t("Keep in chat"),
                        L10n.t("Photos and videos sent as view once or replayable stay in the chat so you can open "
                                + "them again. Open the chat again to update ones already loaded.")));
            }
            if (build.contains(PatchFamily.VIEW_ONCE_MARK)) {
                messages.addPreference(toggle(context, Settings.MARK_VIEW_ONCE, L10n.t("Mark one-time photos and videos"),
                        L10n.t("Puts a border and Instagram's view once icon on a one-time photo or video in a chat, "
                                + "so you can tell it from the rest before you open it. Only how the chat looks changes.")));
            }
            if (build.contains(PatchFamily.CHAT_REELS)) {
                messages.addPreference(toggle(context, Settings.SWIPE_CHAT_REELS, L10n.t("Swipe through a chat's reels"),
                        L10n.t("Swiping up on a reel you opened from a chat goes through the other reels shared in that "
                                + "chat before any suggested reels. Back takes you to the chat.")));
                messages.addPreference(toggle(context, Settings.STOP_AFTER_CHAT_REELS, L10n.t("Stop after the chat's reels"),
                        L10n.t("Reels you opened from a chat end after the last one shared in that chat, with no "
                                + "suggested reels after it. Works with Swipe through a chat's reels on.")));
            }
            if (build.contains(PatchFamily.CHAT_HISTORY)) {
                messages.addPreference(toggle(context, Settings.JUMP_IN_CHAT, L10n.t("Jump to the first message or a date"),
                        L10n.t("Long press a chat's name and pick Jump to the first message or Jump to a date. The "
                                + "chat loads its older messages on its own, with a count and a Cancel button.")));
                messages.addPreference(toggle(context, Settings.EXPORT_CHAT, L10n.t("Export a chat as text"),
                        L10n.t("Long press a chat's name and pick Export chat. The whole chat is saved as a text file "
                                + "in Downloads, HushGram folder, one line per message.")));
            }
            if (build.contains(PatchFamily.ASK_BEFORE_CALL)) {
                messages.addPreference(toggle(context, Settings.ASK_BEFORE_CALL, L10n.t("Ask before a call"),
                        L10n.t("Tapping a call button in a chat asks first, so a stray tap doesn't ring anyone. "
                                + "Call starts it, Cancel doesn't.")));
                messages.addPreference(toggle(context, Settings.HIDE_CALL_BUTTONS, L10n.t("Hide the call buttons in chats"),
                        L10n.t("Takes the voice and video call buttons off the top of a chat. The name keeps its room. "
                                + "Turn it off and they're back the next time a chat opens.")));
            }
            if (build.contains(PatchFamily.MESSAGE_BOX)) {
                messages.addPreference(page.messageBoxRow(context));
            }
            if (build.contains(PatchFamily.FULL_SCREEN_BARS)) {
                messages.addPreference(toggle(context, Settings.FULL_SCREEN_MESSAGES, L10n.t("Full screen Messages"),
                        L10n.t("Hides the status bar and the navigation bar while your messages or a chat shows. Swipe "
                                + "in from the edge to bring them back for a moment. Back and the keyboard work as usual.")));
            }
            if (build.contains(PatchFamily.MESSAGES_LOCK)) {
                messages.addPreference(lockToggle(context, Settings.LOCK_MESSAGES, L10n.t("Lock your messages"),
                        L10n.t("Your inbox and chats stay covered until your fingerprint, face or screen lock says it's "
                                + "you. Message notifications say only that a message came.")));
                messages.addPreference(lockToggle(context, Settings.LOCK_APP, L10n.t("Lock all of Instagram"),
                        L10n.t("All of Instagram stays covered until your fingerprint, face or screen lock says it's "
                                + "you, your messages too.")));
                messages.addPreference(lockDelayRow(context));
                messages.addPreference(unlockWithRow(context));
                messages.addPreference(changePinRow(context));
                messages.addPreference(page.lockedChatsRow(context));
                messages.addPreference(page.hiddenChatsRow(context));
                messages.addPreference(toggle(context, Settings.DOUBLE_TAP_HIDDEN_CHATS,
                        L10n.t("Double-tap Messages for hidden chats"),
                        L10n.t("Two quick taps on the Messages button, on the tab bar or at the top of Home, open "
                                + "your hidden chats. While a lock is on, it asks for it first. One tap opens Messages "
                                + "as usual.")));
                messages.addPreference(page.pinnedChatsRow(context));
            }
        }
    }
}
