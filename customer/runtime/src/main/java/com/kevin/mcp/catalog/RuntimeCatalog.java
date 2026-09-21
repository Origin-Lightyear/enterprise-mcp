package com.kevin.mcp.catalog;

import com.kevin.mcp.processor.JsonExecutionPlan;

/**
 * 管理 Runtime 当前 Catalog、Current MDL、规划就绪和旧计划失效。
 *
 * @author Kevin
 * @date 2026-09-18
 */
public interface RuntimeCatalog {

    /**
     * 校验并应用一次完整 MDL Update。
     *
     * <p>实现必须先获得 Control Plane 对候选 Catalog 的确认，再提交 Current MDL。</p>
     *
     * @param command MDL Update 命令
     * @return 成功应用后的绑定
     * @throws CatalogException 校验、并发、云端确认或提交失败
     */
    MdlUpdateResult applyMdl(MdlUpdateCommand command);

    /**
     * 返回不包含 Catalog JSON 或 MDL 内容的当前状态。
     *
     * @return 当前 Catalog 状态
     */
    CatalogState current();

    /**
     * 返回规划请求必须携带的精确当前绑定。
     *
     * @return 当前规划绑定
     * @throws CatalogException 当前未规划就绪
     */
    PlanningBinding requirePlanningBinding();

    /**
     * 校验计划在当前执行检查点仍绑定 Current Catalog。
     *
     * @param plan 云端执行计划
     * @throws CatalogException Runtime 未就绪或计划已过期
     */
    void requireExecutable(JsonExecutionPlan plan);

    /**
     * 重新确认并引用本地实际 Current Catalog。
     *
     * @throws CatalogException 云端不可用或摘要确认失败
     */
    void synchronize();
}
