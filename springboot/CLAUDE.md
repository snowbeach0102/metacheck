# springboot — 백엔드 작업 인수인계

> 최종 갱신: 2026-11-03(화) · 이 문서는 **사람과 Claude가 같이 읽는다.** 처음 보는 Claude는 위에서부터 읽으면 바로 이어서 작업할 수 있다.
> 비밀번호 · IP · 키 파일 경로는 일부러 적지 않았다. 필요하면 사용자에게 물어볼 것.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 프로젝트 | 메타체크(MetaCheck) 백엔드 — Spring Boot **4.1.1**, Java 17, Gradle 9.7.1, 패키지 `com.example.metacheck` |
| 지금 있는 기능 | **AI 문제 생성**(`question` 패키지, 가짜 모델로 동작) — 11/03에 만듦 |
| 아직 없는 것 | 실제 Bedrock 호출 · 2차 풀이 검증 · 개념 추출(AI-01) · DB 엔티티 전부 · 로그인 · 호출 제한 |
| 테스트 | 단위 테스트 **55개 전부 통과** (`question` 51 + `config` 4, DB 없이 돎) |
| DB | EC2의 Docker MySQL 8 (`metacheck` DB). **접속 정보(주소 · 비밀번호)는 git 밖에 둔다** — 서버는 환경변수, 내 PC는 `config/application.properties`. 아래 "DB 접속 설정" |
| git | 이 폴더는 상위 `C:\metacheck` 저장소(GitHub `snowbeach0102/metacheck`)의 일부. **11/03 작업은 아직 커밋 안 함** |

## 먼저 알아야 할 위험 (꼭 읽을 것)

1. **`bootRun`과 `MetacheckApplicationTests.contextLoads`는 운영 DB에 접속한다.** `spring.jpa.hibernate.ddl-auto=update`라서 엔티티를 추가한 뒤 실행하면 **운영 DB의 테이블이 바로 바뀐다.** 사용자 허락 없이 실행하지 말 것. 아래 "DB 없이 앱 띄우기"를 쓰면 안전하다.
2. **지금 DB 비밀번호는 이미 GitHub에 올라간 이력에 들어 있다.** 옛 RDS 때 쓰던 것과 같은 비밀번호라서, 커밋 `ee589b9` 등 4개 커밋(전부 `origin/main`에 포함)의 `application.properties`에 남아 있다. 11/03에 현재 파일에서는 뺐지만(아래 "DB 접속 설정") **이력은 그대로**다 → 노출된 것으로 보고 **DB 비밀번호를 바꿔야 한다**(서버 관리 쪽 일. 바꾸면 서버의 `DB_PASSWORD`와 각자의 `config/application.properties`도 새 값으로). 저장소 공개 여부는 확인하지 못했다. 자세한 내용은 `../CLAUDE.md`.
3. **서버 관리(AWS · EC2 · Docker DB · 재배포)는 Claude 일이 아니다.** 사용자 + 외부 AI(Gemini)가 맡는다. Claude는 로컬 개발(로직 · API · 엔티티 · 버그)만 하고, `bootJar` 배포는 서버 관리 쪽이 한다.
4. 인계 기준으로 EC2의 3306 포트(DB)가 **바깥에 열려 있다.** 닫거나 SSH 터널로 바꾸라고 사용자에게 이미 권했다(처리 여부는 모름). 위 2번(비밀번호 노출)과 겹치면 DB가 외부에서 뚫릴 수 있으니 같이 처리할 것.

## DB 접속 설정 (비밀번호는 git 밖에)

git이 추적하는 `src/main/resources/application.properties`에는 비밀번호 · 서버 주소가 **없다.** `url=${DB_URL:jdbc:mysql://localhost:3306/metacheck?...}` · `username=${DB_USERNAME:root}` · `password=${DB_PASSWORD}` 처럼 자리표시자만 있다.

