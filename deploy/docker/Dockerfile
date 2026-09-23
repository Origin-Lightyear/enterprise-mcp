FROM node:22-bookworm-slim AS frontend-build

WORKDIR /workspace/customer/app/src/main/frontend
COPY customer/app/src/main/frontend/package.json customer/app/src/main/frontend/package-lock.json ./
RUN npm ci
COPY customer/app/src/main/frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS java-build

WORKDIR /workspace
COPY pom.xml ./
COPY shared ./shared
COPY customer/runtime ./customer/runtime
COPY customer/app ./customer/app
COPY cloud ./cloud
COPY --from=frontend-build /workspace/customer/app/src/main/resources/static/admin/wren \
    customer/app/src/main/resources/static/admin/wren
RUN mvn -pl customer/app -am -DskipTests -Dexec.skip=true clean package \
    && mkdir -p /opt/build \
    && cp customer/app/target/customer-enterprise-mcp-*.jar /opt/build/enterprise-mcp.jar

FROM eclipse-temurin:21-jre-noble AS wren-build

ARG WREN_EXTRAS=mysql,postgres,mssql
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl default-libmysqlclient-dev pkg-config unixodbc-dev \
        python3 python3-dev python3-pip python3-venv \
    && curl --fail --silent --show-error --location \
        --output /tmp/packages-microsoft-prod.deb \
        https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb \
    && dpkg -i /tmp/packages-microsoft-prod.deb \
    && rm /tmp/packages-microsoft-prod.deb \
    && apt-get update \
    && ACCEPT_EULA=Y apt-get install -y --no-install-recommends msodbcsql18 \
    && rm -rf /var/lib/apt/lists/*
COPY customer/wren/upstream /opt/enterprise-mcp/wren/upstream
RUN python3 -m venv /opt/enterprise-mcp/wren/venv \
    && /opt/enterprise-mcp/wren/venv/bin/pip install --disable-pip-version-check --upgrade pip \
    && /opt/enterprise-mcp/wren/venv/bin/pip install --disable-pip-version-check "wren-core-py==0.8.0" \
    && /opt/enterprise-mcp/wren/venv/bin/pip install --disable-pip-version-check \
        "/opt/enterprise-mcp/wren/upstream[${WREN_EXTRAS}]" \
    && /opt/enterprise-mcp/wren/venv/bin/pip install --disable-pip-version-check "lancedb==0.38.0"

FROM eclipse-temurin:21-jre-noble

ARG VERSION=dev
ENV DEBIAN_FRONTEND=noninteractive \
    ENTERPRISE_MCP_ARTIFACT_VERSION=${VERSION} \
    ENTERPRISE_MCP_PORT=6001 \
    ENTERPRISE_MCP_WREN_ENABLED=true \
    ENTERPRISE_MCP_WREN_BASE_URL=http://127.0.0.1:8091 \
    ENTERPRISE_MCP_WREN_EXECUTABLE=/opt/enterprise-mcp/bin/run-wren-adapter.sh \
    ENTERPRISE_MCP_WREN_PROJECT_ROOT=/var/lib/enterprise-mcp/wren \
    ENTERPRISE_MCP_WREN_INSTANCE_DIR=/var/lib/enterprise-mcp/wren \
    ENTERPRISE_MCP_UPDATER_COMMAND=/opt/enterprise-mcp/bin/queue-container-upgrade.sh \
    ENTERPRISE_MCP_UPGRADE_REQUEST_FILE=/var/lib/enterprise-mcp/upgrade/request.json
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && curl --fail --silent --show-error --location \
        --output /tmp/packages-microsoft-prod.deb \
        https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb \
    && dpkg -i /tmp/packages-microsoft-prod.deb \
    && rm /tmp/packages-microsoft-prod.deb \
    && apt-get update \
    && ACCEPT_EULA=Y apt-get install -y --no-install-recommends \
        libgomp1 libmariadb3 libpq5 msodbcsql18 python3 tini unixodbc \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 enterprise-mcp \
    && useradd --uid 10001 --gid 10001 --home-dir /var/lib/enterprise-mcp --shell /usr/sbin/nologin enterprise-mcp \
    && install -d -o enterprise-mcp -g enterprise-mcp -m 0750 \
        /var/lib/enterprise-mcp /var/lib/enterprise-mcp/wren /var/lib/enterprise-mcp/upgrade

COPY --from=java-build /opt/build/enterprise-mcp.jar /opt/enterprise-mcp/app/enterprise-mcp.jar
COPY --from=wren-build /opt/enterprise-mcp/wren/venv /opt/enterprise-mcp/wren/venv
COPY customer/wren/adapter /opt/enterprise-mcp/wren/adapter
COPY customer/wren/launcher/ensure_dependencies.py /opt/enterprise-mcp/wren/launcher/ensure_dependencies.py
COPY customer/wren/upstream/pyproject.toml /opt/enterprise-mcp/wren/upstream/pyproject.toml
COPY customer/wren/NOTICE.md /opt/enterprise-mcp/licenses/NOTICE-WrenAI.md
COPY deploy/docker/runtime/container-entrypoint.sh deploy/docker/runtime/run-wren-adapter.sh \
    deploy/docker/runtime/queue-container-upgrade.sh /opt/enterprise-mcp/bin/
RUN chmod 0555 /opt/enterprise-mcp/bin/*.sh \
    && chown -R root:root /opt/enterprise-mcp

LABEL org.opencontainers.image.title="Origin Lightyear Enterprise MCP" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.vendor="Origin Lightyear"

USER 10001:10001
WORKDIR /var/lib/enterprise-mcp
EXPOSE 6001
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${ENTERPRISE_MCP_PORT}/internal/health" >/dev/null || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/enterprise-mcp/bin/container-entrypoint.sh"]
