# 개발 진행 현황

**2026 오픈소스 개발자대회 지정과제 (리원에이스) — MCP 기반 지능형 데이터 플랫폼**

기준일 2026-08-23 · 출품작 제출 마감 2026-08-27 18:00 (**D-4**)

저장소: https://github.com/HyunBeenL/liwonaceProject

---

## 1. 한 줄 요약

사용자가 일상어로 질문하면 규칙 기반 라우터가 질문 유형을 판별해 세 개의 MCP 도구 중
하나로 위임하고, 각 도구가 PostgreSQL을 조회한 뒤 Ollama가 그 결과를 문장으로 정리한다.
청킹·임베딩·인덱싱·검색·리랭킹으로 이어지는 기존 RAG 파이프라인의 튜닝 지점을
MCP 한 겹으로 줄이는 것이 과제의 취지다.

---

## 2. 진행 상태

**과제가 요구하는 6단계가 모두 동작한다.** 질문을 던지면 답변이 나온다.

| # | 단계 | 상태 | 구현 |
|---|------|------|------|
| 1 | 사용자 질문 수신 | 완료 | `POST /api/ask` |
| 2 | AI 에이전트 | 완료 | `AgentService` |
| 3 | 규칙 기반 라우터 | 완료 | `QuestionRouter` — 도구 선택 30/30 |
| 4 | MCP 서버 · 도구 3종 | 완료 | `Registered tools: 3` |
| 5 | PostgreSQL 조회 | 완료 | pgvector · 재귀 CTE · NL2SQL |
| 6 | 자연어 답변 생성 | 완료 | Ollama |

### 역할 분담

**Ollama는 도구를 선택하지 않는다.** 도구 선택은 규칙 기반 라우터가 맡고, Ollama는
두 곳에서만 일한다. `nl2sql` 안에서 자연어를 SQL로 옮길 때, 그리고 조회 결과를 문장으로
만들 때다.

소형 모델에 도구 선택을 맡기면 같은 질문에도 결과가 흔들린다. 규칙 기반으로 빼면
결정적이 되고, 데이터셋의 `questions.json`에 정답 도구가 들어 있어 30문항 정확도를
그대로 측정할 수 있다.

이 구조 때문에 **라우터는 MCP 서버 바깥, 에이전트 쪽에 위치한다.** 서버는 도구만
노출하고 선택에는 관여하지 않는다.

```
질문
 │ POST /api/ask
 ▼
AgentService
 ├─▶ QuestionRouter          도구와 인자를 결정한다 (LLM 아님)
 ├─▶ McpToolClient
 │     └── HTTP JSON-RPC ──▶ /mcp ──▶ 도구 3종 ──▶ PostgreSQL
 └─▶ Ollama                  조회 결과를 문장으로
 ▼
AgentAnswer {답변, 도구, 인자, 라우터 점수, 원본 근거, 소요시간}
```

에이전트는 도구를 자바 메서드로 직접 부르지 않고 **MCP 프로토콜로 호출한다.** 같은 JVM
안의 빈을 주입받으면 프로토콜 계층을 건너뛰게 되어 MCP 서버를 만든 의미가 없다.
`app.agent.mcp-url`을 바꾸면 별도 프로세스로 띄운 서버에 붙는다.

> 멀티모듈로 분리하지 않았다. 마감 직전에 pom 재구성과 코드 이동으로 동작하는 빌드를
> 흔드는 것보다, 주소를 설정으로 빼서 언제든 분리 가능한 상태로 두는 편이 낫다고 판단했다.
> 프로토콜은 실제로 HTTP를 그대로 탄다.

---

## 3. 구현한 것

### 커밋 이력

각 커밋은 독립적으로 컴파일된다(임시 worktree로 체크아웃해 검증).

