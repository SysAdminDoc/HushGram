"""Counts the JUnit results a release description quotes.

With no folders named, it reads this checkout's two: the runtime tests'
(extensions/instagram/build/test-results/testDebugUnitTest) and the patch tests'
(patches/build/test-results/test). --gate reads the ones the push gate kept for a commit instead
(HEAD unless one is named), from HUSHGRAM_GATE_CACHE or HushGram\\gate in the local application
data folder (or HUSHGRAM_GATE_CACHE set only for the user on Windows), the place
scripts/gate-evidence.ps1 keeps them, and refuses a run whose manifest doesn't say it passed or
names another commit or tree than the one asked for.

Tests are counted one per testcase element, the way validate-release-facts.ps1 counts them, so
the numbers are the ones the index description has to quote. A folder with no results, or with
a failure, an error or a skip, makes the run exit 1. --description prints the description's
validation sentence from the two counts.

    py -3.13 -I scripts/release/count_tests.py --gate --description

Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
GPL-3.0-only.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUNTIME = "extensions/instagram/build/test-results/testDebugUnitTest"
PATCHES = "patches/build/test-results/test"


def count(folder: pathlib.Path) -> dict[str, int]:
    """Files, test cases, failures, errors and skips in the TEST-*.xml files of one folder."""
    totals = {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in sorted(folder.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            totals["failures"] += int(suite.get("failures", 0))
            totals["errors"] += int(suite.get("errors", 0))
            totals["skipped"] += int(suite.get("skipped", 0))
            totals["tests"] += len(suite.findall("testcase"))
        totals["files"] += 1
    return totals


def user_environment(name: str) -> str | None:
    """A variable set for the user only (Windows), which a shell started earlier doesn't carry."""
    if sys.platform != "win32":
        return None
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value, _ = winreg.QueryValueEx(key, name)
    except OSError:
        return None
    return os.path.expandvars(value) if isinstance(value, str) and value else None


def gate_root() -> pathlib.Path:
    configured = os.environ.get("HUSHGRAM_GATE_CACHE") or user_environment("HUSHGRAM_GATE_CACHE")
    if configured:
        return pathlib.Path(configured)
    base = os.environ.get("LOCALAPPDATA") or str(pathlib.Path.home() / ".cache")
    return pathlib.Path(base) / "HushGram" / "gate"


def git_value(root: pathlib.Path, *arguments: str) -> str:
    """One line of git's answer, empty when git can't say."""
    result = subprocess.run(["git", "-C", str(root), *arguments], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else ""


def gate_folders(root: pathlib.Path, commit: str | None) -> list[pathlib.Path]:
    full = git_value(root, "rev-parse", "--verify", "--quiet", f"{commit or 'HEAD'}^{{commit}}")
    if not full:
        raise SystemExit(f"[tests] git can't say what {commit or 'HEAD'} is in {root}")
    commit = full
    run = gate_root() / commit
    manifest_path = run / "manifest.json"
    if not manifest_path.is_file():
        raise SystemExit(f"[tests] the gate kept no run of {commit[:12]} in {gate_root()}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("passed") is not True:
        raise SystemExit(f"[tests] the gate's run of {commit[:12]} didn't pass (it stopped at {manifest.get('stage')})")
    if manifest.get("commit") != commit:
        raise SystemExit(f"[tests] the manifest in {run} names commit {manifest.get('commit')}, not {commit[:12]}")
    tree = git_value(root, "rev-parse", "--verify", "--quiet", f"{commit}^{{tree}}")
    if not tree or manifest.get("tree") != tree:
        raise SystemExit(f"[tests] the gate's run of {commit[:12]} names tree {manifest.get('tree')} and git says {tree or 'nothing'}")
    return [run / "test-results" / "testDebugUnitTest", run / "test-results" / "test"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("folders", nargs="*", help="folders of TEST-*.xml files")
    parser.add_argument("--root", default=str(ROOT), help="the checkout whose results are read")
    parser.add_argument("--gate", nargs="?", const="", metavar="COMMIT", help="read the gate's kept results for COMMIT (HEAD)")
    parser.add_argument("--description", action="store_true", help="print the index description's validation sentence")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root)
    if args.gate is not None:
        folders = gate_folders(root, args.gate or None)
    elif args.folders:
        folders = [pathlib.Path(folder) for folder in args.folders]
    else:
        folders = [root / RUNTIME, root / PATCHES]

    status = 0
    results = []
    for folder in folders:
        totals = count(folder)
        results.append(totals)
        print(f"[tests] {folder}: {totals['tests']} tests in {totals['files']} files, "
              f"failures={totals['failures']} errors={totals['errors']} skipped={totals['skipped']}")
        if totals["files"] == 0:
            print(f"[tests] {folder} holds no results", file=sys.stderr)
            status = 1
        elif totals["failures"] or totals["errors"] or totals["skipped"]:
            print(f"[tests] {folder} has failures, errors or skips, so no release can quote it", file=sys.stderr)
            status = 1
    if args.description:
        if len(results) != 2:
            print("[tests] --description needs the runtime and the patch results, in that order", file=sys.stderr)
            return 1
        print(f"Validation: {results[0]['tests']} runtime tests passed locally. All {results[1]['tests']} patch tests passed too.")
    return status


if __name__ == "__main__":
    sys.exit(main())
