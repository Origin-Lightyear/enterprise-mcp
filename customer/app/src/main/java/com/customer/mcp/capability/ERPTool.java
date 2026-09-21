package com.customer.mcp.capability;

import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.annotation.LocalCapability;
import com.kevin.mcp.annotation.OperationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 提供 ERP 领域数据库实体的内部查询 MCP Tool。
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Component
@RequiredArgsConstructor
public class ERPTool {

    /**
     * 根据主键查询财务交易流水明细表（数据湖）记录。
     *
     * @param id 财务交易流水明细表（数据湖）ID
     * @return 匹配的财务交易流水明细表（数据湖）记录；不存在时返回 null
     */
//    @PrivateMcpTool(description = "根据主键查询财务交易流水明细表（数据湖）记录")
//    @LocalCapability(id = "cap.erp.finance-journal.get-by-id", mode = OperationMode.READ_ONLY,
//            batchMode = BatchMode.FOREACH_FALLBACK, dataDomain = "FINANCE")
//    public Object getFinanceJournalById(
//            @PrivateMcpToolParam(description = "财务交易流水明细表（数据湖）ID", required = true) Long id
//    ) {
//        return id;
//    }
}
