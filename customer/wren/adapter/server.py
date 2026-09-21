"""Customer-internal Wren RPC adapter.

This module deliberately does not import or expose the MCP SDK. customerapp is
the only public MCP server; this process is a loopback database engine.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import parse_qs, unquote, urlparse
from urllib.request import Request, urlopen

import sqlglot
from sqlglot import exp
from sqlglot.lineage import lineage as build_lineage

# Wren profile 模块在导入时读取 WREN_HOME，因此必须在导入 Wren 前固定客户实例目录。
_PROJECT_ROOT = Path(os.environ.get("WREN_PROJECT_ROOT", "./project-root")).resolve()
_INSTANCE_HOME = Path(os.environ.get("WREN_INSTANCE_DIR", _PROJECT_ROOT)).resolve() / ".wren"
os.environ.setdefault("WREN_HOME", str(_INSTANCE_HOME))

from wren.context import build_json
from wren.engine import WrenEngine
from wren.model.data_source import DataSource
from wren.profile import add_profile, expand_profile_secrets, list_profiles, remove_profile, resolve_profile_for_project

_LOGGER = logging.getLogger(__name__)
_MAX_UPSTREAM_ERROR_BYTES = 4096

try:
    from .retrieval import build_generation_context, memory_status
except ImportError:
    from retrieval import build_generation_context, memory_status

try:
    from .admin_service import AdminService, _read_json
except ImportError:
    from admin_service import AdminService, _read_json

try:
    from .observability import (
        current_request_id,
        request_scope,
        sanitize_message,
        trace_methods,
        trace_module_functions,
    )
except ImportError:
    from observability import current_request_id, request_scope, sanitize_message, trace_methods, trace_module_functions


MAX_RESULT_ROWS = 1000
MAX_REQUEST_BYTES = 1_048_576
MAX_QUESTION_LENGTH = 20_000
MAX_CLARIFICATION_OPTIONS = 5
MAX_CLARIFICATION_QUESTION_LENGTH = 1000
MAX_CLARIFICATION_LABEL_LENGTH = 200
ALLOWED_LLM_KEYS = {
    "baseUrl", "apiKey", "generationModel", "embeddingModel", "rerankModel", "timeoutSeconds"
}


def _read_chunked_body(stream: Any) -> bytes:
    """Decode one bounded HTTP chunked body from a binary request stream."""
    body = bytearray()
    while True:
        size_line = stream.readline(128)
        if not size_line:
            raise ValueError("chunked request body ended unexpectedly")
        try:
            chunk_size = int(size_line.split(b";", 1)[0].strip(), 16)
        except ValueError as exception:
            raise ValueError("chunked request contains an invalid size") from exception
        if chunk_size == 0:
            while True:
                trailer = stream.readline(8192)
                if trailer in {b"\r\n", b"\n", b""}:
                    return bytes(body)
        if len(body) + chunk_size > MAX_REQUEST_BYTES:
            raise ValueError("request body is missing or too large")
        chunk = stream.read(chunk_size)
        if len(chunk) != chunk_size or stream.read(2) != b"\r\n":
            raise ValueError("chunked request body is malformed")
        body.extend(chunk)


def _masked_profiles() -> list[dict[str, Any]]:
    """返回不含凭据的 profile 摘要，避免管理查询泄漏密码和 API Key。"""
    sensitive = ("password", "secret", "token", "key", "credential")
    items = []
    for name, profile in list_profiles().items():
        safe = {"name": name, "datasource": profile.get("datasource")}
        safe["configured"] = bool(profile)
        safe["secretFields"] = [str(k) for k in profile if any(part in str(k).lower() for part in sensitive)]
        items.append(safe)
    return items


@trace_methods()
class WrenRuntime:
    """按数据源持有 Current MDL 和数据库 Engine，禁止一次查询跨越多个数据源。"""

    def __init__(self, projects: dict[str, Path] | None = None):
        self._lock = threading.RLock()
        self._projects = dict(projects or {})
        self._engines: dict[str, WrenEngine] = {}
        self._manifests: dict[str, dict[str, Any]] = {}

    def health(self) -> dict[str, Any]:
        with self._lock:
            return {
                "status": "UP",
                "sources": len(self._projects),
                "models": sum(len(self.manifest(source).get("models", [])) for source in self._projects),
                "mdlDigest": self.mdl_digest(),
                "vectorMemory": memory_status(_INSTANCE_HOME / "memory"),
            }

    def mdl_digest(self, source_key: str | None = None) -> str:
        """返回单个数据源或全部活动数据源的稳定 SHA-256 摘要。"""
        if source_key is not None:
            payload_value: Any = self.manifest(source_key)
        else:
            payload_value = {
                source: self.mdl_digest(source)
                for source in sorted(self._projects)
            }
        if not payload_value:
            return ""
        payload = json.dumps(payload_value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode()).hexdigest()

    def context_snapshot(self) -> dict[str, Any]:
        """在同一把锁内返回所有活动数据源的 MDL 与摘要。"""
        with self._lock:
            sources = {
                source: {
                    "mdlDigest": self.mdl_digest(source),
                    "manifest": self.manifest(source),
                }
                for source in sorted(self._projects)
            }
            return {
                "configured": bool(sources),
                "mdlDigest": self.mdl_digest(),
                "manifest": {"sources": sources} if sources else {},
            }

    def manifest(self, source_key: str) -> dict[str, Any]:
        with self._lock:
            project = self._require_project(source_key)
            if source_key not in self._manifests:
                self._manifests[source_key] = build_json(project)
            return self._manifests[source_key]

    def project(self, source_key: str) -> Path:
        """返回指定 Current MDL 的项目目录，供规则与确认 SQL Memory 读取。"""
        with self._lock:
            return self._require_project(source_key)

    def engine(self, source_key: str) -> WrenEngine:
        with self._lock:
            project = self._require_project(source_key)
            if source_key in self._engines:
                return self._engines[source_key]
            _, active_profile = resolve_profile_for_project(project)
            profile = expand_profile_secrets(dict(active_profile or {}))
            if not profile:
                raise RuntimeError("Wren database profile is not configured")
            datasource = profile.pop("datasource", None)
            if not datasource:
                raise RuntimeError("Wren database datasource is not configured")
            manifest = self.manifest(source_key)
            encoded = base64.b64encode(json.dumps(manifest).encode()).decode()
            engine = WrenEngine(encoded, DataSource(datasource), profile)
            self._engines[source_key] = engine
            return engine

    def close(self, source_key: str | None = None) -> None:
        with self._lock:
            source_keys = [source_key] if source_key is not None else list(self._engines)
            for current_source in source_keys:
                engine = self._engines.pop(current_source, None)
                if engine is not None:
                    engine.close()

    def replace_current(self, source_key: str, project: Path) -> None:
        """只替换指定数据源的 Current MDL 内容，其他数据源查询不受影响。"""
        with self._lock:
            self.close(source_key)
            self._projects[source_key] = project
            self._manifests.pop(source_key, None)

    def commit_project(self, source_key: str, candidate: Path, target: Path, previous: Path) -> None:
        """在查询锁内替换 Current MDL 文件和 Engine 引用，失败时恢复原内容。"""
        with self._lock:
            self.close(source_key)
            if target.exists():
                os.replace(target, previous)
            try:
                os.replace(candidate, target)
                self._projects[source_key] = target
                self._manifests.pop(source_key, None)
                self.manifest(source_key)
            except Exception:
                if target.exists():
                    shutil.rmtree(target, ignore_errors=True)
                if previous.exists():
                    os.replace(previous, target)
                    self._projects[source_key] = target
                    self._manifests.pop(source_key, None)
                raise

    def query(self, source_key: str, sql: str, limit: int = MAX_RESULT_ROWS,
              expected_mdl_digest: str | None = None) -> dict[str, Any]:
        """在同一把锁内校验摘要并完成查询，防止返回过期 MDL 结果。"""
        with self._lock:
            if expected_mdl_digest and expected_mdl_digest != self.mdl_digest():
                raise ValueError("MDL digest changed during request; retry with a new plan")
            if not sql or not sql.strip():
                raise ValueError("sql is required")
            limit = max(1, min(int(limit), MAX_RESULT_ROWS))
            table = self.engine(source_key).query(sql, limit + 1)
            if expected_mdl_digest and expected_mdl_digest != self.mdl_digest():
                raise ValueError("MDL digest changed during request; discard the query result")
            if table.num_rows > limit:
                raise ValueError("query result exceeds the configured completeness limit; aggregate in SQL")
            return {
                "columns": table.column_names,
                "rows": table.to_pylist(),
                "rowCount": table.num_rows,
                "truncated": False,
            }

    def dry_plan(self, source_key: str, sql: str) -> dict[str, str]:
        if not sql or not sql.strip():
            raise ValueError("sql is required")
        return {"sql": self.engine(source_key).dry_plan(sql)}

    def dry_run(self, source_key: str, sql: str) -> dict[str, bool]:
        if not sql or not sql.strip():
            raise ValueError("sql is required")
        self.engine(source_key).dry_run(sql)
        return {"valid": True}

    def rebuild_context(self, source_key: str) -> dict[str, Any]:
        with self._lock:
            project = self._require_project(source_key)
            self._manifests[source_key] = build_json(project)
            self.close(source_key)
            return {"sourceKey": source_key, "models": len(self._manifests[source_key].get("models", []))}

    def _require_project(self, source_key: str) -> Path:
        if not isinstance(source_key, str) or not source_key.strip():
            raise ValueError("sourceKey is required")
        project = self._projects.get(source_key)
        if project is None:
            raise ValueError("sourceKey is not present in Current MDL")
        return project


def _generate_sql(question: str, llm: dict[str, Any], manifest: dict[str, Any],
                  project: Path, source_key: str, mdl_digest: str) -> dict[str, Any]:
    """使用请求级员工模型生成只读 SQL，或返回绑定 MDL 的结构化澄清请求。"""
    if not isinstance(llm, dict) or set(llm) - ALLOWED_LLM_KEYS:
        raise ValueError("llm contains unsupported fields")
    if not isinstance(question, str) or len(question) > MAX_QUESTION_LENGTH:
        raise ValueError("question is missing or too long")
    base_url = str(llm.get("baseUrl", "")).rstrip("/")
    api_key = str(llm.get("apiKey", ""))
    model = str(llm.get("generationModel", ""))
    if not base_url or not api_key or not model:
        raise ValueError("llm baseUrl, apiKey and generationModel are required for question queries")
    context = build_generation_context(
        question, llm, manifest, project, source_key, mdl_digest, _INSTANCE_HOME / "memory"
    )
    examples = json.dumps(context["queryExamples"], ensure_ascii=False)
    payload = {
        "model": model,
        "temperature": 0,
        "messages": [
            {
                "role": "system",
                "content": (
                    "Return only one JSON object. When the question has one clear interpretation, return "
                    '{"outcome":"READY","sql":"..."}. When two or more materially different semantic '
                    "fields or business meanings are plausible and choosing one would change the answer, return "
                    '{"outcome":"NEEDS_CLARIFICATION","clarification":{"question":"...",'
                    '"reason":"MULTIPLE_SEMANTIC_FIELDS","options":[{"id":"Model.column",'
                    '"label":"..."}],"allowFreeText":true}}. '
                    "Clarification options must contain 2 to 5 exact Model.column identifiers from the Wren schema. "
                    "Do not ask when a column can be qualified mechanically without changing business meaning. "
                    "sql must contain exactly one read-only query using MDL model names; SELECT * is forbidden. "
                    "Every final projection must have an explicit stable alias. Do not output Markdown. "
                    "Use only the retrieved Wren schema and obey all business rules.\n"
                    "Wren schema:\n" + context["schema"]
                    + "\nBusiness rules:\n" + context["rules"]
                    + "\nConfirmed NL-SQL examples:\n" + examples
                ),
            },
            {"role": "user", "content": question},
        ],
    }
    chat_url = base_url + "/chat/completions" if base_url.endswith("/v1") else base_url + "/v1/chat/completions"
    request = Request(
        chat_url,
        data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=60) as response:
            body = json.loads(response.read())
    except HTTPError as exception:
        error_body = exception.read(_MAX_UPSTREAM_ERROR_BYTES + 1)
        truncated = len(error_body) > _MAX_UPSTREAM_ERROR_BYTES
        detail = error_body[:_MAX_UPSTREAM_ERROR_BYTES].decode("utf-8", errors="replace").strip()
        if truncated:
            detail += "... [truncated]"
        if not detail:
            detail = str(exception.reason)
        raise ValueError(
            f"LLM request failed, HTTP status {exception.code}: {sanitize_message(detail)}"
        ) from exception
    content = body["choices"][0]["message"]["content"].strip()
    start = content.find("{")
    end = content.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("LLM did not return a structured SQL result")
    generated = json.loads(content[start : end + 1])
    if generated.get("outcome") == "NEEDS_CLARIFICATION":
        return _validate_clarification(generated.get("clarification"), manifest, question,
                                       source_key, mdl_digest, context["strategy"])
    sql = generated.get("sql")
    if not isinstance(sql, str):
        raise ValueError("LLM SQL result is missing sql")
    _validate_read_only_sql(sql)
    lineage = _derive_lineage(sql, manifest)
    return {
        "outcome": "READY",
        "sql": sql.strip(),
        "columnLineage": lineage,
        "retrievalStrategy": context["strategy"],
    }


def _validate_clarification(clarification: Any, manifest: dict[str, Any], question: str,
                            source_key: str, mdl_digest: str, retrieval_strategy: str) -> dict[str, Any]:
    """校验模型只能把 Current MDL 中的真实字段作为澄清候选。"""
    if not isinstance(clarification, dict):
        raise ValueError("LLM clarification result is invalid")
    prompt = clarification.get("question")
    reason = clarification.get("reason")
    raw_options = clarification.get("options")
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_CLARIFICATION_QUESTION_LENGTH:
        raise ValueError("LLM clarification question is invalid")
    if reason != "MULTIPLE_SEMANTIC_FIELDS":
        raise ValueError("LLM clarification reason is unsupported")
    if not isinstance(raw_options, list) or not 2 <= len(raw_options) <= MAX_CLARIFICATION_OPTIONS:
        raise ValueError("LLM clarification options are invalid")
    allowed_fields = {
        f"{model.get('name')}.{column.get('name')}"
        for model in manifest.get("models", [])
        for column in model.get("columns", [])
        if model.get("name") and column.get("name")
    }
    options: list[dict[str, str]] = []
    option_ids: set[str] = set()
    for raw_option in raw_options:
        if not isinstance(raw_option, dict):
            raise ValueError("LLM clarification option is invalid")
        option_id = raw_option.get("id")
        label = raw_option.get("label")
        if option_id not in allowed_fields or option_id in option_ids:
            raise ValueError("LLM clarification option is not present in Current MDL")
        if not isinstance(label, str) or not label.strip() or len(label) > MAX_CLARIFICATION_LABEL_LENGTH:
            raise ValueError("LLM clarification option label is invalid")
        option_ids.add(option_id)
        options.append({"id": option_id, "label": label.strip()})
    clarification_id = hashlib.sha256(json.dumps({
        "sourceKey": source_key,
        "mdlDigest": mdl_digest,
        "question": question,
        "options": sorted(option_ids),
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:32]
    return {
        "outcome": "NEEDS_CLARIFICATION",
        "sourceKey": source_key,
        "mdlDigest": mdl_digest,
        "retrievalStrategy": retrieval_strategy,
        "clarification": {
            "id": clarification_id,
            "question": prompt.strip(),
            "reason": reason,
            "options": options,
            "allowFreeText": bool(clarification.get("allowFreeText", False)),
        },
    }


def _validate_read_only_sql(sql: str) -> None:
    """使用 SQL AST 拒绝多语句、写操作和无法约束输出列的星号投影。"""
    statements = sqlglot.parse(sql)
    if len(statements) != 1 or not isinstance(statements[0], exp.Query):
        raise ValueError("exactly one read-only query is required")
    statement = statements[0]
    forbidden = (exp.Insert, exp.Update, exp.Delete, exp.Create, exp.Drop, exp.Alter, exp.Command, exp.Merge)
    if any(statement.find(node_type) is not None for node_type in forbidden):
        raise ValueError("write or administrative SQL is forbidden")
    for star in statement.find_all(exp.Star):
        if not isinstance(star.parent, exp.Count):
            raise ValueError("SELECT * is forbidden; output columns must be explicit")


def _derive_lineage(sql: str, manifest: dict[str, Any]) -> dict[str, list[str]]:
    """使用 sqlglot 和 MDL Schema 自动推导每个最终输出列的规范源字段。"""
    schema: dict[str, dict[str, str]] = {}
    canonical_models: dict[str, tuple[str, dict[str, str]]] = {}
    for model in manifest.get("models", []):
        model_name = model.get("name")
        if not isinstance(model_name, str):
            continue
        columns: dict[str, str] = {}
        canonical_columns: dict[str, str] = {}
        for column in model.get("columns", []):
            column_name = column.get("name")
            if isinstance(column_name, str):
                columns[column_name] = str(column.get("type") or "UNKNOWN")
                canonical_columns[column_name.lower()] = column_name
        schema[model_name] = columns
        canonical_models[model_name.lower()] = (model_name, canonical_columns)
    try:
        graphs = build_lineage(None, sql, schema=schema)
    except Exception as exc:
        raise ValueError("SQL column lineage cannot be resolved") from exc
    result: dict[str, list[str]] = {}
    for output_column, root in graphs.items():
        sources: set[str] = set()
        for node in root.walk():
            if node.downstream or not isinstance(node.expression, exp.Table) or "." not in node.name:
                continue
            model = canonical_models.get(node.expression.name.lower())
            if model is None:
                raise ValueError("SQL column lineage references an unknown MDL model")
            column_name = node.name.rsplit(".", 1)[-1]
            canonical_column = model[1].get(column_name.lower())
            if canonical_column is None:
                raise ValueError("SQL column lineage references an unknown MDL field")
            sources.add(f"{model[0]}.{canonical_column}")
        result[output_column] = sorted(sources)
    _validate_lineage(result, manifest)
    return result


def _validate_lineage(lineage: dict[str, Any], manifest: dict[str, Any]) -> None:
    """校验模型声明的列来源只能引用当前 MDL 中存在的规范字段。"""
    known_fields: set[str] = set()
    for model in manifest.get("models", []):
        model_name = model.get("name")
        if not isinstance(model_name, str):
            continue
        for column in model.get("columns", []):
            column_name = column.get("name")
            if isinstance(column_name, str):
                known_fields.add(f"{model_name}.{column_name}")
    for output_column, sources in lineage.items():
        if not isinstance(output_column, str) or not output_column or not isinstance(sources, list):
            raise ValueError("columnLineage entries must map output names to source field arrays")
        if any(not isinstance(source, str) or source not in known_fields for source in sources):
            raise ValueError("columnLineage references an unknown MDL field")


def _validate_result_lineage(columns: list[str], lineage: dict[str, Any]) -> None:
    """要求数据库实际输出列与模型声明的来源映射完全一致。"""
    if set(columns) != set(lineage):
        raise ValueError("query output columns do not match columnLineage")


@trace_methods(exclude={"handle_one_request", "log_message"})
class Handler(BaseHTTPRequestHandler):
    runtime: WrenRuntime
    admin: AdminService
    service_token: str = ""

    def handle_one_request(self) -> None:
        """为一个 HTTP 请求绑定稳定 requestId，供所有下游方法和响应复用。"""
        with request_scope(uuid.uuid4().hex):
            super().handle_one_request()

    def do_GET(self) -> None:  # noqa: N802
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            if path == "/internal/health":
                self._respond(200, "OK", self.runtime.health())
                return
            if path == "/internal/context":
                self._respond(200, "OK", self.runtime.context_snapshot())
                return
            if path.startswith("/internal/admin/"):
                self._require_service_token()
                if path == "/internal/admin/datasources":
                    self._respond(200, "OK", self.admin.list_datasources())
                    return
                if path == "/internal/admin/modeling":
                    profile_name = parse_qs(parsed.query).get("profileName", [None])[0]
                    self._respond(200, "OK", self.admin.get_modeling(profile_name))
                    return
                job_match = re.fullmatch(r"/internal/admin/introspection-jobs/([a-f0-9]{32})(/snapshot)?", path)
                if job_match:
                    job_id = job_match.group(1)
                    data = self.admin.get_snapshot(job_id) if job_match.group(2) else self.admin.get_job(job_id)
                    self._respond(200, "OK", data)
                    return
            self.send_error(404)
        except Exception as exception:
            self._respond_exception(exception)

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch_admin_mutation("PUT")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch_admin_mutation("DELETE")

    def _dispatch_admin_mutation(self, method: str) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        if not path.startswith("/internal/admin/"):
            self.send_error(404)
            return
        try:
            self._require_service_token()
            payload = self._read_payload(required=method != "DELETE")
            datasource_match = re.fullmatch(r"/internal/admin/datasources/([^/]+)", path)
            relationship_match = re.fullmatch(r"/internal/admin/modeling/relationships/([^/]+)", path)
            model_match = re.fullmatch(r"/internal/admin/modeling/models/([^/]+)", path)
            job_match = re.fullmatch(r"/internal/admin/introspection-jobs/([a-f0-9]{32})", path)
            candidate_match = re.fullmatch(r"/internal/admin/mdl-candidates/([a-f0-9]{32})", path)
            if path == "/internal/admin/modeling/relationships" and method == "PUT":
                self._respond(200, "OK", self.admin.replace_relationships(payload))
                return
            if candidate_match and method == "DELETE":
                self._respond(200, "OK", self.admin.discard_candidate(candidate_match.group(1)))
                return
            if datasource_match:
                name = unquote(datasource_match.group(1))
                data = (
                    self.admin.save_datasource(payload, update_name=name)
                    if method == "PUT"
                    else self.admin.delete_datasource(name)
                )
                self._respond(200, "OK", data)
                return
            if relationship_match:
                name = unquote(relationship_match.group(1))
                if method == "PUT":
                    data = self.admin.save_relationship(name, payload)
                else:
                    revision = int(parse_qs(parsed.query).get("revision", ["-1"])[0])
                    profile_name = parse_qs(parsed.query).get("profileName", [None])[0]
                    data = self.admin.delete_relationship(name, revision, profile_name)
                self._respond(200, "OK", data)
                return
            if model_match and method == "PUT":
                self._respond(200, "OK", self.admin.update_model(unquote(model_match.group(1)), payload))
                return
            if job_match and method == "DELETE":
                self._respond(200, "OK", self.admin.cancel_job(job_match.group(1)))
                return
            self.send_error(404)
        except Exception as exc:
            self._respond_exception(exc)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/internal/admin/"):
            self._dispatch_admin_post(path)
            return
        if path not in {"/internal/query", "/internal/dry-plan", "/internal/dry-run", "/internal/context/rebuild"}:
            self.send_error(404)
            return
        try:
            payload = self._read_payload()
            source_key = payload.get("sourceKey")
            if path == "/internal/context/rebuild":
                self._respond(200, "OK", self.runtime.rebuild_context(source_key))
                return
            if path == "/internal/dry-plan":
                self._respond(200, "OK", self.runtime.dry_plan(source_key, payload.get("sql")))
                return
            if path == "/internal/dry-run":
                self._respond(200, "OK", self.runtime.dry_run(source_key, payload.get("sql")))
                return
            expected_digest = payload.get("expectedMdlDigest")
            sql = payload.get("sql")
            manifest = self.runtime.manifest(source_key)
            if expected_digest and expected_digest != self.runtime.mdl_digest():
                raise ValueError("MDL digest changed during request; retry with a new plan")
            if not sql and payload.get("question"):
                generated = _generate_sql(
                    payload["question"], payload.get("llm", {}), manifest,
                    self.runtime.project(source_key), source_key, self.runtime.mdl_digest(source_key)
                )
                if generated["outcome"] == "NEEDS_CLARIFICATION":
                    generated["mdlDigest"] = self.runtime.mdl_digest()
                    self._respond(200, "NEEDS_CLARIFICATION", generated)
                    return
                sql = generated["sql"]
                lineage = generated["columnLineage"]
                retrieval_strategy = generated["retrievalStrategy"]
            else:
                _validate_read_only_sql(sql)
                lineage = _derive_lineage(sql, manifest)
            result = self.runtime.query(
                source_key, sql, payload.get("limit", MAX_RESULT_ROWS), expected_digest
            )
            _validate_result_lineage(result["columns"], lineage)
            result["columnLineage"] = {
                output: [f"{source_key}:{field}" for field in fields]
                for output, fields in lineage.items()
            }
            result["sourceKey"] = source_key
            result["mdlDigest"] = self.runtime.mdl_digest()
            result["retrievalStrategy"] = retrieval_strategy if not payload.get("sql") else "provided-sql"
            if payload.get("includeSql", False):
                result["sql"] = sql
            self._respond(200, "OK", result)
        except Exception as exc:  # adapter boundary returns structured failure
            self._respond_exception(exc)

    def _dispatch_admin_post(self, path: str) -> None:
        try:
            self._require_service_token()
            payload = self._read_payload(required=False)
            if path == "/internal/admin/datasources":
                self._respond(201, "OK", self.admin.save_datasource(payload))
                return
            test_match = re.fullmatch(r"/internal/admin/datasources/([^/]+)/test", path)
            if test_match:
                self._respond(200, "OK", self.admin.test_datasource(unquote(test_match.group(1))))
                return
            if path == "/internal/admin/introspection-jobs":
                self._respond(202, "OK", self.admin.create_introspection_job(payload))
                return
            modeling_match = re.fullmatch(r"/internal/admin/modeling/from-snapshot/([a-f0-9]{32})", path)
            if modeling_match:
                self._respond(201, "OK", self.admin.create_modeling(modeling_match.group(1), payload))
                return
            if path == "/internal/admin/modeling/validation":
                self._respond(200, "OK", self.admin.validate_modeling(payload.get("profileName")))
                return
            if path == "/internal/admin/mdl-candidates":
                self._respond(201, "OK", self.admin.prepare_mdl(payload.get("profileName")))
                return
            commit_match = re.fullmatch(r"/internal/admin/mdl-candidates/([a-f0-9]{32})/commit", path)
            if commit_match:
                self._respond(200, "OK", self.admin.commit_mdl(
                    commit_match.group(1), payload.get("expectedMdlDigest")
                ))
                return
            self.send_error(404)
        except Exception as exc:
            self._respond_exception(exc)

    def _read_payload(self, *, required: bool = True) -> dict[str, Any]:
        length = self.headers.get("Content-Length")
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if length is not None:
            size = int(length)
            if size <= 0 or size > MAX_REQUEST_BYTES:
                raise ValueError("request body is missing or too large")
            raw = self.rfile.read(size)
        elif transfer_encoding == "chunked":
            raw = _read_chunked_body(self.rfile)
        elif not required:
            return {}
        else:
            raise ValueError("request body is missing or too large")
        if not raw:
            raise ValueError("request body is missing or too large")
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("request body must be a JSON object")
        return payload

    def _require_service_token(self) -> None:
        if not self.service_token:
            raise PermissionError("ADAPTER_SERVICE_TOKEN_NOT_CONFIGURED")
        supplied = self.headers.get("X-Wren-Service-Token", "")
        if not secrets.compare_digest(supplied, self.service_token):
            raise PermissionError("ADAPTER_AUTHENTICATION_FAILED")

    def _respond_exception(self, exception: Exception) -> None:
        _LOGGER.exception(
            "Wren Adapter request failed: method=%s, path=%s, type=%s",
            self.command,
            self.path,
            type(exception).__name__,
        )
        code = sanitize_message(exception)
        status = 400
        if isinstance(exception, PermissionError):
            status = 401
        elif isinstance(exception, KeyError):
            status = 404
            code = exception.args[0]
        elif code in {"PROFILE_IN_USE", "MODELING_CHANGED", "CURRENT_MDL_CHANGED", "INTROSPECTION_IN_PROGRESS"}:
            status = 409
        self._respond(
            status,
            code if code.isupper() else "INVALID_REQUEST",
            {
                "error": sanitize_message(exception),
                "errorType": type(exception).__name__,
                "path": urlparse(self.path).path,
            },
        )

    def _manage_datasource(self, payload: dict[str, Any]) -> None:
        action = payload.get("action")
        name = payload.get("name")
        if action == "delete" and isinstance(name, str):
            self.runtime.close()
            self._respond(200, "OK", {"deleted": remove_profile(name)})
            return
        if action in {"create", "update"} and isinstance(name, str) and isinstance(payload.get("profile"), dict):
            profile = dict(payload["profile"])
            datasource = profile.get("datasource")
            if not isinstance(datasource, str):
                raise ValueError("profile.datasource is required")
            DataSource(datasource)
            add_profile(name, profile, activate=bool(payload.get("activate", False)))
            self.runtime.close()
            self._respond(200, "OK", {"name": name})
            return
        raise ValueError("invalid datasource management request")

    def _respond(self, status: int, code: str, data: dict[str, Any]) -> None:
        self._write(
            status,
            {
                "ok": status < 400,
                "code": code,
                "message": code,
                "data": data,
                "requestId": current_request_id(),
            },
        )

    def _write(self, status: int, body: dict[str, Any]) -> None:
        encoded = json.dumps(body, ensure_ascii=False, default=str).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("X-Wren-Request-Id", current_request_id())
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_: Any) -> None:
        return


def main() -> None:
    log_level = os.environ.get("WREN_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    project_root = Path(os.environ.get("WREN_PROJECT_ROOT", "./project-root")).resolve()
    instance_home = Path(os.environ.get("WREN_INSTANCE_DIR", project_root)).resolve()
    os.environ.setdefault("WREN_HOME", str(instance_home / ".wren"))
    host = os.environ.get("WREN_ADAPTER_HOST", "127.0.0.1")
    port = int(os.environ.get("WREN_ADAPTER_PORT", "8091"))
    current_dir = project_root / "projects" / "current"
    projects = {
        project.name: project
        for project in current_dir.iterdir()
        if project.is_dir() and (project / "wren_project.yml").is_file()
    } if current_dir.is_dir() else {}
    runtime = WrenRuntime(projects)
    admin = AdminService(project_root, instance_home, runtime)
    Handler.runtime = runtime
    Handler.admin = admin
    Handler.service_token = os.environ.get("WREN_ADAPTER_SERVICE_TOKEN", "")
    server = ThreadingHTTPServer((host, port), Handler)
    _LOGGER.info(
        "Wren Adapter 已启动: baseUrl=http://%s:%s, healthPath=/internal/health",
        host,
        port,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        _LOGGER.info("正在停止 Wren Adapter")
    finally:
        admin.close()
        runtime.close()
        server.server_close()

trace_module_functions(globals(), __name__, exclude={"main"})


if __name__ == "__main__":
    main()
