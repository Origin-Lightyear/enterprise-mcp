import json
import io
import os
import tempfile
import unittest
import zipfile
import shutil
import threading
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from pathlib import Path
from unittest.mock import MagicMock, patch

import yaml

from .admin_service import AdminService, _metadata_text, _next_revision
from .server import Handler, WrenRuntime


class FakeRuntime:
    def __init__(self):
        self.reloaded = None

    def close(self):
        return None

    def replace_current(self, _source_key, _path):
        return None

    def reload_from_disk(self, path):
        self.reloaded = path


class AdminDatasourceDeletionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.home = self.root / "instance" / ".wren"
        self.home.mkdir(parents=True)
        self.profiles = self.home / "profiles.yml"
        self.profiles.write_text(yaml.safe_dump({"active": "sales", "profiles": {
            "sales": {"datasource": "mysql", "password": "${WREN_SALES_PASSWORD}"},
            "finance": {"datasource": "postgres", "password": "${WREN_FINANCE_PASSWORD}"},
        }}), encoding="utf-8")
        (self.home / ".env").write_text("WREN_SALES_PASSWORD=one\nWREN_FINANCE_PASSWORD=two\n", encoding="utf-8")
        self.profile_patch = patch.multiple("wren.profile", _WREN_HOME=self.home, _PROFILES_FILE=self.profiles)
        self.profile_patch.start()
        self.runtime = WrenRuntime({})
        self.service = AdminService(self.root / "project", self.root / "instance", self.runtime)
        self.service._write_datasource_display_names({"sales": "销售数据库", "finance": "财务数据库"})
        for name in ("sales", "finance"):
            current = self.service.current_dir / name
            self.service._persist_modeling({"revision": 1, "profileName": name, "datasource": "mysql", "models": [{
                "name": "Orders", "displayName": "订单", "description": "", "source": {"catalog": "", "schema": "public", "table": "orders"},
                "primaryKey": ["id"], "columns": [{"name": "id", "displayName": "编号", "description": "", "type": "BIGINT", "rawType": "bigint", "notNull": True, "primaryKey": True, "visible": True}],
            }], "relationships": []})
            shutil.copytree(self.service._modeling_path(name), current)
        self.runtime.reload_from_disk(self.service.current_dir)

    def tearDown(self):
        self.service.close()
        self.runtime.close()
        self.profile_patch.stop()
        self.temporary.cleanup()

    def test_prepare_and_cancel_preserve_all_local_state(self):
        before = self.profiles.read_bytes()
        digest = self.runtime.mdl_digest()
        candidate = self.service.prepare_mdl("sales", True)
        self.assertEqual({"finance"}, set(candidate["manifest"]["sources"]))
        self.assertEqual(digest, self.runtime.mdl_digest())
        self.assertEqual(before, self.profiles.read_bytes())
        self.service.discard_candidate(candidate["candidateToken"])
        self.assertTrue((self.service.current_dir / "sales").exists())
        self.assertTrue(self.service._modeling_path("sales").exists())

    def test_commit_removes_connection_modeling_current_and_owned_secret_preserving_other_source(self):
        digest = self.runtime.mdl_digest()
        candidate = self.service.prepare_mdl("sales", True)
        result = self.service.commit_mdl(candidate["candidateToken"], digest)
        self.assertEqual(candidate["mdlDigest"], result["mdlDigest"])
        self.assertEqual({"finance"}, set(result["manifest"]["sources"]))
        self.assertFalse((self.service.current_dir / "sales").exists())
        self.assertFalse(self.service._modeling_path("sales").exists())
        profiles = yaml.safe_load(self.profiles.read_text(encoding="utf-8"))
        self.assertEqual("finance", profiles["active"])
        self.assertEqual({"finance"}, set(profiles["profiles"]))
        self.assertEqual("WREN_FINANCE_PASSWORD=two\n", (self.home / ".env").read_text(encoding="utf-8"))
        self.assertEqual({"finance": "财务数据库"}, self.service._load_datasource_display_names())
        with self.assertRaisesRegex(ValueError, "sourceKey is not present"):
            self.runtime.manifest("sales")

    def test_last_source_deletion_returns_empty_current_and_no_active_connection(self):
        for source in ("sales", "finance"):
            digest = self.runtime.mdl_digest()
            candidate = self.service.prepare_mdl(source, True)
            result = self.service.commit_mdl(candidate["candidateToken"], digest)
        self.assertEqual({"configured": False, "mdlDigest": "", "manifest": {}}, result)
        self.assertEqual([], self.service.list_datasources()["items"])
        self.assertEqual([], self.service.get_modeling()["models"])

    def test_profile_write_failure_restores_connection_modeling_and_current(self):
        before = self.profiles.read_bytes()
        digest = self.runtime.mdl_digest()
        candidate = self.service.prepare_mdl("sales", True)
        with patch("customer.wren.adapter.admin_service.remove_profile", side_effect=OSError("write failed")):
            with self.assertRaisesRegex(OSError, "write failed"):
                self.service.commit_mdl(candidate["candidateToken"], digest)
        self.assertEqual(before, self.profiles.read_bytes())
        self.assertEqual(digest, self.runtime.mdl_digest())
        self.assertTrue(self.service._modeling_path("sales").exists())
        self.assertTrue((self.service.current_dir / "sales").exists())

    def test_rejects_changed_mdl_or_modeling_and_running_introspection(self):
        digest = self.runtime.mdl_digest()
        candidate = self.service.prepare_mdl("sales", True)
        with self.assertRaisesRegex(RuntimeError, "CURRENT_MDL_CHANGED"):
            self.service.commit_mdl(candidate["candidateToken"], "outdated")
        model = self.service.get_modeling("sales")
        model["revision"] = 2
        self.service._persist_modeling(model)
        with self.assertRaisesRegex(RuntimeError, "MODELING_CHANGED"):
            self.service.commit_mdl(candidate["candidateToken"], digest)
        self.service._active_job_id = "job"
        with patch.object(self.service, "get_job", return_value={"profileName": "sales", "status": "RUNNING"}):
            with self.assertRaisesRegex(RuntimeError, "INTROSPECTION_IN_PROGRESS"):
                self.service.prepare_mdl("sales", True)

    def test_unmodeled_connection_still_commits_and_deletion_is_repeatable(self):
        from wren.profile import add_profile
        add_profile("unused", {"datasource": "mysql"})
        digest = self.runtime.mdl_digest()
        for _ in range(2):
            candidate = self.service.prepare_mdl("unused", True)
            result = self.service.commit_mdl(candidate["candidateToken"], digest)
            self.assertEqual(digest, result["mdlDigest"])
        self.assertNotIn("unused", {item["name"] for item in self.service.list_datasources()["items"]})

    def test_http_candidate_commit_deletes_real_project_and_changes_query_context(self):
        class TestHandler(Handler):
            pass
        TestHandler.admin = self.service
        TestHandler.runtime = self.runtime
        TestHandler.service_token = "test-service"
        server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"

        def post(path, body):
            request = Request(base + path, data=json.dumps(body).encode(), method="POST", headers={
                "Content-Type": "application/json", "X-Wren-Service-Token": "test-service",
            })
            with urlopen(request, timeout=5) as response:
                return json.load(response)["data"]

        try:
            digest = self.runtime.mdl_digest()
            candidate = post("/internal/admin/mdl-candidates", {"profileName": "sales", "deleteDatasource": True})
            self.assertTrue(self.service._modeling_path("sales").exists())
            result = post(f"/internal/admin/mdl-candidates/{candidate['candidateToken']}/commit", {"expectedMdlDigest": digest})
            self.assertEqual(candidate["mdlDigest"], result["mdlDigest"])
            self.assertEqual({"finance"}, set(result["manifest"]["sources"]))
            self.assertFalse(self.service._modeling_path("sales").exists())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_http_display_name_update_keeps_profile_unchanged(self):
        class TestHandler(Handler):
            pass
        TestHandler.admin = self.service
        TestHandler.runtime = self.runtime
        TestHandler.service_token = "test-service"
        server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        profiles_before = self.profiles.read_bytes()
        request = Request(
            f"http://127.0.0.1:{server.server_port}/internal/admin/datasources/sales/display-name",
            data=json.dumps({"displayName": "华东销售库"}).encode(),
            method="PUT",
            headers={"Content-Type": "application/json", "X-Wren-Service-Token": "test-service"},
        )
        try:
            with urlopen(request, timeout=5) as response:
                result = json.load(response)["data"]
            self.assertEqual({"name": "sales", "displayName": "华东销售库"}, result)
            self.assertEqual(profiles_before, self.profiles.read_bytes())
            self.assertEqual("华东销售库", self.service._load_datasource_display_names()["sales"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_startup_recovers_interrupted_deletion_before_candidate_cleanup(self):
        from .admin_service import _write_json
        root = self.service.candidates_dir / ("c" * 32)
        root.mkdir()
        _write_json(root / "deletion-journal.json", {
            "profileName": "sales", "profiles": self.profiles.read_text(encoding="utf-8"),
            "env": (self.home / ".env").read_text(encoding="utf-8"), "committed": False,
            "metadata": self.service.datasource_metadata_file.read_text(encoding="utf-8"),
        })
        (self.service.current_dir / "sales").rename(root / "previous")
        self.service._modeling_path("sales").rename(root / "modeling")
        self.profiles.write_text("active: null\nprofiles: {}\n", encoding="utf-8")
        self.service._write_datasource_display_names({"finance": "财务数据库"})
        restarted_runtime = WrenRuntime({})
        restarted = AdminService(self.root / "project", self.root / "instance", restarted_runtime)
        try:
            self.assertTrue(restarted._modeling_path("sales").exists())
            self.assertEqual({"sales", "finance"}, set(restarted_runtime.context_snapshot()["manifest"]["sources"]))
            self.assertEqual(2, len(restarted.list_datasources()["items"]))
            self.assertEqual("销售数据库", restarted._load_datasource_display_names()["sales"])
            self.assertFalse(root.exists())
        finally:
            restarted.close()
            restarted_runtime.close()


class AdminServiceModelingTest(unittest.TestCase):
    def test_datasource_display_name_is_unicode_mutable_and_keeps_profile_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "instance" / ".wren"
            profiles = home / "profiles.yml"
            home.mkdir(parents=True)
            with patch.multiple("wren.profile", _WREN_HOME=home, _PROFILES_FILE=profiles):
                service = AdminService(root / "project", root / "instance", FakeRuntime())
                request = {
                    "name": "sales",
                    "displayName": "销售数据库",
                    "datasource": "mysql",
                    "host": "db.internal",
                    "port": "3306",
                    "database": "sales",
                    "user": "app",
                    "password": "secret",
                }
                try:
                    with patch.object(service, "_test_profile"):
                        service.save_datasource(request)
                        service.save_datasource(
                            {**request, "name": "ignored", "displayName": "华东销售库", "password": ""},
                            update_name="sales",
                        )

                    item = service.list_datasources()["items"][0]
                    self.assertEqual("sales", item["name"])
                    self.assertEqual("华东销售库", item["displayName"])
                    self.assertEqual({"sales"}, set(yaml.safe_load(profiles.read_text(encoding="utf-8"))["profiles"]))
                finally:
                    service.close()

    def test_datasource_display_name_rejects_duplicates_and_control_characters(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "instance" / ".wren"
            profiles = home / "profiles.yml"
            home.mkdir(parents=True)
            with patch.multiple("wren.profile", _WREN_HOME=home, _PROFILES_FILE=profiles):
                service = AdminService(root / "project", root / "instance", FakeRuntime())
                request = {
                    "datasource": "mysql", "host": "db.internal", "port": "3306",
                    "database": "sales", "user": "app", "password": "secret",
                }
                try:
                    with patch.object(service, "_test_profile"):
                        service.save_datasource({**request, "name": "sales", "displayName": "销售数据库"})
                        with self.assertRaisesRegex(ValueError, "displayName already exists"):
                            service.save_datasource({**request, "name": "finance", "displayName": "销售数据库"})
                        with self.assertRaisesRegex(ValueError, "displayName contains control characters"):
                            service.save_datasource({**request, "name": "finance", "displayName": "财务\n数据库"})
                finally:
                    service.close()

    def test_display_name_only_update_does_not_test_or_rewrite_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "instance" / ".wren"
            profiles = home / "profiles.yml"
            home.mkdir(parents=True)
            profiles.write_text(yaml.safe_dump({"active": "sales", "profiles": {
                "sales": {"datasource": "mysql", "host": "db.internal", "password": "${WREN_SALES_PASSWORD}"},
            }}), encoding="utf-8")
            with patch.multiple("wren.profile", _WREN_HOME=home, _PROFILES_FILE=profiles):
                service = AdminService(root / "project", root / "instance", FakeRuntime())
                profiles_before = profiles.read_bytes()
                try:
                    with patch.object(service, "_test_profile") as test_profile:
                        result = service.update_datasource_display_name("sales", {"displayName": "销售数据库"})
                    self.assertEqual({"name": "sales", "displayName": "销售数据库"}, result)
                    self.assertEqual(profiles_before, profiles.read_bytes())
                    self.assertEqual({"sales": "销售数据库"}, service._load_datasource_display_names())
                    test_profile.assert_not_called()
                finally:
                    service.close()

    def test_datasource_list_falls_back_to_profile_name_without_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                with patch("customer.wren.adapter.admin_service.list_profiles", return_value={
                    "legacy_sales": {"datasource": "mysql"},
                }), patch("customer.wren.adapter.admin_service.get_active_name", return_value="legacy_sales"):
                    item = service.list_datasources()["items"][0]
                self.assertEqual("legacy_sales", item["name"])
                self.assertEqual("legacy_sales", item["displayName"])
            finally:
                service.close()

    def test_datasource_metadata_write_failure_rolls_back_profile_secret_and_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "instance" / ".wren"
            profiles = home / "profiles.yml"
            home.mkdir(parents=True)
            profiles.write_text("active: null\nprofiles: {}\n", encoding="utf-8")
            env_file = home / ".env"
            env_file.write_text("EXISTING=value\n", encoding="utf-8")
            with patch.multiple("wren.profile", _WREN_HOME=home, _PROFILES_FILE=profiles):
                service = AdminService(root / "project", root / "instance", FakeRuntime())
                profiles_before = profiles.read_bytes()
                env_before = env_file.read_bytes()
                try:
                    with patch.object(service, "_test_profile"), patch.object(
                            service, "_write_datasource_display_names", side_effect=OSError("metadata write failed")):
                        with self.assertRaisesRegex(OSError, "metadata write failed"):
                            service.save_datasource({
                                "name": "sales", "displayName": "销售数据库", "datasource": "mysql",
                                "host": "db.internal", "port": "3306", "database": "sales",
                                "user": "app", "password": "secret",
                                "secretName": "WREN_METADATA_ROLLBACK_TEST_PASSWORD",
                            })
                    self.assertEqual(profiles_before, profiles.read_bytes())
                    self.assertEqual(env_before, env_file.read_bytes())
                    self.assertFalse(service.datasource_metadata_file.exists())
                    self.assertNotIn("WREN_METADATA_ROLLBACK_TEST_PASSWORD", os.environ)
                finally:
                    service.close()

    def test_backup_round_trip_restores_profiles_and_current_mdl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = FakeRuntime()
            service = AdminService(root / "project", root / "instance", runtime)
            try:
                (root / "instance" / ".wren").mkdir(parents=True)
                (root / "instance" / ".wren" / "profiles.yml").write_text("sales: {}\n", encoding="utf-8")
                (root / "instance" / ".wren" / ".env").write_text("DB_PASSWORD=secret\n", encoding="utf-8")
                service.datasource_metadata_file.write_text(
                    json.dumps({"version": 1, "displayNames": {"sales": "销售数据库"}}, ensure_ascii=False),
                    encoding="utf-8",
                )
                current = root / "project" / "projects" / "current" / "sales"
                current.mkdir(parents=True)
                (current / "wren_project.yml").write_text("name: customer_database\n", encoding="utf-8")
                backup = service.create_backup()
                (root / "instance" / ".wren" / ".env").write_text("DB_PASSWORD=changed\n", encoding="utf-8")
                result = service.restore_backup(backup)
                self.assertTrue(result["restored"])
                self.assertEqual("DB_PASSWORD=secret\n", (root / "instance" / ".wren" / ".env").read_text(encoding="utf-8"))
                self.assertEqual("销售数据库", service._load_datasource_display_names()["sales"])
                self.assertEqual(current, runtime.reloaded / "sales")
            finally:
                service.close()

    def test_restore_rejects_path_traversal_without_touching_existing_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                (root / "instance" / ".wren").mkdir(parents=True)
                existing = root / "instance" / ".wren" / ".env"
                existing.write_text("DB_PASSWORD=original\n", encoding="utf-8")
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w") as archive:
                    archive.writestr("manifest.json", json.dumps({"format": "enterprise-mcp-wren-backup", "version": 1}))
                    archive.writestr("../outside", "bad")
                with self.assertRaisesRegex(ValueError, "unsafe path"):
                    service.restore_backup(output.getvalue())
                self.assertEqual("DB_PASSWORD=original\n", existing.read_text(encoding="utf-8"))
            finally:
                service.close()

    def test_keeps_independent_modeling_content_for_each_datasource(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                service._persist_modeling({
                    "revision": 1,
                    "profileName": "sales",
                    "datasource": "mysql",
                    "models": [],
                    "relationships": [],
                })
                service._persist_modeling({
                    "revision": 2,
                    "profileName": "finance",
                    "datasource": "postgres",
                    "models": [],
                    "relationships": [],
                })

                self.assertEqual("sales", service.get_modeling("sales")["profileName"])
                self.assertEqual(1, service.get_modeling("sales")["revision"])
                self.assertEqual("finance", service.get_modeling("finance")["profileName"])
                self.assertEqual(2, service.get_modeling("finance")["revision"])
                with patch("customer.wren.adapter.admin_service.list_profiles", return_value={
                    "sales": {"datasource": "mysql"},
                    "finance": {"datasource": "postgres"},
                }), patch("customer.wren.adapter.admin_service.get_active_name", return_value="sales"):
                    items = service.list_datasources()["items"]
                self.assertTrue(all(item["modelingInUse"] for item in items))
            finally:
                service.close()

    def test_revision_is_monotonic_and_javascript_safe(self):
        current = _next_revision()
        following = _next_revision(current)

        self.assertGreater(following, current)
        self.assertLessEqual(following, 2**53 - 1)

    def test_decodes_binary_database_metadata_as_utf8(self):
        self.assertEqual("customer_data", _metadata_text(b"customer_data"))
        self.assertEqual("客户主键", _metadata_text("客户主键".encode("utf-8")))

    def test_mysql_introspection_defaults_to_database_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                profiles = {
                    "customer-primary": {
                        "datasource": "mysql",
                        "database": "customer_data",
                    }
                }
                with patch("customer.wren.adapter.admin_service.list_profiles", return_value=profiles), \
                        patch.object(service._executor, "submit"):
                    job = service.create_introspection_job({"profileName": "customer-primary", "schemas": []})

                self.assertEqual(["customer_data"], job["schemas"])
            finally:
                service.close()

    def test_mysql_introspection_omits_catalog_from_table_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            connector = MagicMock()
            columns = MagicMock()
            columns.to_pylist.return_value = [{
                "table_schema": "customer_data",
                "table_name": "store",
                "column_name": "id",
                "data_type": "bigint",
                "is_nullable": "NO",
                "ordinal_position": 1,
                "table_comment": "门店",
                "column_comment": "主键",
            }]
            keys = MagicMock()
            keys.to_pylist.return_value = []
            foreign_keys = MagicMock()
            foreign_keys.to_pylist.return_value = []
            connector.query.side_effect = [columns, keys, foreign_keys]
            try:
                snapshot = service._introspect(
                    connector,
                    {"datasource": "mysql", "database": "customer_data"},
                    "customer-primary",
                    ["customer_data"],
                )

                self.assertEqual("", snapshot["tables"][0]["catalog"])
                self.assertEqual("customer_data", snapshot["tables"][0]["schema"])
            finally:
                service.close()

    def test_introspection_records_unexpected_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                job_id = "b" * 32
                job = {
                    "jobId": job_id,
                    "profileName": "customer-primary",
                    "schemas": ["customer_data"],
                    "status": "PENDING",
                    "stage": "QUEUED",
                }
                (service.jobs_dir / f"{job_id}.json").write_text(json.dumps(job), encoding="utf-8")
                with patch("customer.wren.adapter.admin_service.list_profiles", return_value={
                    "customer-primary": {"datasource": "mysql", "database": "customer_data"}
                }), patch.object(service, "_connector", side_effect=OSError("driver unavailable")):
                    service._run_introspection(job_id)

                failed = service.get_job(job_id)
                self.assertEqual("FAILED", failed["status"])
                self.assertEqual("driver unavailable", failed["error"])
            finally:
                service.close()

    def test_creates_modeling_with_composite_primary_key_and_confirmed_relation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                job_id = "a" * 32
                job = {"jobId": job_id, "status": "SUCCEEDED"}
                snapshot = {
                    "profileName": "customer-primary",
                    "datasource": "mysql",
                    "tables": [
                        {
                            "id": "shop.public.region",
                            "catalog": "shop",
                            "schema": "public",
                            "name": "region",
                            "comment": "区域",
                            "primaryKey": ["tenant_id", "id"],
                            "columns": [
                                {"name": "tenant_id", "type": "BIGINT", "rawType": "bigint", "nullable": False},
                                {"name": "id", "type": "BIGINT", "rawType": "bigint", "nullable": False},
                            ],
                        },
                        {
                            "id": "shop.public.store",
                            "catalog": "shop",
                            "schema": "public",
                            "name": "store",
                            "comment": "门店",
                            "primaryKey": ["id"],
                            "columns": [
                                {"name": "id", "type": "BIGINT", "rawType": "bigint", "nullable": False},
                                {"name": "region_id", "type": "BIGINT", "rawType": "bigint", "nullable": False},
                            ],
                        },
                    ],
                    "foreignKeys": [
                        {
                            "name": "fk_store_region",
                            "fromTableId": "shop.public.store",
                            "fromColumns": ["region_id"],
                            "toTableId": "shop.public.region",
                            "toColumns": ["id"],
                        }
                    ],
                }
                (service.jobs_dir / f"{job_id}.json").write_text(json.dumps(job), encoding="utf-8")
                (service.jobs_dir / f"{job_id}.snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")

                with patch("customer.wren.adapter.admin_service.list_profiles", return_value={snapshot["profileName"]: {"datasource": "mysql"}}):
                    modeling = service.create_modeling(
                        job_id,
                        {"tableIds": [item["id"] for item in snapshot["tables"]]},
                    )

                self.assertEqual(["tenant_id", "id"], modeling["models"][0]["primaryKey"])
                self.assertFalse(modeling["setupCompleted"])
                self.assertEqual("CONFIRMED", modeling["relationships"][0]["status"])
                metadata = yaml.safe_load(
                    (service._modeling_path("customer-primary") / "models" / "Region" / "metadata.yml").read_text(encoding="utf-8")
                )
                self.assertEqual(["tenant_id", "id"], metadata["primary_key"])
            finally:
                service.close()

    def test_replaces_relationships_atomically_and_rejects_stale_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                modeling = {
                    "revision": 10,
                    "profileName": "customer-primary",
                    "datasource": "mysql",
                    "models": [
                        {
                            "name": "Order",
                            "displayName": "订单",
                            "description": "",
                            "source": {"catalog": "shop", "schema": "public", "table": "orders"},
                            "primaryKey": ["id"],
                            "columns": [
                                {"name": "id", "displayName": "id", "description": "", "type": "BIGINT", "rawType": "bigint", "notNull": True, "primaryKey": True, "visible": True},
                                {"name": "customer_id", "displayName": "customer_id", "description": "", "type": "BIGINT", "rawType": "bigint", "notNull": True, "primaryKey": False, "visible": True},
                            ],
                        },
                        {
                            "name": "Customer",
                            "displayName": "客户",
                            "description": "",
                            "source": {"catalog": "shop", "schema": "public", "table": "customers"},
                            "primaryKey": ["id"],
                            "columns": [
                                {"name": "id", "displayName": "id", "description": "", "type": "BIGINT", "rawType": "bigint", "notNull": True, "primaryKey": True, "visible": True},
                            ],
                        },
                    ],
                    "relationships": [],
                }
                service._persist_modeling(modeling)
                payload = {
                    "revision": 10,
                    "relationships": [{
                        "name": "Order_Customer",
                        "fromModel": "Order",
                        "fromColumns": ["customer_id"],
                        "toModel": "Customer",
                        "toColumns": ["id"],
                        "cardinality": "MANY_TO_ONE",
                        "status": "CONFIRMED",
                        "source": "MANUAL",
                    }],
                }

                updated = service.replace_relationships(payload)

                self.assertEqual(["Order_Customer"], [item["name"] for item in updated["relationships"]])
                self.assertTrue(updated["setupCompleted"])
                with self.assertRaisesRegex(RuntimeError, "MODELING_CHANGED"):
                    service.replace_relationships(payload)
            finally:
                service.close()

    def test_updates_column_semantics_and_removes_unreferenced_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                service._persist_modeling({
                    "revision": 1, "profileName": "customer-primary", "datasource": "mysql",
                    "models": [
                        {"name": "Order", "displayName": "Order", "description": "", "source": {"catalog": "shop", "schema": "public", "table": "orders"}, "primaryKey": ["id"],
                         "columns": [{"name": "id", "displayName": "id", "description": "", "type": "BIGINT", "rawType": "bigint", "notNull": True, "primaryKey": True, "visible": True}]},
                        {"name": "Unused", "displayName": "Unused", "description": "", "source": {"catalog": "shop", "schema": "public", "table": "unused"}, "primaryKey": [], "columns": []},
                    ], "relationships": [],
                })
                updated = service.update_model("Order", {"revision": 1, "profileName": "customer-primary",
                                                           "columns": [{"name": "id", "description": "订单主键", "visible": False}]})
                self.assertEqual("订单主键", updated["models"][0]["columns"][0]["description"])
                self.assertFalse(updated["models"][0]["columns"][0]["visible"])
                removed = service.delete_model("Unused", updated["revision"], "customer-primary")
                self.assertEqual(["Order"], [item["name"] for item in removed["models"]])
            finally:
                service.close()

    def test_rejects_removing_model_used_by_relationship(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = AdminService(root / "project", root / "instance", FakeRuntime())
            try:
                service._persist_modeling({
                    "revision": 1, "profileName": "customer-primary", "models": [
                        {"name": "Order", "source": {"catalog": "shop", "schema": "public", "table": "orders"}, "columns": [{"name": "id", "type": "BIGINT", "rawType": "bigint", "notNull": True}]}, {"name": "Customer", "source": {"catalog": "shop", "schema": "public", "table": "customers"}, "columns": [{"name": "id", "type": "BIGINT", "rawType": "bigint", "notNull": True}]}
                    ], "relationships": [{"name": "Order_Customer", "fromModel": "Order", "toModel": "Customer", "fromColumns": ["id"], "toColumns": ["id"], "cardinality": "MANY_TO_ONE", "status": "CONFIRMED", "source": "MANUAL"}]
                })
                with self.assertRaisesRegex(ValueError, "MODEL_HAS_RELATIONSHIPS"):
                    service.delete_model("Order", 1, "customer-primary")
            finally:
                service.close()

    def test_merges_reintrospection_without_losing_manual_semantics(self):
        existing = {
            "models": [{
                "name": "Order", "displayName": "业务订单", "description": "人工说明",
                "source": {"catalog": "", "schema": "public", "table": "orders"},
                "columns": [{"name": "id", "displayName": "订单编号", "description": "人工注释", "visible": False, "primaryKey": True}],
            }],
            "relationships": [],
        }
        fresh = {
            "models": [{
                "name": "Orders", "displayName": "orders", "description": "",
                "source": {"catalog": "", "schema": "public", "table": "orders"},
                "columns": [
                    {"name": "id", "displayName": "id", "description": "", "visible": True, "primaryKey": True},
                    {"name": "total", "displayName": "total", "description": "金额", "visible": True, "primaryKey": False},
                ],
            }],
            "relationships": [],
        }
        merged = AdminService._merge_modeling_semantics(existing, fresh)
        model = merged["models"][0]
        self.assertEqual("业务订单", model["displayName"])
        self.assertEqual("人工注释", model["columns"][0]["description"])
        self.assertFalse(model["columns"][0]["visible"])
        self.assertEqual("total", model["columns"][1]["name"])


if __name__ == "__main__":
    unittest.main()
