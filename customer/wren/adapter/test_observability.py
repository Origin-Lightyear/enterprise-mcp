import inspect
import unittest

from . import admin_service, retrieval, server
from .observability import request_scope, sanitize_message, trace_methods, traced


class ObservabilityTest(unittest.TestCase):
    def test_logs_method_entry_exit_and_request_id_without_arguments(self):
        @traced
        def execute(secret):
            return "ok"

        with self.assertLogs("wren.trace", level="INFO") as captured, request_scope("request-1"):
            self.assertEqual("ok", execute("api-key-secret"))

        output = "\n".join(captured.output)
        self.assertIn("Wren 方法进入: requestId=request-1", output)
        self.assertIn("Wren 方法退出: requestId=request-1", output)
        self.assertNotIn("api-key-secret", output)

    def test_logs_exception_summary_and_rethrows_original_exception(self):
        @trace_methods()
        class Service:
            def fail(self):
                raise ValueError("invalid model")

        with self.assertLogs("wren.trace", level="ERROR") as captured, \
                request_scope("request-2"), \
                self.assertRaisesRegex(ValueError, "invalid model"):
            Service().fail()

        output = "\n".join(captured.output)
        self.assertIn("Wren 方法异常: requestId=request-2", output)
        self.assertIn("type=ValueError, message=invalid model", output)

    def test_sanitizes_common_secret_formats(self):
        message = (
            'Authorization: Bearer token-value, "apiKey":"key-value", '
            "password=pass-value, url=mysql://user:db-password@localhost/demo"
        )

        sanitized = sanitize_message(message)

        self.assertNotIn("token-value", sanitized)
        self.assertNotIn("key-value", sanitized)
        self.assertNotIn("pass-value", sanitized)
        self.assertNotIn("db-password", sanitized)
        self.assertGreaterEqual(sanitized.count("***"), 4)

    def test_traces_every_adapter_method_and_module_function(self):
        class_exclusions = {
            server.Handler: {"handle_one_request", "log_message"},
        }
        for target in (
            server.WrenRuntime,
            server.Handler,
            admin_service.AdminService,
            retrieval.EmployeeModelClient,
            retrieval.EmployeeVectorMemory,
        ):
            for name, member in vars(target).items():
                if name in class_exclusions.get(target, set()):
                    continue
                if name.startswith("__") and name != "__init__":
                    continue
                if inspect.isfunction(member):
                    self.assertTrue(getattr(member, "__wren_traced__", False), f"{target.__name__}.{name}")

        for module in (server, admin_service, retrieval):
            for name, member in vars(module).items():
                if name == "main" or not inspect.isfunction(member) or member.__module__ != module.__name__:
                    continue
                self.assertTrue(getattr(member, "__wren_traced__", False), f"{module.__name__}.{name}")


if __name__ == "__main__":
    unittest.main()
