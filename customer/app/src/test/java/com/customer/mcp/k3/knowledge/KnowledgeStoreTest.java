package com.customer.mcp.k3.knowledge;

import com.customer.mcp.k3.client.K3Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证账套知识的信任边界和本地快照恢复。
 *
 * @author Kevin
 * @date 2026-10-14
 */
class KnowledgeStoreTest {

    @TempDir
    Path directory;

    /**
     * 验证客观事实不会直接固化意图映射，用户确认可以覆盖待验证候选。
     */
    @Test
    void separatesObservationFromUserConfirmation() {
        KnowledgeStore store = new KnowledgeStore(properties("tenant-a"));
        store.observe(KnowledgeEntry.Layer.ENTITY, "form:GL_Balance", "present", 3600);
        store.pending(KnowledgeEntry.Layer.ENTITY, "intent:科目余额", "GL_Balance_Np");
        assertEquals(KnowledgeEntry.Source.PENDING,
                store.get(KnowledgeEntry.Layer.ENTITY, "intent:科目余额").source());

        store.confirm(KnowledgeEntry.Layer.ENTITY, "intent:科目余额", "GL_Balance", 1001L, 0);
        store.pending(KnowledgeEntry.Layer.ENTITY, "intent:科目余额", "GL_Balance_Np");
        assertEquals("GL_Balance", store.get(KnowledgeEntry.Layer.ENTITY, "intent:科目余额").value());
        assertThrows(IllegalArgumentException.class, () -> store.observe(
                KnowledgeEntry.Layer.TEMPLATE, "intent:科目余额", "unsafe-template", 0));
    }

    /**
     * 验证知识快照能在相同账套恢复，且不同账套互不共享。
     */
    @Test
    void persistsPerAccountSet() {
        KnowledgeStore first = new KnowledgeStore(properties("tenant-a"));
        first.confirm(KnowledgeEntry.Layer.ENTITY, "intent:总账", "GL_Balance", 1001L, 0);
        first.flush();

        KnowledgeStore restored = new KnowledgeStore(properties("tenant-a"));
        assertEquals("GL_Balance", restored.get(KnowledgeEntry.Layer.ENTITY, "intent:总账").value());
        KnowledgeStore other = new KnowledgeStore(properties("tenant-b"));
        assertNull(other.get(KnowledgeEntry.Layer.ENTITY, "intent:总账"));
    }

    private K3Properties properties(String accountSet) {
        K3Properties properties = new K3Properties();
        properties.setServerUrl("http://k3.example/k3cloud/");
        properties.setAcctId(accountSet);
        properties.setKnowledgeDir(directory.toString());
        return properties;
    }
}
