# springboot — 백엔드 작업 인수인계

> 최종 갱신: 2026-11-03(화) · 이 문서는 **사람과 Claude가 같이 읽는다.** 처음 보는 Claude는 위에서부터 읽으면 바로 이어서 작업할 수 있다.
> 비밀번호 · IP · 키 파일 경로는 일부러 적지 않았다. 필요하면 사용자에게 물어볼 것.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 프로젝트 | 메타체크(MetaCheck) 백엔드 — Spring Boot **4.1.1**, Java 17, Gradle 9.7.1, 패키지 `com.example.metacheck` |
| 지금 있는 기능 | **AI 문제 생성**(`question` 패키지) — 11/03부터 수험생은 **정보처리산업기사 고정 · 시험형 단계 기준 · 2차 풀이 검증**까지. 기본은 가짜 모델이고, 실제 Bedrock 호출 코드도 있다(켜려면 설정 필요) |
| 아직 없는 것 | **실제 Bedrock으로 돌려 본 적 없음** · 개념 추출(AI-01) · DB 엔티티 전부 · 로그인 · 호출 제한 |
| 테스트 | 단위 테스트 **101개 전부 통과** (`question` 97 + `config` 4, DB · AWS 없이 돎) |
| DB | EC2의 Docker MySQL 8 (`metacheck` DB). **접속 정보(주소 · 비밀번호)는 git 밖에 둔다** — 서버는 환경변수, 내 PC는 `config/application.properties`. 아래 "DB 접속 설정" |
| git | 이 폴더는 상위 `C:\metacheck` 저장소(GitHub `snowbeach0102/metacheck`)의 일부. 11/03 작업은 **커밋 · 푸시 완료**(오전 `f7f1b84`~`c38d7a7`, 저녁 `11/03/화 정보처리산업기사 출제 기준 · 단원 세분화`) |

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

