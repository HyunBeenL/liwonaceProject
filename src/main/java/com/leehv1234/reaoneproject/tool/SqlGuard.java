package com.leehv1234.reaoneproject.tool;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * LLM이 생성한 SQL을 실행 전에 검사한다.
 *
 * <p>이 도구의 최대 위험은 모델이 만든 문자열을 그대로 DB에 넣는다는 점이다.
 * 모델이 악의적이지 않더라도 질문에 섞인 지시문에 휘둘릴 수 있으므로
 * 구문 수준에서 조회 외의 모든 것을 차단한다.
 *
 * <p>여기서 통과해도 실행 단계에서 읽기 전용 트랜잭션과 실행 시간 제한이
 * 한 겹 더 걸린다. 어느 한쪽만으로 충분하다고 보지 않는다.
 */
final class SqlGuard {

    /** 조회 외의 작업을 뜻하는 키워드. 단어 경계로만 매칭해 컬럼명 오탐을 피한다. */
    private static final List<String> FORBIDDEN = List.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "comment", "copy", "vacuum", "analyze", "reindex",
            "call", "do", "execute", "prepare", "listen", "notify", "lock",
            "set", "reset", "begin", "commit", "rollback", "savepoint",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_sleep",
            "dblink", "lo_import", "lo_export", "pg_terminate_backend");

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?s)```(?:sql)?\\s*(.*?)\\s*```");

    private SqlGuard() {
    }

    /**
     * 모델 응답에서 SQL 본문만 꺼낸다. 지시를 해도 코드 펜스나 설명을 덧붙이는 일이 잦다.
     */
    static String extractSql(String raw) {
        if (raw == null) {
            return "";
        }
        String sql = raw.trim();

        var fence = MARKDOWN_FENCE.matcher(sql);
        if (fence.find()) {
            sql = fence.group(1).trim();
        }

        // 펜스가 없을 때를 대비해 첫 SELECT/WITH부터 잘라낸다.
        int start = indexOfFirstKeyword(sql);
        if (start > 0) {
            sql = sql.substring(start);
        }

        // 끝의 세미콜론과 그 뒤 설명 문장을 버린다.
        int semi = sql.indexOf(';');
        if (semi >= 0) {
            sql = sql.substring(0, semi);
        }
        return sql.trim();
    }

    private static int indexOfFirstKeyword(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int select = lower.indexOf("select");
        int with = lower.indexOf("with");
        if (select < 0) {
            return with;
        }
        if (with < 0) {
            return select;
        }
        return Math.min(select, with);
    }

    /**
     * @return 거부 사유. 통과하면 null.
     */
    static String reject(String sql) {
        if (sql == null || sql.isBlank()) {
            return "모델이 SQL을 만들지 못했다.";
        }

        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return "조회 질의가 아니다. SELECT 또는 WITH로 시작해야 한다.";
        }

        // 주석과 문자열 리터럴을 지운 뒤 검사한다. 지우지 않으면 '서울' 같은 값이나
        // 주석에 들어간 단어 때문에 오탐이 난다.
        String scrubbed = STRING_LITERAL.matcher(
                        BLOCK_COMMENT.matcher(
                                LINE_COMMENT.matcher(sql).replaceAll(" "))
                                .replaceAll(" "))
                .replaceAll(" ' ' ")
                .toLowerCase(Locale.ROOT);

        if (scrubbed.contains(";")) {
            return "여러 문장을 한 번에 실행할 수 없다.";
        }

        for (String word : FORBIDDEN) {
            if (containsWord(scrubbed, word)) {
                return "허용되지 않는 키워드가 있다: " + word;
            }
        }
        return null;
    }

    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            int end = at + word.length();
            boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
