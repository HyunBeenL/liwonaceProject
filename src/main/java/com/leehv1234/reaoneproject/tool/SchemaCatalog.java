package com.leehv1234.reaoneproject.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * NL2SQL 프롬프트에 넣을 스키마 설명을 DB에서 직접 뽑아 만든다.
 *
 * <p>하드코딩하지 않는 이유는 스키마가 바뀌었을 때 프롬프트만 조용히 낡는 상황을 막기 위해서다.
 *
 * <p>컬럼 목록과 외래키뿐 아니라 <b>저값 카디널리티 컬럼의 실제 값 목록</b>을 함께 싣는다.
 * 소형 모델은 "보안 솔루션"을 category='security'로, "서울"을 region='서울'로 옮기는 데서
 * 가장 많이 틀린다. 값 목록이 있으면 이 변환이 추측이 아니라 조회가 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaCatalog {

    /** NL2SQL 대상 테이블. 벡터·그래프 테이블은 다른 도구의 몫이라 제외한다. */
    private static final List<String> TABLES = List.of(
            "departments", "employees", "clients", "products",
            "contracts", "projects", "sales", "support_tickets");

    /** 이보다 값 종류가 많으면 목록을 싣지 않는다. 이름·이메일 같은 고유값 컬럼을 걸러낸다. */
    private static final int MAX_DISTINCT = 12;

    private final JdbcTemplate jdbc;

    private volatile String cached;

    /** 기동 후 첫 호출에 한 번만 만든다. 매 질문마다 information_schema를 뒤질 이유는 없다. */
    public String describe() {
        String local = cached;
        if (local == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = build();
                    log.info("NL2SQL 스키마 카탈로그 생성 완료 ({}자)", cached.length());
                }
                local = cached;
            }
        }
        return local;
    }

    private String build() {
        StringBuilder sb = new StringBuilder();

        for (String table : TABLES) {
            sb.append("TABLE ").append(table).append('(');
            StringJoiner cols = new StringJoiner(", ");
            jdbc.query("""
                    SELECT column_name, data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ?
                    ORDER BY ordinal_position
                    """,
                    rs -> {
                        cols.add(rs.getString("column_name") + " " + shortType(rs.getString("data_type")));
                    }, table);
            sb.append(cols).append(")\n");
        }

        sb.append("\n-- 외래키\n");
        jdbc.query("""
                SELECT tc.table_name AS src, kcu.column_name AS src_col,
                       ccu.table_name AS tgt, ccu.column_name AS tgt_col
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_name = tc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                  AND tc.table_name = ANY(?)
                ORDER BY tc.table_name, kcu.column_name
                """,
                rs -> {
                    sb.append(rs.getString("src")).append('.').append(rs.getString("src_col"))
                      .append(" -> ").append(rs.getString("tgt")).append('.').append(rs.getString("tgt_col"))
                      .append('\n');
                },
                (Object) TABLES.toArray(new String[0]));

        sb.append("\n-- 값이 정해진 컬럼 (질문의 표현을 이 값으로 옮길 것)\n");
        for (String table : TABLES) {
            List<String> textCols = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema='public' AND table_name = ?
                      AND data_type IN ('character varying','text')
                    ORDER BY ordinal_position
                    """, String.class, table);

            for (String col : textCols) {
                List<String> values = jdbc.queryForList(
                        "SELECT DISTINCT " + quote(col) + " FROM " + quote(table)
                                + " WHERE " + quote(col) + " IS NOT NULL ORDER BY 1 LIMIT " + (MAX_DISTINCT + 1),
                        String.class);
                if (!values.isEmpty() && values.size() <= MAX_DISTINCT) {
                    sb.append(table).append('.').append(col).append(": ")
                      .append(String.join(", ", values)).append('\n');
                }
            }
        }

        return sb.toString();
    }

    private static String shortType(String dataType) {
        return switch (dataType) {
            case "character varying" -> "text";
            case "timestamp without time zone" -> "timestamp";
            default -> dataType;
        };
    }

    /** 테이블·컬럼 이름은 information_schema에서 온 값이지만 식별자 결합이므로 인용한다. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
