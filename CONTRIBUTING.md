# Contributing

Bug reports, fixes for a new Instagram build, new patches and pull requests are all welcome.

If you open an issue, include:

- the Instagram version and build number you patched (APKMirror shows both, for example 450.0.0.50.77, build 385611438)
- the Morphe Manager version and the HushGram version
- the patches you selected
- what you expected and what happened, with steps to get there
- a diagnostic report, or screenshots if it's visual. Take private messages and account details out of screenshots first.

GitHub doesn't let the person who opened an issue reopen it once a maintainer closes it, so a closing comment always says how to get it reopened: comment there and we'll reopen it.

## When a new Instagram build breaks a patch

Instagram renames most of its code every week, so a patch never looks for a method by its name. Each one anchors on something Instagram keeps from build to build: a log string, a server field name, a manifest component, or a call into Android itself. When patching stops, the message names the anchor it couldn't find.

To fix it, find where that anchor went in the new build and tighten the fingerprint so it matches exactly one method again. A fingerprint that matches two methods fails too, on purpose. A guess that lands on the wrong method gives you an app that looks patched and does nothing, or worse.

When the behavior is unclear, compare the original Meta-signed Instagram build with a HushGram build. Follow the README's [factory-baseline procedure](README.md#establishing-a-factory-baseline) and keep the two installs' results separate. Turning HushGram switches off doesn't remove the hooks from its build.

## Building and checking

Read the README's build section first. Gradle needs `GITHUB_ACTOR` and `GITHUB_TOKEN` (a token with `read:packages`) to fetch the Morphe patcher. Run `:patches:generatePatchesList` before `:patches:buildAndroid`.

Before a change goes in:

- `./gradlew :patches:test :extensions:instagram:testDebugUnitTest` with `HUSHGRAM_FIXTURE_DIR` set, so the tests that read real Instagram builds run instead of skipping.
- `./gradlew :extensions:instagram:lint :extensions:shared:library:lint`. Instagram runs on Android 9, so a call Android added later needs a version check, and lint catches the ones that don't have it.
- `scripts/verify-all-patches.ps1` on every build the catalog declares. It applies every patch in one run without forcing anything, checks the CLI's own report, and compares the patched manifest to Meta's against `scripts/manifest-delta-allowlist.txt`. The allowlist approves the three advertising permissions `Remove the advertising ID` takes out and the version code `Change version code` raises, and nothing else.

`scripts/install-hooks.ps1` installs a pre-push hook that runs those tests and lints when a push changes `extensions/` or `patches/`. A push that changes a PowerShell script has every tracked script parsed first. One that changes a script with a suite runs that suite, and one that changes the README, the CHANGELOG or another file a release states facts from runs `scripts/validate-release-facts.ps1` too. Set `HUSHGRAM_SKIP_PRE_PUSH=1` to push without it.

## Releasing

A release waits for the maintainer. The pre-push hook stops a push that carries a tag or `patches-bundle.json` unless `HUSHGRAM_ALLOW_RELEASE=1` is set for that one push. With it set, the hook's builds and patch runs also go ahead of everything else in the machine's build queue. It takes two commits:

