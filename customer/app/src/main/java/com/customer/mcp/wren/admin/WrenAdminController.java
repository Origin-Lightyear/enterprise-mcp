package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.catalog.CatalogState;
import com.kevin.mcp.catalog.MdlUpdateCommand;
import com.kevin.mcp.catalog.MdlUpdateResult;
import com.kevin.mcp.catalog.RuntimeCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 代理 Wren 管理操作，并隐藏 Adapter 网络位置与 service token。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@RestController
@RequestMapping("/internal/wren/admin")
public class WrenAdminController {

    private static final Logger log = LoggerFactory.getLogger(WrenAdminController.class);

    private final WrenSidecarClient client;
    private final RuntimeCatalog runtimeCatalog;

    /**
     * 创建管理 BFF。
     *
     * @param client Wren 本机客户端
     * @param runtimeCatalog Runtime Catalog module
     */
    public WrenAdminController(WrenSidecarClient client,
                               RuntimeCatalog runtimeCatalog) {
        this.client = client;
        this.runtimeCatalog = runtimeCatalog;
    }

    /**
     * 查询数据源脱敏列表。
     *
     * @return 数据源脱敏列表
     */
    @GetMapping("/datasources")
    public Map<?, ?> datasources() { return exchange(HttpMethod.GET, "/internal/admin/datasources", null); }