| 어디서 | 어떻게 |
|---|---|
| **내 PC** | `springboot/config/application.properties`를 만든다(`config/application.properties.example`을 복사해 채움). **git이 무시하는 파일**(`.gitignore`의 `/config/*`)이고, `springboot` 폴더에서 실행하면 스프링이 jar 안 설정보다 먼저 읽는다. 실제 값은 서버 관리 담당에게 받는다 |
| **서버(EC2)** | DB가 같은 서버 안이라 주소 기본값(`localhost:3306`)을 그대로 쓰고, **환경변수 `DB_PASSWORD`**(필요하면 `DB_USERNAME` · `DB_URL`)를 준다. 또는 jar 옆에 `application.properties`를 두고 `spring.datasource.password=...`를 적는다(그쪽이 우선). **재배포할 때 이 값이 없으면 앱이 시작되지 않는다**(이미 떠 있는 앱은 그대로) |

비밀번호가 없으면 DB에 접속하기 전에 `DB 비밀번호가 설정되지 않았습니다…`라고 알리고 멈춘다. 이 점검(`config/DatabaseSettingsCheck`)은 `MetacheckApplication.main`이 **설정이 읽힌 직후**에 부른다 — 일반 빈으로 두면 Hibernate가 먼저 접속을 시도해서 소용없다(실제 jar로 돌려 보고 알게 됨).

## 실행 · 테스트 (Windows PowerShell)

