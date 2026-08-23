package com.leehv1234.reaoneproject.router;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 라우터가 내린 결정.
 *
 * @param tool      호출할 MCP 도구 이름
 * @param arguments 그 도구에 넘길 인자
 * @param scores    도구별 점수. 왜 이 도구가 뽑혔는지 설명하는 근거다.
 * @param evidence  점수에 기여한 키워드. 규칙을 고칠 때 어디를 봐야 하는지 알려준다.
 */
public record ToolRoute(String tool,
                        Map<String, Object> arguments,
                        Map<String, Integer> scores,
                        Map<String, String> evidence) {

    public ToolRoute {
        arguments = Map.copyOf(arguments);
        scores = Map.copyOf(scores);
        evidence = Map.copyOf(evidence);
    }

    /** 로그와 시연에서 한 줄로 보여주기 위한 표현. */
    public String describe() {
        Map<String, Object> ordered = new LinkedHashMap<>(arguments);
        return "%s %s (scores=%s)".formatted(tool, ordered, scores);
    }
}
