package com.customer.mcp.k3.client;

import com.kingdee.bos.webapi.entity.IdentifyInfo;
import com.kingdee.bos.webapi.sdk.K3CloudApi;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.model.ApiCredential;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证金蝶 SDK 会话按员工隔离并随凭证版本更新。
 *
 * @author Kevin
 * @date 2026-10-26
 */
class K3ClientTest {

    /** 验证不同员工不共享会话，同一员工凭证更新时间变化后替换旧会话。 */
    @Test
    void shouldIsolateEmployeesAndReplaceChangedCredentialSession() {
        K3Properties properties = new K3Properties();
        properties.setEnabled(true);
        properties.setServerUrl("https://kingdee.example");
        properties.setAcctId("acct-1");
        properties.setAppId("app-1");
        properties.setAppSecret("app-secret-1");
        RequestExecutionContextHolder holder = new RequestExecutionContextHolder();
        HttpAgentServerClient tenantClient = mock(HttpAgentServerClient.class);
        K3CloudApiFactory factory = mock(K3CloudApiFactory.class);
        K3CloudApi firstEmployeeApi = mock(K3CloudApi.class);
        K3CloudApi secondEmployeeApi = mock(K3CloudApi.class);
        K3CloudApi rotatedApi = mock(K3CloudApi.class);
        LocalDateTime firstVersion = LocalDateTime.of(2026, 10, 26, 10, 0);
        LocalDateTime rotatedVersion = firstVersion.plusMinutes(1);
        when(factory.create(any(IdentifyInfo.class)))
                .thenReturn(firstEmployeeApi, secondEmployeeApi, rotatedApi);
        when(tenantClient.fetchApiCredential("tenant-1", 1001L, "kingdee-k3"))
                .thenReturn(credential("alice", firstVersion), credential("alice", firstVersion),
                        credential("alice", rotatedVersion));
        when(tenantClient.fetchApiCredential("tenant-1", 1002L, "kingdee-k3"))
                .thenReturn(credential("bob", firstVersion));
        K3Client client = new K3Client(properties, holder, tenantClient, factory);

        assertThat(callForEmployee(client, holder, 1001L)).isSameAs(firstEmployeeApi);
        assertThat(callForEmployee(client, holder, 1001L)).isSameAs(firstEmployeeApi);
        assertThat(callForEmployee(client, holder, 1002L)).isSameAs(secondEmployeeApi);
        assertThat(callForEmployee(client, holder, 1001L)).isSameAs(rotatedApi);
        ArgumentCaptor<IdentifyInfo> identifyInfoCaptor = ArgumentCaptor.forClass(IdentifyInfo.class);
        verify(factory, times(3)).create(identifyInfoCaptor.capture());
        assertThat(identifyInfoCaptor.getAllValues())
                .extracting(IdentifyInfo::getUserName)
                .containsExactly("alice", "bob", "alice");
        assertThat(identifyInfoCaptor.getAllValues())
                .allSatisfy(info -> {
                    assertThat(info.getAppId()).isEqualTo("app-1");
                    assertThat(info.getAppSecret()).isEqualTo("app-secret-1");
                    assertThat(info.getPwd()).isNull();
                });
    }

    private K3CloudApi callForEmployee(K3Client client, RequestExecutionContextHolder holder, Long employeeId) {
        RequestExecutionContext context = new RequestExecutionContext(
                null, "", "tenant-1", employeeId, Map.of());
        try (RequestExecutionContextHolder.Scope ignored = holder.bind(context)) {
            final K3CloudApi[] selected = new K3CloudApi[1];
            client.call("test", api -> {
                selected[0] = api;
                return "ok";
            });
            return selected[0];
        }
    }

    private ApiCredential credential(String username, LocalDateTime updateTime) {
        return new ApiCredential("UNIQUE_IDENTIFIER", Map.of("identifier", username), updateTime);
    }
}