| 커밋 | 내용 |
|------|------|
| 개발 인프라 구성 | PostgreSQL+pgvector, Ollama, 초기화 스크립트 |
| 데이터셋 로더 | 그래프 적재 + 문서 임베딩 |
| vector_search MCP 도구 | pgvector 유사도 검색 |
| knowledge_graph MCP 도구 | 재귀 CTE 관계 탐색 |
| nl2sql MCP 도구 | 자연어 → SQL → 읽기 전용 실행 |
| nl2sql 생성 옵션 실측 | 추론 옵션 실험 결과 |
| 규칙 기반 라우터 | 도구 선택 30/30 |
| 에이전트 | 질문 → 라우터 → MCP → 답변 |
| 답변 생성 추론 비활성화 | 응답 시간 1/3 |

### 소스 구성

```
src/main/java/com/leehv1234/reaoneproject/
├── ReaoneProjectApplication.java
├── config/McpToolConfig.java        도구 3종을 MCP 서버에 등록
├── dataset/
│   ├── DatasetLoader.java           그래프 JSON 적재 + 문서 임베딩
│   └── DatasetProperties.java
├── router/
│   ├── QuestionRouter.java          가중 점수 기반 도구 선택
│   └── ToolRoute.java               결정 + 점수 + 근거
├── agent/
│   ├── AskController.java           POST /api/ask, GET /api/route, GET /api/tools
│   ├── AgentService.java            라우터 → MCP → 답변 생성
│   ├── McpToolClient.java           MCP 프로토콜 클라이언트
│   └── AgentAnswer.java
└── tool/
    ├── VectorSearchTool.java        pgvector 유사도 검색
    ├── KnowledgeGraphTool.java      재귀 CTE 관계 탐색
    ├── Nl2SqlTool.java              자연어 → SQL → 실행
    ├── SchemaCatalog.java           프롬프트용 스키마를 DB에서 생성
    └── SqlGuard.java                LLM이 만든 SQL의 구문 검사
```

### 스택

| 구분 | 선택 | 이유 |
|------|------|------|
| 런타임 | Java 21, Spring Boot 4.1.0, Spring AI 2.0.0 | — |
| 저장소 | PostgreSQL 17 + pgvector, HNSW 코사인 인덱스 | 과제 요구사항 |
| 임베딩 | **bge-m3** (1024차원) | 문서 40건이 모두 한국어. 데이터셋이 예시로 든 nomic-embed-text는 영어 위주 |
| 언어모델 | **gemma4:e2b** (Ollama 로컬) | 과제 권장. 외부 API 사용 금지 규정 |
| 프로토콜 | MCP Streamable HTTP (`/mcp`) | 서버·클라이언트 양쪽 모두 |

---

## 4. 데이터

`docker compose up` 한 번으로 컨테이너가 뜨고 초기화 스크립트 5개가 순서대로 실행된다.
실행 순서가 곧 의존 순서다: 확장 → 데이터셋 스키마 → 데이터셋 데이터 → 임베딩 차원 변경 → 그래프 스키마.

| 데이터 | 규모 | 적재 방식 |
|--------|------|-----------|
| 정형 데이터 | 8개 테이블 818행 | 데이터셋 SQL |
| 문서 임베딩 | 40건, 1024차원 | `DatasetLoader`가 기동 시 |
| 그래프 노드 | 133개 (client·product·employee·project·department) | `DatasetLoader`가 기동 시 |
| 그래프 관계 | 354개 (7종) | `DatasetLoader`가 기동 시 |

### 스키마 관련 결정

**임베딩 차원 768 → 1024.** 데이터셋의 `01-schema.sql`은 `embedding vector(768)`로
차원을 못 박아두었다. 한국어 문서에 맞춰 bge-m3(1024차원)를 쓰기로 했으므로 별도
마이그레이션(`04-embedding-dimension.sql`)으로 컬럼을 확장한다. **데이터셋 원본 SQL은
수정하지 않는다.**

**그래프 스키마 직접 설계.** 데이터셋은 `nodes.json`/`edges.json`만 제공하고 이를 적재할
DDL이 없다. `graph_nodes`(JSONB properties) / `graph_edges`(정·역방향 인덱스)를 직접
설계했다. 133노드 354관계 규모라 별도 그래프 엔진 없이 재귀 CTE로 충분하다.

