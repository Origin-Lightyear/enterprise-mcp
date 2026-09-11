# Agent Verify Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fixed-caller AgentServer verification flow for MCPServer using an embedded public key, signed request headers, and version-driven tenant cache refresh.

**Architecture:** Add a connector layer where `AgentVerify` orchestrates header validation, replay protection, tenant parsing, signature verification, and cache refresh. Keep cryptographic parsing in a focused codec class, keep AgentServer HTTP access in a client class, and keep tenant/auth caching in a cache service so tool entrypoints stay simple.

**Tech Stack:** Java 21, Spring Boot 4, Spring AI MCP, JUnit 5, Spring Boot Test

---

### Task 1: Add the first failing security codec test

**Files:**
- Create: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\agent\connector\AgentSecurityCodecTest.java`
- Modify: `D:\Codes\mcp-project\pom.xml`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldDecodeTenantAndVerifySignature() {
    // given
    String tenantJson = "{\"tenantId\":\"1\",\"employeeId\":\"7\",\"versionNumber\":3}";
    String tenantHeader = BaseEncoding.base32().encode(tenantJson.getBytes(StandardCharsets.UTF_8));
    String timestamp = "1753171200";
    String nonce = "nonce-001";
    String payload = "Tenant=" + tenantHeader + "&Timestamp=" + timestamp + "&Nonce=" + nonce;
    String signature = sign(payload);

    // when
    TenantRequestInfo info = codec.parseTenantHeader(tenantHeader);
    boolean verified = codec.verifySignature(tenantHeader, timestamp, nonce, signature);

    // then
    assertThat(info.tenantId()).isEqualTo("1");
    assertThat(info.employeeId()).isEqualTo("7");
    assertThat(info.versionNumber()).isEqualTo(3L);
    assertThat(verified).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd -Dtest=AgentSecurityCodecTest test`
Expected: FAIL because `AgentSecurityCodec` and `TenantRequestInfo` do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
record TenantRequestInfo(String tenantId, String employeeId, long versionNumber) {}

boolean verifySignature(String tenantHeader, String timestamp, String nonce, String signature) {
    String payload = "Tenant=" + tenantHeader + "&Timestamp=" + timestamp + "&Nonce=" + nonce;
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(publicKey);
    verifier.update(payload.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(Base64.getDecoder().decode(signature));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd -Dtest=AgentSecurityCodecTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test/java/com/kevin/mcp/agent/connector/AgentSecurityCodecTest.java src/main/java/com/kevin/mcp/agent/connector/*
git commit -m "feat: add agent security codec"
```

### Task 2: Add replay-window validation tests

**Files:**
- Modify: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\agent\connector\AgentSecurityCodecTest.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\agent\connector\AgentRequestHeaders.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void shouldRejectExpiredTimestamp() {
    assertThat(codec.isTimestampAllowed(Instant.now().minusSeconds(600).getEpochSecond(), 300)).isFalse();
}

@Test
void shouldBuildCanonicalPayload() {
    assertThat(codec.buildPayload("tenant", "100", "abc"))
            .isEqualTo("Tenant=tenant&Timestamp=100&Nonce=abc");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd -Dtest=AgentSecurityCodecTest test`
Expected: FAIL because timestamp validation and payload builder are missing.

- [ ] **Step 3: Write minimal implementation**

```java
boolean isTimestampAllowed(long epochSeconds, long allowedSkewSeconds) {
    long delta = Math.abs(Instant.now(clock).getEpochSecond() - epochSeconds);
    return delta <= allowedSkewSeconds;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd -Dtest=AgentSecurityCodecTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/kevin/mcp/agent/connector/AgentSecurityCodecTest.java src/main/java/com/kevin/mcp/agent/connector/AgentRequestHeaders.java src/main/java/com/kevin/mcp/agent/connector/AgentSecurityCodec.java
git commit -m "feat: add agent request validation helpers"
```

### Task 3: Add AgentVerify orchestration test

**Files:**
- Create: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\agent\connector\AgentVerifyTest.java`
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\agent\connector\AgentVerify.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\agent\connector\TenantCacheService.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\agent\connector\VerifiedAgentRequest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldRefreshTenantCacheWhenIncomingVersionIsNewer() {
    when(cacheService.getTenantVersion("1")).thenReturn(1L);

    boolean verified = agentVerify.verifySource(requestContext);

    assertThat(verified).isTrue();
    verify(agentServerClient).fetchTenantConfig("1");
    verify(agentServerClient).fetchTenantAuth("1", "7");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd -Dtest=AgentVerifyTest test`
Expected: FAIL because `AgentVerify` still only checks `Bearer`.

- [ ] **Step 3: Write minimal implementation**

```java
if (requestVersion > cachedVersion) {
    cacheService.refreshTenantConfig(agentServerClient.fetchTenantConfig(tenantId));
    cacheService.refreshTenantAuth(agentServerClient.fetchTenantAuth(tenantId, employeeId));
}
return cacheService.isTenantRequestAllowed(tenantId, employeeId);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd -Dtest=AgentVerifyTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/kevin/mcp/agent/connector/AgentVerifyTest.java src/main/java/com/kevin/mcp/agent/connector/*
git commit -m "feat: add agent verification flow"
```

### Task 4: Add startup synchronization support

**Files:**
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\agent\connector\AgentServerClient.java`
- Modify: `D:\Codes\mcp-project\src\main\resources\application.yml`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldLoadTenantConfigAtStartup() {
    initializer.syncTenantConfig();
    verify(agentServerClient).fetchTenantConfig("1");
    verify(cacheService).refreshTenantConfig(any());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd -Dtest=AgentConnectorStartupTest test`
Expected: FAIL because there is no startup synchronization component.

- [ ] **Step 3: Write minimal implementation**

```java
@PostConstruct
void syncTenantConfig() {
    cacheService.refreshTenantConfig(agentServerClient.fetchTenantConfig(tenantId));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd -Dtest=AgentConnectorStartupTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/kevin/mcp/agent/connector/*
git commit -m "feat: sync tenant config on startup"
```

### Task 5: Run integration verification

**Files:**
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\tools\CommonInternalTool.java`

- [ ] **Step 1: Keep tool integration stable**

```java
if (!agentVerify.verifySource(requestContext)) {
    return "Unauthorized";
}
```

- [ ] **Step 2: Run targeted tests**

Run: `.\mvnw.cmd -Dtest=AgentSecurityCodecTest,AgentVerifyTest,AgentConnectorStartupTest test`
Expected: PASS

- [ ] **Step 3: Run compile verification**

Run: `.\mvnw.cmd -DskipTests test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/kevin/mcp/tools/CommonInternalTool.java
git commit -m "chore: verify agent connector integration"
```
