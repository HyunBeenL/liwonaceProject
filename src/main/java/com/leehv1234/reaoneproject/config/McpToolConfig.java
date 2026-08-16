package com.leehv1234.reaoneproject.config;

import com.leehv1234.reaoneproject.tool.VectorSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @Tool} 메서드를 MCP 서버에 노출한다.
 *
 * <p>도구가 추가되면(nl2sql, knowledge_graph) toolObjects에 함께 넘긴다.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(VectorSearchTool vectorSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(vectorSearchTool)
                .build();
    }
}