**문서는 청킹하지 않는다.** 문서가 511~1024바이트로 작아 1문서 = 1청크로 둔다.
다만 임베딩 대상에는 제목을 덧붙인다. 본문에 없는 고객사·제품·날짜가 제목에 담겨 있다.

**데이터셋은 저장소에 커밋하지 않는다.** "대회 참가 목적으로만 사용 가능" 라이선스라
`.gitignore`로 제외하고 경로를 `app.dataset.path`로 지정한다.

---

## 5. 검증 결과

데이터셋이 제공한 예시 질문을 그대로 사용했다. 검증 스크립트는 `scripts/`에 있다.

### 라우터 도구 선택 — 30/30

`questions.json`에 문항마다 정답 도구가 들어 있어 자동 채점이 된다.
`QuestionRouterTest`가 30문항을 채점하며, **Docker 없이 `./mvnw test`로 돌아간다.**

**첫 매치가 아니라 가중 점수 합산으로 고른다.** 같은 단어가 도구를 가로질러 나타나기
때문이다.

- "가장 많은"은 세 문항에서 두 도구로 갈린다. 관계 키워드가 함께 있으면 그래프 집계,
  없으면 SQL 집계다.
- "이슈"는 REPORTED_ISSUE 신호지만 "미팅에서 논의된 일정 지연 이슈"는 회의록을 찾는
  질문이다. 문서 신호가 더 무거워야 한다.
- "Product-C1"은 개체명이지만 "Product-C1 설치 방법"은 기술문서 질문이다.
  개체명만으로 그래프를 확정하면 안 된다.

라우터는 도구 이름뿐 아니라 **인자까지 만든다.** 개체명 추출, 관계 추론, 2홉 판단,
집계 여부를 정해 그대로 호출할 수 있는 형태로 넘긴다.
판단 근거는 `ToolRoute.scores`와 `evidence`에 담겨 나간다.

### vector_search — 10/10

10문항 전부 의도한 문서 타입을 상위에 반환한다.

| 질문 | 결과 |
|------|------|
| Product-C1 설치 방법이 궁금해 | 「Product-C1 설치 가이드」 유사도 `0.7086` |
| SSL 인증서 관련 장애가 있었어? | 장애보고서 3건 (`0.6852`, `0.6759`, `0.6025`) |

무관한 문서를 거르는 하한은 `0.5`다. 실측에서 정답 문서는 0.55 이상,
무관한 문서는 0.49 이하에 몰렸다.

### knowledge_graph — 9/10

1홉 정·역방향, 2홉, 관계 집계, 속성 필터가 모두 동작한다.
"서울물산 담당 엔지니어"만 조회할 수 없다(8절 데이터셋 문제 참조).

설계상 결정 세 가지:

- **양방향 탐색.** 사람이 묻는 방향과 데이터의 방향이 자주 반대다(10문항 중 5개).
  "Product-C1을 쓰는 고객사"는 `USES`(고객사→제품)를 거꾸로 타야 한다.
- **`targetType` 파라미터.** 2홉 탐색은 무차별 확장된다. 없으면 "Product-D1 관련
  프로젝트"가 60건 상한에 걸려 정작 프로젝트가 잘려 나간다.
- **집계는 양쪽 모두 반환.** `REPORTED_ISSUE`는 제품(도착지) 기준,
  `MANAGES_ACCOUNT`는 직원(출발지) 기준이라 세는 쪽이 관계마다 다르다.

### nl2sql — 실행 10/10, 정답 9/10 안팎

정확도는 실행마다 9/10 안팎이고 **틀리는 문항이 바뀐다.** `temperature`가 0인데도
편차가 있다.

**"실행 성공"과 "정답"은 다르다.** 첫 검증에서 10/10 실행 성공이 나왔지만 SQL을 하나씩
확인하니 두 개가 조용히 틀려 있었다. 에러 없이 잘못된 답을 내놓는 것이 이 도구의 가장
위험한 실패 방식이다. 그래서 도구는 **실행한 SQL을 항상 결과와 함께 반환한다.**

