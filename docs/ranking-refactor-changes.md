# ranking 도메인(RankingService) 리팩토링 — 의도된 동작 변경 목록 및 버그 판정 요청

브랜치: `refactor/ranking-testability` (develop 955836c 기반)
근거 계획: ralplan 합의 계획 `pending-approval.md` (deep-interview 스펙 `ranking-testability-refactor` 4.75% PASSED 기반), Architect CLEAR/APPROVE + Critic OKAY (4 iteration)

## 1. 의도된 동작 변경 목록 (AC)

> 원칙: 리팩토링 전후 관찰 가능한 동작(API 응답·DB 상태·Caffeine 캐시·발행 이벤트·예외)은 동일하다.
> 명백한 버그는 사용자 판정 후에만 별도 커밋으로 수정한다.

| # | 지점 | 사용자 승인 근거 | 커밋 해시 |
|---|------|-----------------|----------|
| — | (없음) | 승인된 버그 수정 0건 — 프로덕션 관찰 동작 변경 없음 | — |

### 참고: 순수 구조 변경(관찰 동작 아님)

| 지점 | 내용 | 성격 |
|------|------|------|
| `RankingService` 711→530줄 | God Class를 순수 계산기 + 가격수집기 + 슬림 오케스트레이터로 분해 | 책임 분리(SRP), public API·캐시·트랜잭션 시맨틱스 보존 |
| `RankingCalculationService` (129줄, 의존 0) | `calculateTotalAssets`/`calculateReturnRate`/`calculateReturnRateFromAssets` 이동 + `assignCompetitionRanks` 순수함수 | 이동 3메서드 본문 develop과 정규화 대조 IDENTICAL. `assignCompetitionRanks`는 두 convert에 인라인 중복된 동률순위 로직을 dedup 추출(동일 산출) |
| `RankingPriceCollectionService` (118줄) | `collectAllHeldStockCodes`/`batchFetchCurrentPrices`/`fetchPricesWithRateLimit` + `RateLimiter(25/s)` 이동 | 본문 IDENTICAL. 싱글턴 1 인스턴스로 전역 25/s 시맨틱스 보존 |
| `TestSchedulingConfig` (테스트 전용) | `setScheduler(null)` → **no-op TaskScheduler**(§3 참조) | 테스트 인프라 하드닝. 프로덕션 무관 |
| 특성화 하네스 | @DirtiesContext·실 @EventListener 캡터·실 PropertySource 게이트 | 테스트 신뢰성, 프로덕션 무관 |

## 2. 버그 의심 지점 — 사용자 판정 요청

> 아래 11건(a~k)은 전부 **현재 동작 그대로 특성화 테스트로 고정**(수정 안 함). 수정 승인 시 해당 특성화를 기대값 갱신과 함께 별도 커밋으로 처리한다.
> 리팩토링 후 계산·수집 로직이 이동했으나 **본문은 원본과 정규화 대조 IDENTICAL**이라 동작은 develop과 동일하다. 뮤테이션 red-team(M1/M2/M3)으로 안전망이 실제 이 동작들을 잡음을 증명했다.

| # | 지점(현 소유 클래스) | 현재 동작 | 왜 의심스러운가 | 고정한 시나리오 |
|---|------|----------|----------------|--------------|
| a | `RankingPriceCollectionService.fetchPricesWithRateLimit` | KIS `getCurrentPrice`가 null/0/타임아웃/예외면 해당 종목을 **0원**으로 처리 | 총자산이 잔액만으로 계산되어 순위 왜곡. 인프라 오류(timeout/5xx)를 조용히 0원으로 은폐 | BugFreeze a1~a4(null/0/exception/timeout) |
| b | `RankingService.getMyRank` | `myTotalAssets`(즉석 재계산)와 내부 랭킹의 `totalAssets`가 **다른 가격 스냅샷** 사용 | 자기호출 캐시 우회로 매번 재계산 → 같은 응답 내 두 총자산이 불일치 가능 | BugFreeze b(시점가변 스텁 P1→P2, 단일 종목) |
| c | `RankingService.getContestRankingsWithPrices`(returnRate 경로) | `AccountWithAssets.totalAssets` 필드에 **수익률을 오버로딩** 저장 + `convert...ForReturnRate`에서 actualTotalAssets **재계산(중복)** | 필드 의미 이중화로 취약, 동일 총자산을 두 번 계산 | BugFreeze c |
| d | `RankingService.updateAllRankings` | 배치 중 모든 예외를 `catch(Exception)`으로 삼키고 throw 안 함 | 스케줄러가 조용히 실패(부분 갱신·무갱신을 숨김) | Update U6 |
| e | `RankingService.getTierForMember` | `MissionQueryService.getTierInfo` 예외 시 `tier=null` 반환(예외 전파 안 함) | 티어 조회 실패를 은폐 | BugFreeze e |
| f | `RankingCalculationService.assignCompetitionRanks`(원래 convert 인라인) | 동률 순위(`rank`/`sameRankCount`, `compareTo()==0`): 2동률군 `[a,a,b,b,c]→[1,1,3,3,5]` | competition ranking이 의도인지 확인 필요(‘정상일 수도’) | Query Q11/Q12, BugFreeze f, 순수 유닛 |
| g | `RankingService.getContestRankings` | `sortBy` 파라미터를 `"balance"→"totalAssets"`로 **변이**(재할당) | 파라미터 변이 자체가 스멜. **[QA 정정]** 실제 응답 `sortBy`는 `getContestRankingsWithPrices`의 `isReturnRate` 삼항으로 **독립 계산**되므로, 이 정규화 라인은 관찰 동작에 영향 없는 **행위상 데드코드** | BugFreeze g |
| h | `RankingService.getMainRankingsWithPrices`/`getContestRankingsWithPrices` | `currentPrices.isEmpty()`면 잔액만 사용하는 **레거시 폴백** 분기 | **[QA 정정]** 현재 코드에서 이 폴백은 비폴백 분기와 **행위상 동치**(`calculateTotalAssets`가 누락 가격을 이미 ZERO 기본처리) → 사실상 데드 분기 | BugFreeze h |
| i | `RankingService.updateAllRankings`(대회 루프) | `getContestRankingsWithPrices` 반환값을 **폐기**(private 메서드라 캐시 워밍·이벤트 없음) | 로그 외 부수효과 없는 낭비 연산(대회 랭킹 계산 결과가 캐시에 반영 안 됨) | BugFreeze i |
| j | `RankingService.calculateReturnRate`(→ `RankingCalculationService`) | 두 호출부가 `includeReturn=false`만 전달 → **실질 데드코드**(프로덕션 경로 미도달) | 도달 불가 로직. 응답 `returnRate`가 항상 null임으로 간접 동결 | BugFreeze j + 순수 유닛(직접 커버) |
| k | `RankingService.getMyRank` | `myReturnRate`(스냅샷1)와 대회 랭킹 `returnRate`(재계산 스냅샷2)가 **다른 가격 스냅샷** | b의 수익률 확장 — 같은 응답 내 수익률 불일치 가능 | BugFreeze k(시점가변 스텁) |

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
