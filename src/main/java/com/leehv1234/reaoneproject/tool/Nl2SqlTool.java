package com.leehv1234.reaoneproject.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 자연어 질문을 SQL로 옮겨 실행하는 MCP 도구.
 *
 * <p>세 도구 중 유일하게 LLM이 개입한다. 매출·계약 건수처럼 정형 데이터를 집계하는
 * 질문을 담당한다.
 *
 * <p>모델이 만든 문자열을 DB에 넣는 구조라 방어를 세 겹으로 둔다.
 * <ol>
 *   <li>{@link SqlGuard} 구문 검사 — 조회가 아닌 모든 것을 거부</li>
 *   <li>읽기 전용 트랜잭션 — 구문 검사를 빠져나가도 쓰기가 실패한다</li>
 *   <li>실행 시간 제한과 행 수 제한 — 무거운 질의가 서버를 붙잡지 못하게</li>
 * </ol>
 * 어느 한 겹만으로 충분하다고 보지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Nl2SqlTool {

    private static final int MAX_ROWS = 100;
    private static final int STATEMENT_TIMEOUT_MS = 8_000;

    /**
     * ChatModel을 직접 주입하면 기동이 실패한다.
     *
     * <p>Nl2SqlTool -> ChatModel -> ToolCallingManager -> ToolCallbackResolver
     * -> ToolCallbackProvider -> Nl2SqlTool 로 고리가 닫히기 때문이다.
     * Spring AI가 모든 도구를 모델에 물려주는 구조라, 도구가 모델을 참조하는 순간 순환이 생긴다.
     * 실제로 쓰는 시점까지 해결을 미뤄 고리를 끊는다.
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    private final SchemaCatalog schemaCatalog;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Tool(name = "nl2sql", description = """
            매출·계약·직원·프로젝트·기술지원 티켓 같은 정형 데이터를 질의한다.
            "얼마", "몇 개", "몇 명", "상위 N개", "평균", "합계", "가장 많은"처럼
            수치를 세거나 집계하거나 순위를 매기는 질문에 사용한다.
            문서 본문 검색이나 개체 간 관계 탐색에는 사용하지 않는다.
            """)
    public SqlResult query(
            @ToolParam(description = "사용자의 질문. 원문 그대로 넘기는 편이 정확하다.")
            String question) {

        String sql;
        try {
            sql = SqlGuard.extractSql(chatModelProvider.getObject().call(buildPrompt(question)));
        } catch (Exception e) {
            log.warn("nl2sql 모델 호출 실패: {}", e.toString());
            return SqlResult.error(null, "SQL을 생성하지 못했다: " + e.getMessage());
        }

        String rejection = SqlGuard.reject(sql);
        if (rejection != null) {
            log.warn("nl2sql 거부: {} / sql={}", rejection, sql);
            return SqlResult.error(sql, rejection);
        }

        try {
            return execute(sql);
        } catch (Exception e) {
            // 실패한 SQL을 그대로 돌려준다. 답변 생성 쪽에서 왜 못 냈는지 설명할 수 있어야 한다.
            log.warn("nl2sql 실행 실패: {} / sql={}", e.getMessage(), sql);
            return SqlResult.error(sql, "질의 실행에 실패했다: " + rootMessage(e));
        }
    }

    // ------------------------------------------------------------------

    private String buildPrompt(String question) {
        return """
                당신은 PostgreSQL 전문가다. 아래 스키마를 보고 질문에 답하는 SQL 한 문장을 작성한다.

                규칙:
                - SELECT 문 하나만 출력한다. 설명, 주석, 코드 블록 표시를 붙이지 않는다.
                - 스키마에 있는 테이블과 컬럼만 사용한다.
                - "값이 정해진 컬럼" 목록에 있는 컬럼은 반드시 그 값 중 하나로 비교한다.
                - 그런 컬럼을 조건으로 걸 때는 부정(!=, NOT IN) 대신 해당하는 값을 IN으로 나열한다.
                  값 목록이 주어져 있으므로 어느 값이 조건에 맞는지 직접 고를 수 있다.
                - 조인은 "외래키" 목록에 있는 관계로만 한다. 목록에 없는 컬럼끼리 잇지 않는다.
                - 목록이나 순위를 묻는 질문에는 ORDER BY와 LIMIT을 넣는다.
                - 집계 결과에는 사람이 읽을 이름 컬럼을 함께 선택한다.

                스키마:
                %s
                질문: %s

                SQL:""".formatted(schemaCatalog.describe(), question);
    }

    private SqlResult execute(String sql) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);

        return tx.execute(status -> {
            // SET LOCAL은 이 트랜잭션에만 적용된다. 커넥션 풀에 설정이 새어 나가지 않는다.
            jdbc.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);

            return jdbc.query(sql, rs -> {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }

                log.debug("nl2sql: {}행 반환 (truncated={}) / sql={}", rows.size(), truncated, sql);
                return new SqlResult(sql, columns, rows, rows.size(), truncated, null);
            });
        });
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage().trim();
    }

    /**
     * @param sql       실제로 실행한 SQL. 결과의 근거이자 검증 수단이므로 항상 함께 돌려준다.
     * @param truncated 행 수 상한에 걸려 잘렸는지 여부
     * @param error     처리하지 못했을 때의 사유
     */
    public record SqlResult(String sql, List<String> columns, List<Map<String, Object>> rows,
                            int rowCount, boolean truncated, String error) {

        static SqlResult error(String sql, String message) {
            return new SqlResult(sql, List.of(), List.of(), 0, false, message);
        }
    }
}
