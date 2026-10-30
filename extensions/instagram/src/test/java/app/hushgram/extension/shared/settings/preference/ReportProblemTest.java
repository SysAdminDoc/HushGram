/*
 * Copyright 2026 HushPinterest contributors
 * Taken from HushPinterest's
 * extensions/pinterest/src/test/java/app/hushpinterest/extension/shared/settings/preference/ReportProblemTest.java
 * (https://github.com/SysAdminDoc/HushPinterest, commit 44fb93384a319d8e5d324f51b416efdc3d3147e6).
 * Modified for HushGram (Instagram), 2026.
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushgram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Report a problem link, built from a fixed install rather than the phone running the test. */
public class ReportProblemTest {
    private static final ReportProblem.Form FORM = new ReportProblem.Form("SysAdminDoc/HushGram", "bug_report.yml",
            "versions", "device", "bug_description", "additional_context");

    private static ReportProblem.Facts facts(List<String> switchesOn) {
        return new ReportProblem.Facts("HushGram", "0.0.9", "Instagram", "450.0.0.50.77", 378612345L,
                "samsung", "SM-S901U", "16", 36, switchesOn);
    }

    @Test
    public void theBodySaysTheVersionsThePhoneAndTheSwitchesThenAsks() {
        ReportProblem.Facts facts = facts(Arrays.asList("Hide ads", "Strip link tracking"));
        assertEquals("Versions: HushGram 0.0.9 on Instagram 450.0.0.50.77 (378612345)\n"
                + "Phone: samsung SM-S901U, Android 16 (SDK 36)\n"
                + "Switches on: Hide ads, Strip link tracking\n"
                + "\n"
                + "What happened:\n\n\n"
                + "What I expected:\n\n", ReportProblem.body(facts));
        // A model that already names its maker isn't given the maker twice.
        assertEquals("Google Pixel 8, Android 15 (SDK 35)", ReportProblem.deviceLine(new ReportProblem.Facts(
                "HushGram", "0.0.9", "Instagram", "450.0.0.50.77", 1, "Google", "Google Pixel 8", "15", 35, null)));
        // Nothing known reads as unknown rather than as a blank, and no switch on says so.
        ReportProblem.Facts bare = new ReportProblem.Facts("HushGram", null, "Instagram", "", 0, null, null, null, 29, null);
        assertEquals("HushGram unknown on Instagram unknown", ReportProblem.versionsLine(bare));
        assertEquals("unknown phone, Android unknown (SDK 29)", ReportProblem.deviceLine(bare));
        assertTrue(ReportProblem.body(bare).contains("Switches on: none\n"));
    }

    @Test
    public void theLinkFillsTheFormsFieldsByTheirIds() throws Exception {
        String url = ReportProblem.issueUrl(FORM, facts(Arrays.asList("Hide ads", "Strip link tracking")));
        assertTrue(url, url.startsWith("https://github.com/SysAdminDoc/HushGram/issues/new?template=bug_report.yml&"));
        assertFalse("a space went out as a plus, which GitHub keeps", url.contains("+"));
        Map<String, String> fields = query(url);
        assertEquals(Arrays.asList("template", "versions", "device", "bug_description", "additional_context"),
                new ArrayList<>(fields.keySet()));
        assertEquals("HushGram 0.0.9 on Instagram 450.0.0.50.77 (378612345)", fields.get("versions"));
        assertEquals("samsung SM-S901U, Android 16 (SDK 36)", fields.get("device"));
        assertEquals("What happened:\n\n\nWhat I expected:\n\n", fields.get("bug_description"));
        assertEquals("Switches on: Hide ads, Strip link tracking", fields.get("additional_context"));

        // A repository without a form gets the whole text as the body.
        ReportProblem.Form plain = new ReportProblem.Form("owner/app", null, null, null, null, null);
        Map<String, String> body = query(ReportProblem.issueUrl(plain, facts(Arrays.asList("Hide ads"))));
        assertEquals(Arrays.asList("body"), new ArrayList<>(body.keySet()));
        assertEquals(ReportProblem.body(facts(Arrays.asList("Hide ads"))), body.get("body"));
    }

    @Test
    public void aTitleOrModelCannotAddLinesOrBreakTheQuery() throws Exception {
        ReportProblem.Facts facts = new ReportProblem.Facts("HushGram", "0.0.9", "Instagram", "450.0.0.50.77", 1,
                "Acme", "Phone&x=1\nSecond line", "16", 36, Arrays.asList("Hide\nads", "A+B #1", "  "));
        Map<String, String> fields = query(ReportProblem.issueUrl(FORM, facts));
        assertEquals("Acme Phone&x=1 Second line, Android 16 (SDK 36)", fields.get("device"));
        assertEquals("Switches on: Hide ads, A+B #1", fields.get("additional_context"));
        assertFalse(fields.containsKey("x"));
        assertEquals("a%20b%2Bc%26d%3D%23", ReportProblem.encode("a b+c&d=#"));
    }

    @Test
    public void aLongListOfSwitchesIsCutSoTheLinkFitsAndTheQuestionsSurvive() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add("A switch with a fairly long title number " + i);
        String url = ReportProblem.issueUrl(FORM, facts(many));
        assertTrue("the link is " + url.length() + " characters", url.length() <= ReportProblem.MAX_LENGTH);
        Map<String, String> fields = query(url);
        assertEquals("What happened:\n\n\nWhat I expected:\n\n", fields.get("bug_description"));
        String switches = fields.get("additional_context");
        assertTrue(switches, switches.startsWith("Switches on: A switch with a fairly long title number 0, "));
        assertTrue(switches, switches.matches(".*, and \\d+ more"));
        int kept = switches.split(", ").length - 1;
        int left = Integer.parseInt(switches.replaceAll(".*, and (\\d+) more", "$1"));
        assertEquals(many.size(), kept + left);
        // The list is cut no further than it has to be.
        assertTrue("only " + kept + " titles fit", kept > 50);

        // A phone whose names alone run past the limit still gets a link that fits.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 7000; i++) huge.append('m');
        ReportProblem.Facts odd = new ReportProblem.Facts("HushGram", "0.0.9", "Instagram", "450.0.0.50.77", 1,
                "Acme", huge.toString(), "16", 36, many);
        String cut = ReportProblem.issueUrl(FORM, odd);
        assertTrue(cut.length() <= ReportProblem.MAX_LENGTH);
        assertTrue(query(cut).get("bug_description").startsWith("What happened:"));
    }

    /** The query's parameters in order, decoded. */
    private static Map<String, String> query(String url) throws UnsupportedEncodingException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            fields.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
        return fields;
    }
}
