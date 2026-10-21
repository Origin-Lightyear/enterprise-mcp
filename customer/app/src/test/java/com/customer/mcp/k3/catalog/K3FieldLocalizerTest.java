package com.customer.mcp.k3.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证动态列头翻译的精确命中、lookup 拼接、未知回落和元数据缓存。
 *
 * @author Kevin
 * @date 2026-10-19
 */
class K3FieldLocalizerTest {

    /**
     * 精确 Key 使用元数据中文名；lookup 未命中时按基础字段名加后缀语义拼接。
     */
    @Test
    void localizesExactAndLookupFields() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("GL_Voucher_New")).thenReturn(metadata());
        K3FieldLocalizer localizer = new K3FieldLocalizer(catalog);

        assertEquals(List.of("凭证编号", "账簿名称", "账簿编码", "UNKNOWN_FIELD"),
                localizer.fieldNames("GL_Voucher_New",
                        "FBillNo,FAccountBookID.FName,FAccountBookID.FNumber,UNKNOWN_FIELD"));
    }

    /**
     * 元数据不可用时保留原始 Key，查询结果展示不被列头翻译中断。
     */
    @Test
    void fallsBackToFieldKeysWhenMetadataUnavailable() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("GL_Voucher_New")).thenThrow(new IllegalStateException("down"));
        K3FieldLocalizer localizer = new K3FieldLocalizer(catalog);

        assertEquals(List.of("FBillNo", "FAccountBookID.FName"),
                localizer.fieldNames("GL_Voucher_New", "FBillNo,FAccountBookID.FName"));
    }

    /**
     * 同一表单 60 秒内复用元数据映射，避免固定工具批量导出反复探测。
     */
    @Test
    void cachesMetadataForRepeatedQueries() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("GL_Voucher_New")).thenReturn(metadata());
        K3FieldLocalizer localizer = new K3FieldLocalizer(catalog);

        localizer.fieldNames("GL_Voucher_New", "FBillNo");
        localizer.fieldNames("GL_Voucher_New", "FBillNo");
        verify(catalog, times(1)).describe("GL_Voucher_New");
    }

    /**
     * 构造金蝶 QueryBusinessInfo 的最小元数据结构。
     *
     * @return 含两个字段的元数据
     */
    private JsonObject metadata() {
        JsonObject billNo = field("FBillNo", "凭证编号");
        JsonObject accountBook = field("FAccountBookID", "账簿");
        JsonObject entry = new JsonObject();
        entry.add("Fields", new JsonArray());
        entry.getAsJsonArray("Fields").add(billNo);
        entry.getAsJsonArray("Fields").add(accountBook);
        JsonObject model = new JsonObject();
        model.add("Entrys", new JsonArray());
        model.getAsJsonArray("Entrys").add(entry);
        JsonObject result = new JsonObject();
        result.add("NeedReturnData", model);
        JsonObject response = new JsonObject();
        response.add("Result", result);
        return response;
    }

    /**
     * 构造带简体中文多语言名称的字段节点。
     *
     * @param key 字段 Key
     * @param name 简体中文名
     * @return 字段 JSON
     */
    private JsonObject field(String key, String name) {
        JsonObject localized = new JsonObject();
        localized.addProperty("Key", 2052);
        localized.addProperty("Value", name);
        JsonObject field = new JsonObject();
        field.addProperty("Key", key);
        field.add("Name", new JsonArray());
        field.getAsJsonArray("Name").add(localized);
        return field;
    }
}