    /** 下载包含凭据的 Wren 配置备份。 */
    @GetMapping(value = "/backup", produces = "application/zip")
    public ResponseEntity<byte[]> backup() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wren-backup.zip\"")
                .body(client.downloadBackup());
    }

    /** 恢复上传的 Wren 配置备份并要求 Runtime 重新同步 Catalog。 */
    @PostMapping(value = "/restore", consumes = "application/zip")
    public Map<?, ?> restore(@RequestBody byte[] backup) {
        runtimeCatalog.invalidate("WREN_RESTORE_PENDING");
        try {
            return client.restoreBackup(backup);
        } finally {
            try {
                runtimeCatalog.resumeAfterRestore();
            } catch (RuntimeException exception) {
                log.warn("Wren backup restore finished but Catalog synchronization is pending", exception);
            }
        }
    }

    /**
     * 创建并测试数据源。
     *
     * @param request 数据源请求
     * @return 创建结果
     */
    @PostMapping("/datasources")
    public Map<?, ?> createDatasource(@RequestBody DatasourceRequest request) {
        return exchange(HttpMethod.POST, "/internal/admin/datasources", request);
    }

    /**
     * 更新并测试指定数据源。
     *
     * @param name Profile 名称
     * @param request 数据源请求
     * @return 更新结果
     */
    @PutMapping("/datasources/{name}")
    public Map<?, ?> updateDatasource(@PathVariable String name, @RequestBody DatasourceRequest request) {
        return exchange(HttpMethod.PUT, "/internal/admin/datasources/" + segment(name), request);
    }

    /**
     * 测试指定数据源连接。
     *
     * @param name Profile 名称
     * @return 测试结果
     */
    @PostMapping("/datasources/{name}/test")
    public Map<?, ?> testDatasource(@PathVariable String name) {
        return exchange(HttpMethod.POST, "/internal/admin/datasources/" + segment(name) + "/test", Map.of());
    }

    /**
     * 删除未被建模内容或 Current MDL 使用的数据源。
     *
     * @param name Profile 名称
     * @return 删除结果
     */
    @DeleteMapping("/datasources/{name}")
    public Map<?, ?> deleteDatasource(@PathVariable String name) {
        return exchange(HttpMethod.DELETE, "/internal/admin/datasources/" + segment(name), null);
    }

    /**
     * 创建 Schema 异步探测任务。
     *
     * @param request 探测请求
     * @return 异步任务
     */
    @PostMapping("/introspection-jobs")
    public Map<?, ?> createJob(@RequestBody IntrospectionRequest request) {
        return exchange(HttpMethod.POST, "/internal/admin/introspection-jobs", request);
    }

    /**
     * 查询 Schema 探测任务状态。
     *
     * @param jobId 任务 ID
     * @return 任务状态
     */
    @GetMapping("/introspection-jobs/{jobId}")
    public Map<?, ?> job(@PathVariable String jobId) {
        return exchange(HttpMethod.GET, "/internal/admin/introspection-jobs/" + segment(jobId), null);
    }

    /**
     * 查询已完成任务的 Schema Snapshot。
     *
     * @param jobId 任务 ID
     * @return Schema Snapshot
     */
    @GetMapping("/introspection-jobs/{jobId}/snapshot")
    public Map<?, ?> snapshot(@PathVariable String jobId) {
        return exchange(HttpMethod.GET, "/internal/admin/introspection-jobs/" + segment(jobId) + "/snapshot", null);
    }

    /**
     * 取消未完成的 Schema 探测任务。
     *
     * @param jobId 任务 ID
     * @return 取消结果
     */
    @DeleteMapping("/introspection-jobs/{jobId}")
    public Map<?, ?> cancelJob(@PathVariable String jobId) {
        return exchange(HttpMethod.DELETE, "/internal/admin/introspection-jobs/" + segment(jobId), null);
    }

    /**
     * 从 Snapshot 选表并生成建模内容。
     *
     * @param jobId 任务 ID
     * @param request 选表请求
     * @return 建模内容
     */
    @PostMapping("/modeling/from-snapshot/{jobId}")
    public Map<?, ?> createModeling(@PathVariable String jobId, @RequestBody TableSelectionRequest request) {
        return exchange(HttpMethod.POST, "/internal/admin/modeling/from-snapshot/" + segment(jobId), request);
    }

    /**
     * 查询指定数据源的建模内容；未指定时返回当前连接或最近编辑内容。
     *
     * @param profileName Profile 名称
     * @return 当前建模内容
     */
    @GetMapping("/modeling")
    public Map<?, ?> modeling(@RequestParam(required = false) String profileName) {
        String query = profileName == null ? "" : "?profileName=" + segment(profileName);
        return exchange(HttpMethod.GET, "/internal/admin/modeling" + query, null);
    }

    /**
     * 更新模型元数据和字段配置。
     *
     * @param modelName 模型名
     * @param request 模型更新
     * @return 最新建模内容
     */
    @PutMapping("/modeling/models/{modelName}")
    public Map<?, ?> updateModel(@PathVariable String modelName, @RequestBody ModelUpdateRequest request) {
        return exchange(HttpMethod.PUT, "/internal/admin/modeling/models/" + segment(modelName), request);
    }

    /**
     * 从 Draft 语义模型中移除一张表；不会修改真实数据库。
     *
     * @param modelName 模型名
     * @param revision 建模 revision
     * @param profileName Profile 名称
     * @return 最新建模内容
     */
    @DeleteMapping("/modeling/models/{modelName}")
    public Map<?, ?> deleteModel(@PathVariable String modelName, @RequestParam long revision,
                                 @RequestParam(required = false) String profileName) {
        String profileQuery = profileName == null ? "" : "&profileName=" + segment(profileName);
        return exchange(HttpMethod.DELETE,
                "/internal/admin/modeling/models/" + segment(modelName) + "?revision=" + revision + profileQuery,
                null);
    }

    /**
     * 新增或更新一条关系。
     *
     * @param name 关系名
     * @param request 关系更新
     * @return 最新建模内容
     */
    @PutMapping("/modeling/relationships/{name}")
    public Map<?, ?> saveRelationship(@PathVariable String name, @RequestBody RelationshipRequest request) {
        return exchange(HttpMethod.PUT, "/internal/admin/modeling/relationships/" + segment(name), request);
    }

    /**
     * 以一个建模 revision 原子替换全部关系，支持首次建模时先审核再统一保存。
     *
     * @param request 关系集合和建模 revision
     * @return 最新建模内容
     */
    @PutMapping("/modeling/relationships")
    public Map<?, ?> replaceRelationships(@RequestBody RelationshipBatchRequest request) {
        return exchange(HttpMethod.PUT, "/internal/admin/modeling/relationships", request);
    }

    /**
     * 删除一条关系并校验建模 revision。
     *
     * @param name 关系名
     * @param revision 建模 revision
     * @param profileName Profile 名称
     * @return 最新建模内容
     */
    @DeleteMapping("/modeling/relationships/{name}")
    public Map<?, ?> deleteRelationship(@PathVariable String name, @RequestParam long revision,
                                         @RequestParam(required = false) String profileName) {
        String profileQuery = profileName == null ? "" : "&profileName=" + segment(profileName);
        return exchange(HttpMethod.DELETE,
                "/internal/admin/modeling/relationships/" + segment(name) + "?revision=" + revision + profileQuery,
                null);
    }

    /**
     * 校验指定数据源的建模内容是否可应用。
     *
     * @param request 建模数据源选择
     * @return 校验结果
     */
    @PostMapping("/modeling/validation")
    public Map<?, ?> validateModeling(@RequestBody(required = false) ModelingSelectionRequest request) {
        return exchange(HttpMethod.POST, "/internal/admin/modeling/validation",
                request == null ? Map.of() : request);
    }

    /**
     * 经云端确认后原子应用指定数据源的建模更新。
     *
     * @param request MDL Update 输入
     * @param servletRequest 已通过管理 Session 校验的请求
     * @return 更新后的 Current MDL 和 Catalog 绑定
     */
    @PostMapping("/mdl-update")
    public Map<String, Object> applyMdl(@RequestBody MdlUpdateRequest request,
                                        HttpServletRequest servletRequest) {
        WrenAdminSessionService.Session session = (WrenAdminSessionService.Session) servletRequest.getAttribute(
                WrenAdminSessionService.Session.class.getName());
        if (session == null) {
            throw new SecurityException("Authenticated administrator is required");
        }
        MdlUpdateResult result = runtimeCatalog.applyMdl(new MdlUpdateCommand(request.profileName(),
                request.expectedMdlDigest(), request.idempotencyKey(), session.id()));
        return response(result);
    }

    /**
     * 查询 Runtime 当前规划就绪状态。
     *
     * @return Catalog 状态
     */
    @GetMapping("/catalog-state")
    public Map<String, Object> catalogState() {
        CatalogState current = runtimeCatalog.current();
        return response(current);
    }

    private Map<?, ?> exchange(HttpMethod method, String path, Object body) {
        return client.adminExchange(method, path, body);
    }

    private Map<String, Object> response(Object data) {
        return Map.of("code", "OK", "message", "OK", "data", data,
                "requestId", UUID.randomUUID().toString());
    }

    private String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    /**
     * 表示数据源创建或更新输入。
     *
     * @param name Profile 名称
     * @param datasource 数据源类型
     * @param host 数据库主机
     * @param port 数据库端口
     * @param database 数据库名称
     * @param user 用户名
     * @param password 创建或轮换时提供的密码
     * @param secretName 本地密钥变量名
     * @param sslMode SSL 模式
     * @param driver SQL Server 驱动名称
     * @param active 是否切换为活动 Profile
     */
    public record DatasourceRequest(String name, String datasource, String host, String port, String database,
                                    String user, String password, String secretName, String sslMode,
                                    String driver, Boolean active) { }

    /**
     * 表示 Schema 探测输入。
     *
     * @param profileName Profile 名称
     * @param schemas 需要探测的 Schema 列表
     */
    public record IntrospectionRequest(String profileName, List<String> schemas) { }

    /**
     * 表示从 Snapshot 选择数据表的输入。
     *
     * @param tableIds 数据表稳定 ID 列表
     * @param mergeExisting 是否在已有 Draft 上保留人工语义并执行差异合并
     */
    public record TableSelectionRequest(List<String> tableIds, boolean mergeExisting) { }

    /**
     * 表示 Model 元数据和字段更新。
     *
     * @param revision 建模 revision
     * @param profileName Profile 名称，用于隔离多个数据源的建模内容
     * @param displayName 展示名
     * @param description 业务描述
     * @param columns 字段更新列表
     */
    public record ModelUpdateRequest(long revision, String profileName, String displayName, String description,
                                     List<Map<String, Object>> columns) { }

    /**
     * 表示关系端点、基数和确认状态。
     *
     * @param revision 建模 revision
     * @param profileName Profile 名称，用于隔离多个数据源的建模内容
     * @param fromModel 起点模型
     * @param fromColumns 起点字段
     * @param toModel 终点模型
     * @param toColumns 终点字段
     * @param cardinality 关系基数
     * @param status 审核状态
     * @param source 关系来源
     */
    public record RelationshipRequest(long revision, String profileName, String fromModel, List<String> fromColumns,
                                      String toModel, List<String> toColumns, String cardinality,
                                      String status, String source) { }

    /**
     * 表示关系审核页一次提交的完整结果。
     *
     * @param revision 建模 revision
     * @param profileName Profile 名称，用于隔离多个数据源的建模内容
     * @param relationships 完整关系集合
     */
    public record RelationshipBatchRequest(long revision, String profileName,
                                           List<Map<String, Object>> relationships) { }

    /**
     * 表示需要读取或校验的建模数据源。
     *
     * @param profileName Profile 名称
     */
    public record ModelingSelectionRequest(String profileName) { }

    /**
     * 表示一次完整 MDL Update 输入。
     *
     * @param profileName 待应用的建模数据源
     * @param expectedMdlDigest 页面加载时的 Current MDL Digest
     * @param idempotencyKey 本次提交幂等键
     */
    public record MdlUpdateRequest(String profileName, String expectedMdlDigest, String idempotencyKey) { }
}