JDK 17이 PATH에 없어서 `JAVA_HOME`을 지정해야 한다. 아래 경로는 11/03에 쓴 PC 기준이니 **다른 컴퓨터에서는 그 컴퓨터의 JDK 17 위치로 바꿀 것**(처음 하는 컴퓨터라면 `../CLAUDE.md`의 "다른 컴퓨터에서 이어서 하기"부터).

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'
cd C:\metacheck\springboot
.\gradlew test --tests "com.example.metacheck.question.*"     # 안전: DB에 안 붙는다
.\gradlew compileJava                                          # 컴파일만
```

**DB 없이 앱 띄우기** (엔드포인트를 직접 호출해 보고 싶을 때): DB 자동 구성을 빼고, DB 주소를 닫힌 로컬 포트로 덮어쓴다.

```powershell
$env:SERVER_PORT='18080'
$env:DB_PASSWORD='none'     # 비밀번호 점검은 DB 자동구성을 빼도 적용되므로 아무 값이나
$env:SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:1/none'   # config 파일이 있어도 환경변수가 우선해서 원격 DB로 안 간다
$env:SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration'
.\gradlew bootRun
```

끝나면 `taskkill /PID <gradlew PID> /T /F`로 확인한 프로세스만 종료한다.

## AI 문제 생성 — `question` 패키지

팀이 Node로 만든 러너(`C:\mine_metacheck\ai관련\bedrock_12칸\run-bedrock.mjs`)를 Java로 옮긴 것이다. 사양서는 `C:\mine_metacheck\ai관련\AI_출제_사양서.pdf`.

**흐름:** 요청 → 입력 정리 → 프롬프트 조립 → 모델 호출 → 게이트 ① 형식 · ② 규칙 · 중복 검사 → 걸린 문항만 **1회 재생성** → 보기 섞기 → 응답. (사양서의 ③ 2차 풀이 검증 호출은 아직 없다.)

| 파일 | 하는 일 |
|---|---|
| `QuestionController` | `POST /api/v1/ai/questions`. 잘못된 요청 400, 모델 호출 실패 502. **로그인 · 호출 제한 없음** |
| `QuestionGenerationService` | 위 흐름 전체. 진입점은 `generate(GenerateRequest)` — 진단 흐름에서는 이걸 서버 안에서 직접 부르면 된다 |
| `GenerateRequest` / `PromptInput` / `InputCleaner` | 요청 검증 · 정리(줄바꿈 · 중괄호 · 태그 흉내 제거, 길이 자르기). 3단계는 `related` 필수, 문항 수 1~5 |
| `PromptAssembler` / `PromptTemplates` / `ExampleBank` | SYSTEM · USER 조립. 템플릿은 `resources/prompts/*.txt`, 예시는 `examples.json`(팀 검수 전 AI 초안 18개) |
| `QuestionGates` | 게이트 ① 형식 · ② 규칙 · 문장 수 · 금지 표현 · 정답 길이 편향 · 중복(Dice 유사도) |
| `OptionShuffler` | 보기 섞기. 조합(ㄱ,ㄴ) · 숫자 · 순서 보기는 섞지 않고 정렬, 모델이 `shuffle:false`면 그대로 |
| `ModelClient` | **모델 호출부 인터페이스.** 실제 Bedrock은 이 구현체 하나만 더하면 된다 |
| `MockModelClient` | 개발용 가짜 모델(기본값). 내용은 `[모의]` 표시가 붙은 뜻 없는 글자 |
| `GenerateResult` | 응답 모양. `status`: `pass` · `pass-after-retry` · `partial` · `skipped` · `fail` |
| `config/DatabaseSettingsCheck` | (`question` 패키지 밖) DB 비밀번호가 없으면 접속 전에 이유를 알리고 멈춤. `MetacheckApplication.main`이 연결 |
| `Json` | Jackson 3 도구. `pretty()`는 JS `JSON.stringify(v,null,2)`와 같은 모양(프롬프트 [예시]가 Node와 같아야 해서) |

**요청 예**

```json
{ "persona": "CERT_EXAM", "subject": "정보처리기사 필기", "topic": "데이터베이스", "examName": "정보처리기사 필기",
  "concept": { "id": "c1", "name": "정규화", "desc": "중복을 줄이는 설계 과정" },
  "related": [ { "name": "함수적 종속", "desc": "결정 관계" } ],
  "level": 3, "count": 2,
  "previousQuestions": [], "material": null, "userRequest": null }
```

응답: `status`, `requested`, `questions[]`(`c, lv, concepts, steps, q, o[4], a, distractors{"보기번호":"노린 오개념"}, x(해설), ev(근거), shuffleMode, shuffleNote`), `attempts[]`(`n, ms, errors`), `skipped[]`, `exampleIds`.
`persona`가 없으면 `UNIVERSITY`(앱에서 직업군을 안 고르면 대학생 수준으로 출제한다는 팀 결정).

**사양서의 초안 숫자** — 전부 실제 모델 출력을 보고 조정해야 한다: 정답 길이 편향 1.25배(`QuestionGates.BIAS`), 짧은 보기 제외 15자, 중복 0.75, 호출당 5문항, 재시도 2회, 온도 0.7.
**사양서에 없어서 직접 정한 값:** 응답 길이 문항당 2000토큰(최대 8000), PDF 발췌 8000자, 사용자 요청 100자, 과목 40자 · 단원 60자(앱 입력칸과 같음), 관련 개념이 여럿이면 ` / `로 이어 붙임.

**Node 원본과 일부러 다르게 한 곳 4가지**
1. 금지 표현 "N번": Node의 `\b[1-4]번\b`은 한글 문장에서 안 걸린다 → 의도대로 걸리게 다시 씀("2번째"는 제외).
2. 호출자가 준 `previousQuestions`는 지문끼리만 비교(보기까지 합치면 유사도가 희석돼 놓침).
3. `null` 필드는 "없음"으로 취급(더 엄격).
4. 과목 · 시험 같은 한 줄 입력에도 `</사용자 요청>` 같은 태그 흉내를 제거.

## 테스트가 보장하는 것

- `PromptAssemblerTest`: 프롬프트 4칸이 **Node 조립기 결과와 글자 하나까지 같다**(`golden/cells.json`).
- `NodeParityTest`: 예시 문항 6개를 14가지로 망가뜨린 84건 · 보기 고정순서 23건 · 유사도 23쌍에서 **Node 원본과 같은 판정**(`golden/parity.json`). 기준값을 바꾸면 이 테스트가 깨진다(실제로 확인함).
- `QuestionGenerationServiceTest` · `MockModelClientTest` · `QuestionGatesTest` · `OptionShufflerTest` · `QuestionControllerTest`(스프링 컨텍스트 없는 MockMvc) · `QuestionWiringTest`(빈 연결).
- 모든 `question` 테스트는 `@SpringBootTest`를 쓰지 않아서 DB에 안 붙는다.

## 사양서와 맞추기 (Node 쪽이 바뀌었을 때)

프롬프트 조각 · 예시 · 골든 파일은 `C:\mine_metacheck\ai관련`에서 뽑아 온 사본이다. 사양서나 Node 러너를 고치면 어긋난다.

```powershell
cd C:\mine_metacheck\ai관련\소스
node assemble-bedrock.js bedrock_out3          # prompts12.json 다시 만들기 → ../bedrock_12칸/ 으로 복사 (소스/README.md 참고)
cd C:\metacheck\springboot
node tools/sync-from-ai-spec.mjs               # 프롬프트 조각 · 예시 · 골든 4종을 다시 씀 (--ai, --out 옵션)
.\gradlew test --tests "com.example.metacheck.question.*"
```

테스트가 깨지면 "Node와 Java가 어긋났다"는 뜻이니 어느 쪽이 맞는지 사용자에게 물어볼 것. (`--out <폴더>`로 다른 곳에 먼저 써서 비교할 수 있다.)

## 남은 일 (우선순위 순)

1. **실제 Bedrock 연결** — 인프라 쪽에서 모델 ID · 리전 · 접근 방식(EC2 역할 권장, 로컬은 `aws configure`)을 받아야 한다. 해야 할 일:
   `ModelClient` 구현체 추가(`@ConditionalOnProperty(name="metacheck.ai.client", havingValue="bedrock")`) · AWS SDK `bedrockruntime`의 Converse API(system / messages / inferenceConfig 그대로 대응) · 스로틀링 재시도(Node는 최대 4회, 2초×n) · SDK 예외를 `ModelCallException`으로 감싸기 · 모델 ID · 리전은 설정으로(코드나 저장소에 키 금지).
   설정이 `bedrock`인데 구현체가 없으면 앱이 일부러 시작되지 않는다(가짜 응답이 운영에 섞이지 않게 — `QuestionWiringTest`가 보장).
   사양서 권장: 1 · 2단계는 Haiku급, 수험생 심화(3단계)는 Sonnet급 → 지금 인터페이스는 모델이 하나라 단계별 선택을 추가해야 한다.
2. **엔드포인트 보호** — 로그인 + 호출 횟수 제한. 실제 Bedrock을 붙이면 호출마다 돈이 든다. 붙이기 전에 먼저.
3. **게이트 ③ 2차 풀이 검증** — 생성된 문항을 다른 호출로 풀어 보게 해서 정답 일치 · 근거 · 단계를 확인(프롬프트는 `ai관련/bedrock_12칸/프롬프트_12칸.md`와 사양서 8쪽).
4. **개념 추출(AI-01)** — 과목 · 단원 → 핵심 개념 3개(프롬프트 `AI-01_프롬프트.md`, 응답 10초 제한이라 검증 호출 없이 서버 규칙만).
5. **DB 엔티티와 저장** — `src`에 `@Entity`가 **하나도 없다.** 팀 ERD v5(`C:\mine_metacheck\ERD\`)가 기준이다. DB에 지금 어떤 테이블이 있는지 사용자에게 먼저 확인할 것(인계 때 "엔티티 테이블이 자동 생성됨"이라 했지만 코드엔 엔티티가 없어서 출처가 불분명하다).
6. 프론트와 연동 — `static/index.html`은 11/03에 **10/26 최신본**으로 교체했고(다크 모드는 빠짐) `API_CONFIG.mock: true`다. 자세한 내용은 `../CLAUDE.md`.

## 코딩할 때 걸리는 점

- **Spring Boot 4 + Jackson 3**: `tools.jackson.*` 패키지, `JsonNode.isString()` / `asString()` / `propertyNames()` / `properties()`, 예외는 unchecked `JacksonException`. 자동 구성 클래스 이름도 3.x와 다르다(`org.springframework.boot.jdbc.autoconfigure...`).
- 발표 PPT 28번에는 "Spring Boot 3.2.4"로 적혀 있지만 실제 `build.gradle`은 4.1.1이다.
- 코드 스타일은 기존 파일을 따른다(주석은 한국어, 이유를 적는 짧은 주석).
