# member 도메인(LocalMemberService) 리팩토링 — 의도된 동작 변경 목록 및 버그 판정 요청

브랜치: `refactor/member-testability` (develop 4efc65d 기반)
근거 계획: ralplan 합의 계획 `pending-approval.md` (deep-interview 스펙 `member-testability-refactor` 4.05% PASSED 기반), Architect APPROVE + Critic OKAY (2 iteration)

## 1. 의도된 동작 변경 목록 (AC)

> 원칙: 리팩토링 전후 관찰 가능한 동작(API 응답 본문·예외 타입/메시지·DB 상태·발행 이벤트·트랜잭션 경계)은 동일하다.
> 명백한 버그는 사용자 판정 후에만 별도 커밋으로 수정한다.

| # | 지점 | 사용자 승인 근거 | 커밋 해시 |
|---|------|-----------------|----------|
| — | (없음) | 승인된 버그 수정 0건 — 프로덕션 관찰 동작 변경 없음 | — |

### 참고: 순수 구조 변경(관찰 동작 아님)

| 지점 | 내용 | 성격 |
|------|------|------|
| `LocalMemberService` **삭제**(271줄) | public 14개(살아있는 13개)·협력자 7개의 God Class를 5개 서비스로 분해하고 `MemberController`를 직접 재배선 | 위임 간접층 0. 선행 사이클과 달리 원본이 사라지므로 오라클을 컨트롤러 레벨로 올림 |
| `LocalAuthService` (83줄) | `signup`/`login`/private `validateDuplicateEmail` | `KakaoAuthService`와 대칭 명명 |
| `MemberRegistrationService` (36줄) | `save → createDefaultAccountForMember → publishEvent` 3단계. Local·Kakao **공용** | 유일하게 외부 소비자와 불변식을 가진 컴포넌트. **트랜잭션 선언 0**(호출자 트랜잭션에 참여) |
| `MemberProfileService` (101줄) | `updateMember`(단일 `@Transactional` 소유) + 조회 유틸 3 + 설문 2 = 9메서드 | 칭호만 `MemberTitleService`에 위임, `save`는 1회 |
| `MemberNotificationSettingsService` (48줄) | FCM 등록/삭제 + 체결알림설정 | |
| `MemberTitleService` (84줄) | 진입점 3(컨트롤러용 2 + 프로필 위임용 1) + private `findTitle`/`owns` | **클래스 레벨 `@Transactional` 없음**, `equipRepresentativeTitle`은 애노테이션·`save` 모두 0 |
| `KakaoAuthService` | 가입 3단계를 공용 호출로 치환 | `try/catch(DataIntegrityViolationException)` 경계와 중복 이메일 가드는 **원위치 유지**(옮기면 로컬 경로가 500→400으로 변함) |
| `MemberController` | 주입 2 → 5, 엔드포인트 14개 시그니처·응답·401 분기 **불변** | API 주소·요청·응답 계약 불변 |
| `MemberSignupMissionFlowIntegrationTest` | 주입 대상 교체 4행(import·필드·호출 2) | 단언·`@MockitoSpyBean`·`@Sql`·`uniqueEmail` 무수정 |
| 주석 정리(별도 커밋) | `MemberController`의 diff 흔적 주석 5곳 제거 | `docs/conventions.md` 「주석」 준수. 코드 변경 0 |
| `suppressions.xml` | `LocalMemberService`의 `AvoidStarImport` 항목 **삭제** | 신규 추가 0. 「품질 도구」 절의 "정리하면 항목을 제거합니다"와 일치 |

## 2. 버그 의심 지점 — 사용자 판정 요청

> 라벨은 **`member-a` ~ `member-j`** 네임스페이스를 쓴다. `docs/mission-refactor-changes.md`가 이미 라벨 `a`~`i`를 mission 도메인 버그에 쓰고 있어 충돌을 피하기 위함이다.
> 아래 10건은 전부 **현재 동작 그대로 고정**(수정 안 함). 수정 승인 시 해당 특성화를 기대값 갱신과 함께 별도 커밋으로 처리한다.

### 보호 강도 구분

**중요**: 보호 강도가 균일하지 않다. 판정 시 이 차이를 고려해야 한다.

