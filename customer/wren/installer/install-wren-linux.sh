#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
用法:
  sudo ./install-wren-linux.sh
  sudo ./install-wren-linux.sh --instance-id ID [选项]

不带参数时启动交互安装向导；带参数时用于 CI/无人值守安装。

选项:
  --source-dir DIR       Wren 交付源码目录，默认是脚本上级目录
  --install-root DIR     程序安装根目录，默认 /opt/enterprise-mcp/<ID>/wren
  --state-root DIR       持久化目录，默认 /var/lib/enterprise-mcp/<ID>/wren
  --config-root DIR      配置目录，默认 /etc/enterprise-mcp/<ID>/wren
  --service-user USER    systemd 运行用户，默认 enterprise-mcp-<ID>
  --python COMMAND       Python 3.11+ 命令，默认 python3
  --port PORT            Adapter 端口，默认 8091
  --extras LIST          Wren 数据源 extras，例如 mysql,postgres,mssql
  --environment-file FILE  数据库密钥环境文件，格式为 KEY=VALUE
  --profile-file FILE    Wren profiles.yml 文件
  --health-timeout SEC   启动健康检查超时，默认 60 秒
  --no-start             只安装并注册服务，不立即启动
EOF
  exit 2
}

prompt_value() {
  local label="$1"
  local default_value="$2"
  local target_name="$3"
  local input_value=""

  if [[ -n "$default_value" ]]; then
    printf '%s [%s]: ' "$label" "$default_value" >/dev/tty
  else
    printf '%s: ' "$label" >/dev/tty
  fi
  IFS= read -r input_value </dev/tty || { echo "读取输入失败" >&2; exit 1; }
  printf -v "$target_name" '%s' "${input_value:-$default_value}"
}

