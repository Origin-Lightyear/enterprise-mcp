"""管理客户数据库 Profile、Schema Snapshot、建模内容与 Current MDL。"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import os
import re
import shutil
import tempfile
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import yaml
from dotenv import dotenv_values

from wren.connector.factory import get_connector
from wren.context import build_json, load_models, load_relationships, save_project_config
from wren.model.data_source import DataSource
from wren.profile import (
    add_profile,
    expand_profile_secrets,
    get_active_name,
    list_profiles,
    remove_profile,
    switch_profile,
)
from wren.type_mapping import parse_type

try:
    from .observability import current_request_id, sanitize_message, trace_methods, trace_module_functions
except ImportError:
    from observability import current_request_id, sanitize_message, trace_methods, trace_module_functions


SUPPORTED_DATASOURCES = {"mysql", "postgres", "mssql"}
PROFILE_NAME_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,63}$")
SECRET_NAME_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{2,127}$")
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_$-]{0,127}$")
LOGGER = logging.getLogger(__name__)
BACKUP_FORMAT_VERSION = 1
MAX_BACKUP_BYTES = 50 * 1024 * 1024
MAX_BACKUP_ENTRIES = 20_000
MAX_BACKUP_UNCOMPRESSED_BYTES = 500 * 1024 * 1024


def _default_mssql_driver() -> str:
    """选择当前操作系统实际已安装的 SQL Server ODBC Driver。"""
    try:
        import pyodbc
    except ImportError as exception:
        raise RuntimeError("MSSQL 需要 pyodbc，请先运行 Wren 启动脚本补齐依赖") from exception
    installed = set(pyodbc.drivers())
    for driver in ("ODBC Driver 18 for SQL Server", "ODBC Driver 17 for SQL Server"):
        if driver in installed:
            return driver
    raise RuntimeError(
        "未找到 Microsoft ODBC Driver 17/18 for SQL Server，"
        f"当前驱动: {', '.join(sorted(installed)) or '无'}"
    )


def _atomic_write(path: Path, content: str) -> None:
    """在同目录完成临时写入和原子替换，避免崩溃留下半文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def _read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, value: Any) -> None:
    _atomic_write(path, json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def _safe_identifier(value: str, field: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER_PATTERN.fullmatch(value):
        raise ValueError(f"{field} contains unsupported characters")
    return value


def _sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _metadata_text(value: Any) -> str:
    """将数据库元数据统一转换为 UTF-8 文本。"""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _next_revision(current: int = 0) -> int:
    """生成 JavaScript 可精确表示且在当前值之后的建模 revision。"""
    return max(int(time.time() * 1000), int(current) + 1)


def _model_name(table_name: str, occupied: set[str]) -> str:
    parts = re.findall(r"[A-Za-z0-9]+", table_name)
    base = "".join(part[:1].upper() + part[1:] for part in parts) or "Model"
    if base[0].isdigit():
        base = "Model" + base
    candidate = base
    if candidate in occupied:
        suffix = hashlib.sha256(table_name.encode()).hexdigest()[:8]
        candidate = f"{base}_{suffix}"
    occupied.add(candidate)
    return candidate


@trace_methods()
class AdminService:
    """维护一个客户实例的管理态，并把查询态切换交给 WrenRuntime。"""

    def __init__(self, project_root: Path, instance_home: Path, runtime: Any):
        self.project_root = project_root
        self.instance_home = instance_home
        self.runtime = runtime
        self.projects_dir = project_root / "projects"
        self.modeling_dir = self.projects_dir / "modeling"
        self.current_dir = self.projects_dir / "current"
        self.candidates_dir = self.projects_dir / "candidates"
        self.jobs_dir = instance_home / "jobs"
        self.audit_file = instance_home / "audit" / "wren-admin-audit.jsonl"
        self.env_file = instance_home / ".wren" / ".env"
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="wren-introspection")
        self._active_job_id: str | None = None
        self._ensure_layout()

    def _ensure_layout(self) -> None:
        self.modeling_dir.mkdir(parents=True, exist_ok=True)
        self.current_dir.mkdir(parents=True, exist_ok=True)
        self.candidates_dir.mkdir(parents=True, exist_ok=True)
        for candidate in self.candidates_dir.iterdir():
            if candidate.is_dir():
                shutil.rmtree(candidate, ignore_errors=True)
        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        self.audit_file.parent.mkdir(parents=True, exist_ok=True)

    def _modeling_path(self, profile_name: str) -> Path:
        """返回经过 Profile 名称约束的数据源独立建模目录。"""
        if not PROFILE_NAME_PATTERN.fullmatch(profile_name):
            raise ValueError("profile name must match [A-Za-z][A-Za-z0-9_-]{1,63}")
        return self.modeling_dir / profile_name

    def _initialize_modeling_path(self, profile_name: str, datasource: str = "mysql") -> Path:
        """创建单个数据源的建模骨架，避免不同 Profile 共用模型文件。"""
        modeling_path = self._modeling_path(profile_name)
        modeling_path.mkdir(parents=True, exist_ok=True)
        if not (modeling_path / "wren_project.yml").exists():
            save_project_config(
                modeling_path,
                {"schema_version": 5, "name": "customer_database", "data_source": datasource, "profile": profile_name},
            )
        (modeling_path / "models").mkdir(exist_ok=True)
        relationships = modeling_path / "relationships.yml"
        if not relationships.exists():
            _atomic_write(relationships, "relationships: []\n")
        return modeling_path

    def _modeled_profiles(self) -> set[str]:
        """列出已有建模内容的 Profile，供管理列表和删除保护使用。"""
        profiles: set[str] = set()
        for directory in self.modeling_dir.iterdir():
            if not directory.is_dir():
                continue
            model = _read_json(directory / "model.json", {})
            profile_name = model.get("profileName")
            if isinstance(profile_name, str):
                profiles.add(profile_name)
        return profiles

    def _current_profiles(self) -> set[str]:
        """列出组成 Current MDL 的数据源 Profile。"""
        return {
            path.name
            for path in self.current_dir.iterdir()
            if path.is_dir() and (path / "wren_project.yml").is_file()
        }

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

    def create_backup(self) -> bytes:
        """打包可恢复的数据源凭据、建模草稿和 Current MDL。"""
        manifest = {"format": "enterprise-mcp-wren-backup", "version": BACKUP_FORMAT_VERSION,
                    "includes": [".wren/profiles.yml", ".wren/.env", "projects/modeling", "projects/current"]}
        output = tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            roots = [(self.instance_home / ".wren" / "profiles.yml", ".wren/profiles.yml"),
                     (self.env_file, ".wren/.env")]
            for root, prefix in roots:
                if root.is_file():
                    if root.is_symlink():
                        raise ValueError("backup contains a symbolic link")
                    archive.write(root, prefix)
            for directory, prefix in ((self.modeling_dir, "projects/modeling"), (self.current_dir, "projects/current")):
                if directory.is_dir():
                    for path in sorted(directory.rglob("*")):
                        if path.is_file():
                            if path.is_symlink():
                                raise ValueError("backup contains a symbolic link")
                            archive.write(path, str(Path(prefix) / path.relative_to(directory)))
        output.seek(0)
        try:
            data = output.read()
        finally:
            output.close()
        if len(data) > MAX_BACKUP_BYTES:
            raise ValueError("backup is too large")
        self._audit("BACKUP_CREATED", "wren", "SUCCEEDED")
        return data

    def restore_backup(self, data: bytes) -> dict[str, Any]:
        """校验并原子恢复备份，失败时保留原有运行数据。"""
        if not data or len(data) > MAX_BACKUP_BYTES:
            raise ValueError("backup is missing or too large")
        runtime_lock = getattr(self.runtime, "_lock", threading.RLock())
        with self._lock, runtime_lock:
            old_secrets = dotenv_values(self.env_file) if self.env_file.is_file() else {}
            staging = Path(tempfile.mkdtemp(prefix="wren-restore-", dir=self.instance_home))
            moved: list[tuple[Path, Path]] = []
            installed: list[Path] = []
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    entries = archive.infolist()
                    if len(entries) > MAX_BACKUP_ENTRIES:
                        raise ValueError("backup contains too many files")
                    total = 0
                    names: set[str] = set()
                    for info in entries:
                        name = info.filename.replace("\\", "/")
                        parts = name.split("/")
                        if name in names or name.startswith("/") or "\\" in info.filename or ":" in name or any(part in {"", ".", ".."} for part in parts):
                            raise ValueError("backup contains an unsafe path")
                        names.add(name)
                        if info.is_dir():
                            raise ValueError("backup contains an unsupported directory entry")
                        if info.external_attr >> 16 & 0o170000 == 0o120000:
                            raise ValueError("backup contains a symbolic link")
                        if name == "manifest.json":
                            continue
                        if name not in {".wren/profiles.yml", ".wren/.env"} and not name.startswith(("projects/modeling/", "projects/current/")):
                            raise ValueError("backup contains an unsupported file")
                        total += info.file_size
                        if total > MAX_BACKUP_UNCOMPRESSED_BYTES:
                            raise ValueError("backup expands to too many bytes")
                    try:
                        manifest = json.loads(archive.read("manifest.json"))
                    except Exception as exception:
                        raise ValueError("backup manifest is invalid") from exception
                    if manifest.get("format") != "enterprise-mcp-wren-backup" or manifest.get("version") != BACKUP_FORMAT_VERSION:
                        raise ValueError("backup format is unsupported")
                    if "manifest.json" not in names:
                        raise ValueError("backup manifest is missing")
                    for info in entries:
                        if info.filename == "manifest.json":
                            continue
                        target = staging / info.filename
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with archive.open(info) as source, target.open("wb") as output:
                            copied = 0
                            while chunk := source.read(1024 * 1024):
                                copied += len(chunk)
                                if copied > info.file_size or copied > MAX_BACKUP_UNCOMPRESSED_BYTES:
                                    raise ValueError("backup entry exceeds declared size")
                                output.write(chunk)
                    for path in (staging / "projects" / "current").glob("*/wren_project.yml"):
                        build_json(path.parent)
                (staging / "projects" / "modeling").mkdir(parents=True, exist_ok=True)
                (staging / "projects" / "current").mkdir(parents=True, exist_ok=True)
                replacements = [(staging / ".wren" / "profiles.yml", self.instance_home / ".wren" / "profiles.yml"),
                                (staging / ".wren" / ".env", self.env_file),
                                (staging / "projects" / "modeling", self.modeling_dir),
                                (staging / "projects" / "current", self.current_dir)]
                for source, target in replacements:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    backup = target.with_name(target.name + ".restore-" + uuid.uuid4().hex)
                    if target.exists():
                        os.replace(target, backup)
                        moved.append((backup, target))
                    if source.exists():
                        os.replace(source, target)
                        installed.append(target)
                self.runtime.reload_from_disk(self.current_dir)
                restored_secrets = dotenv_values(self.env_file) if self.env_file.is_file() else {}
                for name in old_secrets.keys() | restored_secrets.keys():
                    previous = old_secrets.get(name)
                    if name not in os.environ or os.environ[name] == previous:
                        replacement = restored_secrets.get(name)
                        if replacement is None:
                            os.environ.pop(name, None)
                        else:
                            os.environ[name] = replacement
                self._audit("BACKUP_RESTORED", "wren", "SUCCEEDED")
                for backup, _ in moved:
                    shutil.rmtree(backup) if backup.is_dir() else backup.unlink()
                return {"restored": True, "requiresCatalogSync": True}
            except Exception:
                for target in reversed(installed):
                    shutil.rmtree(target) if target.is_dir() else target.unlink()
                for backup, target in reversed(moved):
                    os.replace(backup, target)
                self._audit("BACKUP_RESTORED", "wren", "FAILED")
                raise
            finally:
                shutil.rmtree(staging, ignore_errors=True)

    def list_datasources(self) -> dict[str, Any]:
        active = get_active_name()
        modeled_profiles = self._modeled_profiles()
        current_profiles = self._current_profiles()
        items = []
        for name, profile in sorted(list_profiles().items()):
            items.append(
                {
                    "name": name,
                    "datasource": profile.get("datasource"),
                    "host": profile.get("host"),
                    "port": profile.get("port"),
                    "database": profile.get("database"),
                    "user": profile.get("user"),
                    "active": name == active,
                    "modelingInUse": name in modeled_profiles,
                    "currentInUse": name in current_profiles,
                    "configured": True,
                }
            )
        return {"items": items}

    def save_datasource(self, payload: dict[str, Any], *, update_name: str | None = None) -> dict[str, Any]:
        name = update_name or payload.get("name")
        if not isinstance(name, str) or not PROFILE_NAME_PATTERN.fullmatch(name):
            raise ValueError("profile name must match [A-Za-z][A-Za-z0-9_-]{1,63}")
        datasource = str(payload.get("datasource", ""))
        if datasource not in SUPPORTED_DATASOURCES:
            raise ValueError("datasource must be mysql, postgres or mssql")
        host = str(payload.get("host", "")).strip()
        database = str(payload.get("database", "")).strip()
        user = str(payload.get("user", "")).strip()
        if not host or not database or not user:
            raise ValueError("host, database and user are required")
        port = str(payload.get("port") or {"mysql": "3306", "postgres": "5432", "mssql": "1433"}[datasource])
        if not port.isdigit() or not 1 <= int(port) <= 65535:
            raise ValueError("port must be between 1 and 65535")
        secret_name = str(payload.get("secretName") or f"WREN_{name.upper().replace('-', '_')}_PASSWORD")
        if not SECRET_NAME_PATTERN.fullmatch(secret_name):
            raise ValueError("secretName must use uppercase letters, digits and underscores")
        password = payload.get("password")
        with self._lock:
            if isinstance(password, str) and password:
                self._update_secret(secret_name, password)
            elif secret_name not in os.environ and update_name is None:
                raise ValueError("password is required when creating a datasource")
            profile = {
                "datasource": datasource,
                "host": host,
                "port": port,
                "database": database,
                "user": user,
                "password": "${" + secret_name + "}",
            }
            if datasource == "mysql":
                profile["sslMode"] = str(payload.get("sslMode") or "ENABLED")
            if datasource == "mssql":
                profile["driver"] = str(payload.get("driver") or _default_mssql_driver())
            self._test_profile(profile)
            add_profile(name, profile, activate=bool(payload.get("active", True)))
            if bool(payload.get("active", True)):
                switch_profile(name)
            self.runtime.close()
            self._audit("DATASOURCE_SAVED", name, "SUCCEEDED")
        return {"name": name, "datasource": datasource, "tested": True}

    def test_datasource(self, name: str) -> dict[str, Any]:
        started = time.monotonic()
        profile = list_profiles().get(name)
        if profile is None:
            raise KeyError("PROFILE_NOT_FOUND")
        connector = self._connector(profile)
        try:
            table = connector.query("SELECT 1 AS connection_test", 1)
            return {
                "success": table.num_rows == 1,
                "elapsedMs": round((time.monotonic() - started) * 1000),
                "datasource": profile.get("datasource"),
            }
        finally:
            connector.close()

    def delete_datasource(self, name: str) -> dict[str, Any]:
        if name in self._modeled_profiles():
            raise RuntimeError("PROFILE_IN_USE")
        if name in self._current_profiles():
            raise RuntimeError("PROFILE_IN_USE")
        deleted = remove_profile(name)
        self.runtime.close()
        self._audit("DATASOURCE_DELETED", name, "SUCCEEDED" if deleted else "NOT_FOUND")
        return {"deleted": deleted}

    def create_introspection_job(self, payload: dict[str, Any]) -> dict[str, Any]:
        profile_name = str(payload.get("profileName", ""))
        profiles = list_profiles()
        if profile_name not in profiles:
            raise KeyError("PROFILE_NOT_FOUND")
        profile = profiles[profile_name]
        schemas = payload.get("schemas") or []
        if not isinstance(schemas, list) or not schemas:
            datasource = profile.get("datasource")
            schemas = [{"mysql": profile.get("database"), "mssql": "dbo"}.get(datasource, "public")]
        schemas = [_safe_identifier(str(item), "schema") for item in schemas]
        with self._lock:
            if self._active_job_id:
                active = self.get_job(self._active_job_id)
                if active.get("status") in {"PENDING", "RUNNING"}:
                    raise RuntimeError("INTROSPECTION_IN_PROGRESS")
            job_id = uuid.uuid4().hex
            job = {
                "jobId": job_id,
                "profileName": profile_name,
                "schemas": schemas,
                "status": "PENDING",
                "stage": "QUEUED",
                "readCount": 0,
                "createdAt": int(time.time() * 1000),
            }
            _write_json(self.jobs_dir / f"{job_id}.json", job)
            self._active_job_id = job_id
            self._executor.submit(self._run_introspection, job_id)
            return job

    def get_job(self, job_id: str) -> dict[str, Any]:
        path = self.jobs_dir / f"{job_id}.json"
        if not path.exists():
            raise KeyError("JOB_NOT_FOUND")
        return _read_json(path, {})

    def cancel_job(self, job_id: str) -> dict[str, Any]:
        with self._lock:
            job = self.get_job(job_id)
            if job.get("status") in {"PENDING", "RUNNING"}:
                job["status"] = "CANCELLED"
                job["stage"] = "CANCELLED"
                _write_json(self.jobs_dir / f"{job_id}.json", job)
            return job

    def get_snapshot(self, job_id: str) -> dict[str, Any]:
        job = self.get_job(job_id)
        if job.get("status") != "SUCCEEDED":
            raise RuntimeError("SNAPSHOT_NOT_READY")
        return _read_json(self.jobs_dir / f"{job_id}.snapshot.json", {})

    def create_modeling(self, job_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        snapshot = self.get_snapshot(job_id)
        merge_existing = bool(payload.get("mergeExisting"))
        existing = self.get_modeling(str(snapshot.get("profileName"))) if merge_existing else None
        selected = payload.get("tableIds") or [table["id"] for table in snapshot.get("tables", [])]
        selected_set = {str(item) for item in selected}
        tables = [table for table in snapshot.get("tables", []) if table.get("id") in selected_set]
        if not tables:
            raise ValueError("at least one table must be selected")
        occupied: set[str] = set()
        table_model: dict[str, str] = {}
        models = []
        for table in tables:
            name = _model_name(table["name"], occupied)
            table_model[table["id"]] = name
            primary_key = list(table.get("primaryKey") or [])
            columns = []
            for column in table.get("columns", []):
                columns.append(
                    {
                        "name": column["name"],
                        "displayName": column.get("comment") or column["name"],
                        "description": column.get("comment") or "",
                        "type": column["type"],
                        "rawType": column["rawType"],
                        "notNull": not column.get("nullable", True),
                        "primaryKey": column["name"] in primary_key,
                        "visible": True,
                        "ordinal": column.get("ordinal"),
                    }
                )
            models.append(
                {
                    "name": name,
                    "displayName": table.get("comment") or name,
                    "description": table.get("comment") or "",
                    "source": {
                        "catalog": table.get("catalog"),
                        "schema": table.get("schema"),
                        "table": table["name"],
                    },
                    "primaryKey": primary_key,
                    "columns": columns,
                }
            )
        relationships = []
        for foreign_key in snapshot.get("foreignKeys", []):
            from_model = table_model.get(foreign_key.get("fromTableId"))
            to_model = table_model.get(foreign_key.get("toTableId"))
            if not from_model or not to_model:
                continue
            relationships.append(
                {
                    "name": foreign_key.get("name") or f"{from_model}_{to_model}",
                    "fromModel": from_model,
                    "fromColumns": foreign_key.get("fromColumns", []),
                    "toModel": to_model,
                    "toColumns": foreign_key.get("toColumns", []),
                    "cardinality": "MANY_TO_ONE",
                    "status": "CONFIRMED",
                    "source": "FOREIGN_KEY",
                }
            )
        modeling = {
            "revision": _next_revision(),
            "profileName": snapshot.get("profileName"),
            "datasource": snapshot.get("datasource"),
            "setupCompleted": False,
            "models": models,
            "relationships": relationships,
            "updatedAt": int(time.time() * 1000),
        }
        if existing and existing.get("models"):
            modeling = self._merge_modeling_semantics(existing, modeling)
        with self._lock:
            self._persist_modeling(modeling)
        self._audit("MODELING_CREATED", job_id, "SUCCEEDED")
        return modeling

    @staticmethod
    def _model_source_key(model: dict[str, Any]) -> tuple[str, str, str]:
        source = model.get("source") or {}
        return (str(source.get("catalog") or ""), str(source.get("schema") or ""), str(source.get("table") or ""))

    @classmethod
    def _merge_modeling_semantics(cls, existing: dict[str, Any], fresh: dict[str, Any]) -> dict[str, Any]:
        """按物理表和字段名合并 Draft，避免重新探测覆盖人工语义。"""
        old_by_source = {cls._model_source_key(model): model for model in existing.get("models", [])}
        name_by_source = {cls._model_source_key(model): model["name"] for model in fresh.get("models", [])}
        fresh_models = []
        for model in fresh["models"]:
            old = old_by_source.get(cls._model_source_key(model))
            if old:
                model["displayName"] = old.get("displayName", model["displayName"])
                model["description"] = old.get("description", model["description"])
                old_columns = {column["name"]: column for column in old.get("columns", [])}
                for column in model["columns"]:
                    previous = old_columns.get(column["name"])
                    if previous:
                        for field in ("displayName", "description", "visible", "primaryKey"):
                            if field in previous:
                                column[field] = previous[field]
                model["primaryKey"] = [column["name"] for column in model["columns"] if column.get("primaryKey")]
            fresh_models.append(model)
        fresh["models"] = fresh_models
        valid_columns = {model["name"]: {column["name"] for column in model["columns"]} for model in fresh_models}
        preserved = []
        for relation in existing.get("relationships", []):
            old_from = next((item for item in existing["models"] if item["name"] == relation.get("fromModel")), None)
            old_to = next((item for item in existing["models"] if item["name"] == relation.get("toModel")), None)
            from_name = name_by_source.get(cls._model_source_key(old_from)) if old_from else None
            to_name = name_by_source.get(cls._model_source_key(old_to)) if old_to else None
            if not from_name or not to_name:
                continue
            if any(column not in valid_columns[from_name] for column in relation.get("fromColumns", [])):
                continue
            if any(column not in valid_columns[to_name] for column in relation.get("toColumns", [])):
                continue
            copied = dict(relation)
            copied["fromModel"] = from_name
            copied["toModel"] = to_name
            preserved.append(copied)
        generated = {item["name"]: item for item in fresh.get("relationships", [])}
        for relation in preserved:
            generated[relation["name"]] = relation
        fresh["relationships"] = list(generated.values())
        fresh["setupCompleted"] = bool(fresh["relationships"])
        return fresh

    def get_modeling(self, profile_name: str | None = None) -> dict[str, Any]:
        if profile_name is None:
            active_profile = get_active_name()
            if active_profile and (self._modeling_path(active_profile) / "model.json").exists():
                profile_name = active_profile
            else:
                candidates = [
                    path for path in self.modeling_dir.glob("*/model.json") if path.is_file()
                ]
                if candidates:
                    profile_name = max(candidates, key=lambda path: path.stat().st_mtime).parent.name
        if profile_name is None:
            return {"revision": 0, "models": [], "relationships": []}
        state_file = self._modeling_path(profile_name) / "model.json"
        if state_file.exists():
            return _read_json(state_file, {})
        return {"revision": 0, "profileName": profile_name, "models": [], "relationships": []}

    def update_model(self, model_name: str, payload: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            modeling = self._require_revision(payload)
            model = next((item for item in modeling["models"] if item["name"] == model_name), None)
            if model is None:
                raise KeyError("MODEL_NOT_FOUND")
            for field in ("displayName", "description"):
                if field in payload:
                    model[field] = str(payload[field])
            if "columns" in payload:
                updates = {item["name"]: item for item in payload["columns"] if isinstance(item, dict)}
                for column in model["columns"]:
                    update = updates.get(column["name"])
                    if update:
                        for field in ("displayName", "description", "visible", "primaryKey"):
                            if field in update:
                                column[field] = update[field]
                model["primaryKey"] = [column["name"] for column in model["columns"] if column.get("primaryKey")]
            modeling["revision"] = _next_revision(modeling["revision"])
            modeling["updatedAt"] = int(time.time() * 1000)
            self._persist_modeling(modeling)
            self._audit("MODELING_UPDATED", model_name, "SUCCEEDED")
            return modeling

    def delete_model(self, model_name: str, revision: int, profile_name: str | None = None) -> dict[str, Any]:
        """从 Draft 移除模型，并在存在关系依赖时拒绝操作。"""
        with self._lock:
            modeling = self._require_revision({"revision": revision, "profileName": profile_name})
            if not any(item["name"] == model_name for item in modeling["models"]):
                raise KeyError("MODEL_NOT_FOUND")
            dependencies = [
                item["name"] for item in modeling.get("relationships", [])
                if item.get("fromModel") == model_name or item.get("toModel") == model_name
            ]
            if dependencies:
                raise ValueError("MODEL_HAS_RELATIONSHIPS:" + ",".join(dependencies))
            modeling["models"] = [item for item in modeling["models"] if item["name"] != model_name]
            modeling["revision"] = _next_revision(modeling["revision"])
            modeling["updatedAt"] = int(time.time() * 1000)
            self._persist_modeling(modeling)
            self._audit("MODELING_MODEL_REMOVED", model_name, "SUCCEEDED")
            return modeling

    def save_relationship(self, relationship_name: str, payload: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            modeling = self._require_revision(payload)
            relationship = {
                "name": relationship_name,
                "fromModel": str(payload.get("fromModel", "")),
                "fromColumns": list(payload.get("fromColumns") or []),
                "toModel": str(payload.get("toModel", "")),
                "toColumns": list(payload.get("toColumns") or []),
                "cardinality": str(payload.get("cardinality") or "MANY_TO_ONE"),
                "status": str(payload.get("status") or "CONFIRMED"),
                "source": str(payload.get("source") or "MANUAL"),
            }
            self._validate_relationship(relationship, modeling)
            items = [item for item in modeling["relationships"] if item["name"] != relationship_name]
            items.append(relationship)
            modeling["relationships"] = items
            modeling["revision"] = _next_revision(modeling["revision"])
            modeling["updatedAt"] = int(time.time() * 1000)
            self._persist_modeling(modeling)
            return modeling

    def replace_relationships(self, payload: dict[str, Any]) -> dict[str, Any]:
        """在同一 revision 上校验并替换全部关系。"""
        with self._lock:
            modeling = self._require_revision(payload)
            requested = payload.get("relationships")
            if not isinstance(requested, list):
                raise ValueError("relationships must be a list")
            relationships = []
            names: set[str] = set()
            for item in requested:
                if not isinstance(item, dict):
                    raise ValueError("relationship must be an object")
                name = str(item.get("name", "")).strip()
                if not name or name in names:
                    raise ValueError("relationship names must be non-empty and unique")
                relationship = {
                    "name": name,
                    "fromModel": str(item.get("fromModel", "")),
                    "fromColumns": list(item.get("fromColumns") or []),
                    "toModel": str(item.get("toModel", "")),
                    "toColumns": list(item.get("toColumns") or []),
                    "cardinality": str(item.get("cardinality") or "MANY_TO_ONE"),
                    "status": str(item.get("status") or "CONFIRMED"),
                    "source": str(item.get("source") or "MANUAL"),
                }
                self._validate_relationship(relationship, modeling)
                names.add(name)
                relationships.append(relationship)
            modeling["relationships"] = relationships
            modeling["setupCompleted"] = True
            modeling["revision"] = _next_revision(modeling["revision"])
            modeling["updatedAt"] = int(time.time() * 1000)
            self._persist_modeling(modeling)
            self._audit("RELATIONSHIPS_REPLACED", str(len(relationships)), "SUCCEEDED")
            return modeling

    def delete_relationship(
        self, relationship_name: str, revision: int, profile_name: str | None = None
    ) -> dict[str, Any]:
        return self.save_relationships_without(relationship_name, revision, profile_name)

    def save_relationships_without(
        self, relationship_name: str, revision: int, profile_name: str | None = None
    ) -> dict[str, Any]:
        with self._lock:
            modeling = self._require_revision({"revision": revision, "profileName": profile_name})
            modeling["relationships"] = [
                item for item in modeling["relationships"] if item["name"] != relationship_name
            ]
            modeling["revision"] = _next_revision(modeling["revision"])
            modeling["updatedAt"] = int(time.time() * 1000)
            self._persist_modeling(modeling)
            return modeling

    def validate_modeling(self, profile_name: str | None = None) -> dict[str, Any]:
        """完整校验待应用的建模内容，但不改变 Current MDL。"""
        modeling = self.get_modeling(profile_name)
        model_profile = modeling.get("profileName")
        errors = []
        warnings = []
        if not modeling.get("models"):
            errors.append({"path": "models", "message": "至少选择一个模型"})
        for model in modeling.get("models", []):
            if not model.get("primaryKey"):
                warnings.append({"path": f"models.{model['name']}", "message": "模型没有主键"})
        try:
            if not model_profile:
                raise ValueError("建模内容未绑定数据源")
            profile = list_profiles().get(str(model_profile))
            if profile is None:
                raise ValueError("建模内容绑定的数据源不存在")
            build_json(self._modeling_path(str(model_profile)))
            self._test_profile(profile)
        except (ValueError, KeyError, TypeError) as exception:
            message = sanitize_message(exception)
            LOGGER.warning(
                "Wren 建模校验失败: requestId=%s, type=%s, message=%s",
                current_request_id(),
                type(exception).__name__,
                message,
                exc_info=True,
            )
            errors.append({"path": "modeling", "message": message})
        return {"valid": not errors, "errors": errors, "warnings": warnings}

    def prepare_mdl(self, profile_name: str | None = None) -> dict[str, Any]:
        """固化一次不可变候选并返回完整候选 Current MDL 快照。"""
        with self._lock:
            model = self.get_modeling(profile_name)
            model_profile = model.get("profileName")
            validation = self.validate_modeling(str(model_profile) if model_profile else None)
            if not validation["valid"]:
                raise RuntimeError("VALIDATION_FAILED")
            candidate_token = uuid.uuid4().hex
            candidate_root = self.candidates_dir / candidate_token
            candidate_project = candidate_root / "project"
            shutil.copytree(self._modeling_path(str(model_profile)), candidate_project)
            (candidate_project / "model.json").unlink(missing_ok=True)
            _write_json(candidate_root / "candidate.json", {"profileName": model_profile})
            projects = {
                current_profile: self.current_dir / current_profile
                for current_profile in self._current_profiles()
            }
            projects[str(model_profile)] = candidate_project
            snapshot = self._snapshot_for_projects(projects)
            self._audit("MDL_CANDIDATE_PREPARED", snapshot["mdlDigest"], "SUCCEEDED")
            return {
                "candidateToken": candidate_token,
                "mdlDigest": snapshot["mdlDigest"],
                "manifest": snapshot["manifest"],
            }

    def commit_mdl(self, candidate_token: str, expected_mdl_digest: str | None) -> dict[str, Any]:
        """校验 Current MDL 未变化后提交候选，并立即删除被替换内容。"""
        if not re.fullmatch(r"[a-f0-9]{32}", candidate_token):
            raise ValueError("candidate token is invalid")
        with self._lock:
            actual_digest = self.runtime.mdl_digest()
            if (expected_mdl_digest or "") != actual_digest:
                raise RuntimeError("CURRENT_MDL_CHANGED")
            candidate_root = self.candidates_dir / candidate_token
            candidate_project = candidate_root / "project"
            metadata = _read_json(candidate_root / "candidate.json", {})
            profile_name = metadata.get("profileName")
            if not isinstance(profile_name, str) or not candidate_project.is_dir():
                raise KeyError("CANDIDATE_NOT_FOUND")
            target = self.current_dir / profile_name
            previous = candidate_root / "previous"
            self.runtime.commit_project(profile_name, candidate_project, target, previous)
            shutil.rmtree(previous, ignore_errors=True)
            shutil.rmtree(candidate_root, ignore_errors=True)
            snapshot = self.runtime.context_snapshot()
            self._audit("CURRENT_MDL_APPLIED", snapshot["mdlDigest"], "SUCCEEDED")
            return snapshot

    def discard_candidate(self, candidate_token: str) -> dict[str, Any]:
        """幂等删除未提交候选。"""
        if not re.fullmatch(r"[a-f0-9]{32}", candidate_token):
            raise ValueError("candidate token is invalid")
        shutil.rmtree(self.candidates_dir / candidate_token, ignore_errors=True)
        return {"discarded": True}

    @staticmethod
    def _snapshot_for_projects(projects: dict[str, Path]) -> dict[str, Any]:
        """从指定项目集合生成一个聚合 Current MDL 候选快照。"""
        sources = {}
        source_digests = {}
        for source, project in sorted(projects.items()):
            manifest = build_json(project)
            encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            source_digest = hashlib.sha256(encoded.encode()).hexdigest()
            source_digests[source] = source_digest
            sources[source] = {"mdlDigest": source_digest, "manifest": manifest}
        aggregate = json.dumps(source_digests, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return {
            "configured": bool(sources),
            "mdlDigest": hashlib.sha256(aggregate.encode()).hexdigest() if sources else "",
            "manifest": {"sources": sources} if sources else {},
        }

    def _run_introspection(self, job_id: str) -> None:
        path = self.jobs_dir / f"{job_id}.json"
        connector = None
        try:
            job = self.get_job(job_id)
            if job.get("status") == "CANCELLED":
                return
            job.update({"status": "RUNNING", "stage": "CONNECTING"})
            _write_json(path, job)
            profile = list_profiles()[job["profileName"]]
            connector = self._connector(profile)
            job["stage"] = "READING_METADATA"
            _write_json(path, job)
            snapshot = self._introspect(connector, profile, job["profileName"], job["schemas"])
            if self.get_job(job_id).get("status") == "CANCELLED":
                return
            _write_json(self.jobs_dir / f"{job_id}.snapshot.json", snapshot)
            job.update(
                {
                    "status": "SUCCEEDED",
                    "stage": "COMPLETED",
                    "readCount": len(snapshot["tables"]),
                    "finishedAt": int(time.time() * 1000),
                }
            )
            _write_json(path, job)
        except Exception as exception:
            job = _read_json(path, {"jobId": job_id})
            message = sanitize_message(exception)
            job.update(
                {
                    "status": "FAILED",
                    "stage": job.get("stage", "FAILED"),
                    "error": message,
                    "finishedAt": int(time.time() * 1000),
                }
            )
            _write_json(path, job)
            LOGGER.exception(
                "Wren Schema 探测失败: requestId=%s, jobId=%s, profileName=%s, type=%s, message=%s",
                current_request_id(),
                job_id,
                job.get("profileName"),
                type(exception).__name__,
                message,
            )
        finally:
            if connector is not None:
                connector.close()
            with self._lock:
                if self._active_job_id == job_id:
                    self._active_job_id = None

    def _introspect(self, connector: Any, profile: dict[str, Any], profile_name: str, schemas: list[str]) -> dict[str, Any]:
        datasource = str(profile["datasource"])
        schema_values = ", ".join(_sql_literal(item) for item in schemas)
        database = str(profile["database"])
        queries = self._metadata_queries(datasource, database, schema_values)
        rows = {name: connector.query(sql).to_pylist() for name, sql in queries.items()}
        table_map: dict[tuple[str, str], dict[str, Any]] = {}
        for row in rows["columns"]:
            schema = _metadata_text(row["table_schema"])
            table_name = _metadata_text(row["table_name"])
            key = (schema, table_name)
            table = table_map.setdefault(
                key,
                {
                    "id": f"{database}.{schema}.{table_name}",
                    # MySQL/PostgreSQL 不支持 database.schema.table 三段引用，只有 MSSQL 保留 catalog。
                    "catalog": database if datasource == "mssql" else "",
                    "schema": schema,
                    "name": table_name,
                    "comment": _metadata_text(row["table_comment"]) if row.get("table_comment") else "",
                    "columns": [],
                    "primaryKey": [],
                },
            )
            raw_type = _metadata_text(row.get("data_type") or "UNKNOWN")
            table["columns"].append(
                {
                    "name": _metadata_text(row["column_name"]),
                    "rawType": raw_type,
                    "type": parse_type(raw_type, "tsql" if datasource == "mssql" else datasource),
                    "nullable": _metadata_text(row.get("is_nullable", "YES")).upper() == "YES",
                    "ordinal": int(row.get("ordinal_position") or 0),
                    "comment": _metadata_text(row["column_comment"]) if row.get("column_comment") else "",
                }
            )
        for row in rows["keys"]:
            table = table_map.get((_metadata_text(row["table_schema"]), _metadata_text(row["table_name"])))
            if table is not None and _metadata_text(row.get("constraint_type", "")).upper() == "PRIMARY KEY":
                table["primaryKey"].append(_metadata_text(row["column_name"]))
        foreign_keys = []
        grouped: dict[tuple[str, str, str], dict[str, Any]] = {}
        for row in rows["foreign_keys"]:
            key = (
                _metadata_text(row["table_schema"]),
                _metadata_text(row["table_name"]),
                _metadata_text(row["constraint_name"]),
            )
            item = grouped.setdefault(
                key,
                {
                    "name": key[2],
                    "fromTableId": f"{database}.{key[0]}.{key[1]}",
                    "fromColumns": [],
                    "toTableId": (
                        f"{database}.{_metadata_text(row['referenced_schema'])}."
                        f"{_metadata_text(row['referenced_table'])}"
                    ),
                    "toColumns": [],
                },
            )
            item["fromColumns"].append(_metadata_text(row["column_name"]))
            item["toColumns"].append(_metadata_text(row["referenced_column"]))
        foreign_keys.extend(grouped.values())
        return {
            "profileName": profile_name,
            "datasource": datasource,
            "database": database,
            "schemas": schemas,
            "tables": sorted(table_map.values(), key=lambda item: item["id"]),
            "foreignKeys": foreign_keys,
            "capturedAt": int(time.time() * 1000),
        }

    @staticmethod
    def _metadata_queries(datasource: str, database: str, schema_values: str) -> dict[str, str]:
        if datasource == "mysql":
            database_literal = _sql_literal(database)
            return {
                "columns": (
                    "SELECT c.TABLE_SCHEMA AS table_schema, c.TABLE_NAME AS table_name, "
                    "c.COLUMN_NAME AS column_name, c.DATA_TYPE AS data_type, c.IS_NULLABLE AS is_nullable, "
                    "c.ORDINAL_POSITION AS ordinal_position, c.COLUMN_COMMENT AS column_comment, "
                    "t.TABLE_COMMENT AS table_comment FROM information_schema.COLUMNS c "
                    "JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME "
                    f"WHERE c.TABLE_SCHEMA={database_literal} ORDER BY c.TABLE_NAME,c.ORDINAL_POSITION"
                ),
                "keys": (
                    "SELECT k.TABLE_SCHEMA AS table_schema,k.TABLE_NAME AS table_name,k.COLUMN_NAME AS column_name,"
                    "tc.CONSTRAINT_TYPE AS constraint_type FROM information_schema.KEY_COLUMN_USAGE k "
                    "JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA "
                    "AND tc.TABLE_NAME=k.TABLE_NAME AND tc.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                    f"WHERE k.TABLE_SCHEMA={database_literal} ORDER BY k.TABLE_NAME,k.ORDINAL_POSITION"
                ),
                "foreign_keys": (
                    "SELECT TABLE_SCHEMA AS table_schema,TABLE_NAME AS table_name,CONSTRAINT_NAME AS constraint_name,"
                    "COLUMN_NAME AS column_name,REFERENCED_TABLE_SCHEMA AS referenced_schema,"
                    "REFERENCED_TABLE_NAME AS referenced_table,REFERENCED_COLUMN_NAME AS referenced_column "
                    "FROM information_schema.KEY_COLUMN_USAGE "
                    f"WHERE TABLE_SCHEMA={database_literal} AND REFERENCED_TABLE_NAME IS NOT NULL "
                    "ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION"
                ),
            }
        if datasource == "postgres":
            return {
                "columns": (
                    "SELECT c.table_schema,c.table_name,c.column_name,c.data_type,c.is_nullable,c.ordinal_position,"
                    "col_description((quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass::oid,c.ordinal_position) AS column_comment,"
                    "obj_description((quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass::oid) AS table_comment "
                    "FROM information_schema.columns c "
                    f"WHERE c.table_schema IN ({schema_values}) ORDER BY c.table_schema,c.table_name,c.ordinal_position"
                ),
                "keys": (
                    "SELECT tc.table_schema,tc.table_name,kcu.column_name,tc.constraint_type "
                    "FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu "
                    "ON tc.constraint_name=kcu.constraint_name AND tc.table_schema=kcu.table_schema "
                    f"WHERE tc.table_schema IN ({schema_values}) ORDER BY tc.table_name,kcu.ordinal_position"
                ),
                "foreign_keys": (
                    "SELECT tc.table_schema,tc.table_name,tc.constraint_name,kcu.column_name,"
                    "ccu.table_schema AS referenced_schema,ccu.table_name AS referenced_table,ccu.column_name AS referenced_column "
                    "FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu "
                    "ON tc.constraint_name=kcu.constraint_name AND tc.table_schema=kcu.table_schema "
                    "JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name=tc.constraint_name "
                    f"WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema IN ({schema_values}) "
                    "ORDER BY tc.table_name,tc.constraint_name,kcu.ordinal_position"
                ),
            }
        return {
            "columns": (
                "SELECT c.TABLE_SCHEMA AS table_schema,c.TABLE_NAME AS table_name,c.COLUMN_NAME AS column_name,"
                "c.DATA_TYPE AS data_type,c.IS_NULLABLE AS is_nullable,c.ORDINAL_POSITION AS ordinal_position,"
                "CAST(ep.value AS nvarchar(4000)) AS column_comment,CAST(tp.value AS nvarchar(4000)) AS table_comment "
                "FROM INFORMATION_SCHEMA.COLUMNS c LEFT JOIN sys.tables st ON st.name=c.TABLE_NAME "
                "LEFT JOIN sys.schemas ss ON ss.schema_id=st.schema_id AND ss.name=c.TABLE_SCHEMA "
                "LEFT JOIN sys.extended_properties ep ON ep.major_id=st.object_id AND ep.minor_id=COLUMNPROPERTY(st.object_id,c.COLUMN_NAME,'ColumnId') AND ep.name='MS_Description' "
                "LEFT JOIN sys.extended_properties tp ON tp.major_id=st.object_id AND tp.minor_id=0 AND tp.name='MS_Description' "
                f"WHERE c.TABLE_SCHEMA IN ({schema_values}) ORDER BY c.TABLE_SCHEMA,c.TABLE_NAME,c.ORDINAL_POSITION"
            ),
            "keys": (
                "SELECT tc.TABLE_SCHEMA AS table_schema,tc.TABLE_NAME AS table_name,kcu.COLUMN_NAME AS column_name,tc.CONSTRAINT_TYPE AS constraint_type "
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu "
                "ON tc.CONSTRAINT_NAME=kcu.CONSTRAINT_NAME AND tc.TABLE_SCHEMA=kcu.TABLE_SCHEMA "
                f"WHERE tc.TABLE_SCHEMA IN ({schema_values}) ORDER BY tc.TABLE_NAME,kcu.ORDINAL_POSITION"
            ),
            "foreign_keys": (
                "SELECT OBJECT_SCHEMA_NAME(fk.parent_object_id) AS table_schema,OBJECT_NAME(fk.parent_object_id) AS table_name,"
                "fk.name AS constraint_name,COL_NAME(fkc.parent_object_id,fkc.parent_column_id) AS column_name,"
                "OBJECT_SCHEMA_NAME(fk.referenced_object_id) AS referenced_schema,OBJECT_NAME(fk.referenced_object_id) AS referenced_table,"
                "COL_NAME(fkc.referenced_object_id,fkc.referenced_column_id) AS referenced_column "
                "FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fkc ON fk.object_id=fkc.constraint_object_id "
                f"WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) IN ({schema_values}) ORDER BY table_name,constraint_name,fkc.constraint_column_id"
            ),
        }

    def _persist_modeling(self, modeling: dict[str, Any]) -> None:
        profile_name = str(modeling.get("profileName") or "")
        datasource = str(modeling.get("datasource") or "mysql")
        modeling_path = self._initialize_modeling_path(profile_name, datasource)
        model_root = modeling_path / "models"
        existing = {path.name for path in model_root.iterdir() if path.is_dir()}
        wanted = {model["name"] for model in modeling["models"]}
        for stale in existing - wanted:
            shutil.rmtree(model_root / stale)
        for model in modeling["models"]:
            directory = model_root / model["name"]
            directory.mkdir(parents=True, exist_ok=True)
            primary_key = list(model.get("primaryKey") or [])
            metadata = {
                "name": model["name"],
                "table_reference": {
                    "catalog": model["source"].get("catalog"),
                    "schema": model["source"].get("schema"),
                    "table": model["source"].get("table"),
                },
                "properties": {
                    "display_name": model.get("displayName"),
                    "description": model.get("description", ""),
                },
                "columns": [
                    {
                        "name": column["name"],
                        "type": column["type"],
                        "not_null": column.get("notNull", False),
                        "is_primary_key": column.get("primaryKey", False),
                        "properties": {
                            "display_name": column.get("displayName"),
                            "description": column.get("description", ""),
                            "visible": column.get("visible", True),
                            "raw_type": column.get("rawType"),
                        },
                    }
                    for column in model["columns"]
                ],
                "primary_key": primary_key[0] if len(primary_key) == 1 else primary_key,
                "cached": False,
            }
            _atomic_write(directory / "metadata.yml", yaml.safe_dump(metadata, allow_unicode=True, sort_keys=False))
        relationships = []
        for relation in modeling["relationships"]:
            pairs = zip(relation["fromColumns"], relation["toColumns"], strict=True)
            condition = " AND ".join(
                f"{relation['fromModel']}.{left} = {relation['toModel']}.{right}" for left, right in pairs
            )
            relationships.append(
                {
                    "name": relation["name"],
                    "models": [relation["fromModel"], relation["toModel"]],
                    "join_type": relation["cardinality"],
                    "condition": condition,
                    "properties": {"status": relation["status"], "source": relation["source"]},
                }
            )
        _atomic_write(
            modeling_path / "relationships.yml",
            yaml.safe_dump({"relationships": relationships}, allow_unicode=True, sort_keys=False),
        )
        config = {
            "schema_version": 5,
            "name": "customer_database",
            "data_source": modeling.get("datasource") or "mysql",
            "profile": modeling.get("profileName"),
        }
        save_project_config(modeling_path, config)
        _write_json(modeling_path / "model.json", modeling)
        self.runtime.close()

    def _require_revision(self, payload: dict[str, Any]) -> dict[str, Any]:
        profile_name = payload.get("profileName")
        modeling = self.get_modeling(str(profile_name) if profile_name else None)
        if int(payload.get("revision", -1)) != int(modeling.get("revision", 0)):
            raise RuntimeError("MODELING_CHANGED")
        return modeling

    @staticmethod
    def _validate_relationship(relationship: dict[str, Any], modeling: dict[str, Any]) -> None:
        models = {model["name"]: model for model in modeling["models"]}
        if relationship["fromModel"] not in models or relationship["toModel"] not in models:
            raise ValueError("relationship references an unknown model")
        if not relationship["fromColumns"] or len(relationship["fromColumns"]) != len(relationship["toColumns"]):
            raise ValueError("relationship endpoints must contain the same number of columns")
        for side in ("from", "to"):
            model = models[relationship[f"{side}Model"]]
            known = {column["name"] for column in model["columns"]}
            if any(column not in known for column in relationship[f"{side}Columns"]):
                raise ValueError("relationship references an unknown column")

    def _connector(self, profile: dict[str, Any]) -> Any:
        expanded = expand_profile_secrets(dict(profile))
        datasource = DataSource(str(expanded.pop("datasource")))
        connection_info = datasource.get_connection_info(expanded)
        return get_connector(datasource, connection_info)

    def _test_profile(self, profile: dict[str, Any]) -> None:
        connector = self._connector(profile)
        try:
            result = connector.query("SELECT 1 AS connection_test", 1)
            if result.num_rows != 1:
                raise RuntimeError("connection test did not return one row")
        finally:
            connector.close()

    def _update_secret(self, name: str, value: str) -> None:
        entries: dict[str, str] = {}
        if self.env_file.exists():
            for line in self.env_file.read_text(encoding="utf-8").splitlines():
                if line and not line.lstrip().startswith("#") and "=" in line:
                    key, existing = line.split("=", 1)
                    entries[key] = existing
        entries[name] = value.replace("\r", "").replace("\n", "")
        _atomic_write(self.env_file, "".join(f"{key}={entries[key]}\n" for key in sorted(entries)))
        os.environ[name] = entries[name]

    def _audit(self, action: str, object_id: str, status: str) -> None:
        record = {
            "action": action,
            "objectId": object_id,
            "status": status,
            "timestamp": int(time.time() * 1000),
        }
        self.audit_file.parent.mkdir(parents=True, exist_ok=True)
        with self.audit_file.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


trace_module_functions(globals(), __name__)
