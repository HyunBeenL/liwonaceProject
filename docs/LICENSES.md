# 라이선스 검토

**2026 오픈소스 개발자대회 2차 평가 · 라이선스 충돌 여부 및 위반 사항 검증 대응 자료**

작성일 2026-08-24 · 원본 목록: [DEPENDENCIES.txt](DEPENDENCIES.txt) (의존성 187개)

> 이 문서는 검토 편의를 위한 정리이며 법률 자문이 아니다.
> 원본 목록은 `license-maven-plugin`으로 자동 생성했으므로 재현 가능하다.

---

## 1. 본 프로젝트의 라이선스

**Apache License 2.0** ([LICENSE](../LICENSE), `pom.xml`의 `<licenses>`에 명시)

선택 이유:

- 의존성의 압도적 다수(187개 중 약 150개)가 Apache-2.0이라 충돌 여지가 없다.
- 특허 실시권 조항이 있어 기업 수요 기반 과제에 적합하다.
- 지정과제를 낸 리원에이스의 air 프레임워크도 Apache-2.0이다.

---

## 2. 라이선스 분포

| 라이선스 | 대략 개수 | 성격 |
|---------|---------|------|
| Apache License 2.0 (표기 변형 포함) | ~150 | 허용적 |
| MIT / MIT-0 | 11 | 허용적 |
| Eclipse Public License 2.0 | 12 | 약한 카피레프트 (파일 단위) |
| Eclipse Distribution License 1.0 (= BSD-3) | 7 | 허용적 |
| BSD 계열 (2/3-Clause) | 6 | 허용적 |
| 기타 (CC0 등) | 1 | 허용적 |

---

## 3. 카피레프트 항목 검토

**GPL, AGPL 등 강한 카피레프트 라이선스는 발견되지 않았다.**
정적 검색에서 "GPL" 문자열로 잡힌 항목은 모두 아래 셋 중 하나이며, 각각 배포에 제약이 없다.

### 3.1 Logback — EPL-2.0 **또는** LGPL-2.1-only (듀얼)

| 항목 | 내용 |
|------|------|
| 아티팩트 | `ch.qos.logback:logback-classic`, `logback-core` 1.5.34 |
| 유입 경로 | `spring-boot-starter` 기본 로깅 구현체 |
| 판단 | **문제 없음** |

듀얼 라이선스이므로 수신자가 EPL-2.0을 선택할 수 있다. 또한 본 프로젝트는 Logback을
**수정하지 않고 라이브러리로 링크만** 한다. LGPL-2.1을 택하더라도 라이브러리를 수정하지 않는
링크 사용은 소스 공개 의무를 발생시키지 않는다.

### 3.2 Jakarta API — EPL-2.0 **또는** GPL-2.0 **with Classpath Exception**

| 항목 | 내용 |
|------|------|
| 아티팩트 | `jakarta.annotation:jakarta.annotation-api`, `jakarta.transaction:jakarta.transaction-api` |
| 유입 경로 | Spring Boot / JPA 표준 API |
| 판단 | **문제 없음** |

GPL-2.0이 붙어 있으나 **Classpath Exception**이 명시된 형태다. 이 예외는 해당 라이브러리와
링크하는 코드에 GPL이 전파되지 않음을 명시적으로 허용한다. Java 표준 API의 통상적인 라이선싱이며,
EPL-2.0을 선택할 수도 있다.

### 3.3 EPL-2.0 단독 항목

| 아티팩트 | 스코프 |
|---------|-------|
| `org.aspectj:aspectjweaver` | runtime |
| `jakarta.persistence:jakarta.persistence-api` | runtime (EDL 1.0과 듀얼) |
| `org.junit.jupiter:*` | **test** — 배포물에 포함되지 않음 |

EPL-2.0은 **파일 단위** 약한 카피레프트다. 해당 파일을 수정해 배포할 때만 그 파일의 소스 공개
의무가 생기며, 수정 없이 링크하는 경우에는 의무가 없다. 본 프로젝트는 어느 것도 수정하지 않는다.

---

## 4. 대회 데이터셋 취급

`companyx-dataset-v1.0.zip`의 README는 **"본 데이터셋은 대회 참가 목적으로만 사용 가능합니다"**로
사용 범위를 제한한다. 이는 Apache-2.0으로 배포되는 본 저장소와 양립하지 않는다.

따라서 **데이터셋을 저장소에 포함하지 않는다.**

- `.gitignore`에 `/dataset/` 등록 (앞의 슬래시가 없으면 동명의 소스 패키지까지 제외되므로 주의)
- 데이터셋 경로는 `app.dataset.path` 설정으로 외부에서 지정
- 실행 방법은 [PROGRESS.md](PROGRESS.md) 10절에 안내

재배포가 불가한 자료를 오픈소스 라이선스로 배포하는 상황을 사전에 차단한 것이다.

---

## 5. 결론

| 점검 항목 | 결과 |
|----------|------|
| 강한 카피레프트(GPL/AGPL) 포함 여부 | **없음** |
| 프로젝트 라이선스와 의존성 라이선스 충돌 | **없음** |
| 수정 후 재배포로 의무가 발생하는 의존성 | **없음** (전부 미수정 링크 사용) |
| 재배포 제한 자료 포함 여부 | **없음** (데이터셋 제외 처리) |

---

## 6. 재현 방법

```bash
./mvnw org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party \
       -Dlicense.outputDirectory=docs \
       -Dlicense.thirdPartyFilename=DEPENDENCIES.txt
```