프롬프트 규칙 두 개를 실측으로 추가했다.

- *조인은 외래키 목록에 있는 관계로만 한다* — `employees.dept_id = departments.head_id`
  오조인을 고쳤다. 이 데이터는 `head_id`가 `id`와 같아 **결과가 우연히 맞았을 뿐**,
  실제 데이터에서는 틀렸을 질의였다. 결과만 봐서는 잡을 수 없는 종류의 버그다.
- *값이 정해진 컬럼은 부정 대신 IN으로 나열한다* — 효과가 일정하지 않다.

---

## 6. 성능

### 세 단계에 걸친 개선

질문 → 답변 전 구간 실측이다.

| 도구 | ① CPU 최초 | ② 추론 최적화 | ③ **GPU** |
|------|-----------|-------------|----------|
| `knowledge_graph` | 32.3초 | 4.8초 | **0.9초** |
| `vector_search` | 59.1초 | 18.3초 | **3.7초** |
| `nl2sql` | 63.5초 | 34.5초 | **8.4초** |
| 3문항 합계 | 155초 | 58초 | **13초** |

**최초 대비 12배.** 답변 내용의 정확도는 그대로다.

### 어디에 시간이 가는가

```
응답시간 ≈ 모델 적재 + 프롬프트 처리 + (생성 토큰 수 ÷ 생성 속도) × LLM 호출 횟수
```

구간을 나눠 재보니 클라이언트가 잰 총 45.34초 중 **Ollama 내부가 45.28초**였다.
Spring·MCP·JDBC를 모두 합친 바깥 오버헤드는 **0.06초**다. PostgreSQL 조회는 밀리초,
임베딩은 1초 미만이다. **애플리케이션 코드는 병목에 기여하지 않는다.**

`nl2sql`이 구조적으로 가장 느린 이유는 **LLM을 두 번 타기 때문이다.** 중간에 DB 실행이
끼어 한 호출로 합칠 수 없다. 1차 호출 시점에 모델은 데이터를 보지 못했고, 2차 호출
시점에는 조회 결과를 문장으로 만들어야 한다. 다른 두 도구는 무엇을 조회할지 라우터가
이미 정했으므로 질의 생성 호출이 없다.

### ② 추론은 작업별로 갈린다

`gemma4:e2b`는 답을 내기 전 내부 추론을 길게 돌린다. SQL 한 줄을 얻는 데 570토큰 넘게
생성했다. 그런데 추론을 끄는 것이 **작업에 따라 정반대 결과**를 냈다.

| 작업 | 추론 켬 | 추론 끔 | 결론 |
|------|--------|--------|------|
| SQL 생성 | 9/10 정답 | **5/10 정답** | **켠다** |
| 답변 생성 | 39.7초 / 671토큰 | **14.3초 / 147토큰** | **끈다** |

SQL 생성에서 추론을 끄면 별칭이 어긋나거나(`T1`로 조인하고 `T2` 참조) 없는 컬럼을
부른다. **조인과 별칭을 맞추는 것이 바로 추론이 하던 일이었다.**

답변 생성은 반대다. 끄는 쪽이 **더 빠르고 더 정확했다.** 켜면 "장애가 있었습니다"
수준으로 추상화되면서 어느 고객사 얘기인지 사라지는데, 끄면 "Client-A의 Product-C1에서는…"
처럼 근거의 고유명사를 그대로 짚는다. 주어진 문서를 요약하는 일이라 추론할 것이 없고,
길게 생각할수록 구체성이 증발한 것으로 보인다.

`thinkLow` 같은 중간 설정은 양쪽 다 나빴다. 추론 토큰이 생성 상한을 먹어
SQL이 `FROM clients AS T1 INNER JOIN projects` 처럼 중간에 잘린다.

> **소형 모델의 추론 옵션은 켜고 끄는 문제가 아니라 작업별로 나누는 문제다.**

### ③ GPU

