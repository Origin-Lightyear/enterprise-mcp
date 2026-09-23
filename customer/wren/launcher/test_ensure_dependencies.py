"""验证 Wren 启动前的数据源依赖补齐与 ODBC Driver 校验。"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent))
import ensure_dependencies


class EnsureDependenciesTest(unittest.TestCase):
    """验证缺失依赖和 SQL Server 系统驱动均在启动前被识别。"""

    def test_installs_only_missing_datasource_extras(self) -> None:
        upstream = Path("wren-upstream")
        with patch.object(
            ensure_dependencies.importlib.util,
            "find_spec",
            side_effect=lambda module: None if module in {"psycopg", "pyodbc"} else MagicMock(),
        ), patch.object(ensure_dependencies.subprocess, "run") as run:
            missing = ensure_dependencies._missing_datasources()
            ensure_dependencies._install_missing(upstream, missing)

        self.assertEqual(["postgres", "mssql"], missing)
        self.assertIn("[postgres,mssql]", run.call_args.args[0][-1])
        self.assertTrue(run.call_args.kwargs["check"])

    def test_rejects_nonstandard_odbc_driver_that_is_not_installed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile_dir = Path(directory) / ".wren"
            profile_dir.mkdir()
            (profile_dir / "profiles.yml").write_text(
                "profiles:\n  crm:\n    datasource: mssql\n"
                "    driver: FreeTDS\n",
                encoding="utf-8",
            )
            fake_pyodbc = MagicMock()
            fake_pyodbc.drivers.return_value = ["ODBC Driver 17 for SQL Server"]
            with patch.dict(os.environ, {"WREN_INSTANCE_DIR": directory}), patch.dict(
                "sys.modules", {"pyodbc": fake_pyodbc}
            ):
                with self.assertRaisesRegex(RuntimeError, "FreeTDS"):
                    ensure_dependencies._verify_mssql_drivers()

    def test_accepts_installed_standard_odbc_driver_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile_dir = Path(directory) / ".wren"
            profile_dir.mkdir()
            (profile_dir / "profiles.yml").write_text(
                "profiles:\n  crm:\n    datasource: mssql\n"
                "    driver: ODBC Driver 18 for SQL Server\n",
                encoding="utf-8",
            )
            fake_pyodbc = MagicMock()
            fake_pyodbc.drivers.return_value = ["ODBC Driver 17 for SQL Server"]
            with patch.dict(os.environ, {"WREN_INSTANCE_DIR": directory}), patch.dict(
                "sys.modules", {"pyodbc": fake_pyodbc}
            ):
                ensure_dependencies._verify_mssql_drivers()


if __name__ == "__main__":
    unittest.main()
