package com.kevin.mcp.export;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 大结果导出机制的部署配置；所有查询类 MCP 工具复用同一套阈值与生命周期。
 *
 * @author Kevin
 * @date 2026-10-19
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.export")
public class ExportProperties {

    /**
     * 导出文件与下载短码的公共基础地址；为空时回落到客户本机地址。
     * 客户部署在独立服务器时必须配置，保证员工电脑上的 Agent 可直接下载。
     */
    private String publicBaseUrl = "";

    /** 导出文件落盘目录；空值时使用系统临时目录下的 enterprise-mcp-exports。 */
    private String exportDir = "";

    /** 导出文件服务器保留时长；超时由清理任务删除。 */
    private Duration fileRetention = Duration.ofHours(24);

    /** 下载短码有效期；短码一次性，下载成功或超时后失效。 */
    private Duration downloadTokenTtl = Duration.ofMinutes(15);

    /** 内联模式允许的最大行数；任一阈值超限即转文件模式。 */
    private int inlineMaxRows = 100;

    /** 内联模式允许的序列化最大字节数；防止少量宽行撑爆模型上下文。 */
    private int inlineMaxBytes = 32 * 1024;

    /** 导出目录磁盘总量上限；超限后清理任务删除最旧文件兜底。 */
    private long maxTotalMb = 500;

    /** 单个导出文件的最大行数；防止无界过滤条件把服务器磁盘写满。 */
    private long maxExportRows = 100_000;

    /**
     * 导出文件格式；csv 为当前默认格式，xlsx 按 ERP 导出风格生成美化工作簿。
     */
    private String format = "csv";
}