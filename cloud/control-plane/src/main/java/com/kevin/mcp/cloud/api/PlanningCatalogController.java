package com.kevin.mcp.cloud.api;

import com.kevin.mcp.cloud.catalog.PlanningCatalogService;
import com.kevin.mcp.cloud.catalog.CatalogCandidate;
import com.kevin.mcp.cloud.catalog.CatalogConfirmation;
import com.kevin.mcp.cloud.catalog.RuntimeCatalogReference;
import com.kevin.mcp.cloud.catalog.RuntimeRegistration;
import com.kevin.mcp.cloud.catalog.PlanningKnowledgeService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 Catalog Snapshot 确认和 Runtime 当前引用接口。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@RestController
@Profile("control-plane")
@RequestMapping("/control-plane")
public class PlanningCatalogController {

    private final PlanningCatalogService catalogService;
    private final PlanningKnowledgeService knowledgeService;

    /**
     * 注入目录服务。
     *
     * @param catalogService 目录服务
     * @param knowledgeService 结构知识快照服务
     */
    public PlanningCatalogController(PlanningCatalogService catalogService,
                                     PlanningKnowledgeService knowledgeService) {
        this.catalogService = catalogService;
        this.knowledgeService = knowledgeService;
    }

    /**
     * 确认 Runtime 构建的不可变 Catalog Snapshot。
     *
     * @param candidate 候选快照
     * @return 独立重算后的确认结果
     */
    @PostMapping("/catalog-snapshots/confirm")
    public CatalogConfirmation confirm(@RequestBody CatalogCandidate candidate) {
        return catalogService.confirm(candidate);
    }

    /**
     * 更新 Runtime 已在本地提交的当前 Catalog 引用。
     *
     * @param reference Runtime 当前引用
     * @return 已保存引用
     */
    @PutMapping("/runtime-catalog-references")
    public RuntimeRegistration reference(@RequestBody RuntimeCatalogReference reference) {
        return catalogService.reference(reference);
    }

    /**
     * 保存 Runtime 上传的金蝶规划知识快照。
     *
     * @param upload 绑定当前 Runtime 身份的知识快照
     * @return 已确认摘要
     */
    @PostMapping("/knowledge-snapshots")
    public Map<String, String> uploadKnowledge(@RequestBody KnowledgeSnapshotUpload upload) {
        return knowledgeService.upload(upload);
    }

}
