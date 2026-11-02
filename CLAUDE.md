# metacheck — 코드 저장소 인수인계

> 최종 갱신: 2026-11-03(화) · 사람과 Claude가 같이 읽는 문서. 비밀번호 · IP · 키 경로는 일부러 적지 않았다.
> 26년 2학기 프로젝트 분석 설계 1팀 **메타체크(MetaCheck)** — 학습 진단 앱. GitHub: `snowbeach0102/metacheck`

## 다른 컴퓨터에서 이어서 하기

1. **이 문서가 git에 올라가 있어야 다른 컴퓨터에서 보인다.** 11/03 작업은 `c38d7a7`까지 푸시했다 → 다른 컴퓨터에서는 `git pull`(처음이면 `git clone`)로 받는다. `git log`에 `11/03` 커밋 4개가 보여야 한다. 그 뒤에 한 작업이 커밋 · 푸시돼 있는지는 최신 커밋 날짜로 확인한다.
2. 두 저장소를 `C:\` 아래 나란히 받는다: `C:\metacheck`(코드), `C:\mine_metacheck`(문서). 경로가 다르면 `springboot/tools/sync-from-ai-spec.mjs`에 `--ai <ai관련 폴더>`를 준다.
3. **`springboot/config/application.properties`는 git에 없다.** `config/application.properties.example`을 복사해 실제 값을 채운다(값은 서버 관리 담당에게 받는다). 없으면 앱이 "DB 비밀번호가 설정되지 않았습니다"라며 멈춘다.
4. JDK 17을 설치하고 `JAVA_HOME`을 지정한다. `springboot/CLAUDE.md`의 경로는 11/03에 쓴 PC 기준이다.
5. 읽는 순서: **이 문서 → `springboot/CLAUDE.md`** → 기준 문서가 필요하면 `C:\mine_metacheck\CLAUDE.md`.
6. Claude의 메모리(`~/.claude/...`)는 컴퓨터마다 따로다. 필요한 내용은 이 `CLAUDE.md`들에 다 적어 둔다.
7. **작업을 마치면 이 문서의 "한 일" · "남은 일"을 갱신**하고, 커밋 · 푸시를 사용자에게 권한다(그래야 다음 컴퓨터에서 이어진다). 커밋은 사용자가 시킬 때만 한다.

## 한눈에 보기

| 폴더 / 파일 | 무엇 | 상태 |
|---|---|---|
| `springboot/` | 백엔드(Spring Boot 4.1.1) | **AI 문제 생성**만 구현(가짜 모델). 자세한 건 `springboot/CLAUDE.md` |
| `index5.html` | 프론트 프로토타입(앱 한 장짜리, 서버 대신 mock) | **10/26 최신본**(11/03에 문서 저장소 것으로 교체, 커밋 `e0427cd`) — 아래 참고 |
| `springboot/src/main/resources/static/index.html` | 스프링이 `/`에서 내주는 프론트 | `index5.html`과 **완전히 같은 파일**(최신본) |
| `README.md` | 프로젝트 소개 두 줄 | — |

**git:** 브랜치 `main` = `origin/main`. 최근 커밋은 프론트 수정 · `index.html` 경로 정리까지. **11/03 작업은 커밋 · 푸시 완료** — 4개 커밋: `f7f1b84` DB 비밀번호 분리 · `b891aa3` AI 문제 생성 · `e0427cd` 프론트 최신본 교체 · `c38d7a7` 인수인계 문서. 실제 접속 정보 파일 `springboot/config/application.properties`는 git이 무시하므로 `git add -A`를 해도 올라가지 않는다(확인함).

## 먼저 알아야 할 위험

1. **현재 DB 비밀번호가 GitHub에 푸시된 이력에 들어 있다.** 옛 RDS(MariaDB, 시드니 리전)를 쓰던 커밋 `ee589b9 스프링부트까지 설정`부터 `e6bde44`까지 **4개 커밋**의 `application.properties`에 지금 EC2 DB와 **같은 비밀번호**가 있고, 전부 `origin/main`에 포함돼 있다(EC2 주소는 이력에 없음). 파일에서 지워도 이력은 남으므로 **노출된 것으로 보고 DB 비밀번호를 바꿔야 한다**(서버 관리 쪽 일). 저장소 공개 여부는 확인하지 못했다(`gh` 미로그인) — GitHub에서 직접 확인 필요. 옛 RDS가 아직 살아 있다면 그쪽도 마찬가지.
2. **11/03에 사용자 결정("비밀번호는 빼자")으로 비밀번호를 파일에서 뺐다(커밋 `f7f1b84`, 푸시됨).** 추적되는 `application.properties`에는 자리표시자만 남고, 실제 값은 git이 무시하는 `springboot/config/application.properties`(내 PC)와 서버 환경변수 `DB_PASSWORD`에 둔다 → `springboot/CLAUDE.md`의 "DB 접속 설정". **서버를 재배포할 때 `DB_PASSWORD`를 주지 않으면 앱이 시작되지 않는다** — 서버 관리 쪽에 알릴 것. (이미 떠 있는 앱은 영향 없음)
3. DB 비밀번호가 11/03 대화에도 한 번 노출됐다. 바꿀 이유가 하나 더 있다.
4. 서버 관리(AWS · EC2 · Docker DB · 재배포)는 사용자 + 외부 AI(Gemini) 담당이다. Claude는 로컬 개발만.

## 프론트 파일 상태

11/03에 사용자 결정("최신 버전으로 가는 게 맞아")으로 `index5.html`과 `springboot/.../static/index.html`을 문서 저장소 `C:\mine_metacheck\index5.html`(**10/26** 버전, 5633줄)로 교체했다(커밋 `e0427cd`, 교체 직후 세 파일 해시 동일). 교체 전 이 저장소 버전은 10/02 커밋(5501줄)이었다.
- **들어온 것:** 직업군 "선택 안 함"(= 대학생 수준 출제), 회원 탈퇴, 복습 카드 규칙 등 그 사이 팀이 정한 것.
- **빠진 것: 다크 모드**(라이트/다크/시스템 테마 전환 + 스타일 182줄). 최신본에는 흔적이 하나도 없어 의도적으로 뺀 것으로 보이지만 팀에 확인하지는 않았다. 되살리려면 교체 직전 버전 `git show e6bde44:index5.html`에서 다크 테마 CSS 블록과 테마 토글을 가져오면 된다. 교체를 통째로 되돌리려면 `git revert e0427cd`.

`API_CONFIG = { baseUrl: '/api/v1', mock: true }` — 백엔드가 준비되면 `mock:false`로 바꾸면 호출 코드는 그대로다.
실제 화면 API는 `POST /diagnoses`, `POST /diagnoses/{id}/answers` 등으로 기능 명세서(문서 저장소 `기능명세서/`)에 정의돼 있다.

## 두 저장소의 관계

| 저장소 | 위치 | 역할 |
|---|---|---|
| 코드 | `C:\metacheck` (이 저장소) | 프론트 · 백엔드 코드 |
| 문서 | `C:\mine_metacheck` (GitHub `snowbeach0102/mine_metacheck`) | 중간발표 PPT · 명세서 · ERD · AI 사양서 · 프롬프트 — **기준 문서는 여기** |

백엔드가 따르는 기준: 테이블은 `C:\mine_metacheck\ERD\`(팀 ERD v5), AI 출제는 `C:\mine_metacheck\ai관련\`(사양서 + Node 러너), 화면 · API는 `기능명세서\` · `input_output\`. 문서와 코드가 다르면 임의로 고치지 말고 사용자에게 물어볼 것.

## 사용자와 일하는 규칙

- **한국어로 답한다.**
- 난해하거나 모르겠으면 **임의로 판단하지 말고 물어본다.** 궁금한 건 실행 전에 묻는다.
- **커밋 · 푸시는 사용자가 시킬 때만.** 커밋 메시지는 `MM/DD/요일 설명`(예: `11/03/화 AI 문제 생성`), 작업 브랜치에서 하고 main에 합친 뒤 푸시하는 방식을 써 왔다.
- 운영 DB를 건드릴 수 있는 실행(`bootRun`, `contextLoads`)은 허락받고 한다.
- 외부에 올리거나 게시하는 건 사용자가 장소를 말하기 전엔 하지 않는다.

## 2026-11-03에 한 일

- 인프라 쪽에서 EC2 배포와 DB 연동이 끝난 걸 인계받음(역할 분담 공지 포함).
- AI 문제 생성 기능을 `springboot`에 구현하고 단위 테스트 55개 통과, 실제 앱 기동 호출까지 확인.
- 사양서 → Java 동기화 스크립트(`springboot/tools/`)와 이 인수인계 문서들을 만듦.
- 사용자 결정으로 **DB 비밀번호를 설정 파일에서 뺌**(환경변수 · git 무시 파일로 분리, 없으면 시작 때 멈추는 점검 추가). 이력에 남은 비밀번호는 바꿔야 한다는 걸 확인함(위 "위험" 1번).
- 사용자 결정으로 **프론트를 10/26 최신본으로 교체**(다크 모드는 빠짐).
