import os
import unittest
import json
import tempfile
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from .server import (
    _derive_lineage,
    _generate_sql,
    _read_chunked_body,
    _validate_clarification,
    _validate_lineage,
    _validate_read_only_sql,
    _validate_result_lineage,
    WrenRuntime,
)


class AdapterValidationTest(unittest.TestCase):
    def _chat_response(self, content: str):
        response = BytesIO(json.dumps({"choices": [{"message": {"content": content}}]}).encode())

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return response.read()

        return Response()

    def test_requires_a_current_mdl_source_for_each_query(self):
        runtime = WrenRuntime({"sales": Path("sales-current")})

        with self.assertRaisesRegex(ValueError, "sourceKey is not present in Current MDL"):
            runtime.manifest("finance")

    def test_exposes_an_independent_manifest_and_digest_for_each_source(self):
        runtime = WrenRuntime({
            "sales": Path("sales-current"),
            "finance": Path("finance-current"),
        })

        def manifest(project):
            return {"models": [{"name": project.name}]}

        with patch("customer.wren.adapter.server.build_json", side_effect=manifest):
            snapshot = runtime.context_snapshot()

        self.assertEqual({"finance", "sales"}, set(snapshot["manifest"]["sources"]))
        self.assertNotEqual(
            snapshot["manifest"]["sources"]["sales"]["mdlDigest"],
            snapshot["manifest"]["sources"]["finance"]["mdlDigest"],
        )

    def test_expands_profile_secrets_before_creating_query_engine(self):
        runtime = WrenRuntime({"sales": Path("sales-current")})
        profile = {
            "datasource": "mysql",
            "host": "localhost",
            "password": "${WREN_TEST_PASSWORD}",
        }
        engine_instance = object()

        with patch.dict(os.environ, {"WREN_TEST_PASSWORD": "resolved-password"}), \
                patch("customer.wren.adapter.server.resolve_profile_for_project", return_value=(None, profile)), \
                patch("customer.wren.adapter.server.build_json", return_value={"models": []}), \
                patch("customer.wren.adapter.server.DataSource", return_value="MYSQL"), \
                patch("customer.wren.adapter.server.WrenEngine", return_value=engine_instance) as engine_class:
            engine = runtime.engine("sales")

        self.assertIs(engine_instance, engine)
        self.assertEqual("resolved-password", engine_class.call_args.args[2]["password"])

    def test_decodes_chunked_json_body(self):
        stream = BytesIO(b"7\r\n{\"a\":1}\r\n0\r\n\r\n")

        self.assertEqual(b'{"a":1}', _read_chunked_body(stream))

    def test_rejects_unknown_llm_field(self):
        with self.assertRaisesRegex(ValueError, "unsupported"):
            _generate_sql("查询员工", {"baseUrl": "x", "apiKey": "k", "generationModel": "m", "secret": "x"},
                          {}, Path("."), "source", "digest")

    def test_requires_request_level_llm(self):
        with self.assertRaisesRegex(ValueError, "baseUrl"):
            _generate_sql("查询员工", {}, {}, Path("."), "source", "digest")

    def test_rejects_oversized_question(self):
        with self.assertRaisesRegex(ValueError, "too long"):
            _generate_sql("x" * 20001, {"baseUrl": "x", "apiKey": "k", "generationModel": "m"},
                          {}, Path("."), "source", "digest")

    def test_generates_sql_with_role_specific_model_contract(self):
        manifest = {
            "models": [{"name": "Employee", "columns": [{"name": "mobile", "type": "VARCHAR"}]}]
        }
        response = BytesIO(json.dumps({
            "choices": [{"message": {"content": '{"sql":"SELECT mobile AS mobile FROM Employee"}'}}]
        }).encode())

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return response.read()

        with tempfile.TemporaryDirectory() as directory, \
                patch("customer.wren.adapter.server.urlopen", return_value=Response()):
            generated = _generate_sql(
                "查询手机号",
                {"baseUrl": "https://models.example/v1", "apiKey": "secret",
                 "generationModel": "chat"},
                manifest, Path(directory), "employee", "digest"
            )

        self.assertEqual("full", generated["retrievalStrategy"])
        self.assertEqual("SELECT mobile AS mobile FROM Employee", generated["sql"])

    def test_returns_only_current_mdl_fields_as_clarification_options(self):
        manifest = {
            "models": [{"name": "Order", "columns": [
                {"name": "createdAt", "type": "TIMESTAMP"},
                {"name": "paidAt", "type": "TIMESTAMP"},
            ]}]
        }

        result = _validate_clarification({
            "question": "销售日期是下单时间还是支付时间？",
            "reason": "MULTIPLE_SEMANTIC_FIELDS",
            "options": [
                {"id": "Order.createdAt", "label": "下单时间"},
                {"id": "Order.paidAt", "label": "支付时间"},
            ],
            "allowFreeText": True,
        }, manifest, "查询销售日期", "sales", "digest", "vector")

        self.assertEqual("NEEDS_CLARIFICATION", result["outcome"])
        self.assertEqual("Order.createdAt", result["clarification"]["options"][0]["id"])
        self.assertEqual(32, len(result["clarification"]["id"]))

    def test_rejects_hallucinated_clarification_option(self):
        manifest = {"models": [{"name": "Order", "columns": [{"name": "createdAt"}]}]}

        with self.assertRaisesRegex(ValueError, "not present in Current MDL"):
            _validate_clarification({
                "question": "请选择日期",
                "reason": "MULTIPLE_SEMANTIC_FIELDS",
                "options": [
                    {"id": "Order.createdAt", "label": "下单时间"},
                    {"id": "Order.deletedAt", "label": "删除时间"},
                ],
            }, manifest, "查询日期", "sales", "digest", "full")

    def test_preserves_upstream_llm_error_body(self):
        upstream_error = HTTPError(
            "https://models.example/v1/chat/completions",
            400,
            "Bad Request",
            {},
            BytesIO(b'{"error":{"message":"model not found"}}'),
        )

        with tempfile.TemporaryDirectory() as directory, \
                patch("customer.wren.adapter.server.urlopen", side_effect=upstream_error), \
                self.assertRaisesRegex(ValueError, "model not found"):
            _generate_sql(
                "查询手机号",
                {"baseUrl": "https://models.example/v1", "apiKey": "secret",
                 "generationModel": "missing-model"},
                {"models": []}, Path(directory), "employee", "digest"
            )

    def test_accepts_one_explicit_read_only_query(self):
        _validate_read_only_sql("SELECT employee.mobile AS mobile FROM employee")

    def test_accepts_count_star_without_exposing_raw_columns(self):
        _validate_read_only_sql("SELECT COUNT(*) AS total FROM employee")

    def test_rejects_multiple_statements(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            _validate_read_only_sql("SELECT 1; SELECT 2")

    def test_rejects_select_star(self):
        with self.assertRaisesRegex(ValueError, "SELECT \\*"):
            _validate_read_only_sql("SELECT * FROM employee")

    def test_rejects_nested_limit(self):
        with self.assertRaisesRegex(ValueError, "LIMIT is forbidden"):
            _validate_read_only_sql(
                "SELECT employee.mobile AS mobile FROM (SELECT mobile FROM employee LIMIT 10) AS employee"
            )

    def test_generates_sql_without_top_level_limit(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch(
                    "customer.wren.adapter.server.urlopen",
                    return_value=self._chat_response(
                        '{"outcome":"READY","sql":"SELECT employee.mobile AS mobile '
                        'FROM employee LIMIT 10"}'
                    ),
                ):
            generated = _generate_sql(
                "查询手机号，最多10条",
                {"baseUrl": "https://models.example/v1", "apiKey": "secret",
                 "generationModel": "test-model"},
                {
                    "models": [{"name": "employee", "columns": [{"name": "mobile"}]}]
                }, Path(directory), "employee", "digest"
            )
        self.assertEqual(
            generated["sql"],
            'SELECT employee.mobile AS mobile FROM employee'
        )
        self.assertEqual(generated["columnLineage"], {"mobile": ["employee.mobile"]})

    def test_rejects_write_sql(self):
        with self.assertRaisesRegex(ValueError, "read-only"):
            _validate_read_only_sql("UPDATE employee SET name = 'x'")

    def test_validates_mdl_lineage_and_output_columns(self):
        manifest = {
            "models": [
                {"name": "Employee", "columns": [{"name": "mobile"}, {"name": "salary"}]}
            ]
        }
        lineage = {"masked_mobile": ["Employee.mobile"], "total_salary": ["Employee.salary"]}
        _validate_lineage(lineage, manifest)
        _validate_result_lineage(["masked_mobile", "total_salary"], lineage)

    def test_rejects_unknown_lineage_field(self):
        manifest = {"models": [{"name": "Employee", "columns": [{"name": "mobile"}]}]}
        with self.assertRaisesRegex(ValueError, "unknown MDL field"):
            _validate_lineage({"salary": ["Employee.salary"]}, manifest)

    def test_rejects_missing_output_lineage(self):
        with self.assertRaisesRegex(ValueError, "do not match"):
            _validate_result_lineage(["mobile", "name"], {"mobile": ["Employee.mobile"]})

    def test_aligns_database_column_case_with_sqlglot_lineage(self):
        lineage = _validate_result_lineage(
            ["Mobile", "TOTAL"],
            {"mobile": ["Employee.mobile"], "total": ["Employee.salary"]},
        )

        self.assertEqual(
            {"Mobile": ["Employee.mobile"], "TOTAL": ["Employee.salary"]},
            lineage,
        )

    def test_reports_actual_and_expected_columns_when_lineage_is_ambiguous(self):
        with self.assertRaisesRegex(ValueError, "actual=.*missingLineage=.*unexpectedLineage="):
            _validate_result_lineage(["_col_0"], {"total": ["Employee.salary"]})

    def test_derives_aggregate_and_join_lineage_from_sql(self):
        manifest = {
            "models": [
                {"name": "Product", "columns": [{"name": "purchase_cost", "type": "DECIMAL"},
                                                   {"name": "store_id", "type": "INTEGER"}]},
                {"name": "Store", "columns": [{"name": "id", "type": "INTEGER"},
                                                 {"name": "name", "type": "VARCHAR"}]},
            ]
        }
        sql = (
            "SELECT s.name AS store, AVG(p.purchase_cost) AS average_cost "
            "FROM Product p JOIN Store s ON p.store_id = s.id GROUP BY s.name"
        )

        lineage = _derive_lineage(sql, manifest)

        self.assertEqual(["Store.name"], lineage["store"])
        self.assertEqual(["Product.purchase_cost"], lineage["average_cost"])


if __name__ == "__main__":
    unittest.main()
