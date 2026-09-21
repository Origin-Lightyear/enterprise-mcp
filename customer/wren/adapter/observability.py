"""提供 Wren Adapter 统一方法追踪和请求关联能力。"""

from __future__ import annotations

import inspect
import logging
import re
import time
from contextlib import contextmanager
from contextvars import ContextVar
from functools import wraps
from typing import Any, Callable, Iterator, TypeVar


_REQUEST_ID: ContextVar[str] = ContextVar("wren_request_id", default="-")
_TRACE_LOGGER = logging.getLogger("wren.trace")
_FUNCTION = TypeVar("_FUNCTION", bound=Callable[..., Any])
_BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_SECRET_FIELD_PATTERN = re.compile(
    r"(?i)([\"']?(?:api[_-]?key|password|secret|token|authorization)[\"']?\s*[:=]\s*[\"']?)([^\"',\s}]+)"
)
_URI_PASSWORD_PATTERN = re.compile(r"(://[^:/\s]+:)[^@\s]+(@)")


def current_request_id() -> str:
    """返回当前线程上下文中的 Wren 请求标识。"""
    return _REQUEST_ID.get()


def sanitize_message(message: Any) -> str:
    """脱敏异常文本中的凭据，避免诊断日志和内部响应泄露密钥。"""
    value = str(message)
    value = _BEARER_PATTERN.sub("Bearer ***", value)
    value = _SECRET_FIELD_PATTERN.sub(r"\1***", value)
    return _URI_PASSWORD_PATTERN.sub(r"\1***\2", value)


@contextmanager
def request_scope(request_id: str) -> Iterator[None]:
    """绑定一次 HTTP 请求标识，并在请求完成后恢复原上下文。"""
    token = _REQUEST_ID.set(request_id)
    try:
        yield
    finally:
        _REQUEST_ID.reset(token)


def traced(function: _FUNCTION) -> _FUNCTION:
    """记录方法入口、出口、耗时和异常摘要，且不记录参数与返回值。"""
    if getattr(function, "__wren_traced__", False):
        return function

    @wraps(function)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        method = f"{function.__module__}.{function.__qualname__}"
        request_id = current_request_id()
        started = time.perf_counter()
        _TRACE_LOGGER.info("Wren 方法进入: requestId=%s, method=%s", request_id, method)
        try:
            result = function(*args, **kwargs)
        except Exception as exception:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            _TRACE_LOGGER.error(
                "Wren 方法异常: requestId=%s, method=%s, elapsedMs=%s, type=%s, message=%s",
                request_id,
                method,
                elapsed_ms,
                type(exception).__name__,
                sanitize_message(exception),
            )
            raise
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        _TRACE_LOGGER.info(
            "Wren 方法退出: requestId=%s, method=%s, elapsedMs=%s, status=SUCCESS",
            request_id,
            method,
            elapsed_ms,
        )
        return result

    setattr(wrapper, "__wren_traced__", True)
    return wrapper  # type: ignore[return-value]


def trace_methods(*, exclude: set[str] | None = None) -> Callable[[type], type]:
    """包装类中定义的全部方法，跳过无业务意义的魔术方法。"""
    excluded = exclude or set()

    def decorate(target: type) -> type:
        for name, member in list(vars(target).items()):
            if name in excluded or (name.startswith("__") and name != "__init__"):
                continue
            if isinstance(member, staticmethod):
                setattr(target, name, staticmethod(traced(member.__func__)))
            elif isinstance(member, classmethod):
                setattr(target, name, classmethod(traced(member.__func__)))
            elif inspect.isfunction(member):
                setattr(target, name, traced(member))
        return target

    return decorate


def trace_module_functions(namespace: dict[str, Any], module_name: str,
                           exclude: set[str] | None = None) -> None:
    """包装模块内定义的全部函数，不触碰导入的第三方函数。"""
    excluded = exclude or set()
    for name, member in list(namespace.items()):
        if name in excluded or not inspect.isfunction(member):
            continue
        if member.__module__ == module_name:
            namespace[name] = traced(member)
