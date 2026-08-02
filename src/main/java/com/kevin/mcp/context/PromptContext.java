package com.kevin.mcp.context;

import com.kevin.mcp.util.GsonUtil;

/**
 * 承载执行计划生成阶段使用的固定提示词与输出 Schema。
 *
 * @author Kevin
 * @date 2026-08-02
 */
public class PromptContext {
    public static final String QUERY = """
                你是内部方法调用计划生成器。
                你的任务是根据用户请求和可用方法列表生成执行计划。
                你只能输出执行计划实例 JSON，不能输出 JSON Schema 原文，不能解释，不能输出 markdown 代码块。
                如果无法完成，也必须输出合法 JSON，对象格式为 '{'"intent":"原始意图","steps":[],"error":"原因"'}'。
                JSON Schema 只是输出约束，不是输出内容本身。
                禁止输出 $schema、$defs、properties、required、description、additionalProperties、oneOf 等 Schema 定义字段，除非它们本来就是执行计划实例字段。
                ---
                用户请求: "{0}"
                ---
                当前可用方法列表: {1}
                ---
                输出必须满足以下 JSON Schema，只输出最终 JSON 对象: {2}
                """;

    /**
     * 定义 LLM 必须返回的执行计划 Schema。
     * 将循环语义直接写入 description，帮助模型稳定区分普通调用与 foreach 调用。
     */
    public static final String OUTPUT_SCHEMA = GsonUtil.toJson(GsonUtil.fromJson("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "description": "内部方法执行计划。按 steps 顺序执行；type=call 表示普通调用；type=foreach 表示先取一个集合，再对集合中的每个元素重复执行 body。",
                  "additionalProperties": false,
                  "properties": {
                    "intent": {
                      "type": "string",
                      "description": "用户原始意图。"
                    },
                    "steps": {
                      "type": "array",
                      "description": "执行步骤列表，支持 call 和 foreach 两种步骤。",
                      "items": {
                        "oneOf": [
                          {
                            "$ref": "#/$defs/callStep"
                          },
                          {
                            "$ref": "#/$defs/foreachStep"
                          }
                        ]
                      }
                    },
                    "error": {
                      "type": "string",
                      "description": "当无法生成可执行计划时填写失败原因；成功时可省略。"
                    }
                  },
                  "required": ["intent", "steps"],
                  "$defs": {
                    "callStep": {
                      "type": "object",
                      "description": "普通方法调用步骤。用于直接调用一个内部方法，并可把结果保存给后续步骤使用。",
                      "additionalProperties": false,
                      "properties": {
                        "type": {
                          "const": "call",
                          "description": "固定为 call。"
                        },
                        "id": {
                          "type": "string",
                          "description": "当前步骤唯一标识。"
                        },
                        "methodKey": {
                          "type": "string",
                          "description": "要调用的内部方法唯一键，必须从可用方法列表中选择。"
                        },
                        "args": {
                          "type": "object",
                          "description": "方法入参对象，key 必须与目标方法参数名一致。value 可以是字面量、fromStep 或 fromItem 引用。",
                          "additionalProperties": {
                            "oneOf": [
                              {
                                "type": "string",
                                "description": "字符串字面量。"
                              },
                              {
                                "type": "number",
                                "description": "数字字面量。"
                              },
                              {
                                "type": "boolean",
                                "description": "布尔字面量。"
                              },
                              {
                                "type": "object",
                                "description": "引用前面步骤结果中的字段，例如 {\\\"fromStep\\\":\\\"stores.id\\\"}。",
                                "additionalProperties": false,
                                "properties": {
                                  "fromStep": {
                                    "type": "string",
                                    "description": "从前面步骤结果或 saveAs 别名中取值的路径，例如 step1.id、stores.id。"
                                  }
                                },
                                "required": ["fromStep"]
                              },
                              {
                                "type": "object",
                                "description": "仅能在 foreach 的 body 中使用，表示从当前循环元素中取字段，例如 {\\\"fromItem\\\":\\\"id\\\"}。",
                                "additionalProperties": false,
                                "properties": {
                                  "fromItem": {
                                    "type": "string",
                                    "description": "从当前循环元素取值的字段路径，例如 id、storeId、owner.name。"
                                  }
                                },
                                "required": ["fromItem"]
                              }
                            ]
                          }
                        },
                        "saveAs": {
                          "type": "string",
                          "description": "可选。把当前调用结果保存为别名，供后续步骤引用。"
                        },
                        "dependsOn": {
                          "type": "array",
                          "description": "可选。依赖的前置步骤 ID 或别名列表。",
                          "items": {
                            "type": "string"
                          }
                        }
                      },
                      "required": ["type", "id", "methodKey", "args"]
                    },
                    "foreachStep": {
                      "type": "object",
                      "description": "循环步骤。先从 itemsFrom 取到一个集合，然后对集合中的每个元素依次执行 body。",
                      "additionalProperties": false,
                      "properties": {
                        "type": {
                          "const": "foreach",
                          "description": "固定为 foreach。"
                        },
                        "id": {
                          "type": "string",
                          "description": "当前循环步骤唯一标识。"
                        },
                        "itemsFrom": {
                          "type": "string",
                          "description": "要遍历的集合来源，通常填写前面某个步骤的 saveAs 别名或步骤结果路径，例如 stores、step1.records。"
                        },
                        "itemAs": {
                          "type": "string",
                          "description": "当前循环元素别名，仅用于表达语义。"
                        },
                        "body": {
                          "type": "array",
                          "description": "循环体步骤列表。每遍历到一个元素，就顺序执行一次 body。第一版建议 body 中只放 call 步骤。",
                          "items": {
                            "$ref": "#/$defs/callStep"
                          }
                        },
                        "saveAs": {
                          "type": "string",
                          "description": "可选。保存整个循环结果列表。建议语义为收集每次循环体最后一步的结果。"
                        },
                        "dependsOn": {
                          "type": "array",
                          "description": "可选。依赖的前置步骤 ID 或别名列表。",
                          "items": {
                            "type": "string"
                          }
                        }
                      },
                      "required": ["type", "id", "itemsFrom", "itemAs", "body"]
                    }
                  }
                }
                """, Object.class));
}
