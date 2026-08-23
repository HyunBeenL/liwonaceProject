package com.leehv1234.reaoneproject.agent;

import java.util.Map;

/**
 * 에이전트의 답변.
 *
 * <p>답변 문장만 돌려주지 않고 <b>어떤 도구를 왜 골랐는지와 그 도구가 준 근거</b>를 함께 담는다.
 * 답이 틀렸을 때 라우터가 잘못 보낸 것인지, 도구가 잘못 조회한 것인지, 모델이 잘못 요약한 것인지
 * 구분할 수 있어야 하기 때문이다. 시연에서도 이 값들이 곧 설명이 된다.
 *
 * @param tool         라우터가 고른 MCP 도구
 * @param arguments    그 도구에 넘긴 인자
 * @param answer       Ollama가 정리한 답변 문장
 * @param evidence     도구가 돌려준 원본 조회 결과
 * @param routerScores 도구별 점수. 왜 그 도구가 뽑혔는지의 근거다.
 * @param elapsedMs    질문부터 답변까지 걸린 시간
 */
public record AgentAnswer(String question,
                          String tool,
                          Map<String, Object> arguments,
                          String answer,
                          String evidence,
                          Map<String, Integer> routerScores,
                          long elapsedMs) {
}
