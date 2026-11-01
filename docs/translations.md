# Translating HushGram

The local tools can prepare every file and create the hosted Crowdin project. The account owner still needs to sign in once and supply a personal token. Translations stay drafts until a native reader approves their wording.

## Prepare the review package

Use Python 3.13 on Windows with a writable NTFS folder. No extra packages are needed.

```powershell
py -3.13 scripts/crowdin-l10n.py prepare --output build/translation-review
```

The output folder must be new. This protects a reviewer's edits. The package contains the English source JSON, the existing eight language JSON files, and one review CSV per language. `manifest.json` binds the uploads to the current catalog. `REVIEW.txt` explains the worksheets.

Every worksheet starts with `pending` in `review_status`. A native reader records `accepted` or `change`, the replacement if needed, their notes and name. Check these points before calling a language reviewed:

* The wording means the same thing as the English and reads naturally in the app.
* Every format argument, such as `%1$s`, is present with the same type. Numbered arguments can move. Bare arguments keep their order.
* Quantity forms agree with the listed plural category. The `english_key` is a lookup key and can include a category suffix. Translate `english_text`.
* The translated screens fit at large text sizes. Check line wrapping and the controls beside long labels. For a language that reads right to left, check its real locale too.

Spreadsheet review records don't approve translations on Crowdin or write into HushGram. A maintainer applies the reviewed wording to the TSV table and keeps the review record with its evidence. Don't change the identifiers or English keys. The existing German, Spanish, Indonesian, Italian, Brazilian Portuguese and Turkish seeds are drafts for this review. Korean came from a volunteer translator and goes through the same review. The Vietnamese table hasn't been read by a native speaker yet and goes through it too.

## Set up Crowdin

Sign in at [Crowdin](https://crowdin.com/) with the account that will own HushGram. Create a personal token from its API settings. Grant read and write access for Projects, Project Settings, Source Files and Strings, and Translations. The [official scope guide](https://support.crowdin.com/developer/understanding-scopes/) describes these permissions. Project creation requires a token that can create projects, rather than one restricted to an existing project.

Set `CROWDIN_PERSONAL_TOKEN` in the process environment through a trusted local credential workflow. Keep its value out of the repository, command history and review package. Then run:

```powershell
py -3.13 scripts/crowdin-l10n.py setup --package build/translation-review
```

Setup uses the current [Crowdin API](https://support.crowdin.com/developer/api/v2/). It creates a public, file-based project named HushGram with identifier `hushgram`. English is the source. German, Spanish, Indonesian, Italian, Brazilian Portuguese, Turkish, Korean and Vietnamese are targets. Use `--identifier` with another available identifier if the intended one is already owned by someone else.

The tool uploads `en.json` as a flat JSON source, then the existing language files as unapproved suggestions. Suggestions equal to English are preserved. Imports use the asynchronous translation-import API and check each completed report for missing or skipped rows. Tokens are sent only to `api.crowdin.com`. Redirects are refused, and the tool doesn't print server response bodies or credentials.

The required project settings are `type: 0`, `sourceLanguageId: en`, `visibility: open`, `skipUntranslatedStrings: true`, `exportApprovedOnly: true` and `normalizePlaceholder: false`. Setup checks these settings when resuming. It refuses a mismatched existing project or source instead of replacing it. Any existing translations without an import receipt also stop draft seeding so a reader's work stays intact.

Before inviting reviewers, turn on Crowdin's placeholder QA check and set mismatches to errors. See its [QA settings](https://support.crowdin.com/project-settings/qa-checks/). Native readers approve text after reviewing it. The tool never approves drafts and doesn't send invitations or post the project link.

The package's `crowdin-state.json` records project/file identifiers and import jobs. It holds no token. Repeating setup reuses the exact project, checks the source and resumes recorded jobs without uploading completed seeds again. If a request fails after submission but before an import job is recorded, setup stops with an unknown-outcome message. Inspect that language's upload on Crowdin. If it exists, put its real job identifier in the receipt and change `status` from `submitting` to `started`. Setup can then check that job's report. Remove a `submitting` receipt only after confirming no import started. A timed-out recorded job can be resumed by running setup again.

If the local catalog changes, prepare a new folder. Setup refuses stale packages. This initial-setup tool doesn't overwrite a live project's source after English text changes. Update that source deliberately through Crowdin, preserving its existing translations, then use the local bridge for reviewed downloads.

## Import reviewed work

Download approved translations with untranslated strings skipped. See [Crowdin's export settings](https://support.crowdin.com/project-settings/export/). English fallback values in a default export aren't proof that a row was translated.

For an existing language, a partial file keeps the old translation for every absent row:

```powershell
py -3.13 scripts/sync-l10n.py import --language de --input path/to/de.json --partial
py -3.13 scripts/gen-l10n.py
py -3.13 scripts/test-l10n.py
py -3.13 scripts/test-crowdin-l10n.py
```

A language HushGram doesn't have yet needs a complete reviewed catalog and an explicit `--new-language`. Indonesian uses `id` on Crowdin and `in.tsv` locally. Brazilian Portuguese uses `pt-BR` on Crowdin and `pt-rBR.tsv` locally. `sync-l10n.py` validates exact keys, formatting, plural variants and text before committing a TSV change.

Run the normal extension build and catalog tests after importing, then check the translated screens on a leased test device. Hosted setup tests verify preparation, request contracts and refusal/retry behavior with a simulated service. They don't prove access to a real account or native language quality.
