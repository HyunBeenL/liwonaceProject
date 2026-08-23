package com.leehv1234.reaoneproject;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트가 뜨는지 확인한다.
 *
 * <p>PostgreSQL과 Ollama가 필요하므로 {@code integration} 태그를 달아 기본 테스트
 * 실행에서 제외한다. 이것이 없으면 컨테이너를 띄우지 않은 환경에서 {@code mvnw test}가
 * 통째로 실패해, 저장소를 내려받은 사람이 단위 테스트조차 돌려볼 수 없다.
 *
 * <p>실행하려면 컨테이너를 먼저 띄우고 태그 제외를 풀어야 한다.
 * <pre>
 * docker compose up -d
 * ./mvnw test -Dsurefire.excludedGroups=
 * </pre>
 */
@Tag("integration")
@SpringBootTest
class ReaoneProjectApplicationTests {

    @Test
    void contextLoads() {
    }

}
