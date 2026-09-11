# JSON Plan Executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `LLMService` 增加一条可执行的 JSON 协议链路，把 LLM 输出的调用计划解析成步骤并串行调用已注册的内部 Spring Bean 方法，最终返回执行结果。

**Architecture:** 新增协议模型、计划解析器和计划执行器三层结构。`LLMService` 保持提示词组装职责，只在拿到模型结果后委托解析器与执行器完成校验、依赖处理、反射调用与结果汇总。

**Tech Stack:** Java 21, Spring Boot 4.1, Gson, JUnit 5, Spring Test

---

### Task 1: 建立执行器测试骨架

**Files:**
- Modify: `D:\Codes\mcp-project\pom.xml`
- Create: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\service\JsonPlanParserTest.java`
- Create: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\service\JsonPlanExecutorTest.java`

- [ ] **Step 1: 写计划解析的失败用例**

```java
package com.kevin.mcp.service;

import com.kevin.mcp.processor.JsonPlanParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonPlanParserTest {

    @Test
    void shouldRejectDuplicateStepId() {
        String planJson = """
                {
                  "intent": "query order",
                  "steps": [
                    {
                      "stepId": "step1",
                      "methodKey": "tool#get(java.lang.String)",
                      "parameters": {
                        "orderId": "A001"
                      }
                    },
                    {
                      "stepId": "step1",
                      "methodKey": "tool#get(java.lang.String)",
                      "parameters": {
                        "orderId": "A002"
                      }
                    }
                  ]
                }
                """;

        JsonPlanParser parser = new JsonPlanParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(planJson));
    }
}
```

- [ ] **Step 2: 写执行器 `$ref` 串联调用的失败用例**

```java
package com.kevin.mcp.service;

import com.kevin.mcp.entity.ErpOrder;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.processor.JsonPlanExecutor;
import com.kevin.mcp.registry.PrivateMcpToolSchemaDescriptor;
import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonPlanExecutorTest {

    @Test
    void shouldExecuteStepsWithReferenceParameter() throws Exception {
        TestOrderTool tool = new TestOrderTool();
        PrivateMcpToolSchemaRegistry registry = RegistryFixtures.singleMethodRegistry(
                "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#getOrderId(java.lang.String)",
                "testOrderTool",
                TestOrderTool.class.getName(),
                "getOrderId",
                TestOrderTool.class.getDeclaredMethod("getOrderId", String.class),
                tool
        );

        JsonExecutionPlan plan = new JsonExecutionPlan(
                "plan-1",
                "query order",
                List.of(
                        new JsonExecutionStep(
                                "step1",
                                "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#getOrderId(java.lang.String)",
                                "testOrderTool",
                                "getOrderId",
                                TestOrderTool.class.getName(),
                                Map.of("orderId", "A001"),
                                null,
                                List.of()
                        ),
                        new JsonExecutionStep(
                                "step2",
                                "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#wrap(java.lang.String)",
                                "testOrderTool",
                                "wrap",
                                TestOrderTool.class.getName(),
                                Map.of("value", Map.of("$ref", "step1.id")),
                                null,
                                List.of("step1")
                        )
                )
        );

        ReflectionTestUtils.setField(
                registry,
                "schemaDescriptorsByMethodKey",
                Map.of(
                        "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#getOrderId(java.lang.String)",
                        RegistryFixtures.descriptor(
                                "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#getOrderId(java.lang.String)",
                                "testOrderTool",
                                "getOrderId",
                                TestOrderTool.class.getName(),
                                List.of(String.class.getName())
                        ),
                        "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#wrap(java.lang.String)",
                        RegistryFixtures.descriptor(
                                "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#wrap(java.lang.String)",
                                "testOrderTool",
                                "wrap",
                                TestOrderTool.class.getName(),
                                List.of(String.class.getName())
                        )
                )
        );
        ReflectionTestUtils.setField(
                registry,
                "methodsByMethodKey",
                Map.of(
                        "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#getOrderId(java.lang.String)",
                        TestOrderTool.class.getDeclaredMethod("getOrderId", String.class),
                        "com.kevin.mcp.service.JsonPlanExecutorTest$TestOrderTool#wrap(java.lang.String)",
                        TestOrderTool.class.getDeclaredMethod("wrap", String.class)
                )
        );

        JsonPlanExecutor executor = new JsonPlanExecutor(registry);

        JsonExecutionResult result = executor.execute(plan);

        assertEquals("wrapped-A001", result.finalResult());
        assertEquals(List.of("step1", "step2"), result.executedSteps());
    }

    static class TestOrderTool {
        public ErpOrder getOrderId(String orderId) {
            return new ErpOrder(orderId, "Kevin", "北京");
        }

        public String wrap(String value) {
            return "wrapped-" + value;
        }
    }

    static final class RegistryFixtures {
        private RegistryFixtures() {
        }

        static PrivateMcpToolSchemaRegistry singleMethodRegistry(
                String methodKey,
                String beanName,
                String className,
                String methodName,
                Method method,
                Object bean
        ) {
            return new PrivateMcpToolSchemaRegistry(null) {
                @Override
                public Optional<PrivateMcpToolSchemaDescriptor> getMethodSchema(String requestedMethodKey) {
                    return Optional.ofNullable(requestedMethodKey.equals(methodKey)
                            ? descriptor(methodKey, beanName, methodName, className, List.of(String.class.getName()))
                            : null);
                }

                @Override
                public Optional<Method> getRegisteredMethod(String requestedMethodKey) {
                    return Optional.ofNullable(requestedMethodKey.equals(methodKey) ? method : null);
                }

                @Override
                public Optional<Object> getRegisteredBean(String requestedMethodKey) {
                    return Optional.ofNullable(requestedMethodKey.equals(methodKey) ? bean : null);
                }
            };
        }

        static PrivateMcpToolSchemaDescriptor descriptor(
                String methodKey,
                String beanName,
                String methodName,
                String className,
                List<String> parameterTypes
        ) {
            return new PrivateMcpToolSchemaDescriptor(
                    methodKey,
                    beanName,
                    methodName,
                    className,
                    methodName,
                    parameterTypes,
                    "",
                    null,
                    null
            );
        }
    }
}
```

