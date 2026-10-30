"""Prepare native-review files and set up HushGram's Crowdin project.

Copyright 2026 HushGram contributors. GPL-3.0-only.
https://github.com/SysAdminDoc/HushGram
"""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("hushgram_translation_bridge", ROOT / "scripts/sync-l10n.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
API_URL = "https://api.crowdin.com/api/v2/"
TOKEN_ENV = "CROWDIN_PERSONAL_TOKEN"
STATE_NAME = "crowdin-state.json"
LANGUAGES = {"de": "de", "es": "es", "in": "id", "it": "it", "pt-rBR": "pt-BR", "tr": "tr", "ko": "ko", "zh-rTW": "zh-TW"}


class SetupError(ValueError):
    """Setup refused an unsafe or incomplete operation."""


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SetupError("Crowdin redirected the request; credentials were not forwarded")


class Crowdin:
    def __init__(self, token):
        if not token or any(character.isspace() for character in token):
            raise SetupError("set " + TOKEN_ENV + " to the owner's Crowdin personal token")
        self.token = token
        self.opener = request.build_opener(NoRedirect())

    def call(self, method, path, payload=None, filename=None):
        if not re.fullmatch(r"[A-Za-z0-9_/?=&.-]+", path) or ".." in path or path.startswith("/"):
            raise SetupError("invalid Crowdin API path")
        data = payload if filename else json.dumps(payload).encode("utf-8") if payload is not None else None
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if filename else "application/json"
        if filename:
            headers["Crowdin-API-FileName"] = filename
        try:
            with self.opener.open(request.Request(API_URL + path, data=data, headers=headers, method=method),
                                  timeout=30) as response:
                raw = response.read(bridge.MAX_BYTES + 1)
            if len(raw) > bridge.MAX_BYTES:
                raise SetupError("Crowdin response exceeded the size limit")
            parsed = json.loads(raw, object_pairs_hook=bridge.unique_object, parse_constant=bridge.reject_constant)
            if not isinstance(parsed, dict) or "data" not in parsed:
                raise SetupError("Crowdin returned an unexpected response")
            expected_type = list if method == "GET" and "?" in path else dict
            if not isinstance(parsed["data"], expected_type):
                raise SetupError("Crowdin returned an unexpected data type")
            return parsed["data"]
        except error.HTTPError as problem:
            problem.close()
            raise SetupError(f"Crowdin returned HTTP {problem.code}; check project access and token permissions") from None
        except (error.URLError, TimeoutError, OSError):
            raise SetupError("Crowdin network request failed; check connectivity before retrying") from None
        except (UnicodeError, json.JSONDecodeError, RecursionError, bridge.ImportError):
            raise SetupError("Crowdin returned malformed JSON") from None

    def items(self, path, **filters):
        offset = 0
        while offset <= bridge.MAX_ENTRIES:
            query = parse.urlencode({**filters, "limit": 100, "offset": offset})
            page = self.call("GET", path + "?" + query)
            if not isinstance(page, list) or len(page) > 100:
                raise SetupError("Crowdin returned an invalid page")
            for wrapped in page:
                if not isinstance(wrapped, dict) or not isinstance(wrapped.get("data"), dict):
                    raise SetupError("Crowdin returned an invalid item")
                yield wrapped["data"]
            if len(page) < 100:
                return
            offset += len(page)
        raise SetupError("Crowdin listing exceeded the entry limit")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def catalog_files(root):
    _, keys, tables, _ = bridge.catalog(root)
    source = set(keys) | {key for _, rows in tables.values() for key in rows}
    english = {key: key if key in keys else bridge.generator.plural_base(key) for key in source}
    files = {"en.json": bridge.json_bytes(english)}
    for language, (_, rows) in tables.items():
        if language not in LANGUAGES:
            raise SetupError("add the reviewed Crowdin language mapping for " + language + " first")
        files[language + ".json"] = bridge.json_bytes(rows)
    return english, tables, files


def review_csv(english, rows):
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(["identifier", "english_key", "english_text", "current_translation", "format_arguments",
                     "plural_category", "review_status", "replacement", "notes", "reviewer"])
    for key, text in sorted(english.items()):
        arguments, literals = bridge.generator.format_tokens(text)
        variant = bridge.generator.PLURAL_VARIANT.match(key) if key != text else None
        cells = [bridge.identifier(key), key, text, rows.get(key, ""),
                 " ".join(arguments + sorted(literals.elements())),
                 variant.group(2) if variant else "", "pending", "", "", ""]
        # CSV quoting alone doesn't stop a spreadsheet from interpreting a cell as a formula.
        writer.writerow(["'" + cell if cell.lstrip().startswith(("=", "+", "-", "@")) else cell for cell in cells])
    return ("\ufeff" + output.getvalue()).encode("utf-8")


def prepare(root, output):
    english, tables, files = catalog_files(root)
    manifest = {"format": 1, "source_sha256": digest(files["en.json"]),
                "seed_languages": {language: LANGUAGES[language] for language in tables},
                "files": {name: digest(data) for name, data in files.items()}}
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for name, data in files.items():
        (output / name).write_bytes(data)
    for language in sorted(set(tables) | {"ko"}):
        rows = tables[language][1] if language in tables else {}
        (output / (language + "-review.csv")).write_bytes(review_csv(english, rows))
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output / "REVIEW.txt").write_text(
        "HushGram native translation review\n\n"
        "Every row starts pending. A native reader must check meaning and natural wording.\n"
        "Use the language's review CSV in a UTF-8 spreadsheet editor. Keep the identifiers and English keys.\n"
        "Write accepted or change in review_status, a replacement when needed, notes and your name.\n"
        "Keep format arguments unchanged. Numbered arguments may move. Bare arguments keep their order.\n"
        "Plural variants use english_text as their source and plural_category for the quantity form.\n"
        "Every JSON file is a draft seed until a native reader reviews it.\n"
        "Worksheet decisions do not approve Crowdin translations or import them into the app.\n"
        "A maintainer applies reviewed corrections to TSV, regenerates the runtime table, then checks screens.\n"
        "Use docs/translations.md for hosted setup and the full review checklist.\n",
        encoding="utf-8")
    return len(english), sum(len(rows) for _, rows in tables.values())


