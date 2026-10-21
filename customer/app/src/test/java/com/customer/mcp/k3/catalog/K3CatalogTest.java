package com.customer.mcp.k3.catalog;

import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证金蝶目录的候选排序及元数据错误响应隔离。
 *
 * @author Kevin
 * @date 2026-10-14
 */
class K3CatalogTest {

    @TempDir
    Path directory;

    /**
     * 验证用户确认的表单优先，出厂目录仅标记为待验证。
     */
    @Test
    void ranksConfirmedCandidateAheadOfStaticCandidate() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Catalog catalog = new K3Catalog(mock(K3Gateway.class), knowledge);
        var before = catalog.search("采购订单");
        assertFalse(before.isEmpty());
        assertFalse(catalog.search("请帮我查一下采购订单").isEmpty());
        assertFalse(before.getFirst().verified());
        knowledge.confirm(KnowledgeEntry.Layer.ENTITY, "intent:采购订单", before.getFirst().formId(), 1001L, 0);
        assertEquals(1.0, catalog.search("采购订单").getFirst().confidence());
    }

    /**
     * 验证总账凭证的固定工具示例能通过自然语言目录检索命中。
     */
    @Test
    void findsVoucherTemplateInStaticCatalog() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        K3Catalog catalog = new K3Catalog(mock(K3Gateway.class),
                new KnowledgeStore(properties));

        var candidates = catalog.search("查一下2025年11月的总账凭证");
        assertFalse(candidates.isEmpty());
        assertEquals("GL_Voucher_New", candidates.getFirst().formId());
    }
    /**
     * 验证 QueryBusinessInfo 的失败响应不能写成已验证存在。
     */
    @Test
    void rejectsFailedMetadataWithoutLearningPositiveFact() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.metadata("GL_Balance")).thenReturn("{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false}}}");
        K3Catalog catalog = new K3Catalog(gateway, knowledge);
        assertThrows(IllegalStateException.class, () -> catalog.describe("GL_Balance"));
        assertEquals(null, knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:GL_Balance"));
    }
}