- **테스트로 동결** — 특성화가 현재 동작을 단언하므로, 수정하면 즉시 red가 되어 의도치 않은 변경이 차단된다.
- **리뷰로만 보호** — 관측 가능한 표면이 없어 테스트가 두 구현을 구분하지 못한다. 코드 리뷰만이 방어선이다.

뮤테이션 red-team 7종 중 **6종이 목표 행을 red로 만들었고 1종(m6)이 살아남았다**. 살아남은 항목은 아래 §2.1에 별도로 기록한다.

| # | 지점 | 현재 동작 | 왜 의심스러운가 | 보호 강도 |
|---|------|----------|----------------|----------|
| **member-a** | `MemberController` `POST /logout` + 삭제된 `LocalMemberService.logout` | 엔드포인트가 **서비스를 호출하지 않고** 로그만 찍고 200 반환. 서비스 메서드는 호출자 0건의 죽은 코드였고 이번에 삭제됨 | 토큰 무효화·블랙리스트 전무. 로그아웃이 서버 상태를 전혀 바꾸지 않음 | **테스트로 동결** (T2-07 인증 200 + U-01 미인증 401) |
| **member-b** | `LocalAuthService.login` | 미존재 이메일과 비밀번호 불일치가 **서로 다른 메시지**로 400 본문에 노출 | **계정 열거 취약점** — 클라이언트가 계정 존재 여부를 구분 가능 | **테스트로 동결** (T1-04a/T1-04b 완전 일치 단언) |
| **member-c** | `POST /signup` 응답 | `balance=0`, `titles=[]` 고정 | `MemberResponse.from(savedMember)`가 메모리상 빈 컬렉션을 매핑. 실제 생성된 기본계좌가 응답에 반영 안 됨 | **테스트로 동결** (T1-01a) |
| **member-d** | `@AuthenticationPrincipal` 4개 엔드포인트(`/title`×2, `/survey`×2) | null 가드가 없어 미인증 시 NPE → **500 JSON** | 미인증은 401이어야 할 것이 500. 추출 서비스에 null 가드를 추가하면 400으로 바뀌므로 **추가 금지**로 못박음 | **테스트로 동결** (U-03) |
| **member-e** | 미인증 응답 3종 | 401+문자열 / 401+빈본문 / 500 JSON | 같은 상황에 응답 형태가 셋으로 갈림 | **테스트로 동결** (U-01/U-02/U-03) |
| **member-f** | 칭호 2경로의 null 시맨틱스 | `updateMember`는 `representativeTitleId=null`이면 **무시**, `updateRepresentativeTitle`은 `titleId=null`이면 **해제** | 같은 필드의 null이 정반대 의미 | **테스트로 동결** (T1-05b vs T1-07b) |
| **member-g** | `MemberTitleService.updateRepresentativeTitle` 해제 분기 | `save` 없이 early return, 더티 체킹 의존 | 비-null 분기는 명시 `save` — 비대칭(원본에 "확실하게 하려면 추가" 주석이 있었음) | **⚠ 리뷰로만 보호** — 관리 상태 엔티티 + `@Transactional`이라 커밋 시 flush되므로 `save` 유무를 DB·응답 어느 쪽으로도 관측할 수 없다. 두 구현을 구분하는 테스트가 원리적으로 불가능 |
| **member-h** | `PUT /me`·`PATCH /title` | `@Valid` 누락 | 다른 4개 핸들러엔 있음. 검증 없이 바디를 수용해 DB 제약 위반이 **500**으로 표면화 | **테스트로 동결** (T1-06d가 500 관찰) |
| **member-i** | 예외 변환 비대칭 | 같은 `DataIntegrityViolationException`이 로컬 가입은 **500**, 카카오 가입은 **400**("회원 저장 실패: …") | 경로에 따라 상태코드가 갈림. 근본 현상은 `docs/mission-refactor-changes.md`의 라벨 `g`(이메일 로컬파트 20자 초과 시 `Member.name varchar(20)` 위반)와 동일하며, 이 문서는 그 **변환 비대칭**을 다룬다 | **테스트로 동결** (T1-08 로컬 500 / T1-09 카카오 400 접두) |
| **member-j** | `Member` 엔티티 | `@Setter`는 없으나 `setTwoFactorEnabled`·`setSurveyCompleted` 등 **수작업 setter 6종** | `conventions.md` 「엔티티 상태 변경은 의도가 드러나는 도메인 메서드로」의 정신 위반 | **범위 밖** — 리네임 시 `Member.builder()`를 쓰는 12개 테스트 클래스가 영향받아 이번 사이클에서 제외 |

