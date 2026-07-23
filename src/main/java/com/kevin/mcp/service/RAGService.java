package com.kevin.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 知识库服务
 *
 * @author Kevin
 * 2026/7/9
 */
@Service
public class RAGService {
    private static final Logger log = LoggerFactory.getLogger(RAGService.class);

    @Value("${rag.url}") String ragUrl;

    private final RestClient restClient;

    public RAGService() {
        this.restClient = RestClient.builder()
                .baseUrl(ragUrl)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", "Weather-Api/1.0")
                .build();
    }

    /**
     * 使用自然语言查询知识库
     *
     * @param queryMsg
     * @return
     */
    public String query(String queryMsg) {
        try {
            String response = restClient.post()
                    .uri("/query")
                    .body("""
                        {
                          "query": "%s",
                          "mode": "mix"
                        }
                        """
                            .formatted(queryMsg)
                    )
                    .retrieve()
                    .body(String.class);
            log.debug("RAG 返回: {}", response);
            return response;
        } catch (Exception e) {
            log.error("RAG 查询错误: {}", e.getMessage());
            return "知识库连接失败";
        }
    }
}
