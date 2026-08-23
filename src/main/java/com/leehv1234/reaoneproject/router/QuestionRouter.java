package com.leehv1234.reaoneproject.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문을 세 MCP 도구 중 하나로 보내는 규칙 기반 라우터.
 *
 * <p>LLM을 쓰지 않는다. 같은 질문에는 항상 같은 결정을 내려야 하고, 왜 그렇게 정했는지
 * 설명할 수 있어야 하기 때문이다. 판단 근거는 {@link ToolRoute#scores()}와
 * {@link ToolRoute#evidence()}에 담겨 나간다.
 *
 * <p><b>첫 매치가 아니라 점수 합산으로 고른다.</b> 데이터셋 예시 질문을 보면 같은 단어가
 * 도구를 가로질러 나타나기 때문이다.
 * <ul>
 *   <li>"가장 많은"은 세 문항에 걸쳐 두 도구로 갈린다. 관계 키워드가 함께 있으면
 *       그래프, 없으면 집계 SQL이다.</li>
 *   <li>"이슈"는 그래프의 REPORTED_ISSUE 신호지만, "미팅에서 논의된 일정 지연 이슈"는
 *       회의록 문서를 찾는 질문이다. 문서 신호가 더 무거워야 한다.</li>
 *   <li>"Product-C1"은 개체명이지만 "Product-C1 설치 방법"은 기술문서 질문이다.
 *       개체명만으로 그래프를 확정하면 안 된다.</li>
 * </ul>
 * 그래서 개별 키워드에 가중치를 주고 합이 가장 큰 도구를 고른다.
 */
@Slf4j
@Component
public class QuestionRouter {

    public static final String VECTOR_SEARCH = "vector_search";
    public static final String NL2SQL = "nl2sql";
    public static final String KNOWLEDGE_GRAPH = "knowledge_graph";

    // ------------------------------------------------------------------
    // 규칙 표
    // ------------------------------------------------------------------

    /**
     * 그래프 관계를 직접 가리키는 표현. 가장 무겁다.
     * 이 신호가 있으면 "가장 많은" 같은 집계 표현이 함께 있어도 그래프로 간다.
     */
    private static final Map<String, String> RELATION_KEYWORDS = new LinkedHashMap<>();

    static {
        RELATION_KEYWORDS.put("소속", "BELONGS_TO");
        RELATION_KEYWORDS.put("팀장", "HEAD_IS");
        RELATION_KEYWORDS.put("부서장", "HEAD_IS");
        RELATION_KEYWORDS.put("책임자", "HEAD_IS");
        RELATION_KEYWORDS.put("담당", "MANAGES_ACCOUNT");
        RELATION_KEYWORDS.put("이끄는", "LEADS");
        RELATION_KEYWORDS.put("이끌", "LEADS");
        RELATION_KEYWORDS.put("리드하는", "LEADS");
        RELATION_KEYWORDS.put("사용 중", "USES");
        RELATION_KEYWORDS.put("사용하는", "USES");
        RELATION_KEYWORDS.put("사용중", "USES");
        RELATION_KEYWORDS.put("쓰는", "USES");
        RELATION_KEYWORDS.put("이용하는", "USES");
        RELATION_KEYWORDS.put("도입한", "USES");
        RELATION_KEYWORDS.put("이슈", "REPORTED_ISSUE");
    }

    private static final int W_RELATION = 4;

    /** 비정형 문서를 찾는 질문의 표지. 서술형 답을 원한다는 신호다. */
    private static final List<String> DOC_KEYWORDS = List.of(
            "방법", "원인", "사례", "내용", "정책", "방식", "어떻게", "왜",
            "가이드", "매뉴얼", "제안서", "보고서", "회의록", "미팅", "논의",
            "점검", "대응", "튜닝", "취약점", "마이그레이션", "설치", "인증",
            "백업", "최적화", "장애", "절차", "설명");

    private static final int W_DOC = 3;

    /** 정형 데이터를 세거나 집계하는 질문의 표지. 테이블·컬럼 이름과 수량 표현이다. */
    private static final List<String> SQL_KEYWORDS = List.of(
            "매출", "계약", "연봉", "급여", "티켓", "우선순위", "금액", "예산",
            "등록된", "등록한", "몇 개", "몇 명", "몇 건", "얼마", "합계",
            "평균", "상위", "순서", "건수", "총액", "카테고리");

    private static final int W_SQL = 3;

    /** 수량 표현. 단독으로는 약하다. 관계 키워드가 함께 있으면 그래프 집계로 넘어간다. */
    private static final List<String> AGGREGATE_KEYWORDS = List.of(
            "가장 많은", "가장 높은", "가장 큰", "최다", "제일 많은", "총", "활성", "수는");

    private static final int W_AGGREGATE = 2;

    /** 노드 유형을 가리키는 보통명사. 개체명과 함께 있을 때만 그래프 신호가 된다. */
    private static final Map<String, String> TYPE_NOUNS = new LinkedHashMap<>();

    static {
        TYPE_NOUNS.put("고객사", "client");
        TYPE_NOUNS.put("고객", "client");
        TYPE_NOUNS.put("제품", "product");
        TYPE_NOUNS.put("솔루션", "product");
        TYPE_NOUNS.put("직원", "employee");
        TYPE_NOUNS.put("엔지니어", "employee");
        TYPE_NOUNS.put("담당자", "employee");
        TYPE_NOUNS.put("프로젝트", "project");
        TYPE_NOUNS.put("부서", "department");
        TYPE_NOUNS.put("팀", "department");
    }

    private static final int W_ENTITY = 2;
    private static final int W_TWO_TYPES = 3;

    /** 데이터셋의 개체명 표기. Client-A, Product-C1 형식과 한국어 조직명. */
    private static final Pattern CODE_ENTITY = Pattern.compile("\\b(Client|Product)-[A-Z]{1,2}\\d?\\b");
    private static final Pattern ORG_ENTITY = Pattern.compile("[가-힣]{2,10}(?:사업부|지원팀|솔루션팀|플랫폼팀|본부|영업팀|팀)");

    // ------------------------------------------------------------------

    public ToolRoute route(String question) {
        String q = question == null ? "" : question.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();
        scores.put(KNOWLEDGE_GRAPH, 0);
        scores.put(VECTOR_SEARCH, 0);
        scores.put(NL2SQL, 0);

        // 1. 관계 키워드
        String relation = null;
        for (var e : RELATION_KEYWORDS.entrySet()) {
            if (q.contains(e.getKey())) {
                relation = e.getValue();
                add(scores, KNOWLEDGE_GRAPH, W_RELATION);
                append(evidence, KNOWLEDGE_GRAPH, e.getKey() + "→" + e.getValue());
                break;
            }
        }

        // 2. 개체명
        String entity = extractEntity(q);
        if (entity != null) {
            add(scores, KNOWLEDGE_GRAPH, W_ENTITY);
            append(evidence, KNOWLEDGE_GRAPH, "개체명:" + entity);
        }

        // 3. 유형 명사. 개체명이 있고 서로 다른 유형이 둘 이상이면 관계 질문일 가능성이 크다.
        //    개체명 없이 유형 명사만 있는 경우는 집계 SQL인 경우가 많아 점수를 주지 않는다.
        //    ("가장 많은 프로젝트를 진행 중인 고객사"는 두 유형이 나오지만 SQL 질문이다.)
        List<String> types = typeNouns(q, entity);
        if (entity != null && types.size() >= 2) {
            add(scores, KNOWLEDGE_GRAPH, W_TWO_TYPES);
            append(evidence, KNOWLEDGE_GRAPH, "유형 " + types);
        }

        // 4. 문서 키워드
        for (String kw : DOC_KEYWORDS) {
            if (lower.contains(kw)) {
                add(scores, VECTOR_SEARCH, W_DOC);
                append(evidence, VECTOR_SEARCH, kw);
            }
        }

        // 5. 정형 데이터 키워드
        for (String kw : SQL_KEYWORDS) {
            if (lower.contains(kw)) {
                add(scores, NL2SQL, W_SQL);
                append(evidence, NL2SQL, kw);
            }
        }

        // 6. 수량 표현
        boolean aggregate = false;
        for (String kw : AGGREGATE_KEYWORDS) {
            if (q.contains(kw)) {
                aggregate = true;
                add(scores, NL2SQL, W_AGGREGATE);
                append(evidence, NL2SQL, kw);
            }
        }

        String tool = pick(scores);
        Map<String, Object> arguments = buildArguments(tool, q, entity, relation, types, aggregate);

        ToolRoute route = new ToolRoute(tool, arguments, scores, evidence);
        log.debug("route: '{}' → {}", q, route.describe());
        return route;
    }

    // ------------------------------------------------------------------

    /**
     * 동점이면 그래프 → 문서 → SQL 순으로 고른다. 그래프와 문서는 신호가 구체적이라
     * 우연히 붙은 점수일 가능성이 낮다. 아무 신호도 없으면 문서 검색으로 보낸다.
     * 무엇을 묻는지 모를 때는 의미 검색이 가장 덜 틀린다.
     */
    private String pick(Map<String, Integer> scores) {
        int graph = scores.get(KNOWLEDGE_GRAPH);
        int doc = scores.get(VECTOR_SEARCH);
        int sql = scores.get(NL2SQL);

        int max = Math.max(graph, Math.max(doc, sql));
        if (max == 0) {
            return VECTOR_SEARCH;
        }
        if (graph == max) {
            return KNOWLEDGE_GRAPH;
        }
        if (doc == max) {
            return VECTOR_SEARCH;
        }
        return NL2SQL;
    }

    private Map<String, Object> buildArguments(String tool, String question, String entity,
                                               String relation, List<String> types, boolean aggregate) {
        Map<String, Object> args = new LinkedHashMap<>();

        switch (tool) {
            case VECTOR_SEARCH -> {
                args.put("query", question);
                args.put("limit", 3);
            }
            case NL2SQL -> args.put("question", question);
            case KNOWLEDGE_GRAPH -> {
                // 개체명 없이 "가장 많은 ~"을 물으면 관계 개수 순위를 매기는 질문이다.
                if (entity == null && aggregate && relation != null) {
                    args.put("relation", relation);
                    args.put("rank", true);
                    break;
                }
                if (entity != null) {
                    args.put("entity", entity);
                }
                if (relation != null) {
                    args.put("relation", relation);
                }
                // 관계를 못 짚었는데 다른 유형을 묻고 있으면 한 다리 건너 찾아야 한다.
                // ("Product-D1 제품과 관련된 프로젝트"는 고객사를 거쳐 프로젝트에 닿는다.)
                String target = targetType(entity, types);
                if (target != null) {
                    args.put("targetType", target);
                    if (relation == null) {
                        args.put("depth", 2);
                    }
                }
            }
            default -> throw new IllegalStateException("알 수 없는 도구: " + tool);
        }
        return args;
    }

    /** 개체 자신의 유형과 다른 유형 명사가 있으면 그것이 찾으려는 대상이다. */
    private String targetType(String entity, List<String> types) {
        if (entity == null || types.isEmpty()) {
            return null;
        }
        String own = entityType(entity);
        return types.stream().filter(t -> !t.equals(own)).findFirst().orElse(null);
    }

    private String entityType(String entity) {
        if (entity.startsWith("Client-")) {
            return "client";
        }
        if (entity.startsWith("Product-")) {
            return "product";
        }
        return "department";
    }

    private String extractEntity(String question) {
        Matcher code = CODE_ENTITY.matcher(question);
        if (code.find()) {
            return code.group();
        }
        Matcher org = ORG_ENTITY.matcher(question);
        if (org.find()) {
            return org.group();
        }
        return null;
    }

    /**
     * 질문에 등장하는 노드 유형을 중복 없이 모은다.
     *
     * <p><b>개체명은 먼저 지우고 센다.</b> 지우지 않으면 개체명 자신의 글자가 유형으로
     * 잡힌다. "기술지원팀 직원 목록과 연봉을 알려줘"는 연봉을 묻는 SQL 질문인데,
     * 개체명의 "팀"이 부서로 세어져 "직원"과 함께 유형 두 개가 되고, 그 보너스 때문에
     * 그래프로 잘못 갔다.
     */
    private List<String> typeNouns(String question, String entity) {
        String scrubbed = entity == null ? question : question.replace(entity, " ");
        return TYPE_NOUNS.entrySet().stream()
                .filter(e -> scrubbed.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .toList();
    }

    private void add(Map<String, Integer> scores, String tool, int weight) {
        scores.merge(tool, weight, Integer::sum);
    }

    private void append(Map<String, String> evidence, String tool, String token) {
        evidence.merge(tool, token, (a, b) -> a + ", " + b);
    }
}
