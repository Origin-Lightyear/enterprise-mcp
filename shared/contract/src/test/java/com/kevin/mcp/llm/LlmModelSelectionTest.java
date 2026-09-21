package com.kevin.mcp.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmModelSelectionTest {

    @Test
    void selectsModelsByEndpointCapabilityInsteadOfListOrder() {
        Map<String, Object> response = Map.of("data", List.of(
                model("灵枢向量", "openai", "embeddings"),
                model("灵枢玄同", "openai"),
                model("灵枢玄同Pro", "openai"),
                model("灵枢重排", "openai", "jina-rerank")
        ));

        LlmModelSelection selection = LlmModelSelection.fromResponse(response, null);

        assertEquals("灵枢玄同", selection.generationModel());
        assertEquals("灵枢向量", selection.embeddingModel());
        assertEquals("灵枢重排", selection.rerankModel());
    }

    @Test
    void honorsAnExplicitGenerationModelWhenItSupportsChatOnly() {
        Map<String, Object> response = Map.of("data", List.of(
                model("embedding", "openai", "embeddings"),
                model("chat-basic", "openai"),
                model("chat-pro", "openai")
        ));

        LlmModelSelection selection = LlmModelSelection.fromResponse(response, "chat-pro");

        assertEquals("chat-pro", selection.generationModel());
        assertEquals("embedding", selection.embeddingModel());
        assertNull(selection.rerankModel());
    }

    @Test
    void rejectsAnEmbeddingModelConfiguredAsGenerationModel() {
        Map<String, Object> response = Map.of("data", List.of(
                model("embedding", "openai", "embeddings"),
                model("chat", "openai")
        ));

        assertThrows(IllegalStateException.class,
                () -> LlmModelSelection.fromResponse(response, "embedding"));
    }

    @Test
    void keepsLegacyModelEndpointsCompatibleWhenCapabilitiesAreAbsent() {
        Map<String, Object> response = Map.of("data", List.of(Map.of("id", "legacy-chat")));

        LlmModelSelection selection = LlmModelSelection.fromResponse(response, null);

        assertEquals("legacy-chat", selection.generationModel());
        assertNull(selection.embeddingModel());
        assertNull(selection.rerankModel());
    }

    private Map<String, Object> model(String id, String... endpointTypes) {
        return Map.of("id", id, "supported_endpoint_types", List.of(endpointTypes));
    }
}
