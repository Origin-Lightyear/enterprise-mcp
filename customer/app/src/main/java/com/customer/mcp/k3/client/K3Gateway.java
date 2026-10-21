package com.customer.mcp.k3.client;

import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

/**
 * 封装本次迁移实际开放的三个金蝶官方 SDK 只读端点，集中维护请求包装格式。
 *
 * <p>对照源项目 {@code K3Gateway}：{@code query=ExecuteBillQuery}、
 * {@code metadata=QueryBusinessInfo}、{@code report=GetSysReportData}。
 * 源项目的 View、Save、Submit、Audit、Push 等端点尚未迁入，不应从本类推断写能力可用。
 * 网关只负责调用和序列化，不把 HTTP/SDK 成功当作金蝶业务成功。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
public class K3Gateway {

    private final K3Client client;

    /**
     * 注入金蝶连接。
     *
     * @param client 官方 SDK 客户端
     */
    public K3Gateway(K3Client client) {
        this.client = client;
    }

    /**
     * 通过 ExecuteBillQuery 查询基础资料或单据列表。
     *
     * <p>请求必须含 FormId、FieldKeys、FilterString、OrderString、StartRow、Limit。
     * FilterString 是金蝶过滤表达式，不是数据库 SQL；不支持 GETDATE、CONVERT、DATEADD 等函数，
     * 日期由调用方计算后作为字面量传入。该接口可能把字段错误放在返回行的
     * {@code Result.ResponseStatus.Errors}，不能只凭响应是数组就认定业务成功。</p>
     *
     * @param formId 表单标识
     * @param fields 逗号分隔的字段 Key
     * @param filter 金蝶过滤表达式
     * @param order 金蝶排序表达式
     * @param startRow 起始行
     * @param limit 返回行数
     * @return 原始 JSON 响应
     */
    public String query(String formId, String fields, String filter, String order, int startRow, int limit) {
        JsonObject request = new JsonObject();
        request.addProperty("FormId", formId);
        request.addProperty("FieldKeys", fields);
        request.addProperty("FilterString", filter);
        request.addProperty("OrderString", order);
        request.addProperty("StartRow", startRow);
        request.addProperty("Limit", limit);
        return client.call("ExecuteBillQuery", api -> api.executeBillQueryJson(request.toString()));
    }

    /**
     * 通过 QueryBusinessInfo 读取当前账套的表单元数据。
     *
     * <p>只使用 {@code Result.ResponseStatus.IsSuccess=true} 的元数据；
     * 对错误响应建立正向缓存会令不存在的 FormId 反复被推荐。</p>
     *
     * @param formId 表单标识
     * @return 原始 JSON 响应
     */
    public String metadata(String formId) {
        JsonObject request = new JsonObject();
        request.addProperty("FormId", formId);
        return client.call("QueryBusinessInfo", api -> api.queryBusinessInfo(request.toString()));
    }

    /**
     * 通过 GetSysReportData 查询金蝶系统报表。
     *
     * <p>源项目实测协议为独立的 formid 字段加内层 data JSON，不能把 FormId 塞进 data，
     * 也不能改走 KDSReportAPIService。data 中的分页是 StartRow/Limit，
     * 不是 PageIndex/PageSize；字段与 Model 结构由具体报表决定。</p>
     *
     * @param formId 报表标识
     * @param dataJson 报表参数 JSON
     * @return 原始 JSON 响应
     */
    public String report(String formId, String dataJson) {
        return client.call("GetSysReportData", api -> api.getSysReportData(formId, dataJson));
    }
}
