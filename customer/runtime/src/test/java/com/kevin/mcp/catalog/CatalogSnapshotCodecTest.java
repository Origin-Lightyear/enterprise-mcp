package com.kevin.mcp.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Runtime 与 Control Plane 共用的 Catalog canonical 编码规则。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class CatalogSnapshotCodecTest {

    /** 验证对象字段顺序和空白不影响摘要，数组领域顺序保持不变。 */
    @Test
    void shouldProduceStableDigestForEquivalentJson() {
        String left = "{\"z\":[2,1],\"a\":{\"y\":2,\"x\":1}}";
        String right = " { \"a\" : { \"x\" : 1, \"y\" : 2 }, \"z\" : [2, 1] } ";

        assertThat(CatalogSnapshotCodec.canonicalize(left))
                .isEqualTo("{\"a\":{\"x\":1,\"y\":2},\"z\":[2,1]}");
        assertThat(CatalogSnapshotCodec.digest(left)).isEqualTo(CatalogSnapshotCodec.digest(right));
        assertThat(CatalogSnapshotCodec.digest(left)).matches("sha256:[0-9a-f]{64}");
    }
}