JDK 17이 PATH에 없어서 `JAVA_HOME`을 지정해야 한다. 아래 경로는 11/03 오전 PC 기준이니 **다른 컴퓨터에서는 그 컴퓨터의 JDK 17 위치로 바꿀 것**(PowerPoint가 있는 PC는 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`. 처음 하는 컴퓨터라면 `../CLAUDE.md`의 "다른 컴퓨터에서 이어서 하기"부터).

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

**흐름:** 요청 → 입력 정리 → 프롬프트 조립 → 모델 호출 → 게이트 ① 형식 · ② 규칙 · 중복 검사 → **③ 2차 풀이**(①②를 통과한 문항만, 여러 개면 병렬) → 걸린 문항만 **1회 재생성** → 보기 섞기 → 응답.

**11/03 정보처리산업기사 · 시험형(사용자 결정)** — 기준 문서는 사양서 3 · 4 · 8 · 15쪽.
- 수험생(`CERT_EXAM`)은 `[시험]` 줄이 항상 `exam-name.txt`(정보처리산업기사)이고 요청의 `examName` 은 쓰지 않는다. SYSTEM 의 `{{exam_scope}}` 자리에 출제 범위(`exam-scope.txt`, 출제기준 세부항목 22개)가 들어가고 대학생은 그 줄이 통째로 빠진다.
- 자격증 역할별 형식: 1단계 정의 · 특징(옳은 / 옳지 않은 것), 2단계 코드 · SQL · 계산 적용, 3단계 여러 개념을 엮은 코드 · SQL · 사례(`PromptAssembler.FORMAT_CERT`).
- 출력에 선택 칸 `code`(코드 · SQL · 표). 응답 `questions[].code` 로 내보낸다(없으면 null).
- ③ 2차 풀이(`QuestionVerifier`): 검증자에게 지문 · 코드 · 보기만 보내고(600토큰 · 온도 0 · 생성과 같은 등급), 정답 불일치 · 정답 2개 이상 · 맞는 보기 없음 · 근거 없음이면 그 문항을 재생성한다. 단계 판정이 다르면 기록만(`questions[].verify.stageOk`). 검증 응답이 두 번 다 형식에 안 맞으면 **검증 못 한 채 통과**하고 `verify.verified=false` 로 남긴다(Node 와 같음). 끄려면 `metacheck.ai.verify=false`(환경변수 `AI_VERIFY`) — 호출이 약 2배라서.

| 파일 | 하는 일 |
|---|---|
| `QuestionController` | `POST /api/v1/ai/questions`. 잘못된 요청 400, 모델 호출 실패 502. **로그인 · 호출 제한 없음** |
| `QuestionGenerationService` | 위 흐름 전체. 진입점은 `generate(GenerateRequest)` — 진단 흐름에서는 이걸 서버 안에서 직접 부르면 된다 |
| `GenerateRequest` / `PromptInput` / `InputCleaner` | 요청 검증 · 정리(줄바꿈 · 중괄호 · 태그 흉내 제거, 길이 자르기). 3단계는 `related` 필수, 문항 수 1~5 |
| `PromptAssembler` / `PromptTemplates` / `ExampleBank` | SYSTEM · USER 조립. 템플릿은 `resources/prompts/*.txt`(시험 · 출제 범위 · 2차 풀이 틀 포함), 예시는 `examples.json`(팀 검수 전 AI 초안 18개 — 수험생 9개는 정보처리산업기사) |
| `QuestionGates` | 게이트 ① 형식(`code` 는 글이면 됨) · ② 규칙 · 문장 수(지문만 · 빈칸 없는 마침표 제외) · 금지 표현(보기를 가리키는 꼴만) · 풀이 · 오답 설명(steps 6자 · 풀이 요령 금지, distractors 4자 · "옳은 설명" 금지) · 정답 길이 편향 · 중복(Dice 유사도, 코드 포함) |
| `QuestionVerifier` | 게이트 ③ 2차 풀이 — 검증 요청 글 만들기 · 검증자 답 판정(Node 의 `buildVerifyRequest` · `deriveVerify`) |
| `ExamUnits` | 정보처리산업기사 단원표(`prompts/exam-units.json`, 원본은 `ai관련/소스/exam-units.json`) — 3과목 · 주요항목 8 · 단원 22 · 단원마다 개념 3개(초안, 정보처리기사 공식 출제기준의 같은 세부항목 세세항목에 맞춤 — JSON 의 `basis` 는 서버가 읽지 않는다) |
| `UnitQuestionService` / `UnitController` | 단원별 출제(11/03). `GET /api/v1/ai/units` · `GET /api/v1/ai/units/{id}` · `POST /api/v1/ai/units/{id}/questions` — 본문 `{levels:[1,2,3], count:1, userRequest}`(모두 선택). 개념 3개 × 단계마다 `QuestionGenerationService` 를 병렬로 부르고(수험생 고정, 3단계 [관련 개념] = 같은 단원의 나머지 두 개념), 칸마다 결과(`cells[]`)와 합계(`requested` · `passed`)를 돌려준다. 칸당 3문항 · 전체 15문항 상한(초안), 없는 단원 404 · 잘못된 값 400 · 모델 실패 502. 로그인 · 호출 제한 없음 |
| `OptionShuffler` | 보기 섞기. 조합(ㄱ,ㄴ) · 숫자 · 순서 보기는 섞지 않고 정렬, 모델이 `shuffle:false`면 그대로 |
| `ModelClient` | **모델 호출부 인터페이스.** 요청에 모델 등급(`Tier`: `BASIC` · `ADVANCED`)이 들어 있고, 구현체는 `metacheck.ai.client`로 고른다 |
| `MockModelClient` | 개발용 가짜 모델(기본값). 내용은 `[모의]` 표시가 붙은 뜻 없는 글자. 2차 풀이 요청(`[할 일]` 이 있는 글)에는 자기가 낸 문항의 정답 위치를 답한다 |
| `BedrockModelClient` | 실제 Bedrock 호출(AWS SDK Converse, `metacheck.ai.client=bedrock`일 때만). 일시 오류는 최대 4번 호출(2초 × n 대기, Node와 같음). 권한 · 요청 오류는 재시도 없이 `ModelCallException`(→ 502) |
| `GenerateResult` | 응답 모양. `status`: `pass` · `pass-after-retry` · `partial` · `skipped` · `fail` |
| `config/DatabaseSettingsCheck` | (`question` 패키지 밖) DB 비밀번호가 없으면 접속 전에 이유를 알리고 멈춤. `MetacheckApplication.main`이 연결 |
| `Json` | Jackson 3 도구. `pretty()`는 JS `JSON.stringify(v,null,2)`와 같은 모양(프롬프트 [예시]가 Node와 같아야 해서) |

**요청 예**

```json
{ "persona": "CERT_EXAM", "subject": "데이터베이스 활용", "topic": "논리 데이터베이스 설계",
  "concept": { "id": "c1", "name": "정규화", "desc": "중복을 줄이는 설계 과정" },
  "related": [ { "name": "함수적 종속", "desc": "결정 관계" } ],
  "level": 3, "count": 2,
  "previousQuestions": [], "material": null, "userRequest": null }
```
(`examName` 을 보내도 수험생은 정보처리산업기사로 낸다. `previousQuestions` 는 지문 + 코드를 한 줄로 — 코드 문항은 지문만으로는 구별되지 않는다)

응답: `status`, `requested`, `questions[]`(`c, lv, concepts, steps, q, code(없으면 null), o[4], a, distractors{"보기번호":"노린 오개념"}, x(해설), ev(근거), shuffleMode, shuffleNote, verify{verified, stage, stageOk, issue, note}(검증을 끄면 null)`), `attempts[]`(`n, ms, errors` — ③ 오류는 "③ …"), `skipped[]`, `exampleIds`.
`persona`가 없으면 `UNIVERSITY`(앱에서 직업군을 안 고르면 대학생 수준으로 출제한다는 팀 결정). 11/03부터 앱은 정보처리산업기사 범위로 축소되어 항상 `CERT_EXAM`을 보낸다(`../CLAUDE.md`).

**사양서의 초안 숫자** — 전부 실제 모델 출력을 보고 조정해야 한다: 정답 길이 편향 1.25배(`QuestionGates.BIAS`), 짧은 보기 제외 15자, 중복 0.75, 호출당 5문항, 재시도 2회, 온도 0.7, steps 한 줄 6자 · distractors 한 줄 4자(11/03), 2차 풀이 600토큰 · 온도 0.
**사양서에 없어서 직접 정한 값:** 응답 길이 문항당 2000토큰(최대 8000), PDF 발췌 8000자, 사용자 요청 100자, 과목 40자 · 단원 60자(앱 입력칸과 같음), 이미 낸 문항 한 줄 1500자(코드 포함이라 11/03에 300 → 1500), 관련 개념이 여럿이면 ` / `로 이어 붙임, Bedrock 호출 한 번의 상한 60초(`metacheck.ai.bedrock.timeout-seconds`), 2차 풀이 병렬 풀 8개.
**팀 결정(11/03 사용자 확인):** 1 · 2단계는 `BASIC`(Haiku급), 3단계는 직업군과 상관없이 `ADVANCED`(Sonnet급). 발표 28번과 같고, 사양서 10쪽의 "대학생 심화는 필요할 때"와는 다르다. 규칙은 `QuestionGenerationService.generate` 한 곳에 있다.

**Node 원본과 일부러 다르게 한 곳 3가지** (11/03에 금지 표현은 Node 도 같은 식으로 고쳐 이제 같다)
1. 호출자가 준 `previousQuestions`는 지문 + 코드 한 줄끼리만 비교하고, 새 문항 쪽도 입력 정리(중괄호 · 줄바꿈 제거)를 똑같이 거친 뒤 비교한다(보기까지 합치면 유사도가 희석돼 놓침).
2. `null` 필드는 "없음"으로 취급(더 엄격). `code: null` 은 "코드 없음".
3. 과목 · 단원 같은 한 줄 입력에도 `</사용자 요청>` 같은 태그 흉내를 제거.

## 테스트가 보장하는 것

- `PromptAssemblerTest`: 프롬프트 4칸이 **Node 조립기 결과와 글자 하나까지 같다**(`golden/cells.json`, 수험생 칸은 출제 범위 포함). 출제 범위는 수험생에게만, 시험명은 고정.
- `NodeParityTest`: 예시 문항 6개를 25가지로 망가뜨린 150건(11/03 규칙 11가지 포함) · 보기 고정순서 · 유사도(코드 문항 포함) · 문장 수 세기 · 중복 비교용 글 · **2차 풀이 요청 글**에서 **Node 원본과 같은 결과**(`golden/parity.json`). 기준값을 바꾸면 이 테스트가 깨진다.
- `QuestionGenerationServiceTest`: 생성 · 재생성 흐름 + 2차 풀이(통과 · 정답 불일치 재생성 · 정답 2개 · 검증 응답 오류 · 단계 기록 · 병렬 검증 결과 대응 · 호출 실패 전달 · 코드 문항).
- `BedrockModelClientTest`: 요청 변환 · 등급별 모델 ID · 재시도 · 예외 + **실제 AWS SDK로 로컬 가짜 서버에 보내는 왕복**(AWS 접속 · 비용 없음).
- `ExamUnitsTest`(22단원 · 출제 범위 글과 이름 일치 · 개념 길이) · `UnitQuestionServiceTest`(9칸 · 수험생 · 관련 개념 · 상한 · 404 · 실패 전달) · `UnitControllerTest`.
- `QuestionGenerationServiceTest` · `MockModelClientTest` · `QuestionGatesTest` · `OptionShufflerTest` · `QuestionControllerTest`(스프링 컨텍스트 없는 MockMvc) · `QuestionWiringTest`(빈 연결, bedrock 설정이 빠지면 시작 안 함).
- 모든 `question` 테스트는 `@SpringBootTest`를 쓰지 않아서 DB에 안 붙는다.

## 사양서와 맞추기 (Node 쪽이 바뀌었을 때)

프롬프트 조각 · 예시 · 골든 파일은 `C:\mine_metacheck\ai관련`에서 뽑아 온 사본이다. 사양서나 Node 러너를 고치면 어긋난다.

```powershell
cd C:\mine_metacheck\ai관련\소스
node assemble-bedrock.js bedrock_out3          # prompts12.json 다시 만들기 → ../bedrock_12칸/ 으로 복사 (소스/README.md 참고)
cd C:\metacheck\springboot
node tools/sync-from-ai-spec.mjs               # 프롬프트 조각(시험 · 출제 범위 · 2차 풀이 틀 포함) · 예시 · 골든을 다시 씀 (--ai, --out 옵션)
.\gradlew test --tests "com.example.metacheck.question.*"
```

테스트가 깨지면 "Node와 Java가 어긋났다"는 뜻이니 어느 쪽이 맞는지 사용자에게 물어볼 것. (`--out <폴더>`로 다른 곳에 먼저 써서 비교할 수 있다.)

## 남은 일 (우선순위 순)

팀 결정 · 서버 관리 쪽 일까지 합친 전체 목록은 `C:\mine_metacheck\CLAUDE.md` 의 "다음에 할 일". 아래는 이 백엔드에서 할 일이다.

1. **실제 Bedrock 켜기** — 코드는 11/03에 끝났다(로컬 가짜 서버로 앱 전체 경로 확인). **실제 모델로는 아직 안 돌렸다.** 남은 것:
   인프라 쪽에서 리전 · Haiku급 · Sonnet급 모델 ID(또는 추론 프로필 ID)를 받고, EC2 역할에 `bedrock:InvokeModel`을 준 뒤 환경변수 `AI_CLIENT=bedrock` · `BEDROCK_REGION` · `BEDROCK_BASIC_MODEL_ID` · `BEDROCK_ADVANCED_MODEL_ID`를 준다(키는 코드 · 저장소에 두지 않는다. 로컬 PC에는 AWS 자격 증명이 없다).
   값이 빠지면 앱이 시작되지 않는다(가짜 응답이 운영에 섞이지 않게). 재배포할 때 쓰는 jar에도 같은 값이 필요하다.
   첫 실행에서 볼 것: 호출 시간(사양서의 앱 10초 제한과 맞는지 · 2차 풀이로 약 2배) · 60초 상한 · 사양서 초안 숫자 전부 · **코드 · SQL 문항이 `code` 칸으로 나오는지** · 정답이 가장 긴 보기 비율.
   **Haiku 4.5 · Sonnet 4.5에서는 `temperature` 0.7이 된다.** Claude 4.7 이상으로 바꾸면 기본값이 아닌 `temperature`를 거부할 수 있으니 그때 `QuestionGenerationService.TEMPERATURE` · `QuestionVerifier.TEMPERATURE`(0) 전달을 함께 봐야 한다.
2. **엔드포인트 보호** — 로그인 + 호출 횟수 제한. `POST /api/v1/ai/questions`가 지금 누구나 부를 수 있어서 Bedrock을 켜면 호출마다 돈이 든다. **켜기 전에 먼저.**
3. **앱과 단원별 출제 연결** — 단원표 · 단원 API 는 11/03에 만들었다(위 `UnitController`, 진단 한 번 = 단원 하나, 사용자 결정). 남은 것: 앱이 서버가 만든 문항을 진단 · 기록에 저장하도록 고치고(지금은 `BANKS` 에 있는 문항만 쓴다 — `../CLAUDE.md` "단원 세분화 · 단원별 출제"), `mock:false` 로 붙인다. 실제 Bedrock 을 켠 뒤에. 여러 단원을 섞는 과목 진단과 그 비율은 **팀이 정한다**. 단원 개념(초안)은 큐넷 세세항목을 받으면 `exam-units.json` 에서 고친다.
4. **AI-01 개념 추출** — 범위 축소 동안은 단원표가 대신한다. 다시 쓰게 되면 프롬프트는 `AI-01_프롬프트.md`(10초 제한이라 검증 호출 없이 서버 규칙만).
5. **DB 엔티티와 저장** — `src`에 `@Entity`가 **하나도 없다.** 팀 ERD v5(`C:\mine_metacheck\ERD\`)가 기준이다. DB에 지금 어떤 테이블이 있는지 사용자에게 먼저 확인할 것(인계 때 "엔티티 테이블이 자동 생성됨"이라 했지만 코드엔 엔티티가 없어서 출처가 불분명하다).
6. 프론트와 연동 — `static/index.html`은 11/03에 10/26 최신본으로 교체했고(다크 모드는 빠짐) 정보처리산업기사 범위로 축소했다. `API_CONFIG.mock: true`다. 앱은 시험 이름을 보내지 않고, 서버가 정보처리산업기사로 고정한다(11/03 구현). 앱은 문항의 `code` 칸을 코드 상자로 보여 준다. 자세한 내용은 `../CLAUDE.md`.

## 코딩할 때 걸리는 점

- **Spring Boot 4 + Jackson 3**: `tools.jackson.*` 패키지, `JsonNode.isString()` / `asString()` / `propertyNames()` / `properties()`, 예외는 unchecked `JacksonException`. 자동 구성 클래스 이름도 3.x와 다르다(`org.springframework.boot.jdbc.autoconfigure...`).
- 발표 PPT 28번에는 "Spring Boot 3.2.4"로 적혀 있지만 실제 `build.gradle`은 4.1.1이다.
- 코드 스타일은 기존 파일을 따른다(주석은 한국어, 이유를 적는 짧은 주석).
