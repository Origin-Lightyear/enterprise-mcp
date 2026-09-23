"""补齐并校验管理后台支持的数据源 Python 依赖。"""

from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import yaml


SUPPORTED_DEPENDENCIES = {
    "mysql": "MySQLdb",
    "postgres": "psycopg",
    "mssql": "pyodbc",
}


def _missing_datasources() -> list[str]:
    return [
        datasource
        for datasource, module in SUPPORTED_DEPENDENCIES.items()
        if importlib.util.find_spec(module) is None
    ]


def _install_missing(upstream: Path, missing: list[str]) -> None:
    if not missing:
        return
    package = f"{upstream}[{','.join(missing)}]"
    print(f"Wren 正在安装缺失的数据源依赖: {', '.join(missing)}", flush=True)
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", package],
        check=True,
    )


def _configured_mssql_drivers() -> set[str]:
    instance_dir = Path(os.environ.get("WREN_INSTANCE_DIR", "./project-root"))
    profiles_path = instance_dir / ".wren" / "profiles.yml"
    if not profiles_path.is_file():
        return set()
    document = yaml.safe_load(profiles_path.read_text(encoding="utf-8")) or {}
    profiles = document.get("profiles") or {}
    if not isinstance(profiles, dict):
        raise RuntimeError(f"profiles.yml 的 profiles 必须是对象: {profiles_path}")
    return {
        str(profile.get("driver") or "ODBC Driver 18 for SQL Server")
        for profile in profiles.values()
        if isinstance(profile, dict) and profile.get("datasource") == "mssql"
    }


def _verify_mssql_drivers() -> None:
    required = _configured_mssql_drivers()
    if not required:
        return
    import pyodbc

    installed = set(pyodbc.drivers())
    standard_drivers = {
        "ODBC Driver 18 for SQL Server",
        "ODBC Driver 17 for SQL Server",
    }
    has_standard_fallback = bool(installed & standard_drivers)
    missing = {
        driver
        for driver in required
        if driver not in installed
        and not (driver in standard_drivers and has_standard_fallback)
    }
    if missing:
        raise RuntimeError(
            "SQL Server Profile 配置的 ODBC Driver 未安装: "
            f"{', '.join(sorted(missing))}；当前可用: "
            f"{', '.join(sorted(installed)) or '无'}"
        )


def main() -> None:
    upstream = Path(sys.argv[1]).resolve()
    if not (upstream / "pyproject.toml").is_file():
        raise RuntimeError(f"Wren upstream 目录无效: {upstream}")
    missing = _missing_datasources()
    _install_missing(upstream, missing)
    remaining = _missing_datasources()
    if remaining:
        raise RuntimeError(f"数据源依赖仍未安装: {', '.join(remaining)}")
    _verify_mssql_drivers()
    print("Wren 数据源依赖检查通过: mysql, postgres, mssql", flush=True)


if __name__ == "__main__":
    main()
