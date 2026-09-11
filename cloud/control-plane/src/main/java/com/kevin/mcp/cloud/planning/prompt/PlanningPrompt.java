package com.kevin.mcp.cloud.planning.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 构建云端执行计划生成提示词，并移除仅供客户 Runtime 反射使用的实现细节。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Component
public class PlanningPrompt {

    private static final Set<String> LOCAL_IMPLEMENTATION_FIELDS = Set.of(
            "methodKey", "beanName", "declaringClass", "methodName", "parameterTypeNames");

    /** 定义模型必须遵守的执行计划 JSON Schema。 */
    public static final String OUTPUT_SCHEMA = """
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "intent":{"type":"string"},
                "steps":{"type":"array","items":{"oneOf":[{"$ref":"#/$defs/callStep"},{"$ref":"#/$defs/foreachStep"}]}},
                "error":{"type":"string"}
              },
              "required":["intent","steps"],
              "$defs":{
                "argumentValue":{"anyOf":[
                  {"type":"string"},{"type":"number"},{"type":"boolean"},{"type":"null"},
                  {"type":"array"},{"type":"object"},
                  {"type":"object","additionalProperties":false,"properties":{"fromStep":{"type":"string"}},"required":["fromStep"]},
                  {"type":"object","additionalProperties":false,"properties":{"fromItem":{"type":"string"}},"required":["fromItem"]}
                ]},
                "callStep":{
                  "type":"object","additionalProperties":false,
                  "properties":{
                    "type":{"const":"call"},"id":{"type":"string"},
                    "capabilityId":{"type":"string"},"capabilityVersion":{"type":"integer","minimum":1},
                    "args":{"type":"object","additionalProperties":{"$ref":"#/$defs/argumentValue"}},
                    "saveAs":{"type":"string"},"dependsOn":{"type":"array","items":{"type":"string"}}
                  },
                  "required":["type","id","capabilityId","capabilityVersion","args"]
                },
                "foreachStep":{
                  "type":"object","additionalProperties":false,
                  "properties":{
                    "type":{"const":"foreach"},"id":{"type":"string"},
                    "itemsFrom":{"type":"string"},"itemAs":{"type":"string"},
                    "body":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/callStep"}},
                    "saveAs":{"type":"string"},"dependsOn":{"type":"array","items":{"type":"string"}}
                  },
                  "required":["type","id","itemsFrom","itemAs","body"]
                }
              }
            }
            """;

    /**
     * 构建不包含客户 Java 实现信息的规划消息。
     *
     * @param intent 用户原始意图
     * @param catalogJson 当前 ACTIVE Catalog 的完整技术 Schema
     * @return 分离后的 system 和 user 消息
     */
    public Messages build(String intent, String catalogJson) {
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("Planning intent is required");
        }
        JsonElement catalog = JsonParser.parseString(catalogJson);
        removeLocalImplementationFields(catalog);
        String systemMessage = """
                你是云端内部能力调用计划生成器。根据用户意图和当前客户的能力 Catalog 生成执行计划。
                必须遵守以下规则：
                1. 只能输出一个执行计划 JSON 对象，禁止解释、Markdown 和 JSON Schema 原文。
                2. call 步骤只能使用 Catalog 中存在的 capabilityId 和对应 capabilityVersion，禁止输出 methodKey 或任何 Java 类名。
                3. args 的名称、必填项和字面量类型必须符合目标能力的输入 Schema；禁止生成 SQL、URL 或未在 Catalog 声明的能力。
                4. 按 steps 顺序执行；dependsOn 只能引用之前已完成的步骤 id 或 saveAs。
                5. fromStep 只能引用之前步骤的 id/saveAs 及其结果路径；fromItem 只能出现在 foreach.body 中。
                6. foreach.itemsFrom 必须引用之前返回的集合；body 中只允许 call，循环结果可用 saveAs 保存。
                7. 无法完成时返回 {"intent":"原始意图","steps":[],"error":"原因"}，不得猜测能力或参数。
                8. intent 必须原样返回用户意图。

                输出必须满足以下 JSON Schema：
                """ + OUTPUT_SCHEMA;
        String userMessage = "用户意图：\n" + intent + "\n\n当前 ACTIVE 能力 Catalog：\n" + catalog;
        return new Messages(systemMessage, userMessage);
    }

    private void removeLocalImplementationFields(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                removeLocalImplementationFields(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String field : LOCAL_IMPLEMENTATION_FIELDS) {
            object.remove(field);
        }
        JsonArray children = new JsonArray();
        for (String field : object.keySet()) {
            children.add(object.get(field));
        }
        for (JsonElement child : children) {
            removeLocalImplementationFields(child);
        }
    }

    /**
     * 承载发送给云端 LLM 的两类消息。
     *
     * @param systemMessage 固定规划规则和输出协议
     * @param userMessage 用户意图和客户能力 Catalog
     */
    public record Messages(String systemMessage, String userMessage) {
    }
}
