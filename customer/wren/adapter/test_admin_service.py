import json
import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import yaml

from .admin_service import AdminService, _metadata_text, _next_revision


class FakeRuntime:
    def __init__(self):
        self.reloaded = None

    def close(self):
        return None

    def replace_current(self, _source_key, _path):
        return None

    def reload_from_disk(self, path):
        self.reloaded = path


class AdminServiceModelingTest(unittest.TestCase):
    def test_backup_round_trip_restores_profiles_and_current_mdl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = FakeRuntime()
            service = AdminService(root / "project", root / "instance", runtime)
            try:
                (root / "instance" / ".wren").mkdir(parents=True)
                (root / "instance" / ".wren" / "profiles.yml").write_text("sales: {}\n", encoding="utf-8")
                (root / "instance" / ".wren" / ".env").write_text("DB_PASSWORD=secret\n", encoding="utf-8")
                current = root / "project" / "projects" / "current" / "sales"
                current.mkdir(parents=True)
                (current / "wren_project.yml").write_text("name: customer_database\n", encoding="utf-8")
                backup = service.create_backup()
                (root / "instance" / ".wren" / ".env").write_text("DB_PASSWORD=changed\n", encoding="utf-8")
                result = service.restore_backup(backup)
                self.assertTrue(result["restored"])
                self.assertEqual("DB_PASSWORD=secret\n", (root / "instance" / ".wren" / ".env").read_text(encoding="utf-8"))
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