- [ ] **Step 3: 如缺测试依赖则补充最小依赖**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 运行测试确认失败**

Run: `.\mvnw.cmd -Dtest=JsonPlanParserTest,JsonPlanExecutorTest test`

Expected: FAIL，报 `JsonPlanParser`、`JsonPlanExecutor`、`JsonExecutionPlan` 等类型不存在或行为未实现

### Task 2: 实现协议模型与解析器

**Files:**
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonExecutionPlan.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonExecutionStep.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonExecutionResult.java`
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonPlanParser.java`

- [ ] **Step 1: 新增计划与步骤记录类型**

```java
package com.kevin.mcp.service;

import com.kevin.mcp.processor.JsonExecutionStep;

import java.util.List;

public record JsonExecutionPlan(
        String planId,
        String intent,
        List<JsonExecutionStep> steps
) {
}
```

```java
package com.kevin.mcp.service;

import java.util.List;
import java.util.Map;

public record JsonExecutionStep(
        String stepId,
        String methodKey,
        String beanName,
        String methodName,
        String declaringClassName,
        Map<String, Object> parameters,
        String saveResultAs,
        List<String> dependsOn
) {
}
```

- [ ] **Step 2: 新增执行结果记录类型**

```java
package com.kevin.mcp.service;

import java.util.List;
import java.util.Map;

public record JsonExecutionResult(
        String planId,
        String intent,
        boolean success,
        Object finalResult,
        Map<String, Object> stepResults,
        List<String> executedSteps
) {
}
```

- [ ] **Step 3: 写最小计划解析器**

```java
package com.kevin.mcp.service;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.util.GsonUtil;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JsonPlanParser {

    public JsonExecutionPlan parse(String json) {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        Map<String, Object> payload = GsonUtil.fromJson(json, type);
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("Plan JSON is empty");
        }
        return this.toPlan(payload);
    }

    private JsonExecutionPlan toPlan(Map<String, Object> payload) {
        String planId = this.stringValue(payload.get("planId"));
        String intent = this.stringValue(payload.get("intent"));
        List<JsonExecutionStep> steps = this.toSteps(payload.get("steps"));
        this.validateSteps(steps);
        return new JsonExecutionPlan(planId, intent, steps);
    }

    private List<JsonExecutionStep> toSteps(Object rawSteps) {
        throw new UnsupportedOperationException("Implement in code");
    }

    private void validateSteps(List<JsonExecutionStep> steps) {
        Set<String> stepIds = new HashSet<>();
        for (JsonExecutionStep step : steps) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("Duplicate stepId: " + step.stepId());
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
```

- [ ] **Step 4: 运行解析测试**

Run: `.\mvnw.cmd -Dtest=JsonPlanParserTest test`

Expected: 先 FAIL 于 `toSteps` 未实现；补全后 PASS

### Task 3: 实现执行器与 `$ref` 解析

**Files:**
- Create: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonPlanExecutor.java`
- Modify: `D:\Codes\mcp-project\src\test\java\com\kevin\mcp\service\JsonPlanExecutorTest.java`

- [ ] **Step 1: 新增执行器最小骨架**

```java
package com.kevin.mcp.service;

