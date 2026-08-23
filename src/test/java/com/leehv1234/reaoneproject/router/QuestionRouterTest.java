package com.leehv1234.reaoneproject.router;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라우터의 도구 선택 정확도를 데이터셋 예시 질문 30개로 채점한다.
 *
 * <p>데이터셋의 {@code questions.json}에는 문항마다 정답 도구가 들어 있어 자동 채점이 된다.
 * 답변 정확도는 기대 답변이 제공되지 않아 채점할 수 없지만, 도구 선택은 여기서 완결된다.
 *
 * <p>데이터셋은 라이선스상 저장소에 없다. 없으면 테스트를 건너뛴다.
 */
class QuestionRouterTest {

    private static final Path QUESTIONS = Path.of("dataset", "questions.json");

    private final QuestionRouter router = new QuestionRouter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    record Example(String q, String tool, String hint) {
    }

    private List<Example> load() throws IOException {
        Assumptions.assumeTrue(Files.exists(QUESTIONS),
                "데이터셋이 없어 건너뛴다. " + QUESTIONS.toAbsolutePath() + " 에 배치할 것.");
        try (var in = Files.newInputStream(QUESTIONS)) {
            return objectMapper.readValue(in, new TypeReference<List<Example>>() {
            });
        }
    }

    @Test
    @DisplayName("예시 질문 30개의 도구 선택이 모두 정답과 일치한다")
    void routesEveryExampleQuestionCorrectly() throws IOException {
        List<Example> examples = load();
        assertThat(examples).hasSize(30);

        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n라우터 채점 결과\n");

        for (Example example : examples) {
            ToolRoute route = router.route(example.q());
            boolean ok = example.tool().equals(route.tool());
            if (!ok) {
                failures.add("%s\n  기대=%s 실제=%s 점수=%s 근거=%s"
                        .formatted(example.q(), example.tool(), route.tool(),
                                route.scores(), route.evidence()));
            }
            report.append("  %s %-14s %s\n".formatted(ok ? "O" : "X", route.tool(), example.q()));
        }

        report.append("  정확도 %d/%d\n".formatted(examples.size() - failures.size(), examples.size()));
        System.out.println(report);

        assertThat(failures)
                .describedAs("도구 선택이 틀린 문항")
                .isEmpty();
    }

    @Test
    @DisplayName("같은 질문은 항상 같은 결정을 낸다")
    void isDeterministic() throws IOException {
        for (Example example : load()) {
            ToolRoute first = router.route(example.q());
            ToolRoute second = router.route(example.q());
            assertThat(second.tool()).isEqualTo(first.tool());
            assertThat(second.arguments()).isEqualTo(first.arguments());
        }
    }

    @Test
    @DisplayName("그래프 질문의 인자가 도구가 이해하는 형태로 만들어진다")
    void buildsGraphArguments() {
        // 개체 + 관계
        ToolRoute uses = router.route("Client-A가 사용 중인 제품 목록은?");
        assertThat(uses.tool()).isEqualTo(QuestionRouter.KNOWLEDGE_GRAPH);
        assertThat(uses.arguments()).containsEntry("entity", "Client-A");
        assertThat(uses.arguments()).containsEntry("relation", "USES");

        // 개체 없이 "가장 많은" → 관계 개수 순위
        ToolRoute rank = router.route("가장 많은 고객을 담당하는 직원은?");
        assertThat(rank.tool()).isEqualTo(QuestionRouter.KNOWLEDGE_GRAPH);
        assertThat(rank.arguments()).containsEntry("relation", "MANAGES_ACCOUNT");
        assertThat(rank.arguments()).containsEntry("rank", true);
        assertThat(rank.arguments()).doesNotContainKey("entity");

        // 관계를 못 짚고 다른 유형을 물으면 2홉
        ToolRoute twoHop = router.route("Product-D1 제품과 관련된 프로젝트는?");
        assertThat(twoHop.tool()).isEqualTo(QuestionRouter.KNOWLEDGE_GRAPH);
        assertThat(twoHop.arguments()).containsEntry("entity", "Product-D1");
        assertThat(twoHop.arguments()).containsEntry("targetType", "project");
        assertThat(twoHop.arguments()).containsEntry("depth", 2);
    }

    @Test
    @DisplayName("신호가 없는 질문은 문서 검색으로 보낸다")
    void fallsBackToVectorSearch() {
        ToolRoute route = router.route("그래서 결론이 뭐야");
        assertThat(route.tool()).isEqualTo(QuestionRouter.VECTOR_SEARCH);
        assertThat(route.arguments()).containsEntry("query", "그래서 결론이 뭐야");
    }
}
