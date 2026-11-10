package com.customer.mcp.k3.knowledge;

import com.customer.mcp.k3.client.K3Properties;
import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.RuntimeArtifactDigest;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.catalog.CatalogState;
import com.kevin.mcp.catalog.PlanningReadinessStatus;
import com.kevin.mcp.catalog.RuntimeCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证金蝶知识上传失败时保留可定位原因并抑制重复告警。
 *
 * @author Kevin
 * @date 2026-10-28
 */
@ExtendWith(OutputCaptureExtension.class)
class K3KnowledgePublisherTest {

    /** 验证相同上传失败只告警一次，并输出不含请求数据的具体失败原因。 */
    @Test
    void shouldLogConcreteFailureOnceForRepeatedUploadError(CapturedOutput output) {
        K3Properties properties = configuredProperties();
        KnowledgeStore knowledge = mock(KnowledgeStore.class);
        RuntimeCatalog runtimeCatalog = mock(RuntimeCatalog.class);
        RuntimeIdentityProperties identity = new RuntimeIdentityProperties();
        identity.setTenantId("tenant-1");
        identity.setInstanceId("runtime-1");
        RuntimeArtifactDigest artifactDigest = mock(RuntimeArtifactDigest.class);
        PlanningGatewayClient gateway = mock(PlanningGatewayClient.class);
        CatalogState ready = new CatalogState(PlanningReadinessStatus.READY, "catalog-digest", "", "",
                Instant.parse("2026-10-28T00:00:00Z"));
        when(runtimeCatalog.current()).thenReturn(ready);
        when(knowledge.snapshot()).thenReturn(java.util.List.of());
        when(artifactDigest.getValue()).thenReturn("sha256:artifact");
        when(gateway.uploadKnowledge(any())).thenThrow(
                new IllegalStateException("Control Plane returned an empty knowledge acknowledgment"));
        K3KnowledgePublisher publisher = new K3KnowledgePublisher(
                knowledge, properties, runtimeCatalog, identity, artifactDigest, gateway);

        publisher.synchronize();
        publisher.synchronize();

        assertThat(output).contains("tenantId=tenant-1")
                .contains("instanceId=runtime-1")
                .contains("Control Plane returned an empty knowledge acknowledgment");
        assertThat(countOccurrences(output.getOut(), "K3 knowledge upload pending"))
                .isEqualTo(1);
    }

    private K3Properties configuredProperties() {
        K3Properties properties = new K3Properties();
        properties.setEnabled(true);
        properties.setServerUrl("https://kingdee.example/k3cloud/");
        properties.setAcctId("acct-1");
        properties.setAppId("app-1");
        properties.setAppSecret("app-secret-1");
        return properties;
    }

    private int countOccurrences(String source, String target) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