select_multiple_extras() {
  local labels=(
    "MySQL" "PostgreSQL" "SQL Server" "Oracle" "ClickHouse" "Trino"
    "Snowflake" "BigQuery" "Databricks" "Redshift" "Athena" "Spark" "Memory"
  )
  local values=(
    "mysql" "postgres" "mssql" "oracle" "clickhouse" "trino"
    "snowflake" "bigquery" "databricks" "redshift" "athena" "spark" "memory"
  )
  local selected=()
  local cursor=0
  local key=""
  local suffix=""
  local index
  local result=()

  for ((index = 0; index < ${#labels[@]}; index++)); do
    if [[ ",${EXTRAS}," == *",${values[index]},"* ]]; then
      selected[index]=1
    else
      selected[index]=0
    fi
  done

  while true; do
    printf '\033[2J\033[H' >/dev/tty
    echo "选择需要安装的数据源驱动" >/dev/tty
    echo "使用 ↑/↓ 移动，空格多选或取消，回车确认。DuckDB/本地文件无需选择。" >/dev/tty
    echo >/dev/tty
    for ((index = 0; index < ${#labels[@]}; index++)); do
      local pointer=" "
      local marker=" "
      if (( index == cursor )); then pointer=">"; fi
      if (( selected[index] == 1 )); then marker="x"; fi
      printf '%s [%s] %s\n' "$pointer" "$marker" "${labels[index]}" >/dev/tty
    done

    IFS= read -rsn1 key </dev/tty || { echo "读取按键失败" >&2; exit 1; }
    case "$key" in
      $'\x1b')
        suffix=""
        IFS= read -rsn2 -t 0.2 suffix </dev/tty || true
        case "$suffix" in
          '[A') cursor=$(( (cursor - 1 + ${#labels[@]}) % ${#labels[@]} )) ;;
          '[B') cursor=$(( (cursor + 1) % ${#labels[@]} )) ;;
        esac
        ;;
      ' ') selected[cursor]=$((1 - selected[cursor])) ;;
      '') break ;;
    esac
  done

  for ((index = 0; index < ${#values[@]}; index++)); do
    if (( selected[index] == 1 )); then
      result+=("${values[index]}")
    fi
  done
  INTERACTIVE_EXTRAS="$(IFS=,; echo "${result[*]}")"
}

select_one() {
  local title="$1"
  shift
  local options=("$@")
  local cursor=0
  local key=""
  local suffix=""
  local index

  while true; do
    printf '\033[2J\033[H' >/dev/tty
    echo "$title" >/dev/tty
    echo "使用 ↑/↓ 移动，回车确认。" >/dev/tty
    echo >/dev/tty
    for ((index = 0; index < ${#options[@]}; index++)); do
      if (( index == cursor )); then
        printf '> %s\n' "${options[index]}" >/dev/tty
      else
        printf '  %s\n' "${options[index]}" >/dev/tty
      fi
    done

    IFS= read -rsn1 key </dev/tty || { echo "读取按键失败" >&2; exit 1; }
    case "$key" in
      $'\x1b')
        suffix=""
        IFS= read -rsn2 -t 0.2 suffix </dev/tty || true
        case "$suffix" in
          '[A') cursor=$(( (cursor - 1 + ${#options[@]}) % ${#options[@]} )) ;;
          '[B') cursor=$(( (cursor + 1) % ${#options[@]} )) ;;
        esac
        ;;
      '') MENU_RESULT="$cursor"; return ;;
    esac
  done
}

run_interactive_wizard() {
  local start_choice
  local confirm_choice
  local previous_instance

  while true; do
    printf '\033[2J\033[H' >/dev/tty
    echo "Wren 交互安装向导" >/dev/tty
    echo >/dev/tty

    previous_instance="$INSTANCE_ID"
    while true; do
      prompt_value "客户实例 ID" "$INSTANCE_ID" INSTANCE_ID
      [[ "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] && break
      echo "实例 ID 只能包含字母、数字、点、下划线和连字符。" >/dev/tty
      INSTANCE_ID=""
    done

    prompt_value "Python 3.11+ 命令" "$PYTHON_COMMAND" PYTHON_COMMAND
    while true; do
      prompt_value "Adapter 端口" "$PORT" PORT
      [[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) && break
      echo "端口必须是 1-65535 之间的整数。" >/dev/tty
      PORT="8091"
    done

    if [[ -z "$INSTALL_ROOT" || ( -n "$previous_instance" && "$INSTALL_ROOT" == "/opt/enterprise-mcp/$previous_instance/wren" ) ]]; then
      INSTALL_ROOT="/opt/enterprise-mcp/$INSTANCE_ID/wren"
    fi
    if [[ -z "$STATE_ROOT" || ( -n "$previous_instance" && "$STATE_ROOT" == "/var/lib/enterprise-mcp/$previous_instance/wren" ) ]]; then
      STATE_ROOT="/var/lib/enterprise-mcp/$INSTANCE_ID/wren"
    fi
    if [[ -z "$CONFIG_ROOT" || ( -n "$previous_instance" && "$CONFIG_ROOT" == "/etc/enterprise-mcp/$previous_instance/wren" ) ]]; then
      CONFIG_ROOT="/etc/enterprise-mcp/$INSTANCE_ID/wren"
    fi
    prompt_value "程序安装目录" "$INSTALL_ROOT" INSTALL_ROOT
    prompt_value "持久化目录" "$STATE_ROOT" STATE_ROOT
    prompt_value "配置目录" "$CONFIG_ROOT" CONFIG_ROOT
    prompt_value "wren.env 文件（可选）" "$ENVIRONMENT_FILE" ENVIRONMENT_FILE
    prompt_value "profiles.yml 文件（可选）" "$PROFILE_FILE" PROFILE_FILE

    select_multiple_extras
    EXTRAS="$INTERACTIVE_EXTRAS"
    select_one "安装后是否立即启动 Wren？" "立即启动" "仅安装，暂不启动"
    start_choice="$MENU_RESULT"
    if [[ "$start_choice" == "0" ]]; then START_SERVICE=1; else START_SERVICE=0; fi

    printf '\033[2J\033[H' >/dev/tty
    echo "安装配置摘要" >/dev/tty
    echo "----------------------------------------" >/dev/tty
    printf '实例 ID:       %s\n' "$INSTANCE_ID" >/dev/tty
    printf 'Python:        %s\n' "$PYTHON_COMMAND" >/dev/tty
    printf '监听地址:      127.0.0.1:%s\n' "$PORT" >/dev/tty
    printf '安装目录:      %s\n' "$INSTALL_ROOT" >/dev/tty
    printf '持久化目录:    %s\n' "$STATE_ROOT" >/dev/tty
    printf '配置目录:      %s\n' "$CONFIG_ROOT" >/dev/tty
    printf '数据源驱动:    %s\n' "${EXTRAS:-无（DuckDB/本地文件无需驱动）}" >/dev/tty
    printf '环境文件:      %s\n' "${ENVIRONMENT_FILE:-保留现有配置或创建示例}" >/dev/tty
    printf 'Profile 文件: %s\n' "${PROFILE_FILE:-保留现有配置}" >/dev/tty
    printf '安装后启动:    %s\n' "$([[ "$START_SERVICE" == "1" ]] && echo "是" || echo "否")" >/dev/tty
    echo >/dev/tty
    echo "按回车继续确认。" >/dev/tty
    IFS= read -r </dev/tty || { echo "读取输入失败" >&2; exit 1; }

    select_one "请确认安装操作" "开始安装" "返回修改" "取消"
    confirm_choice="$MENU_RESULT"
    case "$confirm_choice" in
      0) printf '\033[2J\033[H' >/dev/tty; return ;;
      1) continue ;;
      2) echo "已取消安装"; exit 0 ;;
    esac
  done
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INSTANCE_ID=""
INSTALL_ROOT=""
STATE_ROOT=""
CONFIG_ROOT=""
SERVICE_USER=""
PYTHON_COMMAND="python3"
PORT="8091"
EXTRAS=""
ENVIRONMENT_FILE=""
PROFILE_FILE=""
HEALTH_TIMEOUT="60"
START_SERVICE=1
WREN_CORE_VERSION="0.8.0"
INTERACTIVE_MODE=0
INTERACTIVE_EXTRAS=""
MENU_RESULT=""

if (( $# == 0 )); then
  INTERACTIVE_MODE=1
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --instance-id) INSTANCE_ID="$2"; shift 2 ;;
    --source-dir) SOURCE_DIR="$2"; shift 2 ;;
    --install-root) INSTALL_ROOT="$2"; shift 2 ;;
    --state-root) STATE_ROOT="$2"; shift 2 ;;
    --config-root) CONFIG_ROOT="$2"; shift 2 ;;
    --service-user) SERVICE_USER="$2"; shift 2 ;;
    --python) PYTHON_COMMAND="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --extras) EXTRAS="$2"; shift 2 ;;
    --environment-file) ENVIRONMENT_FILE="$2"; shift 2 ;;
    --profile-file) PROFILE_FILE="$2"; shift 2 ;;
    --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
    --no-start) START_SERVICE=0; shift ;;
    -h|--help) usage ;;
    *) usage ;;
  esac
done

[[ $EUID -eq 0 ]] || { echo "必须使用 root 执行" >&2; exit 1; }
if (( INTERACTIVE_MODE == 1 )); then
  [[ -t 0 && -t 1 && -r /dev/tty && -w /dev/tty ]] || {
    echo "交互安装需要可用终端；CI/无人值守环境请使用 --instance-id 等参数" >&2
    exit 1
  }
  run_interactive_wizard
fi
[[ "$INSTANCE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || usage
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || { echo "端口非法: $PORT" >&2; exit 1; }
[[ "$HEALTH_TIMEOUT" =~ ^[0-9]+$ ]] && (( HEALTH_TIMEOUT > 0 )) || { echo "健康检查超时必须为正整数" >&2; exit 1; }
[[ -z "$EXTRAS" || "$EXTRAS" =~ ^[a-z0-9_,.-]+$ ]] || { echo "extras 格式非法" >&2; exit 1; }

SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
[[ -f "$SOURCE_DIR/adapter/server.py" ]] || { echo "缺少 adapter/server.py: $SOURCE_DIR" >&2; exit 1; }
[[ -f "$SOURCE_DIR/upstream/pyproject.toml" ]] || { echo "缺少 upstream/pyproject.toml: $SOURCE_DIR" >&2; exit 1; }
[[ -z "$ENVIRONMENT_FILE" || -f "$ENVIRONMENT_FILE" ]] || { echo "环境文件不存在: $ENVIRONMENT_FILE" >&2; exit 1; }
[[ -z "$PROFILE_FILE" || -f "$PROFILE_FILE" ]] || { echo "profile 文件不存在: $PROFILE_FILE" >&2; exit 1; }

INSTALL_ROOT="${INSTALL_ROOT:-/opt/enterprise-mcp/$INSTANCE_ID/wren}"
STATE_ROOT="${STATE_ROOT:-/var/lib/enterprise-mcp/$INSTANCE_ID/wren}"
CONFIG_ROOT="${CONFIG_ROOT:-/etc/enterprise-mcp/$INSTANCE_ID/wren}"
command -v sha256sum >/dev/null || { echo "缺少 sha256sum" >&2; exit 1; }
command -v cut >/dev/null || { echo "缺少 cut" >&2; exit 1; }
DEFAULT_USER="wren-$(printf '%s' "$INSTANCE_ID" | sha256sum | cut -c1-16)"
SERVICE_USER="${SERVICE_USER:-$DEFAULT_USER}"
[[ "$SERVICE_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || {
  echo "systemd 用户名非法或超过 32 个字符，请使用 --service-user 指定短名称" >&2
  exit 1
}

command -v "$PYTHON_COMMAND" >/dev/null || { echo "找不到 Python: $PYTHON_COMMAND" >&2; exit 1; }
command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v grep >/dev/null || { echo "缺少 grep" >&2; exit 1; }
command -v systemctl >/dev/null || { echo "当前系统不支持 systemd" >&2; exit 1; }
"$PYTHON_COMMAND" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)' || {
  echo "Wren 要求 Python 3.11 或更高版本" >&2
  exit 1
}

SERVICE_NAME="enterprise-wren-$INSTANCE_ID"
RUNTIME_DIR="$INSTALL_ROOT/runtime"
PREVIOUS_DIR="$INSTALL_ROOT/runtime.previous"
VENV_DIR="$INSTALL_ROOT/venv"
PREVIOUS_VENV_DIR="$INSTALL_ROOT/venv.previous"
STAGING_VENV_DIR="$INSTALL_ROOT/.venv-new"
PROJECT_ROOT="$STATE_ROOT"
WREN_HOME_DIR="$STATE_ROOT/.wren"
ENV_FILE="$CONFIG_ROOT/wren.env"
CUSTOMERAPP_ENV_FILE="$CONFIG_ROOT/customerapp-wren.env"
UNIT_FILE="/etc/systemd/system/$SERVICE_NAME.service"

install -d -m 0755 "$INSTALL_ROOT" "$STATE_ROOT" "$CONFIG_ROOT"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$STATE_ROOT" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

STAGING_DIR="$(mktemp -d "$INSTALL_ROOT/.runtime-new.XXXXXX")"
cleanup() {
  if [[ -n "${STAGING_DIR:-}" && -d "$STAGING_DIR" ]]; then
    rm -rf -- "$STAGING_DIR"
  fi
  if [[ -d "${STAGING_VENV_DIR:-}" ]]; then
    rm -rf -- "$STAGING_VENV_DIR"
  fi
}
trap cleanup EXIT

cp -a "$SOURCE_DIR/adapter" "$STAGING_DIR/adapter"
cp -a "$SOURCE_DIR/upstream" "$STAGING_DIR/upstream"
if [[ -f "$SOURCE_DIR/NOTICE.md" ]]; then
  cp "$SOURCE_DIR/NOTICE.md" "$STAGING_DIR/NOTICE.md"
fi

rm -rf -- "$STAGING_VENV_DIR"
"$PYTHON_COMMAND" -m venv "$STAGING_VENV_DIR"
STAGING_PYTHON="$STAGING_VENV_DIR/bin/python"
"$STAGING_PYTHON" -m pip install --disable-pip-version-check --upgrade pip
"$STAGING_PYTHON" -m pip install --disable-pip-version-check "wren-core-py==$WREN_CORE_VERSION"
PACKAGE_SPEC="$STAGING_DIR/upstream"
if [[ -n "$EXTRAS" ]]; then
  PACKAGE_SPEC="${PACKAGE_SPEC}[${EXTRAS}]"
fi
"$STAGING_PYTHON" -m pip install --disable-pip-version-check "$PACKAGE_SPEC"
"$STAGING_PYTHON" -m pip install --disable-pip-version-check "lancedb==0.38.0"

if systemctl list-unit-files "$SERVICE_NAME.service" --no-legend 2>/dev/null | grep -q "$SERVICE_NAME.service"; then
  systemctl stop "$SERVICE_NAME.service" || true
fi

if [[ -d "$PREVIOUS_DIR" ]]; then
  rm -rf -- "$PREVIOUS_DIR"
fi
if [[ -d "$PREVIOUS_VENV_DIR" ]]; then
  rm -rf -- "$PREVIOUS_VENV_DIR"
fi
if [[ -d "$RUNTIME_DIR" ]]; then
  mv "$RUNTIME_DIR" "$PREVIOUS_DIR"
fi
if [[ -d "$VENV_DIR" ]]; then
  mv "$VENV_DIR" "$PREVIOUS_VENV_DIR"
fi
mv "$STAGING_DIR" "$RUNTIME_DIR"
STAGING_DIR=""
mv "$STAGING_VENV_DIR" "$VENV_DIR"

install -d -m 0750 "$PROJECT_ROOT"
install -d -m 0700 "$WREN_HOME_DIR"

if [[ -n "$ENVIRONMENT_FILE" ]]; then
  install -o root -g root -m 0600 "$ENVIRONMENT_FILE" "$ENV_FILE"
elif [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<'EOF'
# 数据库密钥示例；profiles.yml 使用 ${CUSTOMER_DB_PASSWORD} 引用。
# CUSTOMER_DB_PASSWORD=replace-me
EOF
  chmod 0600 "$ENV_FILE"
fi

if [[ -n "$PROFILE_FILE" ]]; then
  install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0600 "$PROFILE_FILE" "$WREN_HOME_DIR/profiles.yml"
fi

SERVICE_TOKEN=""
while IFS='=' read -r key value; do
  if [[ "$key" == "WREN_ADAPTER_SERVICE_TOKEN" ]]; then SERVICE_TOKEN="$value"; fi
done < "$ENV_FILE"
if [[ -z "$SERVICE_TOKEN" ]]; then
  SERVICE_TOKEN="$($PYTHON_COMMAND -c 'import secrets; print(secrets.token_urlsafe(32))')"
  printf '\nWREN_ADAPTER_SERVICE_TOKEN=%s\n' "$SERVICE_TOKEN" >> "$ENV_FILE"
fi

ADMIN_TOKEN=""
if [[ -f "$CUSTOMERAPP_ENV_FILE" ]]; then
  while IFS='=' read -r key value; do
    if [[ "$key" == "ENTERPRISE_MCP_WREN_ADMIN_TOKEN" ]]; then ADMIN_TOKEN="$value"; fi
  done < "$CUSTOMERAPP_ENV_FILE"
fi
if [[ -z "$ADMIN_TOKEN" ]]; then
  ADMIN_TOKEN="$($PYTHON_COMMAND -c 'import secrets; print(secrets.token_urlsafe(32))')"
fi
cat > "$CUSTOMERAPP_ENV_FILE" <<EOF
ENTERPRISE_MCP_WREN_ENABLED=true
ENTERPRISE_MCP_WREN_BASE_URL=http://127.0.0.1:$PORT
ENTERPRISE_MCP_WREN_PROJECT_ROOT=$PROJECT_ROOT
ENTERPRISE_MCP_WREN_INSTANCE_DIR=$STATE_ROOT
ENTERPRISE_MCP_WREN_ADAPTER_SERVICE_TOKEN=$SERVICE_TOKEN
ENTERPRISE_MCP_WREN_ADMIN_TOKEN=$ADMIN_TOKEN
EOF
chmod 0600 "$ENV_FILE" "$CUSTOMERAPP_ENV_FILE"

chown -R root:root "$RUNTIME_DIR" "$VENV_DIR"
chmod -R a+rX "$RUNTIME_DIR" "$VENV_DIR"
chown -R "$SERVICE_USER:$SERVICE_USER" "$STATE_ROOT"

cat > "$UNIT_FILE" <<EOF
[Unit]
Description=Enterprise MCP Wren Adapter $INSTANCE_ID
After=network-online.target
Wants=network-online.target
Before=enterprise-mcp-$INSTANCE_ID.service

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$PROJECT_ROOT
EnvironmentFile=-$ENV_FILE
Environment=PYTHONDONTWRITEBYTECODE=1
Environment=PYTHONPATH=$RUNTIME_DIR/upstream/src
Environment=WREN_PROJECT_ROOT=$PROJECT_ROOT
Environment=WREN_INSTANCE_DIR=$STATE_ROOT
Environment=WREN_ADAPTER_HOST=127.0.0.1
Environment=WREN_ADAPTER_PORT=$PORT
ExecStart=$VENV_DIR/bin/python $RUNTIME_DIR/adapter/server.py
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$STATE_ROOT

[Install]
WantedBy=multi-user.target
EOF
chmod 0644 "$UNIT_FILE"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME.service" >/dev/null

if (( START_SERVICE == 0 )); then
  echo "Wren 已安装但未启动: $SERVICE_NAME.service"
  echo "customerapp 配置文件: $CUSTOMERAPP_ENV_FILE"
  exit 0
fi

systemctl restart "$SERVICE_NAME.service"
deadline=$((SECONDS + HEALTH_TIMEOUT))
healthy=0
while (( SECONDS < deadline )); do
  if systemctl is-active --quiet "$SERVICE_NAME.service" \
      && curl -fsS "http://127.0.0.1:$PORT/internal/health" | grep -q '"status": "UP"'; then
    healthy=1
    break
  fi
  sleep 2
done

if (( healthy == 0 )); then
  echo "Wren 健康检查失败" >&2
  systemctl stop "$SERVICE_NAME.service" || true
  if [[ -d "$PREVIOUS_DIR" ]]; then
    rm -rf -- "$RUNTIME_DIR"
    mv "$PREVIOUS_DIR" "$RUNTIME_DIR"
    rm -rf -- "$VENV_DIR"
    if [[ -d "$PREVIOUS_VENV_DIR" ]]; then
      mv "$PREVIOUS_VENV_DIR" "$VENV_DIR"
    fi
    systemctl restart "$SERVICE_NAME.service" || true
    echo "已恢复上一次 runtime 和 Python venv" >&2
  fi
  exit 1
fi

echo "Wren 安装成功"
echo "服务: $SERVICE_NAME.service"
echo "健康地址: http://127.0.0.1:$PORT/internal/health"
echo "Customer Runtime 配置:"
echo "  ENTERPRISE_MCP_WREN_ENABLED=true"
echo "  ENTERPRISE_MCP_WREN_BASE_URL=http://127.0.0.1:$PORT"
echo "  ENTERPRISE_MCP_WREN_PROJECT_ROOT=$PROJECT_ROOT"
echo "  ENTERPRISE_MCP_WREN_INSTANCE_DIR=$STATE_ROOT"
echo "  customerapp 配置文件=$CUSTOMERAPP_ENV_FILE"
