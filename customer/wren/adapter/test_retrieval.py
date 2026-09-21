import json
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from wren.memory.markdown import write_query_markdown

from .retrieval import (
    EmployeeModelClient,
    EmployeeVectorMemory,
    _schema_context,
    build_generation_context,
)


class _Response:
    def __init__(self, body):
        self._body = BytesIO(json.dumps(body).encode())

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self._body.read()


class RetrievalTest(unittest.TestCase):
    def test_small_schema_uses_full_context_without_embedding_model(self):
        manifest = {"models": [{"name": "Order", "columns": [{"name": "id", "type": "INTEGER"}]}]}
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            context = build_generation_context(
                "订单数量", {}, manifest, project, "sales", "digest", project / "memory"
            )

        self.assertEqual("full", context["strategy"])
        self.assertIn("Model: Order", context["schema"])

    def test_relationship_expansion_adds_join_model_and_condition(self):
        manifest = {
            "models": [
                {"name": "Order", "columns": [{"name": "customer_id", "type": "INTEGER"}]},
                {"name": "Customer", "columns": [{"name": "id", "type": "INTEGER"}]},
            ],
            "relationships": [{
                "name": "order_customer",
                "models": ["Order", "Customer"],
                "joinType": "MANY_TO_ONE",
                "condition": "Order.customer_id = Customer.id",
            }],
        }

        context = _schema_context(manifest, [{
            "record_type": "schema", "model_name": "Order", "text": "Order"
        }])

        self.assertIn("Model: Customer", context)
        self.assertIn("Order.customer_id = Customer.id", context)

    def test_embedding_response_is_sorted_by_index(self):
        client = EmployeeModelClient({
            "baseUrl": "https://models.example/v1",
            "apiKey": "secret",
            "embeddingModel": "embedding",
        })
        response = _Response({"data": [
            {"index": 1, "embedding": [0.0, 1.0]},
            {"index": 0, "embedding": [1.0, 0.0]},
        ]})

        with patch("customer.wren.adapter.retrieval.urlopen", return_value=response):
            vectors = client.embed(["first", "second"])

        self.assertEqual([[1.0, 0.0], [0.0, 1.0]], vectors)

    def test_embedding_request_respects_provider_batch_limit(self):
        client = EmployeeModelClient({
            "baseUrl": "https://models.example/v1",
            "apiKey": "secret",
            "embeddingModel": "embedding",
        })
        batch_sizes = []

        def respond(request, timeout):
            self.assertEqual(120, timeout)
            payload = json.loads(request.data)
            batch_sizes.append(len(payload["input"]))
            return _Response({"data": [
                {"index": index, "embedding": [1.0, 0.0]}
                for index, _text in enumerate(payload["input"])
            ]})

        with patch("customer.wren.adapter.retrieval.urlopen", side_effect=respond):
            vectors = client.embed([f"text-{index}" for index in range(21)])

        self.assertEqual([20, 1], batch_sizes)
        self.assertEqual(21, len(vectors))

    def test_embedding_error_preserves_upstream_response_body(self):
        client = EmployeeModelClient({
            "baseUrl": "https://models.example/v1",
            "apiKey": "secret",
            "embeddingModel": "missing-embedding",
        })
        upstream_error = HTTPError(
            "https://models.example/v1/embeddings",
            400,
            "Bad Request",
            {},
            BytesIO(b'{"error":{"message":"embedding model unavailable"}}'),
        )

        with patch("customer.wren.adapter.retrieval.urlopen", side_effect=upstream_error), \
                self.assertRaisesRegex(ValueError, "embedding model unavailable"):
            client.embed(["store schema"])

    def test_lancedb_keeps_schema_and_confirmed_query_memory_separate(self):
        manifest = {
            "models": [
                {"name": "Order", "columns": [{"name": "amount", "type": "DECIMAL"}]},
                {"name": "Employee", "columns": [{"name": "name", "type": "VARCHAR"}]},
            ]
        }

        class FakeClient:
            def embed(self, texts):
                return [[1.0, 0.0] if "Order" in text or "订单" in text else [0.0, 1.0]
                        for text in texts]

            def rerank(self, _question, rows, _model):
                return rows

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            write_query_markdown(project, "订单总额", "SELECT SUM(amount) AS total FROM Order")
            memory = EmployeeVectorMemory(root / "memory", "sales", "digest", "embedding")

            rows = memory.search("订单总额", manifest, project, FakeClient(), None)

        self.assertTrue(any(row["record_type"] == "schema" for row in rows))
        query_rows = [row for row in rows if row["record_type"] == "query"]
        self.assertEqual(1, len(query_rows))
        self.assertIn("SELECT SUM", query_rows[0]["payload"])

    def test_embedding_dimension_change_rebuilds_index(self):
        manifest = {
            "models": [{"name": "Order", "columns": [{"name": "amount", "type": "DECIMAL"}]}]
        }

        class DimensionClient:
            def __init__(self, dimension):
                self.dimension = dimension

            def embed(self, texts):
                return [[1.0] + [0.0] * (self.dimension - 1) for _text in texts]

            def rerank(self, _question, rows, _model):
                return rows

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            memory = EmployeeVectorMemory(root / "memory", "sales", "digest", "embedding")

            memory.search("订单总额", manifest, project, DimensionClient(2), None)
            memory.search("订单总额", manifest, project, DimensionClient(3), None)

            metadata = json.loads((memory._index_path / "metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(3, metadata["dimension"])


if __name__ == "__main__":
    unittest.main()
