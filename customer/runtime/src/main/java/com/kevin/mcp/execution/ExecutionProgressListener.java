package com.kevin.mcp.execution;

/**
 * 接收不包含内部实现细节的执行进度事件。
 *
 * @author Kevin
 * @date 2026-09-17
 */
public interface ExecutionProgressListener {

    /** 无需对外通知时使用的空监听器。 */
    ExecutionProgressListener NONE = new ExecutionProgressListener() { };

    /**
     * 通知计划即将开始执行。
     *
     * @param totalSteps 顶层业务步骤数
     */
    default void planStarted(int totalSteps) {
    }

    /**
     * 通知一个顶层业务步骤开始执行。
     *
     * @param currentStep 当前步骤序号，从 1 开始
     * @param totalSteps 顶层业务步骤总数
     */
    default void stepStarted(int currentStep, int totalSteps) {
    }

    /**
     * 通知目标系统缺少批量接口并开始逐项处理。
     *
     * @param totalItems 待处理项数
     */
    default void foreachStarted(int totalItems) {
    }

    /**
     * 通知逐项处理进度。
     *
     * @param completedItems 已完成项数
     * @param totalItems 总项数
     */
    default void foreachProgress(int completedItems, int totalItems) {
    }

    /** 通知整份计划执行完成。 */
    default void planCompleted() {
    }
}
