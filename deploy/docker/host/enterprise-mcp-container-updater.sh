#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "用法: $0 (--package FILE [--sha256 HASH] | --request-file FILE)" >&2
  exit 2
}

INSTANCE_ID=""; TENANT_ID=""; PACKAGE=""; EXPECTED_SHA256=""; REQUEST_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --sha256) EXPECTED_SHA256="${2,,}"; shift 2 ;;
    --request-file) REQUEST_FILE="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -n "$PACKAGE" && -z "$REQUEST_FILE" || -z "$PACKAGE" && -n "$REQUEST_FILE" ]] || usage
[[ -z "$EXPECTED_SHA256" || "$EXPECTED_SHA256" =~ ^[a-f0-9]{64}$ ]] || usage

CONFIG_DIR="/etc/enterprise-mcp/$INSTANCE_ID"
STATE_DIR="/var/lib/enterprise-mcp/$INSTANCE_ID"
ENV_FILE="$CONFIG_DIR/container.env"
CONTAINER_NAME="enterprise-mcp-$INSTANCE_ID"
LOCK_FILE="$STATE_DIR/upgrade.lock"
STATUS_FILE="$STATE_DIR/upgrade-state.json"
WORK_DIR=""
DOWNLOADED_PACKAGE=""

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令: $1" >&2; exit 1; }
}

write_status() {
  local status="$1" message="$2"
  jq -n --arg status "$status" --arg message "$message" --arg updatedAt "$(date -u +%FT%TZ)" \
    '{status:$status,message:$message,updatedAt:$updatedAt}' > "$STATUS_FILE.tmp"
  chmod 0640 "$STATUS_FILE.tmp"
  mv -f "$STATUS_FILE.tmp" "$STATUS_FILE"
}

cleanup() {
  [[ -z "$WORK_DIR" || ! -d "$WORK_DIR" ]] || rm -rf -- "$WORK_DIR"
  [[ -z "$DOWNLOADED_PACKAGE" || ! -f "$DOWNLOADED_PACKAGE" ]] || rm -f -- "$DOWNLOADED_PACKAGE"
}
trap cleanup EXIT

