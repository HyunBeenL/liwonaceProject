# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

리원에이스 오픈소스 개발자대회 제출작: MCP 기반 데이터 플랫폼. 사용자 질문을 규칙 기반 라우터가 분석해
적절한 MCP 도구(vector_search / nl2sql / knowledge_graph)로 위임하고, 각 도구가 PostgreSQL을 조회해 응답한다.

**현재 상태**: Spring Initializr로 생성한 스켈레톤만 존재한다 (`ReaoneProjectApplication`, 빈 `application.properties`).
라우터, 3개의 `@Tool` 구현체, DB 스키마, docker-compose 설정은 아직 작성되지 않았다.

## Commands

Windows에서는 `mvnw.cmd`, 다른 셸에서는 `./mvnw`를 사용한다.

- Build: `./mvnw clean install`
- Run: `./mvnw spring-boot:run`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=ReaoneProjectApplicationTests`
- Test (single method): `./mvnw test -Dtest=ReaoneProjectApplicationTests#contextLoads`

## Architecture (target)

```
API Controller → Router (규칙 기반) → @Tool (VectorSearchTool | Nl2SqlTool | KnowledgeGraphTool) → PostgreSQL
```

- 라우터는 사용자 질문을 세 도구 중 하나로 분기한다.
- `Nl2SqlTool`만 내부적으로 Ollama를 호출해 자연어를 SQL로 변환한 뒤 PostgreSQL에 실행한다.
- `VectorSearchTool`은 pgvector를 통한 유사도 검색을, `KnowledgeGraphTool`은 그래프 형태로 저장된 관계 조회를 담당할 것으로 예정되어 있다.
- MCP 서버는 `spring-ai-starter-mcp-server-webmvc`로 노출되며, 각 `@Tool`이 MCP 도구로 등록되는 구조다.

## Stack

- Java 21, Spring Boot 4.1.0 (`spring-boot-starter-data-jpa`, `spring-boot-starter-webmvc`)
- Spring AI 2.0.0 BOM — `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-model-ollama`, `spring-ai-starter-vector-store-pgvector`, `spring-ai-vector-store-advisor`
- PostgreSQL (런타임), Lombok
- 베이스 패키지: `com.leehv1234.reaoneproject`
