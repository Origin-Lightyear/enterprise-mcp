package com.kevin.mcp.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按模型声明的 endpoint 能力选择生成、向量和重排模型。
 *
 * <p>NewAPI 的模型顺序不表达用途，调用方不得再把第一项当作生成模型。</p>
 *
 * @param generationModel OpenAI Chat Completions 生成模型
 * @param embeddingModel OpenAI Embeddings 向量模型，可为空
 * @param rerankModel Jina 兼容重排模型，可为空
 * @author Kevin
 * @date 2026-09-18
 */
public record LlmModelSelection(
        String generationModel,
        String embeddingModel,
        String rerankModel
) {

    private static final String OPENAI_ENDPOINT = "openai";
    private static final String EMBEDDINGS_ENDPOINT = "embeddings";
    private static final String RERANK_ENDPOINT = "jina-rerank";

    /**
     * 解析 OpenAI 兼容模型列表并按 endpoint 能力选择各角色模型。
     *
     * <p>同时声明 {@code openai + embeddings} 的模型只能作为向量模型，避免向量模型因排序靠前而被误用为生成模型。
     * 对完全不返回 {@code supported_endpoint_types} 的旧服务，仅保留首个模型作为生成模型兼容入口。</p>
     *
     * @param response GET /v1/models 响应
     * @param preferredGenerationModel Tenant 显式指定的生成模型，可为空
     * @return 已按能力分配的模型
     * @throws IllegalStateException 响应无效、显式模型不支持生成或不存在生成模型
     */
    public static LlmModelSelection fromResponse(Map<?, ?> response, String preferredGenerationModel) {
        if (response == null || !(response.get("data") instanceof List<?> rawModels) || rawModels.isEmpty()) {
            throw new IllegalStateException("Employee LLM returned no available model");
        }
        List<ModelDescriptor> models = new ArrayList<>(rawModels.size());
        for (Object rawModel : rawModels) {
            if (rawModel instanceof Map<?, ?> model) {
                String id = text(model.get("id"));
                if (id != null) {
                    models.add(new ModelDescriptor(id, endpointTypes(model.get("supported_endpoint_types"))));
                }
            }
        }
        if (models.isEmpty()) {
            throw new IllegalStateException("Employee LLM returned no valid model");
        }

        boolean capabilityMetadataPresent = models.stream().anyMatch(model -> !model.endpointTypes().isEmpty());
        String generationModel = selectGenerationModel(models, preferredGenerationModel, capabilityMetadataPresent);
        String embeddingModel = firstSupporting(models, EMBEDDINGS_ENDPOINT);
        String rerankModel = firstSupporting(models, RERANK_ENDPOINT);
        return new LlmModelSelection(generationModel, embeddingModel, rerankModel);
    }

    private static String selectGenerationModel(List<ModelDescriptor> models, String preferredModel,
                                                boolean capabilityMetadataPresent) {
        if (preferredModel != null && !preferredModel.isBlank()) {
            String preferred = preferredModel.trim();
            ModelDescriptor descriptor = models.stream()
                    .filter(model -> preferred.equals(model.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Configured generation model is unavailable"));
            if (capabilityMetadataPresent && !supportsGeneration(descriptor)) {
                throw new IllegalStateException("Configured model does not support chat generation");
            }
            return preferred;
        }
        if (!capabilityMetadataPresent) {
            return models.getFirst().id();
        }
        return models.stream()
                .filter(LlmModelSelection::supportsGeneration)
                .map(ModelDescriptor::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Employee LLM returned no chat generation model"));
    }

    private static boolean supportsGeneration(ModelDescriptor model) {
        return model.endpointTypes().contains(OPENAI_ENDPOINT)
                && !model.endpointTypes().contains(EMBEDDINGS_ENDPOINT)
                && !model.endpointTypes().contains(RERANK_ENDPOINT);
    }

    private static String firstSupporting(List<ModelDescriptor> models, String endpointType) {
        return models.stream()
                .filter(model -> model.endpointTypes().contains(endpointType))
                .map(ModelDescriptor::id)
                .findFirst()
                .orElse(null);
    }

    private static Set<String> endpointTypes(Object value) {
        if (!(value instanceof List<?> values)) {
            return Set.of();
        }
        Set<String> endpointTypes = new LinkedHashSet<>();
        for (Object item : values) {
            String endpointType = text(item);
            if (endpointType != null) {
                endpointTypes.add(endpointType.toLowerCase());
            }
        }
        return Set.copyOf(endpointTypes);
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private record ModelDescriptor(String id, Set<String> endpointTypes) {
    }
}
