#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$SCRIPT_DIR/../project-root}"
PORT="${2:-8091}"
INSTANCE_DIR="${3:-$PROJECT_ROOT}"
SERVICE_TOKEN="${4:-}"
REQUESTED_PYTHON="${5:-${WREN_PYTHON_EXECUTABLE:-}}"

resolve_python() {
  local candidate
  if [[ -n "$REQUESTED_PYTHON" ]]; then
    if [[ -x "$REQUESTED_PYTHON" ]]; then
      printf '%s\n' "$REQUESTED_PYTHON"
      return 0
    fi
    if command -v "$REQUESTED_PYTHON" >/dev/null 2>&1; then
      command -v "$REQUESTED_PYTHON"
      return 0
    fi
    echo "指定的 Wren Python 解释器不存在: $REQUESTED_PYTHON" >&2
    return 1
  fi

  for candidate in \
    "$SCRIPT_DIR/../../../.venv-wrenai/bin/python" \
    "$SCRIPT_DIR/../.venv/bin/python"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return 0
    fi
  done
  echo "未找到可用的 Python 解释器。请先运行 Wren 安装器，或通过 WREN_PYTHON_EXECUTABLE 指定虚拟环境中的 python。" >&2
  return 1
}

PYTHON_EXECUTABLE="$(resolve_python)"
SERVER_PATH="$SCRIPT_DIR/../adapter/server.py"
export PYTHONPATH="$SCRIPT_DIR/../upstream/src"
mkdir -p "$PROJECT_ROOT"
export WREN_PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
mkdir -p "$INSTANCE_DIR"
export WREN_INSTANCE_DIR="$(cd "$INSTANCE_DIR" && pwd)"
export WREN_ADAPTER_SERVICE_TOKEN="$SERVICE_TOKEN"
export WREN_ADAPTER_HOST="127.0.0.1"
export WREN_ADAPTER_PORT="$PORT"

if ! PREFLIGHT_OUTPUT="$("$PYTHON_EXECUTABLE" -c \
  "import runpy, sys; from pathlib import Path; sys.path.insert(0, str(Path(sys.argv[1]).parent)); runpy.run_path(sys.argv[1], run_name='wren_adapter_preflight')" \
  "$SERVER_PATH" 2>&1)"; then
  echo "Wren Adapter 依赖检查失败。当前解释器: $PYTHON_EXECUTABLE。请运行安装器或指定已安装 customer/wren/upstream 的虚拟环境。" >&2
  printf '%s\n' "$PREFLIGHT_OUTPUT" >&2
  exit 1
fi

if [[ "${WREN_LAUNCHER_CHECK_ONLY:-0}" == "1" ]]; then
  echo "Wren Adapter 依赖检查通过: $PYTHON_EXECUTABLE"
  exit 0
fi

exec "$PYTHON_EXECUTABLE" "$SERVER_PATH"
