# ranking 도메인(RankingService) 리팩토링 — 의도된 동작 변경 목록 및 버그 판정 요청

브랜치: `refactor/ranking-testability` (develop 955836c 기반)
근거 계획: ralplan 합의 계획 `pending-approval.md` (deep-interview 스펙 `ranking-testability-refactor` 4.75% PASSED 기반), Architect CLEAR/APPROVE + Critic OKAY (4 iteration)

## 1. 의도된 동작 변경 목록 (AC)

> 리팩토링 단계(1~9커밋)는 관찰 동작 보존(무변경). 아래는 리팩토링 완료 후 **사용자 승인(그룹1 데드코드 + 그룹2 실질 결함)** 으로 별도 커밋 처리한 버그 수정 목록이다. 각 수정은 해당 특성화 기대값을 새 동작으로 갱신했고, architect CLEAR×3/APPROVE + QA passed(뮤테이션 red-team 통과)로 검증했다.

| # | 지점 | 수정 내용 | 커밋 |
|---|------|----------|------|
| g | `getContestRankings` | 데드 `sortBy` 정규화 라인 제거(관찰 동작 무변) | 1a601ae |
| j | `calculateReturnRate(Account,Contest)` | 데드 메서드 + `includeReturn` 분기 제거(관찰 동작 무변, `returnRate` null 유지) | 889d454 |
| a+h | `calculateTotalAssets` | 가격 미가용(KIS 실패/빈맵) 시 **취득원가(AccountStock.averagePrice) 폴백** → 0원 순위 왜곡 방지 | 92dbfc5 |
| b+k | `getMyRank` | 랭킹 엔트리에서 파생(`findMyEntry`) → myTotalAssets/myReturnRate가 랭킹과 **스냅샷 일치** + 이중 fetch 제거(Q16 seam 2/3→1/2) | 109f1a9 |
| i | `updateAllRankings` | `@CacheEvict(beforeInvocation=true)` + self-injection으로 evict 후 main·대회 캐시 **실제 워밍**(폐기 연산 제거). U6 예외삼킴 보존 위해 조회 메서드 `REQUIRES_NEW` 격리 | bc5cd42 |

> 미수정(동결 유지): **c**(필드 오버로딩=스멜이나 동작 정상), **d**(catch 삼킴=스케줄러 resilience, 이미 ERROR 로깅), **e**(티어 null 은폐=경미), **f**(competition ranking=표준 동작 가능). 각각 특성화로 현재 동작 고정.

### 참고: 순수 구조 변경(관찰 동작 아님)

| 지점 | 내용 | 성격 |
|------|------|------|
| `RankingService` 711→530줄 | God Class를 순수 계산기 + 가격수집기 + 슬림 오케스트레이터로 분해 | 책임 분리(SRP), public API·캐시·트랜잭션 시맨틱스 보존 |
| `RankingCalculationService` (의존 0) | `calculateTotalAssets`/`calculateReturnRateFromAssets` 이동 + `assignCompetitionRanks` 순수함수 (※ `calculateReturnRate(Account,Contest)`는 B에서 이동됐다가 버그 j에서 데드코드로 제거) | 이동 메서드 본문 develop과 정규화 대조 IDENTICAL. `assignCompetitionRanks`는 두 convert에 **라인 동일하게 인라인 중복**돼 있던 동률순위 상태머신(`rank`/`sameRankCount`, `compareTo()==0`)을 dedup 추출 — 동일 rank 시퀀스 산출 |
| `RankingPriceCollectionService` (118줄) | `collectAllHeldStockCodes`/`batchFetchCurrentPrices`/`fetchPricesWithRateLimit` + `RateLimiter(25/s)` 이동 | 본문 IDENTICAL. 싱글턴 1 인스턴스로 전역 25/s 시맨틱스 보존 |
| `TestSchedulingConfig` (테스트 전용) | `setScheduler(null)` → **no-op TaskScheduler**(§3 참조) | 테스트 인프라 하드닝. 프로덕션 무관 |
| 특성화 하네스 | @DirtiesContext·실 @EventListener 캡터·실 PropertySource 게이트 | 테스트 신뢰성, 프로덕션 무관 |
| (범위 밖) `PerformanceTestService` | 동일 패키지의 성능비교 전용 클래스로 중복 `calculateReturnRate`/convert 로직 보유(JaCoCo 0%, javadoc상 "테스트 완료 후 삭제 예정") | **기존 P3**(리팩토링이 신규 도입 아님). throwaway·미도달이라 이번 범위 밖 — 차기 정리 후보 |

## 2. 버그 의심 지점 — 사용자 판정 요청

> 아래 표는 리팩토링 종료 시점의 의심 지점 카탈로그다. 사용자 승인으로 **7건(g/j/a/h/b/k/i)은 §1대로 수정**, **4건(c/d/e/f)은 동결 유지**. 수정 건은 특성화 기대값을 새 동작으로 갱신했고 뮤테이션 red-team으로 비토톨로지 증명(a→6 RED, i→3 RED, b+k→각각 RED).

