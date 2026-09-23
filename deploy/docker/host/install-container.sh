#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "用法: sudo $0 --package FILE --env-file FILE [--sha256 HASH]" >&2
  exit 2
}

INSTANCE_ID=""; TENANT_ID=""; PACKAGE=""; SOURCE_ENV_FILE=""; SHA256=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --env-file) SOURCE_ENV_FILE="$2"; shift 2 ;;
    --sha256) SHA256="${2,,}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$EUID" -eq 0 ]] || { echo "请使用 root 权限执行安装" >&2; exit 1; }
[[ -f "$PACKAGE" && -f "$SOURCE_ENV_FILE" ]] || usage
[[ -z "$SHA256" || "$SHA256" =~ ^[a-f0-9]{64}$ ]] || usage
for command_name in curl docker flock jq sha256sum tar; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "缺少命令: $command_name" >&2; exit 1; }
done

set -a
# shellcheck disable=SC1090
. "$SOURCE_ENV_FILE"
set +a
TENANT_ID="${SAAS_TENANT_ID:-}"
INSTANCE_ID="${ENTERPRISE_MCP_INSTANCE_ID:-}"
[[ "$TENANT_ID" =~ ^[0-9]+$ && "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] \
  || { echo "container.env 必须提供合法的 SAAS_TENANT_ID 和 ENTERPRISE_MCP_INSTANCE_ID" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="/etc/enterprise-mcp/$INSTANCE_ID"
STATE_DIR="/var/lib/enterprise-mcp/$INSTANCE_ID"
UPDATER="/usr/local/libexec/enterprise-mcp-container-updater.sh"
ENV_FILE="$CONFIG_DIR/container.env"
REQUEST_FILE="$STATE_DIR/upgrade/request.json"
UNIT_SUFFIX="enterprise-mcp-container-$INSTANCE_ID"

install -d -o root -g root -m 0750 "$CONFIG_DIR"
install -d -o 10001 -g 10001 -m 0750 "$STATE_DIR" "$STATE_DIR/wren" "$STATE_DIR/upgrade"
install -o root -g root -m 0755 "$SCRIPT_DIR/enterprise-mcp-container-updater.sh" "$UPDATER"
install -o root -g root -m 0640 "$SOURCE_ENV_FILE" "$ENV_FILE"

# Wren 内部密钥由安装器生成，避免把容器内部实现参数暴露给客户配置。
generate_secret() {
  head -c 32 /dev/urandom | sha256sum | cut -d' ' -f1
}
if ! grep -q '^ENTERPRISE_MCP_WREN_ADAPTER_SERVICE_TOKEN=' "$ENV_FILE"; then
  printf '\nENTERPRISE_MCP_WREN_ADAPTER_SERVICE_TOKEN=%s\n' "$(generate_secret)" >> "$ENV_FILE"
fi
if ! grep -q '^ENTERPRISE_MCP_WREN_ADMIN_TOKEN=' "$ENV_FILE"; then
  printf 'ENTERPRISE_MCP_WREN_ADMIN_TOKEN=%s\n' "$(generate_secret)" >> "$ENV_FILE"
fi

cat > "/etc/systemd/system/$UNIT_SUFFIX.path" <<EOF
[Unit]
Description=Watch Enterprise MCP container upgrade request for $INSTANCE_ID

[Path]
PathExists=$REQUEST_FILE
Unit=$UNIT_SUFFIX.service

[Install]
WantedBy=multi-user.target
EOF

cat > "/etc/systemd/system/$UNIT_SUFFIX.service" <<EOF
[Unit]
Description=Upgrade Enterprise MCP container for $INSTANCE_ID
After=docker.service network-online.target
Requires=docker.service

[Service]
Type=oneshot
ExecStart=$UPDATER --request-file $REQUEST_FILE
User=root
Group=root
EOF

systemctl daemon-reload
systemctl enable --now "$UNIT_SUFFIX.path"

ARGS=(--package "$PACKAGE")
[[ -z "$SHA256" ]] || ARGS+=(--sha256 "$SHA256")
"$UPDATER" "${ARGS[@]}"
echo "客户 MCP 容器已安装；后续可继续使用 Tenant 的 MCP 升级按钮。"
