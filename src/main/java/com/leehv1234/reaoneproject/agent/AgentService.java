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

/**
 * 질문 하나를 답변까지 끌고 가는 에이전트.
 *
 * <p>과제가 그린 흐름을 그대로 구현한다.
 * <pre>
 * 질문 → 규칙 기반 라우터 → MCP 도구 호출 → 조회 결과 → Ollama가 문장으로 정리 → 답변
 * </pre>
 *
 * <p><b>도구를 고르는 것은 LLM이 아니라 라우터다.</b> Ollama는 두 곳에서만 일한다.
 * nl2sql 도구 안에서 자연어를 SQL로 옮길 때, 그리고 여기서 조회 결과를 문장으로 만들 때다.
 * 이렇게 나누면 도구 선택이 결정적이 되어 같은 질문에 같은 경로가 보장되고,
 * 왜 그 도구를 썼는지 점수로 설명할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 근거가 길면 소형 모델이 앞부분만 보고 답한다. 도구 결과를 이 길이로 자른다. */
    private static final int MAX_EVIDENCE_CHARS = 6000;

    private final QuestionRouter router;
    private final McpToolClient mcpToolClient;

    /** 도구 등록 빈과의 순환을 피하려면 사용 시점에 받아야 한다. Nl2SqlTool과 같은 이유다. */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 모델 이름을 알아야 만들 수 있어 첫 호출에 한 번 준비한다. */
    private volatile OllamaChatOptions answerOptions;

    public AgentAnswer ask(String question) {
        long startedAt = System.currentTimeMillis();

        ToolRoute route = router.route(question);
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
