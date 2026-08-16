package com.leehv1234.reaoneproject.dataset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 대회 데이터셋 중 SQL로 적재되지 않는 부분을 기동 시 DB에 채운다.
 *
 * <ul>
 *   <li>graph/nodes.json, graph/edges.json → graph_nodes, graph_edges
 *       (데이터셋에 그래프용 DDL과 INSERT가 없어 직접 적재한다)</li>
 *   <li>documents/*.md → document_chunks (임베딩은 참가자 구현 몫이다)</li>
 * </ul>
 *
 * 두 작업 모두 대상 테이블이 비어 있을 때만 수행하므로 재기동해도 중복되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetLoader implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final DatasetProperties properties;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.loadOnStartup()) {
            log.info("데이터셋 적재를 건너뛴다 (app.dataset.load-on-startup=false)");
            return;
        }

        Path root = Path.of(properties.path());
        if (!Files.isDirectory(root)) {
            log.warn("데이터셋 디렉터리를 찾을 수 없어 적재를 건너뛴다: {} (절대경로: {})",
                    root, root.toAbsolutePath());
            return;
        }

        loadGraph(root);
        loadDocuments(root);
    }

    // ------------------------------------------------------------------
    // 지식 그래프
    // ------------------------------------------------------------------

    private void loadGraph(Path root) throws IOException {
        if (count("graph_nodes") > 0) {
            log.info("graph_nodes가 이미 채워져 있어 그래프 적재를 건너뛴다");
            return;
        }

        List<NodeJson> nodes = readJson(root.resolve("graph/nodes.json"), new TypeReference<>() {
        });
        List<EdgeJson> edges = readJson(root.resolve("graph/edges.json"), new TypeReference<>() {
        });

        jdbc.batchUpdate("""
                INSERT INTO graph_nodes (id, type, name, properties)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, nodes, nodes.size(), (ps, node) -> {
            ps.setString(1, node.id());
            ps.setString(2, node.type());
            ps.setString(3, node.name());
            ps.setString(4, writeJson(node.properties()));
        });

        // 엣지는 노드를 참조하므로 반드시 노드 적재 이후에 넣는다.
        jdbc.batchUpdate("""
                INSERT INTO graph_edges (source_id, target_id, relation)
                VALUES (?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_graph_edge DO NOTHING
                """, edges, edges.size(), (ps, edge) -> {
            ps.setString(1, edge.source());
            ps.setString(2, edge.target());
            ps.setString(3, edge.relation());
        });

        log.info("지식 그래프 적재 완료: 노드 {}개, 관계 {}개", count("graph_nodes"), count("graph_edges"));
    }

    // ------------------------------------------------------------------
    // 문서 임베딩
    // ------------------------------------------------------------------

    private void loadDocuments(Path root) throws IOException {
        if (count("document_chunks") > 0) {
            log.info("document_chunks가 이미 채워져 있어 문서 임베딩을 건너뛴다");
            return;
        }

        List<DocumentIndexJson> index = readJson(root.resolve("documents/index.json"), new TypeReference<>() {
        });

        log.info("문서 {}건 임베딩을 시작한다 (모델 차원: {})", index.size(), embeddingModel.dimensions());

        for (DocumentIndexJson entry : index) {
            String content = Files.readString(root.resolve("documents").resolve(entry.filename()));

            // 문서가 511~1024바이트로 작아 청킹하지 않는다. 1문서 = 1청크.
            // 다만 임베딩 대상에는 제목을 덧붙인다. 본문에는 없는 고객사·제품·날짜가
            // 제목에 담겨 있어 검색 정확도에 도움이 된다. 저장하는 본문은 원문 그대로다.
            float[] embedding = embeddingModel.embed(entry.title() + "\n\n" + content);

            jdbc.update("""
                    INSERT INTO document_chunks (doc_id, chunk_index, content, embedding, metadata)
                    VALUES (?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))
                    """,
                    entry.id(),
                    0,
                    content,
                    toVectorLiteral(embedding),
                    writeJson(Map.of(
                            "doc_id", entry.id(),
                            "type", entry.type(),
                            "title", entry.title(),
                            "filename", entry.filename())));
        }

        log.info("문서 임베딩 완료: {}건", count("document_chunks"));
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private <T> T readJson(Path path, TypeReference<T> type) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return objectMapper.readValue(in, type);
        }
    }

    /** Jackson 3의 JacksonException은 unchecked라 별도 래핑이 필요 없다. */
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
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

    // ------------------------------------------------------------------
    // 데이터셋 JSON 매핑
    // ------------------------------------------------------------------

    record NodeJson(String id, String type, String name, Map<String, Object> properties) {
    }

    record EdgeJson(String source, String target, String relation) {
    }

    record DocumentIndexJson(String id, String type, String title, String filename) {
    }
}