## 2.1 안전망 공백 — 트랜잭션 전파 계약 (실측으로 확인)

> 이것은 버그가 아니라 **테스트가 덮지 못하는 계약**이다. 계획은 뮤테이션 m6가 반드시 red가 될 것으로 예측했으나 실측에서 **반증**됐다.

| 항목 | 내용 |
|------|------|
| 계약 | `MemberRegistrationService.register`는 **자체 트랜잭션을 열지 않고** 호출자(`LocalAuthService.signup`, `KakaoAuthService.completeSignup`)의 트랜잭션에 참여해야 한다 |
| 뮤테이션 m6 | `register`에 `@Transactional(propagation = REQUIRES_NEW)` 부여 → **mission 롤백 전파 테스트가 green으로 살아남음**(`--rerun-tasks` 재현 동일) |
| 왜 못 잡는가 | 롤백 오라클은 미션 리스너가 **`register` 내부에서** 예외를 던지도록 스텁한다. `REQUIRES_NEW`여도 그 내부 트랜잭션이 rollback-only로 표시돼 함께 롤백되므로, 관측 가능한 DB 상태(member 부재·account 불변)가 `REQUIRED`와 **바이트 동일**하다 |
| 프록시 아티팩트 아님 | 대조군으로 같은 메서드에 `Propagation.NEVER`를 적용하자 mission 3/3이 `IllegalTransactionStateException`으로 red. **프록시와 전파 메타데이터는 런타임에 정상 동작**하므로 m6 생존은 진짜 커버리지 공백이다 |
| 추가 확인 | `register` **이후** 호출자 실패를 주입해 `REQUIRES_NEW`와 `REQUIRED`를 비교(m6′)했으나 실패 시그니처가 바이트 동일. 기존 두 스위트 어디에도 "호출자 실패 후 행 부재"를 단언하는 오라클이 없다 |
| 현재 보호 수단 | **⚠ 리뷰로만 보호** — 계획 §3.6 트랜잭션 애노테이션 확정표(`register`는 클래스·메서드 양쪽에 선언 0)와 코드 리뷰가 유일한 방어선이다 |
| 후속 권고 | 이 계약을 테스트로 고정하려면 "`register` 성공 직후 호출자가 실패하면 member·account 행이 남지 않는다"를 단언하는 신규 테스트가 필요하다. 이번 사이클은 특성화 스위트를 오라클로 동결한 상태라 신규 테스트 표면을 발명하지 않고 공백으로 기록한다 |

반면 **예외 변환 경계는 테스트로 보호된다** — m7(카카오의 `catch(DataIntegrityViolationException)`를 공용 유닛으로 이동)은 `T1-08`이 `Status expected:<500> but was:<400>`으로 즉시 red가 됐다.

또한 **이벤트 발행 경계는 mission 통합테스트만이 보호한다** — m4(`publishEvent` 삭제)에서 mission 3/3이 red가 되는 동안 컨트롤러 특성화 22/22는 green을 유지했다. 분해 후에도 이 비대칭이 그대로 재확인됐다.

### 이전 사이클 대장과의 관계

`docs/mission-refactor-changes.md`의 라벨 `g`(`LocalMemberService.signup` + `Member.name varchar(20)`)는 이 문서의 **member-i**와 같은 근본 현상을 가리킨다. mission 사이클은 "가입 자체가 실패할 수 있다"는 현상을, 이 문서는 "그 실패가 경로에 따라 500/400으로 갈린다"는 변환 비대칭을 기록한다. 해당 문서의 지점 표기(`LocalMemberService.signup`)는 이번 리팩토링으로 `LocalAuthService.signup` + `MemberRegistrationService.register`로 이동했다.

## 3. 계획 대비 편차 기록

