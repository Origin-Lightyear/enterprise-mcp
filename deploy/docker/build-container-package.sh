#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "用法: $0 --env-file FILE [--image-repository NAME] [--output-dir DIR] [--platform linux/amd64] [--wren-extras LIST]" >&2
  exit 2
}

ENV_FILE=""; VERSION=""; TENANT_ID=""; INSTANCE_ID=""
IMAGE_REPOSITORY="origin-lightyear/enterprise-mcp"
OUTPUT_DIR=""; TARGET_PLATFORM="linux/amd64"; WREN_EXTRAS="mysql,postgres,mssql"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --image-repository) IMAGE_REPOSITORY="$2"; shift 2 ;;
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --platform) TARGET_PLATFORM="$2"; shift 2 ;;
    --wren-extras) WREN_EXTRAS="$2"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -n "$ENV_FILE" && -f "$ENV_FILE" ]] || usage
# container.env 是本次构建和客户运行共用的唯一身份/版本来源。
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
TENANT_ID="${SAAS_TENANT_ID:-}"
INSTANCE_ID="${ENTERPRISE_MCP_INSTANCE_ID:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_CONFIG="$REPO_ROOT/customer/app/src/main/resources/application.yml"
VERSION="$(sed -n 's/.*artifact-version:[^:}]*:\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)}.*/\1/p' "$APP_CONFIG" | head -n 1)"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "application.yml 未配置合法的 artifact-version 默认值" >&2; exit 2; }
[[ "$TENANT_ID" =~ ^[0-9]+$ ]] || usage
[[ "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || usage
[[ "$IMAGE_REPOSITORY" =~ ^[a-z0-9][a-z0-9._/-]*$ ]] || usage
[[ "$TARGET_PLATFORM" =~ ^linux/(amd64|arm64)$ ]] || usage
[[ -z "$WREN_EXTRAS" || "$WREN_EXTRAS" =~ ^[a-z0-9_,.-]+$ ]] || usage

OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/target/container-release}"
mkdir -p "$OUTPUT_DIR"
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf -- "$WORK_DIR"; }
trap cleanup EXIT

IMAGE_REF="$IMAGE_REPOSITORY:$VERSION"
docker build --platform "$TARGET_PLATFORM" \
  --build-arg "VERSION=$VERSION" --build-arg "WREN_EXTRAS=$WREN_EXTRAS" \
  --tag "$IMAGE_REF" --file "$SCRIPT_DIR/Dockerfile" "$REPO_ROOT"
IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_REF")"
IMAGE_ARCHIVE="enterprise-mcp-image.tar"
docker save --output "$WORK_DIR/$IMAGE_ARCHIVE" "$IMAGE_REF"
IMAGE_SHA256="$(sha256sum "$WORK_DIR/$IMAGE_ARCHIVE" | cut -d' ' -f1)"

jq -n \
  --argjson tenantId "$TENANT_ID" --arg instanceId "$INSTANCE_ID" --arg version "$VERSION" \
  --arg archive "$IMAGE_ARCHIVE" --arg repository "$IMAGE_REPOSITORY" --arg tag "$VERSION" \
  --arg imageId "$IMAGE_ID" --arg archiveSha256 "$IMAGE_SHA256" --arg platform "$TARGET_PLATFORM" \
  '{manifestVersion:3, artifactType:"DOCKER_IMAGE", tenantId:$tenantId, instanceId:$instanceId,
    version:$version, image:{archive:$archive, repository:$repository, tag:$tag, imageId:$imageId,
    archiveSha256:$archiveSha256, platform:$platform},
    health:{type:"HTTP", path:"/internal/health", expectedStatus:200}}' > "$WORK_DIR/manifest.json"

PACKAGE="$OUTPUT_DIR/enterprise-mcp-$INSTANCE_ID-$VERSION.tar.gz"
tar -czf "$PACKAGE" -C "$WORK_DIR" manifest.json "$IMAGE_ARCHIVE"
sha256sum "$PACKAGE" > "$PACKAGE.sha256"
echo "已生成客户 MCP 容器发布包: $PACKAGE"
