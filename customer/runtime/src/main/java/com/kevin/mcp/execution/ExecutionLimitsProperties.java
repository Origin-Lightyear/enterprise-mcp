package com.kevin.mcp.execution;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 限制单份执行计划的资源消耗。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.execution")
public class ExecutionLimitsProperties {
    /** 计划允许声明的最大步骤数。 */
    private int maxSteps = 100;
    /** 单个 foreach 最大迭代次数。 */
    private int maxLoopIterations = 1000;
    /** 执行结果序列化后的最大字节数。 */
    private int maxResultBytes = 1_048_576;
    /** 计划总执行时间上限。 */
    private Duration timeout = Duration.ofSeconds(60);
}
