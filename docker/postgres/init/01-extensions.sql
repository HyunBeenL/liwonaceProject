-- pgvector: 벡터 검색 도구용
CREATE EXTENSION IF NOT EXISTS vector;

-- Spring AI PgVectorStore가 문서 id를 UUID로 생성할 때 사용
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 지식 그래프 노드/엣지 조회 시 재귀 CTE로 처리하므로 별도 확장은 불필요하다.