| # | 편차 | 사유 |
|---|------|------|
| **R13** | `GET /me/accounts`를 **404 분기만** 특성화 | 스펙 `f-osiv-resolved`는 이 엔드포인트를 특성화 제외로 잠갔으나, 수용 기준이 "이동 9메서드 전수 스모크"를 요구하고 `findMemberEntityByEmail`의 유일한 도달 경로가 여기였다. 404 분기는 DTO 매핑을 타지 않아 LAZY 역참조가 없으므로 스펙이 피하려던 위험은 완전히 회피된다. 200 분기는 작성·실측 모두 금지(A0-4). **커버리지 주장은 9/9가 아니라 8 완전 + 1 부분**. 실행 중 `LazyInitializationException`은 발생하지 않았다 |
| 하네스 | `standaloneSetup`에 앱 `HttpMessageConverters` 주입 | 계획의 빌더 그대로면 **한글 평문 본문이 ISO-8859-1로 깨진다**(`?? ???? ??????.`). `standaloneSetup`은 Boot의 UTF-8 `StringHttpMessageConverter`를 상속하지 않는다. 계획 각주는 JSON/ObjectMapper 측만 예상했고 평문 측을 놓쳤다. 앱과 동일 컨버터를 쓰므로 프로덕션 충실도가 오히려 상승하며 테스트 전용이다 |
| 시나리오 | 계획 20 메서드 → 실제 **22 메서드**(행은 31로 일치) | A0에서 커밋한 3메서드를 한 글자도 건드리지 않고 보존했기 때문. 계획의 그룹핑은 그 메서드들을 병합해야 했다. 행 수는 정확히 일치하며 메서드가 분리되어 실패 귀속이 더 좁다 |
| B5 | 계획 "9메서드 이관" → 실제 **6메서드** | 계획 §3.5의 9는 stage-01 이월 수치. §3.4가 명시한 잔여 public이 6개였고 코드를 신뢰해 이관했다. 빈 껍데기 목표는 달성 |

## 4. 수용 기준 충족 요약

| 기준 | 증빙 |
|------|------|
| 하이브리드 오라클(정상=컨트롤러 직접 호출 / 에러=`standaloneSetup` MockMvc) | A0 3조건 통과 — T1-02a가 400 + `text/plain` 순수 문자열로 컨트롤러 로컬 `@ExceptionHandler` 우선을 실증, T1-07c가 400으로 객체 principal 주입 확인, U-03이 500 JSON 재현 |
| 특성화 매트릭스 | 22 메서드 / **31행** 전량 green (Tier1 21 · Tier2 7 · 미인증 3) + 기존 mission 3건 재사용 |
| **커밋마다 특성화 무수정 green** | Phase B 7커밋 전 구간 `charac-base` 대비 **diff 무출력 + 0커밋**(G1a ∧ G1b), 커밋마다 22/22 + mission 3/3 green |
| 트랜잭션 경계 보존 | **T1-06d** — 칭호 반영 후 flush 실패 시 500 + **DB 칭호 불변**. 위임 도입 후에도 통과 = 트랜잭션 분열 없음 |
| 예외 경계 보존 | **T1-08**(로컬 500 미변환) / **T1-09**(카카오 400 접두 변환) |
| 안전망 진짜(토톨로지 아님) | 뮤테이션 7종 중 **6종 kill**(m1·m2·m3·m4·m5·m7 각각 목표 행 red 후 원복). **m6 생존** — 트랜잭션 전파 계약이 테스트로 덮이지 않음을 실측 확인(§2.1). 대조군 `Propagation.NEVER`가 red가 되어 프록시 정상 동작을 입증했으므로 생존은 진짜 공백이다 |
| 5서비스 라인 커버리지 ≥70% | **100.00%** (99/99): 5개 서비스 전부 100% |
| clean build green | `./gradlew clean build` BUILD SUCCESSFUL, **274 테스트 0실패 0에러**(3 skip은 기존 StockRanking, 무관), Checkstyle `maxWarnings=0` 0 warning, ArchUnit 2/2 |
| baseline·DTO 계약 | `suppressions.xml`·`archunit_store` **추가 라인 0**(삭제 1행만), DTO diff 0 |
| 원본 무오염 | `~/stockIt`은 develop 4efc65d 그대로, 워크트리만 변경 |
