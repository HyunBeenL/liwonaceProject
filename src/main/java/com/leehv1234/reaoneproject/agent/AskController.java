package com.leehv1234.reaoneproject.agent;

import com.leehv1234.reaoneproject.router.QuestionRouter;
import com.leehv1234.reaoneproject.router.ToolRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 사용자 질문을 받는 진입점.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AskController {

    private final AgentService agentService;
    private final QuestionRouter router;
    private final McpToolClient mcpToolClient;

    /** 질문을 받아 답변까지 돌려준다. */
    @PostMapping("/ask")
    public AgentAnswer ask(@RequestBody AskRequest request) {
        return agentService.ask(request.question());
    }

    /**
     * 도구를 실제로 호출하지 않고 라우터의 판단만 본다.
     *
     * <p>도구 호출 없이 즉시 답하므로 라우터 규칙을 다듬을 때 유용하다.
     * nl2sql은 한 번 호출에 20~45초가 걸려 매번 확인하기 어렵다.
     */
    @GetMapping("/route")
    public ToolRoute route(@RequestParam String question) {
        return router.route(question);
    }

    /** MCP 서버 연결과 노출 중인 도구를 확인한다. */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> tools() {
        List<String> names = mcpToolClient.listToolNames();
        return ResponseEntity.ok(Map.of("connected", true, "tools", names));
    }

    public record AskRequest(String question) {
    }
}