for command_name in curl docker flock jq sha256sum tar; do require_command "$command_name"; done
[[ -f "$ENV_FILE" ]] || { echo "容器环境文件不存在: $ENV_FILE" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
TENANT_ID="${SAAS_TENANT_ID:-}"
INSTANCE_ID="${ENTERPRISE_MCP_INSTANCE_ID:-}"
[[ "$TENANT_ID" =~ ^[0-9]+$ && "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] \
  || { echo "container.env 身份配置非法" >&2; exit 1; }
mkdir -p "$STATE_DIR"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "实例 $INSTANCE_ID 已有升级任务运行中" >&2; exit 1; }

if [[ -n "$REQUEST_FILE" ]]; then
  [[ -f "$REQUEST_FILE" ]] || { echo "升级请求文件不存在: $REQUEST_FILE" >&2; exit 1; }
  PROCESSING_FILE="$REQUEST_FILE.processing.$$"
  mv "$REQUEST_FILE" "$PROCESSING_FILE"
  REQUEST_INSTANCE_ID="$(jq -er '.instanceId' "$PROCESSING_FILE")"
  REQUEST_VERSION="$(jq -er '.version' "$PROCESSING_FILE")"
  DOWNLOAD_URL="$(jq -er '.downloadUrl' "$PROCESSING_FILE")"
  EXPECTED_SHA256="$(jq -er '.sha256 | ascii_downcase' "$PROCESSING_FILE")"
  rm -f "$PROCESSING_FILE"
  [[ "$REQUEST_INSTANCE_ID" == "$INSTANCE_ID" ]] || { write_status FAILED "升级请求实例不匹配"; exit 1; }
  [[ "$REQUEST_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { write_status FAILED "升级请求版本非法"; exit 1; }
  [[ "$EXPECTED_SHA256" =~ ^[a-f0-9]{64}$ ]] || { write_status FAILED "升级请求摘要非法"; exit 1; }
  [[ "$DOWNLOAD_URL" == https://* || "$DOWNLOAD_URL" == http://* ]] || { write_status FAILED "升级下载地址非法"; exit 1; }
  DOWNLOADED_PACKAGE="$(mktemp --tmpdir enterprise-mcp-package.XXXXXX.tar.gz)"
  write_status DOWNLOADING "正在下载容器发布包"
  curl --fail --location --silent --show-error --connect-timeout 10 --max-time 3600 \
    --output "$DOWNLOADED_PACKAGE" "$DOWNLOAD_URL"
  PACKAGE="$DOWNLOADED_PACKAGE"
fi

[[ -f "$PACKAGE" ]] || { write_status FAILED "容器发布包不存在"; exit 1; }
write_status VERIFYING "正在校验容器发布包"
ACTUAL_SHA256="$(sha256sum "$PACKAGE" | cut -d' ' -f1)"
[[ -z "$EXPECTED_SHA256" || "$ACTUAL_SHA256" == "$EXPECTED_SHA256" ]] \
  || { write_status FAILED "容器发布包摘要不匹配"; exit 1; }

WORK_DIR="$(mktemp -d)"
mapfile -t ENTRIES < <(tar -tzf "$PACKAGE")
mapfile -t ENTRY_TYPES < <(tar -tvzf "$PACKAGE" | awk '{print substr($1, 1, 1)}')
[[ ${#ENTRIES[@]} -eq 2 ]] || { write_status FAILED "容器发布包只能包含 manifest 和镜像归档"; exit 1; }
[[ "${ENTRIES[0]}" == "manifest.json" && "${ENTRIES[1]}" == "enterprise-mcp-image.tar" ]] \
  || { write_status FAILED "容器发布包目录结构非法"; exit 1; }
[[ ${#ENTRY_TYPES[@]} -eq 2 && "${ENTRY_TYPES[0]}" == "-" && "${ENTRY_TYPES[1]}" == "-" ]] \
  || { write_status FAILED "容器发布包条目必须是普通文件"; exit 1; }
tar -xzf "$PACKAGE" -C "$WORK_DIR" --no-same-owner --no-same-permissions
MANIFEST="$WORK_DIR/manifest.json"
IMAGE_ARCHIVE="$WORK_DIR/enterprise-mcp-image.tar"
[[ -f "$MANIFEST" && ! -L "$MANIFEST" && -f "$IMAGE_ARCHIVE" && ! -L "$IMAGE_ARCHIVE" ]] \
  || { write_status FAILED "容器发布包内容不完整"; exit 1; }

MANIFEST_TYPE="$(jq -er '.artifactType' "$MANIFEST")"
MANIFEST_TENANT_ID="$(jq -er '.tenantId' "$MANIFEST")"
MANIFEST_INSTANCE_ID="$(jq -er '.instanceId' "$MANIFEST")"
MANIFEST_VERSION="$(jq -er '.version' "$MANIFEST")"
IMAGE_REPOSITORY="$(jq -er '.image.repository' "$MANIFEST")"
IMAGE_TAG="$(jq -er '.image.tag' "$MANIFEST")"
EXPECTED_IMAGE_ID="$(jq -er '.image.imageId' "$MANIFEST")"
EXPECTED_IMAGE_SHA256="$(jq -er '.image.archiveSha256 | ascii_downcase' "$MANIFEST")"
HEALTH_PATH="$(jq -er '.health.path' "$MANIFEST")"
EXPECTED_STATUS="$(jq -er '.health.expectedStatus' "$MANIFEST")"
[[ "$MANIFEST_TYPE" == "DOCKER_IMAGE" && "$MANIFEST_TENANT_ID" == "$TENANT_ID" \
    && "$MANIFEST_INSTANCE_ID" == "$INSTANCE_ID" ]] \
  || { write_status FAILED "容器发布包身份不匹配"; exit 1; }
[[ "$MANIFEST_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$IMAGE_TAG" == "$MANIFEST_VERSION" ]] \
  || { write_status FAILED "容器发布包版本非法"; exit 1; }
if [[ -n "${REQUEST_VERSION:-}" && "$MANIFEST_VERSION" != "$REQUEST_VERSION" ]]; then
  write_status FAILED "升级请求版本与发布包不匹配"; exit 1
fi
[[ "$IMAGE_REPOSITORY" =~ ^[a-z0-9][a-z0-9._/-]*$ && "$EXPECTED_IMAGE_ID" =~ ^sha256:[a-f0-9]{64}$ \
    && "$EXPECTED_IMAGE_SHA256" =~ ^[a-f0-9]{64}$ ]] \
  || { write_status FAILED "容器镜像元数据非法"; exit 1; }
[[ "$HEALTH_PATH" =~ ^/[a-zA-Z0-9._/-]*$ && "$EXPECTED_STATUS" =~ ^[0-9]{3}$ ]] \
  || { write_status FAILED "健康检查配置非法"; exit 1; }
[[ "$(sha256sum "$IMAGE_ARCHIVE" | cut -d' ' -f1)" == "$EXPECTED_IMAGE_SHA256" ]] \
  || { write_status FAILED "镜像归档摘要不匹配"; exit 1; }

docker load --input "$IMAGE_ARCHIVE" >/dev/null
IMAGE_REF="$IMAGE_REPOSITORY:$IMAGE_TAG"
LOADED_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_REF")"
[[ "$LOADED_IMAGE_ID" == "$EXPECTED_IMAGE_ID" ]] || { write_status FAILED "加载后的镜像 ID 不匹配"; exit 1; }

HOST_PORT="$(sed -n 's/^ENTERPRISE_MCP_PORT=//p' "$ENV_FILE" | tail -n 1)"
HOST_PORT="${HOST_PORT:-6001}"
[[ "$HOST_PORT" =~ ^[0-9]+$ && "$HOST_PORT" -ge 1 && "$HOST_PORT" -le 65535 ]] \
  || { write_status FAILED "container.env 中的端口非法"; exit 1; }

BACKUP_NAME="$CONTAINER_NAME-rollback"
docker rm -f "$BACKUP_NAME" >/dev/null 2>&1 || true
if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  write_status SWITCHING "正在切换客户 MCP 容器"
  docker stop --time 30 "$CONTAINER_NAME" >/dev/null
  docker rename "$CONTAINER_NAME" "$BACKUP_NAME"
fi

rollback() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  if docker container inspect "$BACKUP_NAME" >/dev/null 2>&1; then
    docker rename "$BACKUP_NAME" "$CONTAINER_NAME"
    docker start "$CONTAINER_NAME" >/dev/null
    write_status ROLLED_BACK "新版本健康检查失败，已恢复旧容器"
  else
    write_status FAILED "首次安装的容器健康检查失败"
  fi
}

docker run --detach --name "$CONTAINER_NAME" --restart unless-stopped \
  --env-file "$ENV_FILE" \
  --publish "${HOST_PORT}:${HOST_PORT}" \
  --volume "$STATE_DIR:/var/lib/enterprise-mcp" \
  "$IMAGE_REF" >/dev/null || { rollback; exit 1; }

write_status HEALTH_CHECKING "正在等待 Runtime 与 Wren 就绪"
HEALTH_URL="http://127.0.0.1:${HOST_PORT}${HEALTH_PATH}"
HEALTHY=false
for _ in $(seq 1 60); do
  STATUS_CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 "$HEALTH_URL" || true)"
  if [[ "$STATUS_CODE" == "$EXPECTED_STATUS" ]]; then HEALTHY=true; break; fi
  sleep 2
done
if [[ "$HEALTHY" != true ]]; then rollback; exit 1; fi

docker rm -f "$BACKUP_NAME" >/dev/null 2>&1 || true
write_status SUCCEEDED "客户 MCP 已升级到 $MANIFEST_VERSION"
echo "客户 MCP $INSTANCE_ID 已运行版本 $MANIFEST_VERSION"