def read_object(path):
    value = json.loads(bridge.bounded_bytes(path), object_pairs_hook=bridge.unique_object,
                       parse_constant=bridge.reject_constant)
    if not isinstance(value, dict):
        raise SetupError("expected a JSON object in " + Path(path).name)
    return value


def package_files(root, output):
    _, tables, expected = catalog_files(root)
    manifest = read_object(output / "manifest.json")
    wanted = {"format": 1, "source_sha256": digest(expected["en.json"]),
              "seed_languages": {language: LANGUAGES[language] for language in tables},
              "files": {name: digest(data) for name, data in expected.items()}}
    if manifest != wanted or any(bridge.bounded_bytes(output / name) != data for name, data in expected.items()):
        raise SetupError("package differs from the current catalog; prepare a new folder before uploading")
    return manifest, expected


def positive_id(value):
    if type(value) is not int or value <= 0:
        raise SetupError("Crowdin returned an invalid numeric identifier")
    return value


def unique_named(items, field, name):
    matches = [item for item in items if item.get(field) == name]
    if len(matches) > 1:
        raise SetupError("Crowdin returned multiple matching " + field + " values")
    return matches[0] if matches else None


def setup(root, output, client, identifier="hushgram", poll_seconds=120):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,49}", identifier):
        raise SetupError("project identifier must use 3 to 50 lowercase letters, digits or hyphens")
    output = Path(output)
    manifest, files = package_files(root, output)
    state_path = output / STATE_NAME
    saved = bridge.snapshot(state_path)
    package_digest = digest(json.dumps(manifest, sort_keys=True).encode("utf-8"))
    state = read_object(state_path) if saved.data is not None else {
        "format": 1, "source_sha256": manifest["source_sha256"], "package_sha256": package_digest,
        "project_identifier": identifier, "imports": {}}
    if (state.get("format") != 1 or state.get("source_sha256") != manifest["source_sha256"]
            or state.get("package_sha256") != package_digest
            or state.get("project_identifier") != identifier or not isinstance(state.get("imports"), dict)):
        raise SetupError("setup receipt belongs to another package or project")

    def checkpoint():
        nonlocal saved
        data = (json.dumps(state, indent=2, sort_keys=True) + "\n").encode("utf-8")
        bridge.replace_if_unchanged(state_path, saved, data)
        saved = bridge.snapshot(state_path)

    targets = sorted(set(manifest["seed_languages"].values()) | {"ko"})
    settings = {"type": 0, "sourceLanguageId": "en", "visibility": "open",
                "skipUntranslatedStrings": True, "exportApprovedOnly": True, "normalizePlaceholder": False}
    project = unique_named(client.items("projects", hasManagerAccess=1), "identifier", identifier)
    if project is None:
        if state.get("project_id"):
            raise SetupError("recorded project is no longer accessible; setup did not create a replacement")
        project = client.call("POST", "projects", {**settings, "identifier": identifier, "name": "HushGram",
                                                   "targetLanguageIds": targets, "languageAccessPolicy": "open"})
    project_id = positive_id(project.get("id"))
    if state.get("project_id", project_id) != project_id:
        raise SetupError("project identifier now belongs to a different project")
    state["project_id"] = project_id
    checkpoint()
    project = client.call("GET", f"projects/{project_id}")
    if (any(project.get(key) != value for key, value in settings.items())
            or project.get("identifier") != identifier
            or not isinstance(project.get("targetLanguageIds"), list)
            or not set(targets).issubset(project["targetLanguageIds"])):
        raise SetupError("existing project settings differ; see the required settings in docs/translations.md")
    prefix = f"projects/{project_id}"
    source_file = unique_named(client.items(prefix + "/files"), "name", "en.json")
    if source_file is None:
        storage = positive_id(client.call("POST", "storages", files["en.json"], "en.json").get("id"))
        source_file = client.call("POST", prefix + "/files", {
            "storageId": storage, "name": "en.json", "type": "json",
            "context": "HushGram settings. Keep Java format arguments unchanged. Review all draft wording with a native reader.",
            "exportOptions": {"exportPattern": "/%locale%/en.json"}})
    file_id = positive_id(source_file.get("id"))
    if (state.get("file_id", file_id) != file_id or source_file.get("directoryId") is not None
            or source_file.get("branchId") is not None):
        raise SetupError("source file differs from the recorded root file")
    state["file_id"] = file_id
    checkpoint()
    remote = list(client.items(prefix + "/strings", fileId=file_id))
    values = {item.get("identifier"): item.get("text") for item in remote}
    if len(values) != len(remote) or values != json.loads(files["en.json"]):
        raise SetupError("hosted source differs or is still processing; no translations were uploaded")
    for language, hosted_language in sorted(manifest["seed_languages"].items()):
        receipt = state["imports"].get(language)
        if receipt is None:
            if next(client.items(prefix + "/languages/" + hosted_language + "/translations", fileId=file_id), None):
                raise SetupError("existing " + language + " translations need review; draft seeds were not uploaded over them")
            storage = positive_id(client.call("POST", "storages", files[language + ".json"], language + ".json").get("id"))
            state["imports"][language] = {"status": "submitting"}
            checkpoint()
            job = client.call("POST", prefix + "/translations/imports", {
                "storageId": storage, "fileId": file_id, "languageIds": [hosted_language],
                "importEqSuggestions": True, "autoApproveImported": False, "addToTm": False})
            job_id = job.get("identifier")
            if not isinstance(job_id, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,80}", job_id):
                raise SetupError("Crowdin returned an invalid import job; inspect the hosted import before retrying")
            receipt = state["imports"][language] = {"status": "started", "identifier": job_id}
            checkpoint()
        if receipt.get("status") == "submitting":
            raise SetupError("a previous " + language + " upload has an unknown outcome; inspect Crowdin before retrying it")
        if receipt.get("status") == "finished":
            continue
        if receipt.get("status") != "started" or not re.fullmatch(r"[a-zA-Z0-9-]{1,80}", receipt.get("identifier", "")):
            raise SetupError("invalid translation import receipt")
        deadline = time.monotonic() + poll_seconds
        while True:
            job = client.call("GET", prefix + "/translations/imports/" + receipt["identifier"])
            if job.get("status") == "finished":
                report = client.call("GET", prefix + "/translations/imports/" + receipt["identifier"] + "/report")
                records = report.get("languages", [])
                result = unique_named(records, "id", hosted_language)
                statistics = unique_named(result.get("files", []) if result else [], "id", file_id)
                wanted_count = len(json.loads(files[language + ".json"]))
                if (not statistics or statistics.get("statistics", {}).get("phrases") != wanted_count
                        or any(result.get("skipped", {}).values())):
                    raise SetupError("Crowdin did not import every " + language + " draft; inspect the import report")
                receipt["status"] = "finished"
                checkpoint()
                break
            if job.get("status") not in ("created", "inProgress"):
                raise SetupError("Crowdin translation import failed; inspect its report before retrying")
            if time.monotonic() >= deadline:
                raise SetupError("translation import is still running; repeat setup to resume the recorded job")
            time.sleep(1)
    return "https://crowdin.com/project/" + identifier


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="create a new package with JSON drafts and native-review worksheets")
    preparation.add_argument("--output", type=Path, required=True)
    hosted = commands.add_parser("setup", help="create or resume the hosted project using an environment token")
    hosted.add_argument("--package", type=Path, required=True)
    hosted.add_argument("--identifier", default="hushgram")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            keys, rows = prepare(args.root, args.output)
            print(f"prepared {keys} English keys and {rows} draft translations; every review remains pending")
        else:
            url = setup(args.root, args.package, Crowdin(os.environ.get(TOKEN_ENV)))
            print("Crowdin project prepared: " + url + "; native review and screen checks remain required")
    except (OSError, ValueError) as problem:
        print("translation setup failed: " + str(problem), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