CPU 추론은 연산량이 아니라 **메모리 대역폭**에 묶인다. 토큰 하나를 만들 때마다 7.2GB
가중치 전체를 읽어야 하므로, 코어를 더 써도 빨라지지 않는다. 실제로 프롬프트 길이와
무관하게 22~28 tok/s로 고정이었다.

Ollama를 Windows 네이티브로 설치해 AMD Radeon RX 7600(gfx1102)을 ROCm으로 쓰자
**생성 속도가 22.6 → 82.6 tok/s로 3.7배** 올랐다.

```
library=ROCm  compute=gfx1102  name="AMD Radeon RX 7600"
libdirs=ollama,rocm_v7_1  type=discrete  total="8.0 GiB"  available="7.8 GiB"
```

**코드와 설정은 한 줄도 바꾸지 않았다.** `application.yml`이 `localhost:11434`를 보고
있었고, 컨테이너를 내리고 네이티브가 같은 포트를 잡으면서 그대로 붙었다.

전제 조건은 Ollama 문서가 요구하는 **ROCm v7 / HIP7 드라이버 스택**이다
(`amdhip64_7.dll` 존재로 확인). VRAM 8GiB에 모델이 7.2GB라 여유가 크지 않다.

> **제출물의 기본 경로는 Docker로 유지한다.** 심사자 장비에 GPU가 있으리란 보장이 없다.
> 네이티브 GPU는 선택적 가속으로 문서화한다. 둘은 같은 포트를 쓰므로 함께 띄울 수 없다.

---

## 7. 개발 중 해결한 문제

문서화가 얕은 최신 스택 조합에서 겪은 것들이다. 결과보고서 소재이기도 하다.

### MCP 전송 계층이 조용히 비활성화된다

`spring.ai.mcp.server.protocol`을 **명시해야 한다.** Java 필드 기본값은 이미
`STREAMABLE`이지만, 전송 계층 자동 설정이 `@ConditionalOnProperty`(matchIfMissing 없음)로
걸려 있어 속성을 적지 않으면 조건이 "did not find property"로 떨어진다.

증상이 오해를 부른다. 기동 로그에는 `Registered tools: 1`이 정상으로 찍히고 앱도 정상
기동하는데 **모든 MCP 엔드포인트가 404**다. `--debug`의 조건 평가 리포트로 원인을 특정했다.

### 도구가 LLM을 주입받으면 순환 참조가 생긴다

`Nl2SqlTool` → `ChatModel` → `ToolCallingManager` → `ToolCallbackResolver`
→ `ToolCallbackProvider` → 다시 `Nl2SqlTool`로 고리가 닫혀 기동이 실패한다.
Spring AI가 모든 도구를 모델에 물려주는 구조라, 도구가 모델을 참조하는 순간 필연적이다.
`ObjectProvider`로 사용 시점까지 해결을 미뤄 끊었다. `AgentService`도 같은 이유로 같은 방식이다.

### MCP 클라이언트 자동 설정은 기동 시 연결한다

이 애플리케이션이 서버와 클라이언트를 겸하므로, 기동 시점에 연결하면 아직 서버가 요청을
받기 전이라 실패한다. `spring-ai-starter-mcp-client`를 쓰지 않고 MCP SDK의
`McpSyncClient`를 `openConnectionOnStartup(false)`와 지연 생성으로 직접 구성했다.
SDK는 서버 스타터의 전이 의존성으로 이미 클래스패스에 있어 새 의존성이 필요 없었다.

### Prompt에 넘긴 옵션은 기본 설정을 대체한다

병합되지 않는다. 모델 이름을 빠뜨리면 Spring AI 내장 기본값(`mistral`)을 호출해 404가 난다.
`ChatModel`의 기본 설정에서 모델 이름을 가져와 함께 지정해야 한다.

### Spring Boot 4는 Jackson 3를 쓴다

패키지가 `com.fasterxml.jackson.*`에서 `tools.jackson.*`으로 바뀌었고,
`JacksonException`이 검사 예외에서 비검사 예외로 변경되어 기존 예제 코드는
그대로 컴파일되지 않는다.

### 단위 테스트가 DB를 요구하면 안 된다

