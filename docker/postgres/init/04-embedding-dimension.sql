-- ============================================================
-- 데이터셋 스키마 변경: 임베딩 차원 768 → 1024
--
-- 대회 데이터셋의 01-schema.sql은 embedding을 vector(768)로 선언한다
-- (README가 예시로 든 nomic-embed-text 기준).
-- 본 프로젝트는 문서 40건이 모두 한국어라 다국어 모델 bge-m3(1024차원)를
-- 사용하므로 컬럼 차원을 확장한다. 원본 SQL 파일은 수정하지 않는다.
--
-- 이 시점의 document_chunks는 비어 있으므로 데이터 손실은 없다.
-- ============================================================

ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(1024);

-- 코사인 거리 기반 근사 최근접 탐색 인덱스.
-- 적재 후 생성하는 편이 빠르지만, 40건 규모라 여기서 만들어도 무방하다.
CREATE INDEX idx_doc_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
