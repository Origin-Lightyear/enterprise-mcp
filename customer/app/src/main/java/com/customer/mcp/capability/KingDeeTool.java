package com.customer.mcp.capability;

import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.annotation.LocalCapability;
import com.kevin.mcp.annotation.OperationMode;
import org.springframework.stereotype.Component;

/**
 * 提供金蝶员工查询能力的客户实现入口。
 *
 * @author Kevin
 * @date 2026-07-09
 */
@Component
public class KingDeeTool {

    /**
     * 返回金蝶员工查询占位结果，客户接入时由实际 Adapter 替换。
     *
     * @param id 金蝶员工 ID
     * @return 当前客户实现返回的查询结果
     */
    @PrivateMcpTool(description = "查询金蝶员工")
    @LocalCapability(id = "cap.kingdee.employee.query", mode = OperationMode.READ_ONLY,
            batchMode = BatchMode.FOREACH_FALLBACK, dataDomain = "EMPLOYEE")
    public String query(@PrivateMcpToolParam(description = "金蝶员工ID", required = true) long id) {
        return "{\"status\":\"replace-with-kingdee-adapter\",\"id\":" + id + "}";
    }
}