컨텍스트 로딩 테스트에 `integration` 태그를 달아 기본 실행에서 제외했다.
그러지 않으면 컨테이너 없는 환경에서 `mvnw test`가 통째로 실패해, 저장소를 내려받은
사람이 단위 테스트조차 돌려볼 수 없다. **2차 평가에 기능테스트가 있어 중요하다.**

### `docker-entrypoint-initdb.d` 마운트

디렉터리를 read-only로 마운트하면 그 안에 개별 파일을 겹쳐 마운트할 수 없어 컨테이너가
아예 뜨지 않는다. 전부 파일 단위로 마운트한다.
그리고 `docker compose up -d`는 컨테이너 **생성**까지만 기다린다. healthcheck가 `healthy`가
되기 전에 앱을 띄우면 `Connection refused`로 죽는다.

### `.gitignore` 패턴이 소스 패키지를 삼킨다

대회 데이터셋을 제외하려고 `dataset/`을 넣었더니 앞의 슬래시가 없어
`src/main/java/.../dataset/` 패키지까지 함께 제외됐다. `/dataset/`으로 고쳤다.
**커밋을 나누지 않았다면 소스가 통째로 누락된 채 제출될 뻔한 종류의 실수다.**

### 한국어와 Windows

- 셸에서 curl로 한국어를 보내면 인코딩이 깨진 채 **요청은 성공한다.**
  40개 문서의 유사도가 모두 0.37대로 평탄해져 임베딩 모델 문제로 오인할 뻔했으나,
  저장된 벡터는 정상이었고 질의 인코딩만의 문제였다. HTTP 요청은 UTF-8 바이트를 명시한다.
- Windows PowerShell 5.1은 BOM 없는 `.ps1`을 ANSI로 읽어 한국어(주석 포함)가 깨지고
  엉뚱한 줄에서 구문 오류가 난다. 모든 스크립트를 UTF-8 BOM으로 저장한다.
- PowerShell 변수는 대소문자를 구분하지 않는다. `$All` 스위치 파라미터가 있는 스크립트에서
  지역 변수 `$all`을 쓰면 같은 변수라 스위치가 깨진다.

---

## 8. 데이터셋에서 발견한 문제

문의처: 리원에이스 기술개발본부 (`sihyeon@liwonace.co.kr`)

**기대 답변이 없다.** 과제 소개 페이지는 데이터셋에 "예시 질문 30개 + **기대 답변** +
사용 도구"가 있다고 안내하지만, 실제 `questions.json`의 필드는 `q`, `tool`, `hint`
셋뿐이다. 따라서 **라우터의 도구 선택 정확도는 자동 채점이 가능하지만, 답변 정확도는
평가 기준을 직접 정의해야 한다.**

**그래프에 없는 이름이 질문에 등장한다.** "서울물산 담당 엔지니어는 누구야?"의 힌트는
`client_2`를 가리키는데, `nodes.json`의 `client_2`는 이름이 `Client-B`이고
"서울물산"은 `nodes.json`에 한 번도 나오지 않는다. 이름으로 조회할 수 없다.
도구 선택은 맞지만 답변은 낼 수 없는 문항이다.

**그래프용 DDL이 없다.** 4절 참조.

**프로젝트 이름이 중복된다.** 40개 노드 중 고유 이름은 39개다
(`project_2`와 `project_13`이 모두 "Client-B CI/CD 파이프라인 구축").
서로 다른 노드이므로 중복 제거 대상이 아니다.

---

## 9. 남은 작업

구현은 끝났다. 남은 것은 제출물이다.

1. **결과보고서** — 이 문서가 초안이다.
2. **시연 영상 3분 이내** — GPU 전환으로 3문항이 13초에 끝나므로 실시간 촬영이 가능하다.
3. **LICENSE** — 아직 없다. 2차 평가에 라이선스 검증이 있고,
   **라이선스 없는 공개 저장소는 법적으로 "모든 권리 유보"라 오픈소스로 취급되지 않는다.**
