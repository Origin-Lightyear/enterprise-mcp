package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.tool.publicmcp.K3QueryTool;
import com.customer.mcp.k3.catalog.K3Catalog;
import com.customer.mcp.k3.catalog.K3FieldLocalizer;
import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.k3.errors.ErrorPatternCatalog;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import com.kevin.mcp.execution.K3BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证报表错误和不确定响应不得提升为已验证账套知识。
 *
 * @author Kevin
 * @date 2026-10-14
 */
class K3QueryToolTest {

    @TempDir
    Path directory;

    /**
     * 验证 HTTP 层成功但金蝶业务状态失败时不写正向知识。
     */
    @Test
    void doesNotLearnFailedReportResponse() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3QueryTool tool = new K3QueryTool(gateway, new K3Catalog(gateway, knowledge), knowledge,
                new ErrorPatternCatalog(), new K3FieldLocalizer(fallbackCatalog(gateway, knowledge)));
        when(gateway.report(eq("BD_Material"), anyString())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false}}}");

        assertThrows(K3BusinessException.class,
                () -> tool.queryReport("BD_Material", "{\"Limit\":10}", null));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:BD_Material"));
    }

    /**
     * 验证 ExecuteBillQuery 行内错误不会把不存在的字段学成有效字段。
     */
    @Test
    void doesNotLearnEmbeddedQueryError() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3QueryTool tool = new K3QueryTool(gateway,
                new K3Catalog(gateway, knowledge), knowledge, new ErrorPatternCatalog(),
                new K3FieldLocalizer(fallbackCatalog(gateway, knowledge)));
        when(gateway.query(eq("BD_Material"), eq("FBillAmount"), anyString(),
                anyString(), eq(0), eq(20))).thenReturn("""
                [[{"Result":{"ResponseStatus":{"IsSuccess":false,
                "Errors":[{"Message":"元数据中标识为FBillAmount的字段不存在"}]}}}]]
                """);

        assertThrows(K3BusinessException.class,
                () -> tool.queryData("BD_Material", "FBillAmount", "", null, 0, 20, null));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "field:BD_Material:FBillAmount"));
    }

    /**
     * 验证查询返回值已转换为纯 Java 集合，不携带 Gson 类型。
     */
    @Test
    void convertsQueryRowsToPlainJavaCollections() throws Exception {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3QueryTool tool = new K3QueryTool(gateway,
                new K3Catalog(gateway, knowledge), knowledge, new ErrorPatternCatalog(),
                new K3FieldLocalizer(fallbackCatalog(gateway, knowledge)));
        when(gateway.query(eq("BD_Material"), eq("FNumber"), anyString(),
                anyString(), eq(0), eq(20))).thenReturn("[[\"001\"]]");
        var result = tool.queryData("BD_Material", "FNumber", "", null, 0, 20, null);

        assertEquals(1, result.get("count"));
        assertInstanceOf(List.class, result.get("data"));
        assertPlainJavaValue(result);
    }

    /**
     * 验证报表返回值不再暴露 Gson 对象，只包含纯 Java 集合。
     */
    @Test
    void convertsReportResponseToPlainJavaMap() throws Exception {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3QueryTool tool = new K3QueryTool(gateway,
                new K3Catalog(gateway, knowledge), knowledge, new ErrorPatternCatalog(),
                new K3FieldLocalizer(fallbackCatalog(gateway, knowledge)));
        when(gateway.report(eq("BD_Material"), anyString())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Rows\":[[\"1001\"]]}}");
        var result = tool.queryReport("BD_Material", "{\"Limit\":10}", null);

        assertInstanceOf(Map.class, result.get("data"));
        assertPlainJavaValue(result);
    }

    /**
     * 递归断言返回值只包含纯 Java 集合和基础值，不携带 Gson 类型。
     */
    private void assertPlainJavaValue(Object value) {
        switch (value) {
            case null -> {
            }
            case Map<?, ?> map -> map.values().forEach(this::assertPlainJavaValue);
            case List<?> list -> list.forEach(this::assertPlainJavaValue);
            case String ignored -> {
            }
            case Number ignored -> {
            }
            case Boolean ignored -> {
            }
            default -> throw new AssertionError("Unexpected MCP value type: " + value.getClass());
        }
    }
    /**
     * 构造普通测试用现场目录；元数据缺失时列头自然回落原始 Key。
     *
     * @param gateway 金蝶网关
     * @param knowledge 客户本地知识
     * @return 现场目录
     */
    private K3Catalog fallbackCatalog(K3Gateway gateway, KnowledgeStore knowledge) {
        return new K3Catalog(gateway, knowledge);
    }
}
