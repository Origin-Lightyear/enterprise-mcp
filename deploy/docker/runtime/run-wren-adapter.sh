#!/usr/bin/env bash
set -Eeuo pipefail

export WREN_ADAPTER_HOST="127.0.0.1"
export WREN_ADAPTER_PORT="${ENTERPRISE_MCP_WREN_PORT:-8091}"
export WREN_PROJECT_ROOT="${ENTERPRISE_MCP_WREN_PROJECT_ROOT:-/var/lib/enterprise-mcp/wren}"
export WREN_INSTANCE_DIR="${ENTERPRISE_MCP_WREN_INSTANCE_DIR:-$WREN_PROJECT_ROOT}"
export WREN_ADAPTER_SERVICE_TOKEN="${ENTERPRISE_MCP_WREN_ADAPTER_SERVICE_TOKEN:-}"

mkdir -p "$WREN_PROJECT_ROOT" "$WREN_INSTANCE_DIR"
/opt/enterprise-mcp/wren/venv/bin/python /opt/enterprise-mcp/wren/launcher/ensure_dependencies.py \
  /opt/enterprise-mcp/wren/upstream
exec /opt/enterprise-mcp/wren/venv/bin/python /opt/enterprise-mcp/wren/adapter/server.py
