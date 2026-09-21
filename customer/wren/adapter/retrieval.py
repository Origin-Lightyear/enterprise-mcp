"""Wren 官方式 Schema/Query Memory 检索，使用员工请求级模型服务。"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import threading
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from wren.context import load_rules
from wren.memory.markdown import load_query_pairs
from wren.memory.schema_indexer import (
    SCHEMA_DESCRIBE_THRESHOLD,
    describe_schema,
    extract_schema_items,
)

try:
    from .observability import current_request_id, sanitize_message, trace_methods, trace_module_functions
except ImportError:
    from observability import current_request_id, sanitize_message, trace_methods, trace_module_functions

# 员工模型服务当前限制 Embeddings 单批最多 20 条，索引构建必须在客户端主动拆批。
EMBEDDING_BATCH_SIZE = 20
SCHEMA_CANDIDATE_SIZE = 100
SCHEMA_RESULT_SIZE = 50
QUERY_RESULT_SIZE = 5
MAX_SELECTED_MODELS = 25
MAX_UPSTREAM_ERROR_BYTES = 4096

_INDEX_LOCKS: dict[str, threading.Lock] = {}
_INDEX_LOCKS_GUARD = threading.Lock()
logger = logging.getLogger(__name__)


def build_generation_context(
    question: str,
    llm: dict[str, Any],
    manifest: dict[str, Any],
    project: Path,
    source_key: str,
    mdl_digest: str,
    memory_root: Path,
) -> dict[str, Any]:
    """按官方混合策略构造 SQL 生成上下文。"""
    full_schema = describe_schema(manifest)
    rules, _used_legacy = load_rules(project)
    embedding_model = _required_text(llm.get("embeddingModel"), "embeddingModel")

    # 小 Schema 保留完整结构；Query Memory 仍使用向量召回已确认 SQL。
    use_full_schema = len(full_schema) < SCHEMA_DESCRIBE_THRESHOLD
    if not embedding_model:
        if not use_full_schema:
            raise ValueError("embeddingModel is required when the MDL schema exceeds 30000 characters")
        return {
            "strategy": "full",
            "schema": full_schema,
            "queryExamples": [],
            "rules": rules or "",
        }

    client = EmployeeModelClient(llm)
    memory = EmployeeVectorMemory(memory_root, source_key, mdl_digest, embedding_model)
    rows = memory.search(question, manifest, project, client, llm.get("rerankModel"))
    schema = full_schema if use_full_schema else _schema_context(manifest, rows)
    examples = [
        json.loads(row["payload"])
        for row in rows
        if row.get("record_type") == "query"
    ][:QUERY_RESULT_SIZE]
    return {
        "strategy": "full" if use_full_schema else "vector",
        "schema": schema,
        "queryExamples": examples,
        "rules": rules or "",
    }


def memory_status(memory_root: Path) -> dict[str, int]:
    """汇总已成功切换的向量索引，供健康检查和运维查询。"""
    indexes = 0
    records = 0
    query_records = 0
    if memory_root.is_dir():
        for metadata_path in memory_root.glob("*/*/*/metadata.json"):
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exception:
                logger.warning(
                    "Wren 向量索引元数据读取失败: requestId=%s, type=%s, message=%s",
                    current_request_id(),
                    type(exception).__name__,
                    sanitize_message(exception),
                    exc_info=True,
                )
                continue
            if not isinstance(metadata, dict):
                continue
            indexes += 1
            records += int(metadata.get("records", 0))
            query_records += int(metadata.get("queryRecords", 0))
    return {"indexes": indexes, "records": records, "queryRecords": query_records}


@trace_methods()
class EmployeeModelClient:
    """使用单次请求中的员工凭据调用 Embeddings 与可选 rerank endpoint。"""

    def __init__(self, llm: dict[str, Any]):
        self._base_url = _required_text(llm.get("baseUrl"), "baseUrl")
        self._api_key = _required_text(llm.get("apiKey"), "apiKey")
        self._embedding_model = _required_text(llm.get("embeddingModel"), "embeddingModel")
        if not self._base_url or not self._api_key or not self._embedding_model:
            raise ValueError("llm baseUrl, apiKey and embeddingModel are required for vector retrieval")

    def embed(self, texts: list[str]) -> list[list[float]]:
        """批量生成文本向量并保持输入顺序。"""
        if not texts:
            return []
        vectors: list[list[float]] = []
        for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
            batch = texts[start : start + EMBEDDING_BATCH_SIZE]
            body = self._post("embeddings", {"model": self._embedding_model, "input": batch})
            data = body.get("data")
            if not isinstance(data, list) or len(data) != len(batch):
                raise ValueError("embedding endpoint returned an invalid vector count")
            ordered = sorted(data, key=lambda item: int(item.get("index", 0)))
            for item in ordered:
                vector = item.get("embedding") if isinstance(item, dict) else None
                if not isinstance(vector, list) or not vector:
                    raise ValueError("embedding endpoint returned an invalid vector")
                vectors.append([float(value) for value in vector])
        dimensions = {len(vector) for vector in vectors}
        if len(dimensions) != 1:
            raise ValueError("embedding endpoint returned mixed vector dimensions")
        return vectors

    def rerank(self, question: str, rows: list[dict[str, Any]], model: Any) -> list[dict[str, Any]]:
        """使用 Jina 兼容 endpoint 重排召回候选；未配置模型时保持向量顺序。"""
        rerank_model = _required_text(model, "rerankModel")
        if not rerank_model or not rows:
            return rows
        try:
            body = self._post("rerank", {
                "model": rerank_model,
                "query": question,
                "documents": [row["text"] for row in rows],
                "top_n": min(SCHEMA_RESULT_SIZE, len(rows)),
            })
            results = body.get("results")
            if not isinstance(results, list):
                raise ValueError("rerank endpoint returned an invalid result")
            ranked: list[dict[str, Any]] = []
            for result in results:
                if not isinstance(result, dict) or not isinstance(result.get("index"), int):
                    raise ValueError("rerank endpoint returned an invalid item")
                index = result["index"]
                if index < 0 or index >= len(rows):
                    raise ValueError("rerank endpoint returned an out-of-range index")
                ranked.append(rows[index])
            return ranked
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as exception:
            # Wren 官方基础 Memory 不依赖 rerank；增强服务异常时保留向量召回顺序。
            logger.warning(
                "Wren Rerank 不可用，降级为向量顺序: requestId=%s, type=%s, message=%s",
                current_request_id(),
                type(exception).__name__,
                sanitize_message(exception),
                exc_info=True,
            )
            return rows[:SCHEMA_RESULT_SIZE]

    def _post(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any]:
        base_url = self._base_url.rstrip("/")
        url = f"{base_url}/{endpoint}" if base_url.endswith("/v1") else f"{base_url}/v1/{endpoint}"
        request = Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode(),
            headers={"Authorization": "Bearer " + self._api_key, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=120) as response:
                body = json.loads(response.read())
        except HTTPError as exception:
            error_body = exception.read(MAX_UPSTREAM_ERROR_BYTES + 1)
            truncated = len(error_body) > MAX_UPSTREAM_ERROR_BYTES
            detail = error_body[:MAX_UPSTREAM_ERROR_BYTES].decode("utf-8", errors="replace").strip()
            if truncated:
                detail += "... [truncated]"
            if not detail:
                detail = str(exception.reason)
            raise ValueError(
                f"{endpoint} request failed, HTTP status {exception.code}: {sanitize_message(detail)}"
            ) from exception
        if not isinstance(body, dict):
            raise ValueError(f"{endpoint} endpoint returned an invalid response")
        return body


@trace_methods()
class EmployeeVectorMemory:
    """管理绑定 sourceKey、MDL 摘要和向量模型的 LanceDB 派生索引。"""

    def __init__(self, root: Path, source_key: str, mdl_digest: str, embedding_model: str):
        safe_source = re.sub(r"[^a-zA-Z0-9._-]", "_", source_key)
        model_hash = hashlib.sha256(embedding_model.encode()).hexdigest()[:16]
        self._source_root = root / safe_source
        self._index_path = self._source_root / mdl_digest / model_hash
        self._embedding_model = embedding_model

    def search(
        self,
        question: str,
        manifest: dict[str, Any],
        project: Path,
        client: EmployeeModelClient,
        rerank_model: Any,
    ) -> list[dict[str, Any]]:
        """确保索引与当前源一致后召回 Schema 和确认 SQL。"""
        records, fingerprint = _index_records(manifest, project)
        vector = client.embed([question])[0]
        lock = _index_lock(str(self._index_path))
        with lock:
            if not self._is_current(fingerprint, len(vector)):
                self._rebuild(records, fingerprint, client)
        import lancedb  # 延迟导入，缺少部署依赖时给出明确错误

        database = lancedb.connect(str(self._index_path / "lancedb"))
        expected_dimension = int(self._metadata()["dimension"])
        if len(vector) != expected_dimension:
            raise ValueError("embedding model dimension changed; rebuild the memory index")
        schema_table = database.open_table("schema_memory")
        schema_rows = schema_table.search(vector).metric("cosine").limit(SCHEMA_CANDIDATE_SIZE).to_list()
        schema_rows = client.rerank(question, schema_rows, rerank_model)
        query_rows: list[dict[str, Any]] = []
        if int(self._metadata().get("queryRecords", 0)) > 0:
            query_table = database.open_table("query_memory")
            query_rows = query_table.search(vector).metric("cosine").limit(QUERY_RESULT_SIZE).to_list()
        return schema_rows + query_rows

    def _is_current(self, fingerprint: str, dimension: int) -> bool:
        metadata = self._metadata()
        return metadata.get("fingerprint") == fingerprint \
            and metadata.get("embeddingModel") == self._embedding_model \
            and metadata.get("dimension") == dimension

    def _metadata(self) -> dict[str, Any]:
        path = self._index_path / "metadata.json"
        if not path.is_file():
            return {}
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, json.JSONDecodeError) as exception:
            logger.warning(
                "Wren 当前向量索引元数据无效，将重建索引: requestId=%s, type=%s, message=%s",
                current_request_id(),
                type(exception).__name__,
                sanitize_message(exception),
                exc_info=True,
            )
            return {}

    def _rebuild(self, records: list[dict[str, Any]], fingerprint: str,
                 client: EmployeeModelClient) -> None:
        try:
            import lancedb
        except ImportError as exception:
            raise RuntimeError("LanceDB is required for Wren vector memory") from exception
        if not records:
            raise ValueError("Current MDL contains no indexable schema items")
        vectors = client.embed([record["text"] for record in records])
        dimension = len(vectors[0])
        rows = [{**record, "vector": vector} for record, vector in zip(records, vectors)]
        staging = self._index_path.with_name(self._index_path.name + ".staging-" + os.urandom(4).hex())
        previous = self._index_path.with_name(self._index_path.name + ".previous")
        staging.mkdir(parents=True, exist_ok=False)
        try:
            database = lancedb.connect(str(staging / "lancedb"))
            schema_rows = [row for row in rows if row["record_type"] == "schema"]
            query_rows = [row for row in rows if row["record_type"] == "query"]
            database.create_table("schema_memory", data=schema_rows, mode="overwrite")
            if query_rows:
                database.create_table("query_memory", data=query_rows, mode="overwrite")
            (staging / "metadata.json").write_text(json.dumps({
                "fingerprint": fingerprint,
                "embeddingModel": self._embedding_model,
                "dimension": dimension,
                "records": len(records),
                "queryRecords": len(query_rows),
            }, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            self._index_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.rmtree(previous, ignore_errors=True)
            if self._index_path.exists():
                os.replace(self._index_path, previous)
            try:
                os.replace(staging, self._index_path)
            except OSError:
                if previous.exists() and not self._index_path.exists():
                    os.replace(previous, self._index_path)
                raise
            shutil.rmtree(previous, ignore_errors=True)
        finally:
            shutil.rmtree(staging, ignore_errors=True)


def _index_records(manifest: dict[str, Any], project: Path) -> tuple[list[dict[str, Any]], str]:
    records: list[dict[str, Any]] = []
    for item in extract_schema_items(manifest):
        records.append({
            "text": item["text"],
            "record_type": "schema",
            "model_name": item.get("model_name") or "",
            "item_name": item.get("item_name") or "",
            "payload": "{}",
        })
    for pair in load_query_pairs(project):
        records.append({
            "text": str(pair["nl"]),
            "record_type": "query",
            "model_name": "",
            "item_name": "",
            "payload": json.dumps({"question": pair["nl"], "sql": pair["sql"]}, ensure_ascii=False),
        })
    fingerprint_payload = [{key: record[key] for key in ("text", "record_type", "model_name", "payload")}
                           for record in records]
    fingerprint = hashlib.sha256(json.dumps(
        fingerprint_payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()).hexdigest()
    return records, fingerprint


def _schema_context(manifest: dict[str, Any], rows: list[dict[str, Any]]) -> str:
    selected: list[str] = []
    for row in rows:
        if row.get("record_type") != "schema":
            continue
        model_name = str(row.get("model_name") or "")
        if model_name and model_name not in selected:
            selected.append(model_name)
        if len(selected) >= MAX_SELECTED_MODELS:
            break
    if not selected:
        raise ValueError("vector retrieval did not select any MDL model")

    relationships = manifest.get("relationships") if isinstance(manifest.get("relationships"), list) else []
    for relationship in relationships:
        models = relationship.get("models") if isinstance(relationship, dict) else None
        if not isinstance(models, list) or not any(model in selected for model in models):
            continue
        for model in models:
            if isinstance(model, str) and model not in selected and len(selected) < MAX_SELECTED_MODELS:
                selected.append(model)

    filtered = dict(manifest)
    filtered["models"] = [
        model for model in manifest.get("models", [])
        if isinstance(model, dict) and model.get("name") in selected
    ]
    filtered["relationships"] = [
        relationship for relationship in relationships
        if isinstance(relationship, dict)
        and isinstance(relationship.get("models"), list)
        and all(model in selected for model in relationship["models"])
    ]
    return describe_schema(filtered)


def _required_text(value: Any, _field: str) -> str:
    return value.strip() if isinstance(value, str) and value.strip() else ""


def _index_lock(key: str) -> threading.Lock:
    with _INDEX_LOCKS_GUARD:
        return _INDEX_LOCKS.setdefault(key, threading.Lock())


trace_module_functions(globals(), __name__)