4. **README** — 현재 한 줄뿐이다. 데이터셋이 저장소에 없으므로 실행 방법 안내가 필요하다.
5. **의존성 라이선스 정리** — 2차 평가 항목이다.

### 알려진 한계

- 벡터 검색에 시간 정렬이 없다. "최근 서버 장애"처럼 시간 조건이 붙은 질문은
  의미 유사도만으로 찾으므로 최신 문서가 상위에 온다는 보장이 없다.
- `nl2sql` 정확도가 실행마다 흔들린다.

---

## 10. 실행 방법

```bash
# 1. 컨테이너 기동 후 healthy 확인 (이 순서를 지켜야 한다)
docker compose up -d
docker compose ps

# 2. 모델 준비 (최초 1회, 약 7.7 GiB)
docker exec reaone-ollama ollama pull bge-m3
docker exec reaone-ollama ollama pull gemma4:e2b

# 3. 데이터셋을 dataset/ 에 배치
#    https://liwonace.co.kr/blog/9 에서 companyx-dataset-v1.0.zip 내려받아 압축 해제

# 4. 앱 기동 (기동 시 문서 임베딩과 그래프 적재가 자동 수행된다)
./mvnw spring-boot:run
```

### 사용

```bash
curl -X POST http://localhost:8080/api/ask \
     -H "Content-Type: application/json" \
     -d '{"question":"..."}'
```

| 엔드포인트 | 용도 |
|-----------|------|
| `POST /api/ask` | 질문 → 답변 전 구간 |
| `GET /api/route?question=` | 라우터 판단만. 도구를 호출하지 않아 즉시 응답 |
| `GET /api/tools` | MCP 서버 연결과 노출 중인 도구 확인 |

### GPU 가속 (선택)

AMD/NVIDIA GPU가 있으면 Ollama를 호스트에 네이티브로 설치해 쓸 수 있다.
컨테이너와 네이티브는 같은 포트를 쓰므로 함께 띄울 수 없다.

```bash
docker compose stop ollama   # 포트 11434를 네이티브에 넘긴다
```

모델을 다시 받지 않으려면 컨테이너 볼륨에서 복사한다.

```bash
docker cp reaone-ollama:/root/.ollama/models ~/.ollama/
```

AMD의 경우 Ollama 문서가 요구하는 ROCm v7 / HIP7 드라이버 스택이 필요하다.
기동 로그의 `inference compute ... library=ROCm` 줄로 인식 여부를 확인한다.

### 검증 스크립트

`.ps1` 실행이 정책에 막히면 `powershell -ExecutionPolicy Bypass -File <경로>`로 실행한다.

| 스크립트 | 용도 |
|----------|------|
| `scripts/agent-probe.ps1` | 질문 → 답변 전 구간. `-Question`, `-All` 옵션 |
| `scripts/mcp-call.ps1` | MCP 핸드셰이크 후 `tools/list`와 `tools/call` 확인 |
| `scripts/search-probe.ps1` | 임베딩 검색만 직접 확인 (MCP 서버 불필요) |
| `scripts/graph-probe.ps1` | knowledge_graph를 예시 질문 10개로 검증 |
| `scripts/sql-probe.ps1` | nl2sql을 예시 질문 10개로 검증 |
| `scripts/add-claude-connector.ps1` | Claude Desktop에 MCP 서버 등록 (개발용) |

### 테스트

```bash
./mvnw test                                # 라우터 채점 포함. Docker 불필요
./mvnw test -Dsurefire.excludedGroups=     # 컨텍스트 로딩 포함. Docker 필요
```

### 대회 일정

| 일정 | 기간 |
|------|------|
| **출품작 제출** | **~ 8월 27일(목) 18:00** |
| 1차 서면 평가 | 9월 3일 ~ 9월 4일 |
| 멘토링 | 9월 18일 ~ 10월 9일 |
| 2차 평가 (기능테스트·라이선스 검증) | 10월 12일 ~ 10월 28일 |
| 2차 발표 평가 | 11월 4일 ~ 11월 5일 |
| 수상팀 발표 | 11월 11일 |
| 시상식 | 12월 4일 |