1. The source commit. The CHANGELOG moves the version's notes out of `## Unreleased` into a dated `## <version> (YYYY-MM-DD)` heading, and its `* **Instagram:**` bullets are what Morphe Manager shows. The README's latest-release sentence keeps naming the previous release until the index commit. Before pushing, run `scripts/release/preflight.ps1 -Version <version>`. In a few minutes it checks the checkout, the fixtures and the desktop CLI, the release facts, the CHANGELOG section the notes come from (every bullet scoped, no dashes) and the patch tests that read no Instagram build, so a slip turns up before the gate's long run does.
2. Push it with `HUSHGRAM_FIXTURE_DIR` and `HUSHGRAM_ALLOW_RELEASE=1` set. The hook builds and tests the commit in a clean worktree, patches each Instagram build the catalog declares, and keeps the bundle, its SBOM, the test results and the patch runs outside the repository, under the commit's hash in `HUSHGRAM_GATE_CACHE` (or `%LOCALAPPDATA%\HushGram\gate` when that isn't set). Without the fixtures the patch tests skip, and a skipped test stops the release.
3. Run `scripts/build-release-receipt.ps1 -WorkDir <a scratch folder> -FromGate` with `HUSHGRAM_FIXTURE_DIR` and `HUSHGRAM_DESKTOP_JAR` set. It asks OSV about every library the SBOM lists, then holds each declared Instagram build to the verdicts in the CLI's report. With `-FromGate` it copies the gate's bundle and SBOM into `patches/build/release` when none was built here, and reads the gate's patch run of a build instead of patching it again when that run used the same bundle, APK, catalog and CLI. Anything else is patched here, and every check runs either way. If a patch fails or a high or critical advisory turns up, it writes nothing. Otherwise you get `release-receipt-<version>.json` in the repository root, where git ignores it, and `SHA256SUMS.txt` beside the bundle, and a copy of both goes in with the gate's run. An advisory that can't reach anything the bundle does can go in `scripts/advisory-exceptions.txt` with a reason, for 90 days at most.
4. Run `scripts/validate-release-facts.ps1 -FromGate`. It holds the README, the CHANGELOG, the bug form and the source ledger to the generated patch list, holds the receipt to the same catalog, toolchain and Instagram builds, and reads the test results the gate kept for the commit.
5. Run `scripts/release/patch-all-builds.ps1 -OutDir <a scratch folder> -FromGate` to try the bundle on every Instagram build in the fixture folder, not only the declared ones. Each build gets a line with the patches it applied and missed and the CLI's warnings. A build kept as a bundle is patched from the bundle, and one kept only as its base split says so. A declared build that misses a patch fails the run. An older or forced build that misses some doesn't, unless you add `-RequireAll`.
6. Write the notes with `py -3.13 -I scripts/release/release_notes.py --version <version> --intro <file> --highlights <file> --install <file> --validation <file> --out <file>`. It carries every bullet of the section, grouped by scope, and refuses an unscoped bullet or a dash. `py -3.13 -I scripts/release/count_tests.py --gate --description` prints the validation sentence from the gate's test results for the commit you're on.
7. Publish the GitHub release `v<version>` on the source commit with those notes and four files: `patches-<version>.mpp`, `patches-<version>.cdx.json`, the receipt and `SHA256SUMS.txt`.
8. The index commit. `patches-bundle.json` points at the published bundle. Its description names the version, the patch count and the Instagram build, and quotes the sentence from step 6: "Validation: N runtime tests passed locally. All N patch tests passed too." Set the GitHub repository description to name the same version, patch count and build (`gh repo edit --description`). The README's latest-release sentence becomes "The latest release is [v<version>](<release page>), with N patches." and keeps the Morphe add-source link. Push it with `HUSHGRAM_ALLOW_RELEASE=1` set. When the push changes nothing but those index lines on top of a commit the gate passed, the hook doesn't build or test again. It reads the gate's kept results and receipt, then downloads what the release published, holds every file to `SHA256SUMS.txt` and the receipt, compares the bundle with the one the gate built byte for byte, holds the description's counts to the gate's test results, reads the GitHub description and asks OSV again. It also wants a source census from the last 14 days, so run `scripts/audit-instagram-sources.ps1` first if the ledger's is older. A push that changes anything else gets the whole gate.

If the gate's run is gone (the cache was cleared, or the source commit was pushed from another machine), build in a clean checkout of the source commit with `./gradlew :extensions:instagram:testDebugUnitTest :patches:test :patches:generatePatchesList :patches:buildAndroid` and `HUSHGRAM_FIXTURE_DIR` set, then run steps 3 to 8 there without `-FromGate`. The index push then has to come from that same checkout, so the receipt, the bundle and the test results are still there.

`scripts/test-release-tooling.ps1` covers all of this without a network or a phone, running the hook's own checks. The index push's check runs against stand-ins for GitHub, Morphe and OSV, and the check every other push gets runs with its network lookups left out. It runs in the hook whenever one of these scripts, or a file they read, changes.

## Settings for your machine

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do that instead, and none of them has a default:

- `HUSHGRAM_FIXTURE_DIR` is the folder holding the Instagram builds the fixture tests and scripts read. They're hundreds of megabytes each, so they aren't in the repository.
- `HUSHGRAM_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHGRAM_WORKDIR` or a jar under `build/morphe-tools` works too.
- `HUSHGRAM_DEVICE_SERIAL` is the adb serial of a test phone for `scripts/patch-for-device.ps1`. Keep your own phone out of it: a re-signed Instagram can't install over the Play Store copy without uninstalling it, which signs you out.

## Source notices

Keep every existing copyright, license, author credit and source-origin notice when you modify or move a file, and don't remove a notice unless the code it covers is gone from the file. Most of this code came from Hushfacebook and, before it, from Morphe and ReVanced, all GPL-3.0. In this ecosystem a missing notice has already ended in DMCA takedowns more than once.

New source written for this project may use:

```text
/*
 * Copyright <year> HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
```

Code taken from another project keeps its notices and gets a `Forked from:` line with the file's URL at the commit it came from. Record it in `provenance.json` too. A rule naming a single file wins over the folder rule around it. `ProvenanceTest` fails when a shipped file matches no rule or two, when a rule names an upstream that NOTICE doesn't, or when a file's header doesn't link a repository of its rule. A script in `scripts/` that came from another project is named in its rule one file at a time, and its header (the help block, the leading comments or the docstring) is held to the same check.

Code can only come from a source that `sources/instagram-sources.json` lists as adopted. That takes the commit the code came from, a licence that works with GPL-3.0, the source in NOTICE and its rule in `provenance.json`. `scripts/test-instagram-sources.ps1` refuses the ledger without any of them. A source the ledger calls behavior-only is never copied from, only read for what it does. When you find a new source, run `scripts/audit-instagram-sources.ps1`, which reports what moved since the last census.
