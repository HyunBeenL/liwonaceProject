package com.leehv1234.reaoneproject.config;

import com.leehv1234.reaoneproject.tool.KnowledgeGraphTool;
import com.leehv1234.reaoneproject.tool.Nl2SqlTool;
import com.leehv1234.reaoneproject.tool.VectorSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @Tool} 메서드를 MCP 서버에 노출한다.
 *
 * <p>과제가 요구하는 도구 3종이 모두 여기에 등록된다.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(VectorSearchTool vectorSearchTool,
                                                     KnowledgeGraphTool knowledgeGraphTool,
                                                     Nl2SqlTool nl2SqlTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(vectorSearchTool, knowledgeGraphTool, nl2SqlTool)
                .build();
    }
}
