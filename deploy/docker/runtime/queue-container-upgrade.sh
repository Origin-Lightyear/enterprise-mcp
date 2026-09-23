#!/usr/bin/env bash
set -Eeuo pipefail

DOWNLOAD_URL=""
VERSION=""
SHA256=""
REQUEST_FILE="${ENTERPRISE_MCP_UPGRADE_REQUEST_FILE:-/var/lib/enterprise-mcp/upgrade/request.json}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --download-url) DOWNLOAD_URL="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --sha256) SHA256="$2"; shift 2 ;;
    *) echo "不支持的升级参数" >&2; exit 2 ;;
  esac
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "升级版本号非法" >&2; exit 2; }
[[ "$SHA256" =~ ^[a-fA-F0-9]{64}$ ]] || { echo "升级包 SHA-256 非法" >&2; exit 2; }
[[ "$DOWNLOAD_URL" == http://* || "$DOWNLOAD_URL" == https://* ]] || { echo "升级下载地址非法" >&2; exit 2; }

REQUEST_DIR="$(dirname "$REQUEST_FILE")"
mkdir -p "$REQUEST_DIR"
INSTANCE_ID="${ENTERPRISE_MCP_INSTANCE_ID:-}"
[[ "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || { echo "运行时实例配置非法" >&2; exit 2; }
TEMP_FILE="$REQUEST_FILE.tmp.$$"
python3 - "$INSTANCE_ID" "$DOWNLOAD_URL" "$VERSION" "${SHA256,,}" > "$TEMP_FILE" <<'PY'
import json
import sys

print(json.dumps({
    "instanceId": sys.argv[1],
    "downloadUrl": sys.argv[2],
    "version": sys.argv[3],
    "sha256": sys.argv[4],
}, ensure_ascii=True, separators=(",", ":")))
PY
chmod 0600 "$TEMP_FILE"
mv -f "$TEMP_FILE" "$REQUEST_FILE"
