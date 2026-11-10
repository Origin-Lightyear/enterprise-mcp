#!/usr/bin/env bash
set -euo pipefail

root_secret="${TENANT_MCP_ROOT_SECRET:-}"
tenant_id="${SAAS_TENANT_ID:-}"
instance_id="${ENTERPRISE_MCP_INSTANCE_ID:-}"

if [[ -z "${root_secret}" ]]; then
  echo "TENANT_MCP_ROOT_SECRET（Tenant MCP 根密钥）不能为空" >&2
  exit 1
fi
if [[ ! "${tenant_id}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SAAS_TENANT_ID（租户编号）必须是大于 0 的整数" >&2
  exit 1
fi
if [[ -z "${instance_id}" ]]; then
  echo "ENTERPRISE_MCP_INSTANCE_ID（Runtime 实例编号）不能为空" >&2
  exit 1
fi

root_hex="$(printf '%s' "${root_secret}" | openssl base64 -d -A 2>/dev/null | od -An -vtx1 | tr -d ' \n')"
if [[ -z "${root_hex}" ]] || (( ${#root_hex} < 64 )); then
  echo "TENANT_MCP_ROOT_SECRET（Tenant MCP 根密钥）必须是合法 Base64，且解码后不得少于 32 字节" >&2
  exit 1
fi

context="tenant-mcp-token:v1:${tenant_id}:${instance_id}"
token="$(printf '%s' "${context}" \
  | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${root_hex}" -binary \
  | openssl base64 -A \
  | tr '+/' '-_' \
  | tr -d '=')"

printf 'TENANT_MCP_TOKEN=%s\n' "${token}"
