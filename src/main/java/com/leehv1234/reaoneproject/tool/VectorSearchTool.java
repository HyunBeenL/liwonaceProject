package com.leehv1234.reaoneproject.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 비정형 문서(장애보고·기술문서·회의록·제안서)를 의미 기반으로 검색하는 MCP 도구.
 *
 * <p>질문을 bge-m3로 임베딩한 뒤 document_chunks에서 코사인 거리로 최근접 문서를 찾는다.
 * 문서가 40건뿐이라 1문서 = 1청크로 적재되어 있고, 따라서 결과는 곧 문서 단위다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSearchTool {

    /** 문서 40건 규모라 이보다 많이 돌려줘도 LLM 컨텍스트만 낭비한다. */
    private static final int MAX_LIMIT = 10;

    /**
     * 무관한 문서를 걸러내는 하한. 실측 결과 정답 문서는 0.55 이상,
     * 무관한 문서는 0.49 이하에 몰려 있었다.
     */
    private static final double MIN_SIMILARITY = 0.5;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    @Tool(name = "vector_search", description = """
            사내 비정형 문서를 의미 기반으로 검색한다.
            장애 보고서, 기술 문서(설치 가이드·운영 매뉴얼·API 레퍼런스·성능 튜닝 가이드),
            회의록, 제안서의 내용을 찾을 때 사용한다.
            "어떻게", "왜", "방법", "원인", "사례"처럼 서술형 답변이 필요한 질문에 적합하다.
            매출·계약 건수 같은 수치 집계나 개체 간 관계 탐색에는 사용하지 않는다.
            """)
    public List<SearchResult> search(
            @ToolParam(description = "검색할 질문 또는 키워드. 사용자의 원문 표현을 그대로 넘기는 편이 정확하다.")
            String query,
            @ToolParam(required = false, description = "반환할 문서 수. 기본 3, 최대 10.")
            Integer limit) {

        int topK = Math.clamp(limit == null ? 3 : limit, 1, MAX_LIMIT);
        String vector = toVectorLiteral(embeddingModel.embed(query));

        List<SearchResult> results = jdbc.query("""
                SELECT doc_id,
                       metadata ->> 'title' AS title,
                       metadata ->> 'type'  AS type,
                       content,
                       1 - (embedding <=> CAST(? AS vector)) AS similarity
                FROM document_chunks
                WHERE 1 - (embedding <=> CAST(? AS vector)) >= ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
                (rs, rowNum) -> new SearchResult(
                        rs.getString("doc_id"),
                        rs.getString("title"),
                        rs.getString("type"),
                        rs.getString("content"),
                        Math.round(rs.getDouble("similarity") * 10000) / 10000.0),
                vector, vector, MIN_SIMILARITY, vector, topK);

        log.debug("vector_search: query='{}' topK={} → {}건", query, topK, results.size());
        return results;
    }

    /** pgvector는 {@code [0.1,0.2,...]} 형태의 텍스트 리터럴을 vector로 캐스팅한다. */
    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 12 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * @param docId      문서 식별자 (예: DOC-011)
     * @param title      문서 제목
     * @param type       incident_report / technical_doc / meeting_note / proposal
     * @param content    문서 본문 (마크다운 원문)
     * @param similarity 코사인 유사도. 1에 가까울수록 관련성이 높다.
     */
    public record SearchResult(String docId, String title, String type, String content, double similarity) {
    }
}
