package com.leehv1234.reaoneproject.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 서버에 붙어 도구를 호출하는 클라이언트.
 *
 * <p>에이전트는 도구를 자바 메서드로 직접 부르지 않고 <b>MCP 프로토콜을 통해</b> 호출한다.
 * 같은 JVM 안의 빈을 주입받으면 프로토콜 계층을 건너뛰게 되어, MCP 서버를 만든 의미가 없다.
 *
 * <p>연결은 첫 질문이 들어올 때 만든다. 기본 설정에서는 이 애플리케이션이 서버와 클라이언트를
 * 겸하므로, 기동 시점에 연결하면 아직 서버가 요청을 받기 전이라 실패한다.
 * {@code openConnectionOnStartup(false)}와 지연 생성으로 이 문제를 피한다.
 *
 * <p>{@code app.agent.mcp-url}을 다른 주소로 바꾸면 별도 프로세스로 띄운 MCP 서버에 붙는다.
 * 에이전트와 서버를 분리해 시연할 때 쓴다.
 */
@Slf4j
@Component
public class McpToolClient {

    private final String serverUrl;
    private final Duration requestTimeout;

    private volatile McpSyncClient client;

    public McpToolClient(@Value("${app.agent.mcp-url:http://localhost:8080/mcp}") String serverUrl,
                         @Value("${app.agent.request-timeout:PT180S}") Duration requestTimeout) {
        this.serverUrl = serverUrl;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 도구를 호출하고 본문 텍스트를 돌려준다.
     *
     * <p>MCP의 도구 응답은 여러 개의 content 조각으로 올 수 있어 이어 붙인다.
     * 우리 도구들은 JSON 한 덩어리를 돌려주므로 실제로는 한 조각이다.
     */
    public String call(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client().callTool(
                new McpSchema.CallToolRequest(toolName, arguments));

        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));

        if (Boolean.TRUE.equals(result.isError())) {
            log.warn("MCP 도구 오류: {} / {}", toolName, text);
            throw new IllegalStateException("도구 실행 실패(" + toolName + "): " + text);
        }
        return text;
    }

    /** 서버가 노출하는 도구 목록. 연결 확인과 시연에 쓴다. */
    public List<String> listToolNames() {
        return client().listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    // ------------------------------------------------------------------

    private McpSyncClient client() {
        McpSyncClient local = client;
        if (local == null) {
            synchronized (this) {
                if (client == null) {
                    client = connect();
                }
                local = client;
            }
        }
        return local;
    }

    private McpSyncClient connect() {
        log.info("MCP 서버에 연결한다: {}", serverUrl);

        var transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(endpointPath())
                .openConnectionOnStartup(false)
                .build();

        McpSyncClient created = McpClient.sync(transport)
                // nl2sql은 CPU 추론이라 질문당 20~45초가 걸린다. 기본 타임아웃으로는 짧다.
                .requestTimeout(requestTimeout)
                .clientInfo(new McpSchema.Implementation("companyx-agent", "0.0.1"))
                .build();

        var init = created.initialize();
        log.info("MCP 서버 연결됨: {} {}", init.serverInfo().name(), init.serverInfo().version());
        return created;
    }

    private String baseUrl() {
        int at = serverUrl.indexOf('/', serverUrl.indexOf("//") + 2);
        return at < 0 ? serverUrl : serverUrl.substring(0, at);
    }

    private String endpointPath() {
        int at = serverUrl.indexOf('/', serverUrl.indexOf("//") + 2);
        return at < 0 ? "/mcp" : serverUrl.substring(at);
    }

    @PreDestroy
    void shutdown() {
        McpSyncClient local = client;
        if (local != null) {
            local.closeGracefully();
        }
    }
}
