-- ============================================================
-- 지식 그래프 스키마
--
-- 대회 데이터셋은 graph/nodes.json, graph/edges.json만 제공하고
-- 이를 적재할 DDL은 포함하지 않는다. 따라서 직접 설계한다.
--
-- 노드 133개 / 관계 354개 규모라 인접 리스트 + 재귀 CTE로 충분하다.
-- (별도 그래프 확장 없이 순수 PostgreSQL로 다중 홉 탐색이 가능하다.)
-- ============================================================

-- 노드: client(30) / product(12) / employee(45) / project(40) / department(6)
CREATE TABLE graph_nodes (
    id          VARCHAR(50) PRIMARY KEY,   -- 'client_1', 'employee_12' 형식
    type        VARCHAR(30) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    -- 노드 유형마다 속성이 달라(industry/region/size, position/dept, budget...)
    -- 정규화하지 않고 JSONB로 둔다.
    properties  JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP DEFAULT NOW()
);

-- 관계: BELONGS_TO / HEAD_IS / USES / MANAGES_ACCOUNT / HAS_PROJECT / LEADS / REPORTED_ISSUE
CREATE TABLE graph_edges (
    id          SERIAL PRIMARY KEY,
    source_id   VARCHAR(50) NOT NULL REFERENCES graph_nodes(id),
    target_id   VARCHAR(50) NOT NULL REFERENCES graph_nodes(id),
    relation    VARCHAR(30) NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    -- 같은 관계가 중복 적재되는 것을 막는다 (로더 재실행 시 안전)
    CONSTRAINT uq_graph_edge UNIQUE (source_id, target_id, relation)
);

-- 이름으로 시작 노드를 찾는 질의: "김민수가 이끄는 프로젝트는?"
CREATE INDEX idx_graph_nodes_name ON graph_nodes(name);
CREATE INDEX idx_graph_nodes_type ON graph_nodes(type);

-- 정방향/역방향 탐색 모두 필요하다.
-- "Product-C1 담당 엔지니어는?" 은 USES 관계를 거꾸로 타고 올라간다.
CREATE INDEX idx_graph_edges_source ON graph_edges(source_id, relation);
CREATE INDEX idx_graph_edges_target ON graph_edges(target_id, relation);
CREATE INDEX idx_graph_edges_relation ON graph_edges(relation);