import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonPlanExecutor {

    private final PrivateMcpToolSchemaRegistry registry;

    public JsonPlanExecutor(PrivateMcpToolSchemaRegistry registry) {
        this.registry = registry;
    }

    public JsonExecutionResult execute(JsonExecutionPlan plan) {
        Map<String, Object> stepResults = new LinkedHashMap<>();
        List<String> executedSteps = new ArrayList<>();
        Object finalResult = null;
        for (JsonExecutionStep step : plan.steps()) {
            finalResult = this.executeStep(step, stepResults);
            stepResults.put(step.stepId(), finalResult);
            executedSteps.add(step.stepId());
        }
        return new JsonExecutionResult(plan.planId(), plan.intent(), true, finalResult, stepResults, executedSteps);
    }

    private Object executeStep(JsonExecutionStep step, Map<String, Object> stepResults) {
        throw new UnsupportedOperationException("Implement in code");
    }
}
```

- [ ] **Step 2: 先实现字面量参数调用，再补一致性校验**

```java
private Object executeStep(JsonExecutionStep step, Map<String, Object> stepResults) {
    PrivateMcpToolSchemaDescriptor descriptor = this.registry.getMethodSchema(step.methodKey())
            .orElseThrow(() -> new IllegalArgumentException("Method not registered: " + step.methodKey()));
    this.validateDescriptor(step, descriptor);
    Method method = this.registry.getRegisteredMethod(step.methodKey())
            .orElseThrow(() -> new IllegalArgumentException("Method not cached: " + step.methodKey()));
    Object bean = this.registry.getRegisteredBean(step.methodKey())
            .orElseThrow(() -> new IllegalArgumentException("Bean not found: " + step.methodKey()));
    Object[] arguments = this.resolveArguments(method, step.parameters(), stepResults);
    try {
        return method.invoke(bean, arguments);
    } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Failed to invoke step: " + step.stepId(), exception);
    }
}
```

- [ ] **Step 3: 实现 `$ref` 字段路径解析**

```java
private Object resolveParameterValue(Object rawValue, Map<String, Object> stepResults) {
    if (!(rawValue instanceof Map<?, ?> valueMap) || !valueMap.containsKey("$ref")) {
        return rawValue;
    }
    String reference = String.valueOf(valueMap.get("$ref"));
    return this.resolveReference(reference, stepResults);
}
```

```java
private Object resolveReference(String reference, Map<String, Object> stepResults) {
    String[] tokens = reference.split("\\.");
    Object current = stepResults.get(tokens[0]);
    if (current == null) {
        throw new IllegalArgumentException("Reference step result not found: " + reference);
    }
    for (int index = 1; index < tokens.length; index++) {
        current = this.readFieldValue(current, tokens[index], reference);
    }
    return current;
}
```

- [ ] **Step 4: 运行执行器测试**

Run: `.\mvnw.cmd -Dtest=JsonPlanExecutorTest test`

Expected: 先 FAIL 于 `$ref` 未实现或参数绑定不正确；补全后 PASS

### Task 4: 接入 LLMService 并修正协议提示词

**Files:**
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\LLMService.java`

- [ ] **Step 1: 先写一个集成失败用例或最小断言**

```java
@Test
void shouldReturnExecutionResultJsonInsteadOfStubOrder() {
    // 构造 llmProcessor 返回固定计划 JSON，断言 LLMService.chat 不再返回写死 ErpOrder JSON
}
```

- [ ] **Step 2: 给 LLMService 注入解析器和执行器**

```java
private final JsonPlanParser jsonPlanParser;
private final JsonPlanExecutor jsonPlanExecutor;
```

- [ ] **Step 3: 替换 TODO 执行链并修正提示词必填键**

```java
JsonExecutionPlan plan = jsonPlanParser.parse(result);
JsonExecutionResult executionResult = jsonPlanExecutor.execute(plan);
return GsonUtil.toJsonWithSchema(executionResult);
```

同时把输出 Schema 中步骤 `required` 从：

```json
["stepId", "method", "parameters"]
```

改为：

```json
["stepId", "methodKey", "parameters"]
```

- [ ] **Step 4: 运行相关测试**

Run: `.\mvnw.cmd -Dtest=JsonPlanParserTest,JsonPlanExecutorTest,McpApplicationTests test`

Expected: PASS

### Task 5: 完整验证

**Files:**
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonPlanParser.java`
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\JsonPlanExecutor.java`
- Modify: `D:\Codes\mcp-project\src\main\java\com\kevin\mcp\service\LLMService.java`

- [ ] **Step 1: 运行完整测试**

Run: `.\mvnw.cmd test`

Expected: PASS

- [ ] **Step 2: 运行编译校验**

Run: `.\mvnw.cmd -DskipTests test-compile`

Expected: BUILD SUCCESS

- [ ] **Step 3: 自查协议边界**

检查以下场景：
- 重复 `stepId` 是否失败
- 缺失 `dependsOn` 是否失败
- 未注册 `methodKey` 是否失败
- `$ref` 字段不存在是否失败
- 返回值是否包含 `finalResult` 与 `executedSteps`
