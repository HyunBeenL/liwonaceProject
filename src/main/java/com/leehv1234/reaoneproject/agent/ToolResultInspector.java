package com.leehv1234.reaoneproject.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 도구가 돌려준 결과가 쓸 만한지 판정한다.
 *
 * <p>도구는 실패해도 예외를 던지지 않고 "찾지 못했다"는 응답을 정상적으로 돌려준다.
 * 그 상태를 구분해야 에이전트가 다른 도구로 다시 시도할지 정할 수 있다.
 *
 * <p>도구마다 응답 모양이 달라 각각을 명시적으로 본다. 문자열에서 키워드를 찾는 방식은
 * 본문에 우연히 같은 단어가 들어가면 오판하므로 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ToolResultInspector {

    private final ObjectMapper objectMapper;

    /** 근거로 쓸 내용이 없으면 true. */
    public boolean isEmpty(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return true;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(evidence);
        } catch (RuntimeException e) {
            // JSON이 아니면 판정하지 않는다. 내용이 있다고 본다.
            return false;
        }

        // vector_search: 문서 배열
        if (node.isArray()) {
            return node.isEmpty();
        }

        // 세 도구 모두 처리하지 못했을 때 error에 사유를 담는다
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            return true;
        }

        // knowledge_graph: neighbors 또는 ranking 중 하나에 내용이 있어야 한다
        if (node.has("neighbors") || node.has("ranking")) {
            return size(node.get("neighbors")) == 0 && size(node.get("ranking")) == 0;
        }

        // nl2sql: 조회는 성공했지만 결과가 0행
        JsonNode rowCount = node.get("rowCount");
        if (rowCount != null && rowCount.isInt()) {
            return rowCount.intValue() == 0;
        }

        return false;
    }

    private static int size(JsonNode node) {
        return node == null || node.isNull() ? 0 : node.size();
    }
}
