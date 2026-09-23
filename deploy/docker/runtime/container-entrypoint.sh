#!/usr/bin/env bash
set -Eeuo pipefail

STATE_ROOT="${ENTERPRISE_MCP_STATE_ROOT:-/var/lib/enterprise-mcp}"
mkdir -p "$STATE_ROOT/wren" "$STATE_ROOT/upgrade"

JAVA_ARGS=(
  "-XX:MaxRAMPercentage=${ENTERPRISE_MCP_JAVA_MAX_RAM_PERCENTAGE:-60.0}"
  "-Djava.io.tmpdir=/tmp"
)
if [[ -n "${ENTERPRISE_MCP_JAVA_OPTS:-}" ]]; then
  # 仅解析由客户 root 管理的部署配置，不接收 HTTP 请求中的 JVM 参数。
  read -r -a EXTRA_JAVA_ARGS <<< "$ENTERPRISE_MCP_JAVA_OPTS"
  JAVA_ARGS+=("${EXTRA_JAVA_ARGS[@]}")
fi

exec java "${JAVA_ARGS[@]}" -jar /opt/enterprise-mcp/app/enterprise-mcp.jar "$@"

