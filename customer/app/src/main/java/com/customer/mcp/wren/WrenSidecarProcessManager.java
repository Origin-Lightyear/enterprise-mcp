package com.customer.mcp.wren;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 按客户应用生命周期托管本机 Wren Adapter 子进程。
 *
 * @author Kevin
 * @date 2026-09-12
 */
@Component
public class WrenSidecarProcessManager {

    private final WrenProperties properties;
    private final WrenSidecarClient client;
    private Process process;

    /**
     * 注入 Wren 配置和内部客户端；仅在显式启用并配置可执行文件时启动子进程。
     *
     * @param properties Wren 配置
     * @param client Wren 内部客户端
     */
    public WrenSidecarProcessManager(WrenProperties properties, WrenSidecarClient client) {
        this.properties = properties;
        this.client = client;
        this.startIfConfigured();
    }

    private void startIfConfigured() {
        if (!properties.isEnabled() || properties.getExecutable() == null
                || properties.getExecutable().isBlank()) {
            return;
        }
        List<String> command = new ArrayList<>();
        command.add(properties.getExecutable());
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            if (properties.getProjectRoot() != null && !properties.getProjectRoot().isBlank()) {
                builder.environment().put("WREN_PROJECT_ROOT", properties.getProjectRoot());
            }
            if (properties.getInstanceDir() != null && !properties.getInstanceDir().isBlank()) {
                builder.environment().put("WREN_INSTANCE_DIR", properties.getInstanceDir());
            }
            if (properties.getAdapterServiceToken() != null && !properties.getAdapterServiceToken().isBlank()) {
                builder.environment().put("WREN_ADAPTER_SERVICE_TOKEN", properties.getAdapterServiceToken());
            }
            process = builder.start();
            waitForHealthy(properties.getStartupTimeout());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Wren Adapter", exception);
        }
    }

    private void waitForHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Wren Adapter exited during startup");
            }
            if (client.isHealthy()) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Wren Adapter", exception);
            }
        }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        throw new IllegalStateException("Wren Adapter health check timed out");
    }

    /** 停止由 customerapp 启动的 Wren Adapter，避免留下孤儿进程。 */
    @PreDestroy
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
