package com.customer.mcp.capability;

import com.kevin.mcp.annotation.LocalCapability;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.annotation.PrivateMcpTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提供客户能力实现示例；正式项目应替换为客户真实系统 Adapter。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class CustomerCapability {

    /** 查询客户内部系统中的示例数据。 */
    @PrivateMcpTool(description = "查询客户示例数据")
    @LocalCapability(id = "cap.customer.example.query", version = 1,
            mode = OperationMode.READ_ONLY, dataDomain = "customer-example")
    public Map<String, Object> query(Map<String, Object> args) {
        return Map.of("status", "replace-with-tenant-adapter", "args", args == null ? Map.of() : args);
    }
}
