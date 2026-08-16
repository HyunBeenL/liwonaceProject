package com.leehv1234.reaoneproject.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 개체 간 관계를 그래프로 탐색하는 MCP 도구.
 *
 * <p>노드 133개(client·product·employee·project·department), 관계 354개 규모라
 * 별도 그래프 엔진 없이 PostgreSQL 재귀 CTE로 처리한다.
 *
 * <p>세 가지 방식으로 동작한다. 데이터셋이 제공한 예시 질문 10개가 모두 이 셋 중 하나다.
 * <ul>
 *   <li>{@code entity} 지정 → 그 노드의 이웃을 정·역 양방향으로 탐색
 *       ("Client-A가 사용 중인 제품", "Product-C1을 사용하는 고객사")</li>
 *   <li>{@code relation}만 지정 → 해당 관계의 모든 엣지를 속성과 함께 나열
 *       ("진행 중인 프로젝트를 이끄는 직원" 처럼 속성으로 걸러야 하는 질문)</li>
 *   <li>{@code rank} = true → 관계 개수로 순위 집계
 *       ("기술 지원 이슈가 가장 많은 제품", "가장 많은 고객을 담당하는 직원")</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeGraphTool {

    /** 2홉이면 "제품 ← 고객사 → 프로젝트" 같은 질문이 커버된다. 그 이상은 노이즈가 급증한다. */
    private static final int MAX_DEPTH = 2;

    private static final int MAX_ROWS = 60;
    private static final int MAX_RANK_ROWS = 10;

    private final JdbcTemplate jdbc;

    @Tool(name = "knowledge_graph", description = """
            고객사·제품·직원·프로젝트·부서 사이의 관계를 탐색한다.
            "누가 담당인지", "무엇을 쓰는지", "어디 소속인지", "누가 이끄는지"처럼
            개체와 개체를 잇는 질문에 사용한다.
            관계 유형: BELONGS_TO(직원→부서), HEAD_IS(부서→부서장), USES(고객사→제품),
            MANAGES_ACCOUNT(직원→담당고객사), HAS_PROJECT(고객사→프로젝트),
            LEADS(직원→프로젝트), REPORTED_ISSUE(고객사→제품).
            문서 본문 검색이나 매출·금액 집계에는 사용하지 않는다.
            """)
    public GraphResult explore(
            @ToolParam(required = false, description = """
                    탐색을 시작할 노드 이름. 예: Client-A, Product-C1, 경영지원팀, 윤소연.
                    프로젝트는 전체 이름의 일부만 넣어도 된다.""")
            String entity,

            @ToolParam(required = false, description = """
                    관계 유형으로 결과를 좁힌다. entity 없이 이것만 주면 해당 관계 전체를 나열한다.""")
            String relation,

            @ToolParam(required = false, description = "탐색 깊이. 1(직접 연결) 또는 2(한 다리 건너). 기본 1.")
            Integer depth,

            @ToolParam(required = false, description = """
                    결과를 이 노드 유형만 남긴다: client, product, employee, project, department.
                    2홉 탐색에서 특히 중요하다. 예를 들어 "제품과 관련된 프로젝트"는
                    depth=2, targetType=project 로 물어야 고객사를 건너뛴 프로젝트만 나온다.""")
            String targetType,

            @ToolParam(required = false, description = """
                    true이면 relation의 연결 개수로 순위를 집계한다. "가장 많은 ~" 질문에 사용한다.""")
            Boolean rank) {

        boolean doRank = Boolean.TRUE.equals(rank);

        if (doRank) {
            if (isBlank(relation)) {
                return GraphResult.error("순위 집계에는 relation이 필요하다. 예: REPORTED_ISSUE, MANAGES_ACCOUNT");
            }
            return rank(relation);
        }

        if (!isBlank(entity)) {
            return neighbors(entity, relation, depth, targetType);
        }

        if (!isBlank(relation)) {
            return edgesOf(relation);
        }

        return GraphResult.error("entity(노드 이름) 또는 relation(관계 유형) 중 하나는 지정해야 한다.");
    }

    // ------------------------------------------------------------------
    // 1. 이웃 탐색
    // ------------------------------------------------------------------

    private GraphResult neighbors(String entity, String relation, Integer depth, String targetType) {
        String startId = resolveNodeId(entity);
        if (startId == null) {
            return GraphResult.error(
                    "'" + entity + "' 에 해당하는 노드를 찾을 수 없다. 그래프에 등록된 이름과 정확히 일치해야 한다.");
        }

        int hops = Math.clamp(depth == null ? 1 : depth, 1, MAX_DEPTH);

        // und: 방향을 지운 엣지 목록. 재귀 항목에서 참조하려면 비재귀 CTE로 미리 펼쳐야 한다.
        // 사람이 묻는 방향과 데이터의 방향이 자주 반대라(예: "Product-C1을 쓰는 고객사"는
        // USES 관계를 거꾸로 타야 한다) 양방향을 함께 훑고 dir로 구분해 돌려준다.
        List<Neighbor> rows = jdbc.query("""
                WITH RECURSIVE und AS (
                    SELECT source_id AS a, target_id AS b, relation, 'out' AS dir FROM graph_edges
                    UNION ALL
                    SELECT target_id AS a, source_id AS b, relation, 'in'  AS dir FROM graph_edges
                ),
                paths AS (
                    SELECT CAST(? AS varchar) AS node_id,
                           0 AS hop,
                           ARRAY[CAST(? AS varchar)] AS visited,
                           CAST(NULL AS varchar) AS relation,
                           CAST(NULL AS varchar) AS dir
                  UNION ALL
                    SELECT u.b, p.hop + 1, p.visited || u.b, u.relation, u.dir
                    FROM paths p
                    JOIN und u ON u.a = p.node_id
                    WHERE p.hop < ? AND NOT (u.b = ANY(p.visited))
                )
                -- 같은 노드에 여러 경로로 닿을 수 있다. 가장 가까운 경로 하나만 남긴다.
                SELECT * FROM (
                    SELECT DISTINCT ON (n.id)
                           p.hop,
                           p.relation,
                           p.dir,
                           n.id   AS node_id,
                           n.name AS node_name,
                           n.type AS node_type,
                           n.properties::text AS properties
                    FROM paths p
                    JOIN graph_nodes n ON n.id = p.node_id
                    WHERE p.hop >= 1
                      AND (CAST(? AS varchar) IS NULL OR p.relation = CAST(? AS varchar))
                      AND (CAST(? AS varchar) IS NULL OR n.type   = CAST(? AS varchar))
                    ORDER BY n.id, p.hop
                ) t
                ORDER BY t.hop, t.relation, t.node_type, t.node_name
                LIMIT ?
                """,
                (rs, i) -> new Neighbor(
                        rs.getInt("hop"),
                        rs.getString("relation"),
                        "out".equals(rs.getString("dir")) ? "정방향" : "역방향",
                        rs.getString("node_id"),
                        rs.getString("node_name"),
                        rs.getString("node_type"),
                        rs.getString("properties")),
                startId, startId, hops,
                emptyToNull(relation), emptyToNull(relation),
                emptyToNull(targetType), emptyToNull(targetType),
                MAX_ROWS);

        String startName = jdbc.queryForObject(
                "SELECT name FROM graph_nodes WHERE id = ?", String.class, startId);

        log.debug("knowledge_graph: entity='{}'({}) relation={} depth={} → {}건",
                entity, startId, relation, hops, rows.size());

        return new GraphResult("neighbors", startName, rows, List.of(), null);
    }

    /** 정확 일치 → 부분 일치 순으로 노드를 찾는다. 프로젝트 이름이 길어 부분 일치가 필요하다. */
    private String resolveNodeId(String entity) {
        List<String> exact = jdbc.queryForList(
                "SELECT id FROM graph_nodes WHERE name = ? OR id = ? LIMIT 1",
                String.class, entity, entity);
        if (!exact.isEmpty()) {
            return exact.getFirst();
        }
        List<String> partial = jdbc.queryForList(
                "SELECT id FROM graph_nodes WHERE name ILIKE ? ORDER BY length(name) LIMIT 1",
                String.class, "%" + entity + "%");
        return partial.isEmpty() ? null : partial.getFirst();
    }

    // ------------------------------------------------------------------
    // 2. 관계 전체 나열
    // ------------------------------------------------------------------

    private GraphResult edgesOf(String relation) {
        List<Neighbor> rows = jdbc.query("""
                SELECT 1 AS hop,
                       e.relation,
                       src.name AS source_name,
                       tgt.id   AS node_id,
                       tgt.name AS node_name,
                       tgt.type AS node_type,
                       tgt.properties::text AS properties
                FROM graph_edges e
                JOIN graph_nodes src ON src.id = e.source_id
                JOIN graph_nodes tgt ON tgt.id = e.target_id
                WHERE e.relation = ?
                ORDER BY src.name, tgt.name
                LIMIT ?
                """,
                (rs, i) -> new Neighbor(
                        1,
                        rs.getString("relation"),
                        rs.getString("source_name"),
                        rs.getString("node_id"),
                        rs.getString("node_name"),
                        rs.getString("node_type"),
                        rs.getString("properties")),
                relation, MAX_ROWS);

        if (rows.isEmpty()) {
            return GraphResult.error("'" + relation + "' 관계가 없다. "
                    + "사용 가능: BELONGS_TO, HEAD_IS, USES, MANAGES_ACCOUNT, HAS_PROJECT, LEADS, REPORTED_ISSUE");
        }
        return new GraphResult("edges", null, rows, List.of(), null);
    }

    // ------------------------------------------------------------------
    // 3. 관계 개수 순위
    // ------------------------------------------------------------------

    private GraphResult rank(String relation) {
        // 어느 쪽을 세야 하는지는 질문마다 다르다. REPORTED_ISSUE는 제품(도착지) 기준이고
        // MANAGES_ACCOUNT는 직원(출발지) 기준이다. 양쪽을 모두 돌려주고 판단은 호출자에게 맡긴다.
        List<RankEntry> ranking = jdbc.query("""
                WITH counted AS (
                    SELECT 'source' AS side, source_id AS node_id, count(*) AS n
                    FROM graph_edges WHERE relation = ? GROUP BY source_id
                    UNION ALL
                    SELECT 'target' AS side, target_id AS node_id, count(*) AS n
                    FROM graph_edges WHERE relation = ? GROUP BY target_id
                ),
                ordered AS (
                    SELECT c.*, row_number() OVER (PARTITION BY c.side ORDER BY c.n DESC, c.node_id) AS rn
                    FROM counted c
                )
                SELECT o.side, o.n, n.name, n.type, n.properties::text AS properties
                FROM ordered o
                JOIN graph_nodes n ON n.id = o.node_id
                WHERE o.rn <= ?
                ORDER BY o.side, o.n DESC, n.name
                """,
                (rs, i) -> new RankEntry(
                        "source".equals(rs.getString("side")) ? "출발지" : "도착지",
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getInt("n"),
                        rs.getString("properties")),
                relation, relation, MAX_RANK_ROWS);

        if (ranking.isEmpty()) {
            return GraphResult.error("'" + relation + "' 관계가 없어 집계할 수 없다.");
        }

        log.debug("knowledge_graph rank: relation={} → {}건", relation, ranking.size());
        return new GraphResult("ranking", null, List.of(), ranking, null);
    }

    // ------------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }

    /**
     * @param mode      neighbors / edges / ranking / error
     * @param start     탐색 시작 노드 이름 (neighbors 모드에서만)
     * @param neighbors 연결된 노드 목록
     * @param ranking   관계 개수 순위
     * @param error     처리할 수 없을 때의 사유
     */
    public record GraphResult(String mode, String start, List<Neighbor> neighbors,
                              List<RankEntry> ranking, String error) {
        static GraphResult error(String message) {
            return new GraphResult("error", null, List.of(), List.of(), message);
        }
    }

    /**
     * @param hop        시작 노드로부터의 거리
     * @param relation   연결 관계
     * @param direction  정방향/역방향, 또는 edges 모드에서는 출발지 이름
     * @param properties 노드 속성 JSON. 상태·지역·예산 등 추가 판단 재료다.
     */
    public record Neighbor(int hop, String relation, String direction, String nodeId,
                           String name, String type, String properties) {
    }

    /**
     * @param side  관계에서 이 노드가 놓인 위치 (출발지/도착지)
     * @param count 연결 개수
     */
    public record RankEntry(String side, String name, String type, int count, String properties) {
    }
}
