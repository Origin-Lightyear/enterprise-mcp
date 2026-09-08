#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "用法: $0 --instance-id ID --platform-mcp-id ID --tenant-id ID --package FILE --config FILE" >&2
  exit 2
}

INSTANCE_ID=""
PLATFORM_MCP_ID=""
TENANT_ID=""
PACKAGE_FILE=""
CONFIG_FILE=""
EXPECTED_SHA256=""
BASE_DIR="/opt/enterprise-mcp"
ETC_DIR="/etc/enterprise-mcp"
STATE_DIR="/var/lib/enterprise-mcp"
SERVICE_DIR="/etc/systemd/system"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --instance-id) INSTANCE_ID="$2"; shift 2 ;;
    --platform-mcp-id) PLATFORM_MCP_ID="$2"; shift 2 ;;
    --tenant-id) TENANT_ID="$2"; shift 2 ;;
    --package) PACKAGE_FILE="$2"; shift 2 ;;
    --config) CONFIG_FILE="$2"; shift 2 ;;
    --sha256) EXPECTED_SHA256="$2"; shift 2 ;;
    --base-dir) BASE_DIR="$2"; shift 2 ;;
    --etc-dir) ETC_DIR="$2"; shift 2 ;;
    --state-dir) STATE_DIR="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ $EUID -eq 0 ]] || { echo "必须使用 root 执行" >&2; exit 1; }
[[ -n "$INSTANCE_ID" && "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || usage
[[ -n "$PLATFORM_MCP_ID" && "$PLATFORM_MCP_ID" =~ ^[0-9]+$ ]] || usage
[[ -n "$TENANT_ID" && "$TENANT_ID" =~ ^[0-9]+$ ]] || usage
[[ -f "$PACKAGE_FILE" && -f "$CONFIG_FILE" ]] || usage
command -v jq >/dev/null || { echo "缺少 jq" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "缺少 sha256sum" >&2; exit 1; }
command -v java >/dev/null || { echo "缺少 Java 21" >&2; exit 1; }
if [[ -n "$EXPECTED_SHA256" ]]; then
  echo "$EXPECTED_SHA256  $PACKAGE_FILE" | sha256sum -c - >/dev/null || { echo "安装包 SHA-256 校验失败" >&2; exit 1; }
fi

INSTANCE_ROOT="$BASE_DIR/$INSTANCE_ID"
RELEASE_ROOT="$INSTANCE_ROOT/releases"
CURRENT_LINK="$INSTANCE_ROOT/current"
SERVICE_NAME="enterprise-mcp-$INSTANCE_ID"
USER_NAME="enterprise-mcp-$INSTANCE_ID"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

tar -xzf "$PACKAGE_FILE" -C "$TMP_DIR"
MANIFEST="$TMP_DIR/manifest.json"
[[ -f "$MANIFEST" ]] || { echo "安装包缺少 manifest.json" >&2; exit 1; }
MANIFEST_MCP_ID="$(jq -er '.platformMcpId | tostring' "$MANIFEST")"
VERSION="$(jq -er '.version' "$MANIFEST")"
JAR_NAME="$(jq -er '.jarName' "$MANIFEST")"
[[ "$MANIFEST_MCP_ID" == "$PLATFORM_MCP_ID" ]] || { echo "manifest MCP 主键不匹配" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "manifest 版本号非法" >&2; exit 1; }
[[ "$JAR_NAME" != */* && "$JAR_NAME" != *..* ]] || { echo "manifest jarName 非法" >&2; exit 1; }
JAR_FILE="$TMP_DIR/$JAR_NAME"
[[ -f "$JAR_FILE" ]] || { echo "manifest 指定的 jar 不存在" >&2; exit 1; }

install -d -m 0755 "$RELEASE_ROOT" "$ETC_DIR/$INSTANCE_ID" "$STATE_DIR/$INSTANCE_ID"
id "$USER_NAME" >/dev/null 2>&1 || useradd --system --home-dir "$INSTANCE_ROOT" --shell /usr/sbin/nologin "$USER_NAME"
RELEASE_DIR="$RELEASE_ROOT/$VERSION"
[[ ! -e "$RELEASE_DIR" ]] || { echo "版本已存在: $VERSION" >&2; exit 1; }
install -d -o "$USER_NAME" -g "$USER_NAME" -m 0755 "$RELEASE_DIR"
install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$JAR_FILE" "$RELEASE_DIR/$JAR_NAME"
install -o root -g root -m 0640 "$MANIFEST" "$RELEASE_DIR/manifest.json"
ARTIFACT_SHA256="$(jq -er '.artifactSha256' "$MANIFEST")"
echo "$ARTIFACT_SHA256  $JAR_FILE" | sha256sum -c - >/dev/null || { echo "manifest artifactSha256 校验失败" >&2; exit 1; }
install -o root -g root -m 0640 "$CONFIG_FILE" "$ETC_DIR/$INSTANCE_ID/application.yml"
cat > "$ETC_DIR/$INSTANCE_ID/instance.properties" <<EOF
platform.mcp.id=$PLATFORM_MCP_ID
tenant.id=$TENANT_ID
EOF
chmod 0640 "$ETC_DIR/$INSTANCE_ID/instance.properties"

cat > "$SERVICE_DIR/$SERVICE_NAME.service" <<EOF
[Unit]
Description=Enterprise MCP $INSTANCE_ID
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Group=$USER_NAME
WorkingDirectory=$INSTANCE_ROOT
ExecStart=/usr/bin/java -jar $CURRENT_LINK/$JAR_NAME --spring.config.additional-location=file:$ETC_DIR/$INSTANCE_ID/application.yml
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

ln -sfn "$RELEASE_DIR" "$INSTANCE_ROOT/current"
chown -h root:root "$CURRENT_LINK"
chown -R "$USER_NAME:$USER_NAME" "$INSTANCE_ROOT"
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME.service"
echo "已安装 $SERVICE_NAME $VERSION"
