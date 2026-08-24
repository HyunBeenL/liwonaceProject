package com.leehv1234.reaoneproject.agent;

import com.leehv1234.reaoneproject.router.QuestionRouter;
import com.leehv1234.reaoneproject.router.ToolRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 질문 하나를 답변까지 끌고 가는 에이전트.
 *
 * <p>과제가 그린 흐름을 그대로 구현한다.
 * <pre>
 * 질문 → 규칙 기반 라우터 → MCP 도구 호출 → 조회 결과 → Ollama가 문장으로 정리 → 답변
 * </pre>
 *
 * <p><b>규칙이 다룰 수 있는 영역은 규칙이, 못 다루는 영역만 모델이 맡는다.</b>
 * 규칙 기반 라우터가 도구를 결정하고, Ollama는 조회 결과를 문장으로 만든다.
 * 도구 선택이 결정적이 되어 같은 질문에 같은 경로가 보장되고, 왜 그 도구를 썼는지
 * 점수로 설명할 수 있다.
 *
 * <p>다만 규칙은 만능이 아니다. 데이터셋 예시 질문 30개는 모두 규칙이 판별하지만,
 * 규칙이 예상하지 못한 질문은 신호가 잡히지 않는다. 그래서 두 곳에서 모델과 협업한다.
 * <ol>
 *   <li><b>라우터가 확신하지 못하면</b>(신호가 없거나 1·2위가 동점) LLM에게 도구를 고르게 한다.
 *       예시 질문 30개는 이 구간을 밟지 않으므로 검증된 30/30은 그대로 유지된다.</li>
 *   <li><b>도구가 빈손으로 돌아오면</b> 다음 순위 도구로 한 번 더 시도한다.
 *       그래프에 없는 이름이 문서에는 있을 수 있다.</li>
 * </ol>
 * 도구를 LLM이 고른 경우에도 <b>인자는 여전히 규칙이 만든다.</b> 개체명 추출과 관계 추론은
 * 소형 모델보다 규칙이 정확하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 근거가 길면 소형 모델이 앞부분만 보고 답한다. 도구 결과를 이 길이로 자른다. */
    private static final int MAX_EVIDENCE_CHARS = 6000;

    private final QuestionRouter router;
    private final McpToolClient mcpToolClient;
    private final ToolResultInspector inspector;

    /** 도구 등록 빈과의 순환을 피하려면 사용 시점에 받아야 한다. Nl2SqlTool과 같은 이유다. */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 모델 이름을 알아야 만들 수 있어 첫 호출에 한 번 준비한다. */
    private volatile OllamaChatOptions answerOptions;

    public AgentAnswer ask(String question) {
        long startedAt = System.currentTimeMillis();

        ToolRoute route = router.route(question);
        if (!route.confident()) {
            // 규칙이 판별하지 못한 질문이다. 도구 선택만 모델에게 맡기고 인자는 규칙이 만든다.
            String chosen = askModelToChooseTool(question, route);
            route = router.forTool(chosen, question);
            log.info("규칙이 확신하지 못해 모델이 도구를 골랐다: {}", chosen);
        }
        log.info("질문='{}' → {}", question, route.describe());

        String evidence;
        try {
            evidence = mcpToolClient.call(route.tool(), route.arguments());
        } catch (Exception e) {
            log.warn("도구 호출 실패: {}", e.toString());
            return new AgentAnswer(question, route.tool(), route.arguments(),
                    "도구를 호출하지 못해 답변할 수 없다: " + e.getMessage(),
                    null, route.scores(), elapsed(startedAt));
        }

        // 도구가 빈손으로 돌아오면 다음 순위 도구로 한 번 더 시도한다.
        if (inspector.isEmpty(evidence)) {
            String fallback = nextTool(route);
            if (fallback != null) {
                log.info("{}가 결과를 내지 못해 {}로 다시 시도한다", route.tool(), fallback);
                ToolRoute retry = router.forTool(fallback, question);
                try {
                    String second = mcpToolClient.call(retry.tool(), retry.arguments());
                    if (!inspector.isEmpty(second)) {
                        route = retry;
                        evidence = second;
                    }
                } catch (Exception e) {
                    log.warn("대체 도구 호출도 실패: {}", e.toString());
                }
            }
        }

        String trimmed = evidence.length() > MAX_EVIDENCE_CHARS
                ? evidence.substring(0, MAX_EVIDENCE_CHARS) + "\n...(생략)"
                : evidence;

        String answer;
        try {
            ChatModel model = chatModelProvider.getObject();
            answer = model.call(new Prompt(buildPrompt(question, route.tool(), trimmed), answerOptions(model)))
                    .getResult().getOutput().getText().trim();
        } catch (Exception e) {
            log.warn("답변 생성 실패: {}", e.toString());
            // 근거는 이미 확보했으므로 문장 생성이 실패해도 조회 결과는 돌려준다.
            answer = "답변 문장을 만들지 못했다. 조회 결과를 그대로 확인할 것: " + e.getMessage();
        }

        return new AgentAnswer(question, route.tool(), route.arguments(),
                answer, trimmed, route.scores(), elapsed(startedAt));
    }

    // ------------------------------------------------------------------

    /**
     * 규칙이 확신하지 못했을 때 도구 선택을 모델에게 맡긴다.
     *
     * <p>도구 설명은 MCP 서버가 노출하는 것을 쓰지 않고 여기에 요약해 둔다. 소형 모델에는
     * 짧고 대비가 뚜렷한 설명이 유리하고, 선택지가 셋뿐이라 전체 스키마가 필요하지 않다.
     * 모델이 셋 중 하나를 정확히 답하지 못하면 규칙의 추정을 그대로 쓴다.
     */
    private String askModelToChooseTool(String question, ToolRoute fallbackRoute) {
        String prompt = """
                다음 질문에 답하려면 어떤 도구를 써야 하는가?
                도구 이름 하나만 출력한다. 다른 말은 붙이지 않는다.

                vector_search    - 문서 본문에서 찾는다. 장애보고서, 기술문서(설치·운영·API·튜닝),
                                   회의록, 제안서. 방법·원인·사례·정책처럼 설명이 필요한 질문.
                knowledge_graph  - 개체 사이의 연결을 따라간다. 고객사가 쓰는 제품, 직원의 소속 부서,
                                   고객사 담당 직원, 고객사의 프로젝트, 프로젝트를 이끄는 직원,
                                   이슈를 제기한 고객사. "무엇과 무엇이 이어져 있는가".
                nl2sql           - 데이터베이스 테이블을 질의한다. 고객사·제품·직원·계약·프로젝트·
                                   매출·기술지원 티켓의 속성, 날짜, 금액, 개수. 세기·합계·평균·
                                   정렬·기간 조건이 붙는 질문. 목록을 조건으로 뽑는 질문.

                질문: %s

                도구:""".formatted(question);

        try {
            ChatModel model = chatModelProvider.getObject();
            String raw = model.call(new Prompt(prompt, answerOptions(model)))
                    .getResult().getOutput().getText();
            for (String candidate : List.of(QuestionRouter.VECTOR_SEARCH,
                    QuestionRouter.KNOWLEDGE_GRAPH, QuestionRouter.NL2SQL)) {
                if (raw.contains(candidate)) {
                    return candidate;
                }
            }
            log.warn("모델이 도구를 특정하지 못했다: {}", raw.strip());
        } catch (Exception e) {
            log.warn("도구 선택 질의 실패: {}", e.toString());
        }
        return fallbackRoute.tool();
    }

    /** 이미 시도한 도구 다음으로 점수가 높은 도구. 없으면 null. */
    private String nextTool(ToolRoute tried) {
        return router.rankedTools(tried.scores()).stream()
                .filter(t -> !t.equals(tried.tool()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 답변 생성 전용 옵션.
     *
     * <p><b>추론을 끈다.</b> 실측에서 같은 질문이 39.7초에서 14.3초로 줄고
     * 답변이 오히려 좋아졌다. 추론을 켜면 생성 토큰이 671개까지 늘면서
     * "장애가 있었습니다" 수준으로 뭉뚱그려지는데, 끄면 147토큰으로
     * "Client-A의 Product-C1에서는..." 처럼 근거의 고유명사를 그대로 짚는다.
     *
     * <p>주어진 문서를 요약하는 일이라 추론할 것이 없고, 길게 생각할수록 구체성이
     * 사라진 것으로 보인다. SQL 생성은 조인과 별칭을 맞춰야 해서 정반대이므로
     * {@code Nl2SqlTool}에서는 추론을 켠 채로 둔다.
     *
     * <p>모델 이름을 반드시 함께 지정한다. Prompt에 옵션을 넘기면 기본 설정과
     * 병합되지 않고 대체되어, 빠뜨리면 Spring AI 내장 기본값(mistral)을 호출한다.
     */
    private OllamaChatOptions answerOptions(ChatModel model) {
        OllamaChatOptions local = answerOptions;
        if (local == null) {
            synchronized (this) {
                if (answerOptions == null) {
                    answerOptions = OllamaChatOptions.builder()
                            .model(model.getDefaultOptions().getModel())
                            .disableThinking()
                            .temperature(0.0d)
                            .build();
                }
                local = answerOptions;
            }
        }
        return local;
    }

    private String buildPrompt(String question, String tool, String evidence) {
        return """
                당신은 Company-X의 사내 데이터 도우미다.
                아래 조회 결과만 근거로 사용자의 질문에 한국어로 답한다.

                규칙:
                - 조회 결과에 없는 내용은 지어내지 않는다. 근거가 부족하면 부족하다고 말한다.
                - 표나 JSON을 그대로 옮기지 말고 문장으로 정리한다.
                - 수치는 조회 결과의 값을 그대로 쓴다.
                - 세 문장 안팎으로 간결하게 답한다.
                - 목록을 묻는 질문이면 항목을 나열한다.

                사용한 도구: %s

                조회 결과:
                %s

                질문: %s

                답변:""".formatted(tool, evidence, question);
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