| # | 지점 | 상태 | 내용 |
|---|------|------|------|
| a | `RankingCalculationService.calculateTotalAssets` | **수정됨** 92dbfc5 | KIS 실패/미가용 시 0원→취득원가 폴백. 특성화 a1~a3 기대값 1.05M로 갱신 |
| b | `RankingService.getMyRank` | **수정됨** 109f1a9 | 랭킹 엔트리 파생으로 스냅샷 일치. b 특성화 불일치→일치 반전 |
| c | `getContestRankingsWithPrices`(returnRate) | 동결 | `totalAssets` 필드에 수익률 오버로딩 + actualTotalAssets 재계산 중복(스멜, 동작 정상) |
| d | `updateAllRankings` | 동결 | `catch(Exception)` 삼킴 = 스케줄러 resilience, 이미 ERROR 로깅(Update U6). 후속 실패-메트릭 티켓 권고 |
| e | `getTierForMember` | 동결 | 티어 조회 예외→`tier=null` 은폐(경미) |
| f | `assignCompetitionRanks` | 동결 | 동률 `[a,a,b,b,c]→[1,1,3,3,5]` = 표준 competition ranking(의도 가능) |
| g | `getContestRankings` | **수정됨** 1a601ae | 데드 `sortBy` 정규화 라인 제거(동작 무변) |
| h | `calculateTotalAssets` | **수정됨** 92dbfc5 | `isEmpty` 폴백 제거 + 취득원가 평가. 특성화 h 기대값 2.3M로 갱신 |
| i | `updateAllRankings` | **수정됨** bc5cd42 | evict 후 self-호출로 캐시 실제 워밍. Update U1/U5/Cache C2 갱신 |
| j | `calculateReturnRate(Account,Contest)` | **수정됨** 889d454 | 데드 메서드 + `includeReturn` 제거(동작 무변, `returnRate` null 유지) |
| k | `getMyRank` | **수정됨** 109f1a9 | `myReturnRate` 랭킹 일치(b 확장) |

## 3. 테스트 인프라 변경 노트 — `TestSchedulingConfig` (프로덕션 동작 아님)

> deep-interview 의도 정합 게이트에서 사용자 확정(옵션 A 근본 수정). **테스트 전용 변경으로 프로덕션 관찰 동작에 영향 없음.**

- **변경**: `TestSchedulingConfig.configureTasks()`의 `taskRegistrar.setScheduler(null)` → **Runnable을 절대 실행하지 않는 no-op `TaskScheduler`**(6개 오버로드 전부 non-null 더미 `ScheduledFuture` 반환) 주입.
- **이유(아키텍트 근본 원인)**: 기존 `setScheduler(null)`은 스케줄러를 실제로 죽이지 못했다. Spring이 `getScheduler()==null`일 때 by-type으로 `WebSocketConfig`의 `@Bean TaskScheduler`(ThreadPoolTaskScheduler)로 폴백하거나 기본 executor를 재생성하여, 백그라운드 `@Scheduled`가 테스트에서 **살아있었다**. no-op 주입은 `getScheduler()!=null`을 유지해 폴백·재생성을 **양쪽 모두 억제** → 백그라운드 `@Scheduled` 발화가 결정적으로 0.
- **효과/범위**: `IntegrationTestSupport`(전 통합테스트 공용 베이스)의 `spring.task.scheduling.enabled=false` 조건부라 **전 도메인 통합테스트**에 적용된다. 기존엔 스케줄러가 실은 살아있었으므로 이는 ‘실제로 죽이는’ **하드닝**이며(회귀 아님), 전체 `./gradlew clean build` green으로 회귀 없음을 확인했다. 랭킹 배치 특성화는 `@SpyBean` 없이 실 `PropertySource` 오버라이드로 manual gate만 개방하고 수동 `updateAllRankings()`를 호출해 결정적으로 검증한다.

## 4. 수용 기준 충족 요약 (계획 Acceptance criteria)

| 기준 | 증빙 |
|------|------|
| 2 추출 컴포넌트(계산기 의존0) + 오케스트레이터 잔류, DAG 순환 0 | RankingCalculationService(129, dep0)/RankingPriceCollectionService(118) + RankingService(530). architect 5레인 CLEAR |
| public 4 흐름 + 캐시(히트/evict) + 스케줄(배치/조기리턴) + 이벤트(Top10) + 동률순위 + 자기호출 우회 + 버그 a~k 특성화 | 특성화 4클래스 40 시나리오 전량 green |
| 분해 전후 특성화 무수정 green | Phase B 3커밋(B-1/B-2/B-3) 전부 특성화 40 무수정 green(byte 동일) |
| 이동 메서드 본문 IDENTICAL | 6메서드 develop 대비 diff -w=0(접근제한자만 private→package-private). assignCompetitionRanks는 dedup 신규(유닛+뮤테이션 등가 증명) |
| ranking service 라인 커버리지 ≥70%(측정 전용) | 3클래스 집계 **95.98%**(310/323): RankingService 95.04%·RankingCalculationService 100%·RankingPriceCollectionService 97.3% |
| clean build green(Checkstyle 0 + ArchUnit) + baseline·DTO diff 0 | `./gradlew clean build` BUILD SUCCESSFUL 255테스트 0실패(3 skip 무관), Checkstyle 0·ArchUnit 2/2·baseline diff 0, 전 도메인 통합 green(TestSchedulingConfig 하드닝 회귀 없음) |
| 안전망 진짜(토톨로지 아님) | 뮤테이션 red-team M1/M2/M3 전부 RED 전환, 대조 M0 정확히 inert |
| 원본 `~/stockIt` 무오염 | develop 955836c, 워크트리만 변경 |
