"""Verify review packages and safe, resumable Crowdin setup without an account.

Copyright 2026 HushGram contributors. GPL-3.0-only.
https://github.com/SysAdminDoc/HushGram
"""
import csv
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("hushgram_crowdin_setup", ROOT / "scripts/crowdin-l10n.py")
hosted = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hosted)
SCRATCH = Path(os.environ.get("HUSHGRAM_L10N_TEST_WORK", str(ROOT / "build/l10n-tests")))
SCRATCH.mkdir(parents=True, exist_ok=True)


class Response(io.BytesIO):
    def __init__(self, value):
        super().__init__(json.dumps({"data": value}).encode("utf-8"))


class Service:
    """An independent file-based API fixture operating on actual HTTP requests."""
    def __init__(self):
        self.projects = []
        self.files = []
        self.storages = {}
        self.imports = {}
        self.translations = {}
        self.calls = []
        self.lose_response = None
        self.import_status = "finished"
        self.bad_report = False
        self.source_override = None

    def open(self, req, timeout):
        if timeout != 30 or not req.full_url.startswith(hosted.API_URL):
            raise AssertionError("unexpected request destination or timeout")
        if req.get_header("Authorization") != "Bearer fixture-token":
            raise AssertionError("missing authorization")
        url = parse.urlsplit(req.full_url)
        path = url.path.removeprefix("/api/v2/")
        query = parse.parse_qs(url.query)
        method = req.get_method()
        filename = req.get_header("Crowdin-api-filename")
        body = req.data if filename else json.loads(req.data) if req.data else None
        self.calls.append((method, path, body))
        if method == "GET" and path == "projects":
            items = self.projects
        elif method == "POST" and path == "projects":
            if any(project["identifier"] == body["identifier"] for project in self.projects):
                raise AssertionError("duplicate project creation")
            value = {**body, "id": 17}
            self.projects.append(value)
        elif method == "GET" and path == "projects/17":
            value = self.projects[-1]
        elif method == "POST" and path == "storages":
            if req.get_header("Content-type") != "application/octet-stream":
                raise AssertionError("storage must contain raw bytes")
            storage_id = len(self.storages) + 1
            self.storages[storage_id] = req.data
            value = {"id": storage_id}
        elif method == "GET" and path == "projects/17/files":
            items = self.files
        elif method == "POST" and path == "projects/17/files":
            if self.files:
                raise AssertionError("duplicate source creation")
            if body["type"] != "json" or body["name"] != "en.json":
                raise AssertionError("wrong source type")
            value = {**body, "id": 29, "directoryId": None, "branchId": None}
            self.files.append(value)
        elif method == "GET" and path == "projects/17/strings":
            if query["fileId"] != ["29"]:
                raise AssertionError("source must be scoped to the file")
            source = json.loads(self.storages[self.files[0]["storageId"]])
            if self.source_override is not None:
                source = self.source_override
            items = [{"id": index, "identifier": identifier, "text": text}
                     for index, (identifier, text) in enumerate(source.items(), 1)]
        elif method == "GET" and path.startswith("projects/17/languages/") and path.endswith("/translations"):
            items = self.translations.get(path.split("/")[3], [])
        elif method == "POST" and path == "projects/17/translations/imports":
            if (body["fileId"] != 29 or body["autoApproveImported"] is not False
                    or body["importEqSuggestions"] is not True or body["addToTm"] is not False):
                raise AssertionError("unsafe translation import")
            job_id = "job-" + str(len(self.imports) + 1)
            self.imports[job_id] = body
            language = body["languageIds"][0]
            self.translations[language] = [{"text": text} for text in json.loads(self.storages[body["storageId"]]).values()]
            value = {"identifier": job_id, "status": "created"}
        elif method == "GET" and path.startswith("projects/17/translations/imports/"):
            job_id = path.split("/")[4]
            job = self.imports[job_id]
            if path.endswith("/report"):
                phrases = len(json.loads(self.storages[job["storageId"]]))
                value = {"languages": [{"id": job["languageIds"][0],
                          "files": [{"id": 29, "statistics": {"phrases": phrases}}],
                          "skipped": {"translationEqSource": 0, "hiddenStrings": 0,
                                      "qaCheck": 1 if self.bad_report else 0}}]}
            else:
                value = {"identifier": job_id, "status": self.import_status}
        else:
            raise AssertionError("unexpected API endpoint: " + method + " " + path)
        if method == "GET" and query:
            offset, limit = int(query["offset"][0]), int(query["limit"][0])
            value = [{"data": item} for item in items[offset:offset + limit]]
        if self.lose_response == (method, path):
            self.lose_response = None
            raise error.URLError("fixture disconnected after the server committed")
        return Response(value)

    def writes(self, path=None):
        return [call for call in self.calls if call[0] == "POST" and (path is None or call[1] == path)]


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="crowdin-", dir=SCRATCH)
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.root = self.work / "repo"
        application = self.root / "extensions/instagram/src/main/java"
        application.mkdir(parents=True)
        (self.root / "extensions/shared/library/src/main/java").mkdir(parents=True)
        self.rows = {"Hello": "Hallo", "Value %1$s": "Wert %1$s", "Same": "Same"}
        (application / "Catalog.java").write_text(
            'class Catalog { void show() { L10n.t("Hello"); L10n.f("Value %1$s", value); L10n.t("Same"); } }',
            encoding="utf-8")
        folder = self.root / hosted.bridge.TABLE_DIRECTORY
        folder.mkdir(parents=True)
        for language in ("de", "es", "in", "pt-rBR", "tr"):
            (folder / (language + ".tsv")).write_text(
                "".join(key + "\t" + value + "\n" for key, value in self.rows.items()), encoding="utf-8")
        self.package = self.work / "review"
        hosted.prepare(self.root, self.package)
        self.service = Service()
        self.client = hosted.Crowdin("fixture-token")
        self.client.opener = self.service

    def setup_project(self, **options):
        return hosted.setup(self.root, self.package, self.client, **options)

    def state(self):
        return json.loads((self.package / hosted.STATE_NAME).read_bytes())

    def test_package_has_exact_catalog_and_unapproved_native_review_worksheets(self):
        manifest = json.loads((self.package / "manifest.json").read_bytes())
        self.assertEqual({"de": "de", "es": "es", "in": "id", "pt-rBR": "pt-BR", "tr": "tr"},
                         manifest["seed_languages"])
        self.assertEqual({hosted.bridge.identifier(key): key for key in self.rows},
                         json.loads((self.package / "en.json").read_bytes()))
        for language in (*manifest["seed_languages"], "ko"):
            with (self.package / (language + "-review.csv")).open(encoding="utf-8-sig", newline="") as handle:
                records = list(csv.DictReader(handle))
            self.assertEqual(3, len(records))
            self.assertEqual({"pending"}, {record["review_status"] for record in records})
            self.assertTrue(all(not record["reviewer"] for record in records))
            for record in records:
                self.assertEqual("" if language == "ko" else self.rows[record["english_key"]],
                                 record["current_translation"])
        self.assertFalse((self.package / "ko.json").exists())
        second = self.work / "second"
        hosted.prepare(self.root, second)
        self.assertEqual((self.package / "manifest.json").read_bytes(), (second / "manifest.json").read_bytes())

    def test_prepare_refuses_to_overwrite_a_review(self):
        worksheet = self.package / "de-review.csv"
        worksheet.write_text("reader's edits", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            hosted.prepare(self.root, self.package)
        self.assertEqual("reader's edits", worksheet.read_text(encoding="utf-8"))

    def test_traditional_chinese_keeps_its_regional_language_and_review_rows(self):
        folder = self.root / hosted.bridge.TABLE_DIRECTORY
        rows = {"Hello": "你好", "Value %1$s": "值 %1$s", "Same": "相同"}
        (folder / "zh-rTW.tsv").write_bytes("".join(
            key + "\t" + value + "\n" for key, value in rows.items()).encode("utf-8"))
        package = self.work / "taiwan-review"
        hosted.prepare(self.root, package)
        manifest = json.loads((package / "manifest.json").read_bytes())
        self.assertEqual("zh-TW", manifest["seed_languages"]["zh-rTW"])
        self.assertEqual({hosted.bridge.identifier(key): value for key, value in rows.items()},
                         json.loads((package / "zh-rTW.json").read_bytes()))
        with (package / "zh-rTW-review.csv").open(encoding="utf-8-sig", newline="") as handle:
            records = list(csv.DictReader(handle))
        self.assertEqual(rows, {record["english_key"]: record["current_translation"] for record in records})
        self.assertEqual({"pending"}, {record["review_status"] for record in records})

    def test_plural_context_preserves_literal_suffixes_and_formula_text_is_inert(self):
        english = {"Literal|few": "Literal|few", "Items %1$d|few": "Items %1$d", "Value": "Value"}
        data = hosted.review_csv(english, {"Value": "=1+1"}).decode("utf-8-sig")
        records = {row["english_key"]: row for row in csv.DictReader(io.StringIO(data))}
        self.assertEqual("", records["Literal|few"]["plural_category"])
        self.assertEqual("few", records["Items %1$d|few"]["plural_category"])
        self.assertEqual("%1$d", records["Items %1$d|few"]["format_arguments"])
        self.assertEqual("'=1+1", records["Value"]["current_translation"])

    def test_setup_creates_source_targets_and_unapproved_seeds_then_checks_reports(self):
        before = {path.name: path.read_bytes() for path in (self.root / hosted.bridge.TABLE_DIRECTORY).glob("*.tsv")}
        self.assertEqual("https://crowdin.com/project/hushgram", self.setup_project())
        project = self.service.projects[0]
        self.assertEqual(["de", "es", "id", "ko", "pt-BR", "tr"], project["targetLanguageIds"])
        self.assertTrue(project["exportApprovedOnly"])
        self.assertTrue(project["skipUntranslatedStrings"])
        self.assertFalse(project["normalizePlaceholder"])
        self.assertEqual(5, len(self.service.imports))
        self.assertEqual({"finished"}, {receipt["status"] for receipt in self.state()["imports"].values()})
        self.assertTrue(all(any(row["text"] == "Same" for row in rows)
                            for rows in self.service.translations.values()))
        self.assertEqual(before, {path.name: path.read_bytes()
                                 for path in (self.root / hosted.bridge.TABLE_DIRECTORY).glob("*.tsv")})
        self.assertNotIn("fixture-token", (self.package / hosted.STATE_NAME).read_text(encoding="utf-8"))

    def test_repeat_setup_never_reuploads_completed_seeds_or_overwrites_reader_changes(self):
        self.setup_project()
        writes = len(self.service.writes())
        self.service.translations["de"] = [{"text": "Native reader's replacement"}]
        self.setup_project()
        self.assertEqual(writes, len(self.service.writes()))
        self.assertEqual([{"text": "Native reader's replacement"}], self.service.translations["de"])

    def test_pagination_finds_existing_project_instead_of_creating_duplicate(self):
        self.setup_project()
        self.service.projects = [{"identifier": "unrelated-" + str(index)} for index in range(105)] + self.service.projects
        self.service.calls.clear()
        self.setup_project()
        self.assertEqual([], self.service.writes())

    def test_existing_incompatible_project_is_not_changed(self):
        self.setup_project()
        self.service.projects[0]["exportApprovedOnly"] = False
        writes = len(self.service.writes())
        with self.assertRaisesRegex(hosted.SetupError, "settings differ"):
            self.setup_project()
        self.assertEqual(writes, len(self.service.writes()))

    def test_existing_mismatched_source_is_not_replaced(self):
        self.setup_project()
        self.service.source_override = {"another-key": "Another project"}
        writes = len(self.service.writes())
        with self.assertRaisesRegex(hosted.SetupError, "hosted source differs"):
            self.setup_project()
        self.assertEqual(writes, len(self.service.writes()))

    def test_existing_translations_without_receipts_are_not_seeded_again(self):
        self.setup_project()
        (self.package / hosted.STATE_NAME).unlink()
        writes = len(self.service.writes())
        with self.assertRaisesRegex(hosted.SetupError, "existing de translations"):
            self.setup_project()
        self.assertEqual(writes, len(self.service.writes()))

    def test_unknown_project_creation_response_is_recovered_by_identifier(self):
        self.service.lose_response = ("POST", "projects")
        with self.assertRaisesRegex(hosted.SetupError, "network request failed"):
            self.setup_project()
        self.setup_project()
        self.assertEqual(1, len(self.service.writes("projects")))

    def test_unknown_file_creation_response_is_recovered_without_replacing_source(self):
        self.service.lose_response = ("POST", "projects/17/files")
        with self.assertRaisesRegex(hosted.SetupError, "network request failed"):
            self.setup_project()
        self.setup_project()
        self.assertEqual(1, len(self.service.writes("projects/17/files")))

    def test_unknown_translation_import_response_is_not_retried(self):
        self.service.lose_response = ("POST", "projects/17/translations/imports")
        with self.assertRaisesRegex(hosted.SetupError, "network request failed"):
            self.setup_project()
        self.assertEqual("submitting", self.state()["imports"]["de"]["status"])
        with self.assertRaisesRegex(hosted.SetupError, "unknown outcome"):
            self.setup_project()
        self.assertEqual(1, len(self.service.imports))

    def test_timed_out_recorded_job_resumes_without_another_import(self):
        self.service.import_status = "inProgress"
        with self.assertRaisesRegex(hosted.SetupError, "still running"):
            self.setup_project(poll_seconds=0)
        self.assertEqual("started", self.state()["imports"]["de"]["status"])
        self.service.import_status = "finished"
        self.setup_project()
        self.assertEqual(5, len(self.service.imports))

    def test_skipped_translation_report_does_not_claim_seed_completed(self):
        self.service.bad_report = True
        with self.assertRaisesRegex(hosted.SetupError, "did not import every de draft"):
            self.setup_project()
        self.assertEqual("started", self.state()["imports"]["de"]["status"])

    def test_changed_upload_package_refuses_before_any_network_request(self):
        (self.package / "de.json").write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(hosted.SetupError, "package differs"):
            self.setup_project()
        self.assertEqual([], self.service.calls)

    def test_review_edits_do_not_change_the_draft_upload_contract(self):
        (self.package / "de-review.csv").write_text("reviewer edits", encoding="utf-8")
        self.setup_project()
        self.assertEqual(5, len(self.service.imports))

    def test_old_receipts_cannot_skip_changed_drafts_with_the_same_english_source(self):
        self.setup_project()
        source = self.root / hosted.bridge.TABLE_DIRECTORY / "de.tsv"
        source.write_text(source.read_text(encoding="utf-8").replace("Hallo", "Guten Tag"), encoding="utf-8")
        new_package = self.work / "updated-review"
        hosted.prepare(self.root, new_package)
        (new_package / hosted.STATE_NAME).write_bytes((self.package / hosted.STATE_NAME).read_bytes())
        writes = len(self.service.writes())
        with self.assertRaisesRegex(hosted.SetupError, "receipt belongs to another package"):
            hosted.setup(self.root, new_package, self.client)
        self.assertEqual(writes, len(self.service.writes()))

    def test_receipt_write_failure_stops_before_submitting_translation_import(self):
        original = hosted.bridge.replace_if_unchanged

        def refuse_import_receipt(path, expected, data):
            if path.name == hosted.STATE_NAME and json.loads(data)["imports"]:
                raise OSError("fixture receipt write failed")
            return original(path, expected, data)

        with mock.patch.object(hosted.bridge, "replace_if_unchanged", side_effect=refuse_import_receipt), \
                self.assertRaisesRegex(OSError, "receipt write failed"):
            self.setup_project()
        self.assertEqual([], self.service.writes("projects/17/translations/imports"))

    def test_http_error_does_not_echo_server_body_or_token(self):
        problem = error.HTTPError(hosted.API_URL, 403, "fixture-token", {}, io.BytesIO(b"fixture-token"))
        with mock.patch.object(self.client.opener, "open", side_effect=problem), self.assertRaises(hosted.SetupError) as raised:
            self.client.call("GET", "projects/17")
        self.assertNotIn("fixture-token", str(raised.exception))
        self.assertIn("HTTP 403", str(raised.exception))

    def test_redirect_is_refused_before_forwarding_authorization(self):
        req = request.Request(hosted.API_URL, headers={"Authorization": "Bearer fixture-token"})
        with self.assertRaisesRegex(hosted.SetupError, "credentials were not forwarded"):
            hosted.NoRedirect().redirect_request(req, None, 302, "Found", {}, "https://elsewhere.example/")

    def test_wrong_response_type_and_external_paths_are_refused(self):
        with mock.patch.object(self.client.opener, "open", return_value=Response([])), \
                self.assertRaisesRegex(hosted.SetupError, "unexpected data type"):
            self.client.call("GET", "projects/17")
        with self.assertRaisesRegex(hosted.SetupError, "invalid Crowdin API path"):
            self.client.call("GET", "https://elsewhere.example/")

    def test_duplicate_response_keys_do_not_echo_the_response_body(self):
        body = io.BytesIO(b'{"data":{"fixture-token":1,"fixture-token":2}}')
        with mock.patch.object(self.client.opener, "open", return_value=body), \
                self.assertRaisesRegex(hosted.SetupError, "malformed JSON") as raised:
            self.client.call("GET", "projects/17")
        self.assertNotIn("fixture-token", str(raised.exception))

    def test_missing_or_header_injection_token_stops_before_network(self):
        for token in (None, "", "bad\nheader"):
            with self.subTest(token_present=bool(token)), self.assertRaisesRegex(hosted.SetupError, "set CROWDIN"):
                hosted.Crowdin(token)


if __name__ == "__main__":
    unittest.main()
