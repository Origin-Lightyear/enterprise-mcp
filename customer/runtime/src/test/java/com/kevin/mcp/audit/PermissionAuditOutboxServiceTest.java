package com.kevin.mcp.audit;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证权限审计 Outbox 在 Tenant 暂时不可用时保留并重试记录。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class PermissionAuditOutboxServiceTest {

    /** 提供每个测试独立的 Outbox 目录。 */
    @TempDir
    Path temporaryDirectory;

    /** 验证首次上报失败后保留记录，重试成功后再删除。 */
    @Test
    void shouldKeepFailedAuditAndDeleteItAfterSuccessfulRetry() throws Exception {
        HttpAgentServerClient client = mock(HttpAgentServerClient.class);
        doThrow(new IllegalStateException("tenant unavailable"))
                .doNothing()
                .when(client).reportPermissionAudit(anyMap());
        PermissionAuditOutboxService service = new PermissionAuditOutboxService(
                client, temporaryDirectory.toString());

        String auditId = service.enqueue(Map.of("planId", "plan-1", "decision", "allow"));

        assertThat(temporaryDirectory.resolve(auditId + ".json")).exists();
        service.retryPending();
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertThat(files.toList()).isEmpty();
        }
        verify(client, times(2)).reportPermissionAudit(anyMap());
    }
}
