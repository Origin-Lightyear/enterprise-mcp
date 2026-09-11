#!/usr/bin/env bash
set -Eeuo pipefail

usage() { echo "用法: $0 --instance-id ID (--package FILE | --download-url URL | --version VERSION)" >&2; exit 2; }
INSTANCE_ID=""; PACKAGE_FILE=""; DOWNLOAD_URL=""; TARGET_VERSION=""; BASE_DIR="/opt/enterprise-mcp"; ETC_DIR="/etc/enterprise-mcp"; HEALTH_TIMEOUT=60
EXPECTED_SHA256=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --instance-id) INSTANCE_ID="$2"; shift 2 ;;
    --package) PACKAGE_FILE="$2"; shift 2 ;;
    --download-url) DOWNLOAD_URL="$2"; shift 2 ;;
    --version) TARGET_VERSION="$2"; shift 2 ;;
    --base-dir) BASE_DIR="$2"; shift 2 ;;
    --etc-dir) ETC_DIR="$2"; shift 2 ;;
    --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
    --sha256) EXPECTED_SHA256="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo "必须使用 root 执行" >&2; exit 1; }
[[ "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || usage
[[ -n "$PACKAGE_FILE" || -n "$DOWNLOAD_URL" || -n "$TARGET_VERSION" ]] || usage
[[ -z "$PACKAGE_FILE" || -z "$DOWNLOAD_URL" ]] || usage
[[ -z "$PACKAGE_FILE" || -z "$TARGET_VERSION" ]] || usage
command -v jq >/dev/null || { echo "缺少 jq" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "缺少 sha256sum" >&2; exit 1; }
command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
if [[ -n "$EXPECTED_SHA256" && -n "$PACKAGE_FILE" ]]; then
  echo "$EXPECTED_SHA256  $PACKAGE_FILE" | sha256sum -c - >/dev/null || { echo "安装包 SHA-256 校验失败" >&2; exit 1; }
fi

INSTANCE_ROOT="$BASE_DIR/$INSTANCE_ID"; RELEASE_ROOT="$INSTANCE_ROOT/releases"; CURRENT_LINK="$INSTANCE_ROOT/current"; PREVIOUS_LINK="$INSTANCE_ROOT/previous"; SERVICE_NAME="enterprise-mcp-$INSTANCE_ID"
[[ -d "$RELEASE_ROOT" && -L "$CURRENT_LINK" ]] || { echo "实例未安装" >&2; exit 1; }
CURRENT_DIR="$(readlink -f "$CURRENT_LINK")"; CURRENT_VERSION="$(basename "$CURRENT_DIR")"
CURRENT_JAR_NAME="$(jq -er '.jarName' "$CURRENT_DIR/manifest.json")"
TMP_DIR="$(mktemp -d)"; cleanup() { rm -rf "$TMP_DIR"; }; trap cleanup EXIT

if [[ -n "$DOWNLOAD_URL" ]]; then
  PACKAGE_FILE="$TMP_DIR/update.tar.gz"
  curl -fL --retry 2 --connect-timeout 10 --max-time 600 "$DOWNLOAD_URL" -o "$PACKAGE_FILE"
  [[ -n "$EXPECTED_SHA256" ]] || { echo "远程安装包必须提供 SHA-256" >&2; exit 1; }
  echo "$EXPECTED_SHA256  $PACKAGE_FILE" | sha256sum -c - >/dev/null || { echo "安装包 SHA-256 校验失败" >&2; exit 1; }
fi

if [[ -n "$PACKAGE_FILE" || -n "$DOWNLOAD_URL" ]]; then
  tar -xzf "$PACKAGE_FILE" -C "$TMP_DIR"
  MANIFEST="$TMP_DIR/manifest.json"; [[ -f "$MANIFEST" ]] || { echo "安装包缺少 manifest.json" >&2; exit 1; }
  MCP_ID="$(jq -er '.platformMcpId | tostring' "$MANIFEST")"; VERSION="$(jq -er '.version' "$MANIFEST")"; JAR_NAME="$(jq -er '.jarName' "$MANIFEST")"; ARTIFACT_SHA256="$(jq -er '.artifactSha256' "$MANIFEST")"; HEALTH_TYPE="$(jq -er '.health.type' "$MANIFEST")"; HEALTH_PATH="$(jq -er '.health.path' "$MANIFEST")"; EXPECTED_STATUS="$(jq -er '.health.expectedStatus // 200' "$MANIFEST")"
  EXPECTED_MCP_ID="$(awk -F= '/^platform\.mcp\.id=/{print $2}' "$ETC_DIR/$INSTANCE_ID/instance.properties" 2>/dev/null || true)"
  MANIFEST_INSTANCE_ID="$(jq -er '.instanceId // empty' "$MANIFEST")"
  [[ -z "$MANIFEST_INSTANCE_ID" || "$MANIFEST_INSTANCE_ID" == "$INSTANCE_ID" ]] || { echo "manifest 实例编号不匹配实例" >&2; exit 1; }
  [[ -n "$EXPECTED_MCP_ID" && "$MCP_ID" == "$EXPECTED_MCP_ID" ]] || { echo "manifest MCP 主键不匹配实例" >&2; exit 1; }
  [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$JAR_NAME" != */* && "$JAR_NAME" != *..* ]] || { echo "manifest 身份字段非法" >&2; exit 1; }
  [[ "$JAR_NAME" == "$CURRENT_JAR_NAME" ]] || { echo "不同版本必须使用相同 jarName" >&2; exit 1; }
  RELEASE_DIR="$RELEASE_ROOT/$VERSION"; [[ ! -e "$RELEASE_DIR" ]] || { echo "版本已安装: $VERSION" >&2; exit 1; }
  echo "$ARTIFACT_SHA256  $TMP_DIR/$JAR_NAME" | sha256sum -c - >/dev/null || { echo "manifest artifactSha256 校验失败" >&2; exit 1; }
  install -d -m 0755 "$RELEASE_DIR"; install -m 0644 "$TMP_DIR/$JAR_NAME" "$RELEASE_DIR/$JAR_NAME"; install -m 0640 "$MANIFEST" "$RELEASE_DIR/manifest.json"
else
  VERSION="$TARGET_VERSION"; [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "版本号非法" >&2; exit 1; }
  RELEASE_DIR="$RELEASE_ROOT/$VERSION"; [[ -f "$RELEASE_DIR/manifest.json" ]] || { echo "本地版本不存在: $VERSION" >&2; exit 1; }
  HEALTH_TYPE="$(jq -er '.health.type' "$RELEASE_DIR/manifest.json")"; HEALTH_PATH="$(jq -er '.health.path' "$RELEASE_DIR/manifest.json")"; EXPECTED_STATUS="$(jq -er '.health.expectedStatus // 200' "$RELEASE_DIR/manifest.json")"
fi

[[ "$HEALTH_TYPE" == "HTTP" ]] || { echo "当前版本仅支持 HTTP 健康检查" >&2; exit 1; }

PORT="$(awk -F: '/^server:/{found=1} found && /port:/{gsub(/ /,"",$2); print $2; exit}' "$ETC_DIR/$INSTANCE_ID/application.yml")"
[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "无法从 application.yml 读取 server.port" >&2; exit 1; }
link_target() { local target="$1"; local link="$2"; local temp="${link}.tmp.$$"; ln -s "$target" "$temp"; mv -Tf "$temp" "$link"; chown -h root:root "$link"; }
link_target "$CURRENT_DIR" "$PREVIOUS_LINK"
link_target "$RELEASE_DIR" "$CURRENT_LINK"
systemctl restart "$SERVICE_NAME.service"

deadline=$((SECONDS + HEALTH_TIMEOUT))
healthy=0
while (( SECONDS < deadline )); do
  if systemctl is-active --quiet "$SERVICE_NAME.service" && [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT$HEALTH_PATH" || true)" == "$EXPECTED_STATUS" ]]; then healthy=1; break; fi
  sleep 2
done
if (( healthy == 0 )); then
  echo "升级健康检查失败，开始回滚" >&2
  link_target "$CURRENT_DIR" "$CURRENT_LINK"; systemctl restart "$SERVICE_NAME.service"
  exit 1
fi
echo "已切换 $SERVICE_NAME: $CURRENT_VERSION -> $VERSION"
