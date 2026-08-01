package com.kevin.mcp.context;

import com.kevin.mcp.util.GsonUtil;

/**
 * PromptContext
 *
 * @author Kevin
 * 2026/7/21
 */
public class PromptContext {
    public static final String QUERY = """
                你要帮我分析用户指令的含义: "{0}"
                你只能基于给定的方法描述来生成调用计划。
                每个方法描述包含 methodKey、用途说明、参数 Schema 和返回值 Schema。
                ---
                当前可用的方法描述如下: {1}
                ---
                你必须严格按下面的 JSON Schema 返回调用计划，只输出最终 JSON 结果: {2}
                """;
    /**
     * 定义 LLM 必须返回的执行计划 Schema。
     * 保留可读源码并在类加载时压缩空白，避免重复请求携带无意义的格式字符。
     */
    public static final String OUTPUT_SCHEMA = GsonUtil.toJson(GsonUtil.fromJson("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "planId": {
                      "type": "string",
                      "description": "本次调用计划的唯一标识"
                    },
                    "intent": {
                      "type": "string",
                      "description": "原始用户指令"
                    },
                    "steps": {
                      "type": "array",
                      "description": "按顺序执行的调用步骤列表",
                      "items": {
                        "type": "object",
                        "properties": {
                          "stepId": {
                            "type": "string",
                            "description": "步骤的唯一标识，供后续步骤引用"
                          },
                          "methodKey": {
                            "type": "string",
                            "description": "内部方法唯一键，必须来自可用方法描述"
                          },
                          "parameters": {
                            "type": "object",
                            "description": "方法参数，值可以是字面量或引用表达式",
                            "additionalProperties": {
                              "oneOf": [
                                {
                                  "type": "string",
                                  "description": "字面量字符串"
                                },
                                {
                                  "type": "number",
                                  "description": "字面量数字"
                                },
                                {
                                  "type": "boolean",
                                  "description": "字面量布尔值"
                                },
                                {
                                  "type": "object",
                                  "properties": {
                                    "$ref": {
                                      "type": "string",
                                      "description": "引用前面步骤的返回值，格式为 stepId.fieldPath，例如 step1.id"
                                    }
                                  },
                                  "required": ["$ref"],
                                  "additionalProperties": false
                                }
                              ]
                            }
                          },
                          "saveResultAs": {
                            "type": "string",
                            "description": "可选，将本次调用结果保存为指定变量名，供后续步骤复用"
                          },
                          "dependsOn": {
                            "type": "array",
                            "description": "可选，依赖的前置步骤 ID 列表，程序应确保这些步骤先执行",
                            "items": {
                              "type": "string"
                            }
                          }
                        },
                        "required": ["stepId", "methodKey", "parameters"]
                      }
                    }
                  },
                  "required": ["intent", "steps"]
                }
                """, Object.class));
}
