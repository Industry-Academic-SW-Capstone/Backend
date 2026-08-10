# order 도메인(OrderService) 리팩토링 — 의도된 동작 변경 목록 및 버그 판정 요청

브랜치: `refactor/order-testability` (develop 6500db5 기반)
근거 계획: ralplan 합의 계획 `pending-approval.md` (deep-interview 스펙 `order-testability-refactor` 기반), Architect CLEAR/APPROVE + Critic OKAY

## 1. 의도된 동작 변경 목록 (AC)

> 원칙: 리팩토링 전후 관찰 가능한 동작(API 응답·DB 상태·오더북 상태·발행 이벤트·예외)은 동일하다.
> 명백한 버그는 사용자 판정 후에만 별도 커밋으로 수정한다.

| # | 지점 | 사용자 승인 근거 | 커밋 해시 |
|---|------|-----------------|----------|
| — | (없음) | 승인된 버그 수정 0건 — 프로덕션 관찰 동작 변경 없음 | — |

### 참고: 순수 구조 변경(관찰 동작 아님)

| 지점 | 내용 | 성격 |
|------|------|------|
| `OrderService` 373→221줄 | God Class를 C1~C4 + 슬림 오케스트레이터로 분해 | 책임 분리(SRP), public API·트랜잭션 시맨틱스 보존 |
| `OrderBookRegistrationService.registerAfterCommit` | `isActualTransactionActive()` false 시 warn-log 추가(신규 라인) | 방어 관측성. 보존 흐름에선 항상 활성이라 미발화 → 관찰 동작 중립 |
| `OrderHoldService.applyBuyHold` | 인라인 `OrderHold.create/save` 2줄을 승격 메서드로 추출 | save→OrderHold FK 순서 보존, 최종 DB 상태 동일 |
| 테스트 하네스(spy 격리) | 특성화 테스트의 @SpyBean 컨텍스트 격리 강화 | 테스트 신뢰성, 프로덕션 무관 |

## 2. 버그 의심 지점 — 사용자 판정 요청

> 아래 6건은 전부 **현재 동작 그대로 특성화 테스트로 고정**(수정 안 함). 수정 승인 시 해당 특성화를 기대값 갱신과 함께 별도 커밋으로 처리한다.
> 리팩토링 후 메서드가 C1~C4로 이동했으나 **본문은 원본과 정규화 대조 IDENTICAL**(applyBuyHold 승격 제외)이라 동작은 develop과 동일하다.

| # | 지점(현 소유 클래스) | 현재 동작 | 왜 의심스러운가 | 고정한 시나리오 |
|---|------|----------|----------------|--------------|
| a | `OrderService.createMarketOrder` + `OrderBookRegistrationService`(선구독 preSubscribe / afterCommit registerLimitOrder) | 시장가 매수/매도 시 `registerLimitOrder`를 저장 전 1회(선구독) + 커밋 후 afterCommit 1회 = **총 2회** 호출. 지정가엔 없는 선등록 | 이중 등록 비대칭. 롤백(KIS 실패·현금부족)해도 선구독 잔존(unregister 없음) | 8·13(times 2), 14·28(롤백 후 잔존) |
| b | `OrderHoldService.releaseBuyHold` | 매수 주문 취소 시 `OrderHold`가 존재할 때만(`ifPresent`) 홀딩 감소 | `OrderHold` 유실 시 `Account.holdAmount`가 영구 잔류(현금 잠김) | 22 |
| c | `OrderPricingService.calculateMarketHoldAmount` | KIS 현재가 폴백의 모든 예외를 `BadRequestException('최근 체결가 정보를 찾을 수 없습니다')`로 뭉뚱그림 | 타임아웃/네트워크/무효가를 400으로 은폐(원인 소실), 5xx여야 할 케이스 혼동 | 10 |
| d | `OrderService.createMarketOrder`(선구독→applySellHold 순서) | 시장가 SELL에서 `preSubscribe`(선구독)가 `applySellHold`(무보유 시 throw)보다 먼저 | 실패 시 부분 상태·구독 잔존 결합(a와 연동) | 13·14·28 |
| e | `OrderPricingService.validateStockTradeable` | KIS 일반 실패(timeout/5xx/네트워크)를 `UntradeableStockException('종목 거래 가능 여부를 확인할 수 없습니다')`로 포괄 은폐 | c와 동류 — 거래불가와 인프라 오류 혼동, 원인 소실 | 4(+일반실패 변형) |
| f | `OrderService.cancelOrder` + `OrderBookRegistrationService.removeOnCancel` | 취소 시 `removeOrder`+`unregisterLimitOrder`를 **커밋 전 동기** 실행 | 생성 경로의 'DB 커밋 후 Redis' 불변식과 비대칭 — 취소 트랜잭션 롤백 시 오더북만 제거되고 주문 미취소(유령/refcount 누수) | 15·16·17 |

## 3. 수용 기준 충족 요약 (계획 Acceptance criteria)

| 기준 | 증빙 |
|------|------|
| 4 컴포넌트 분해 + DAG(순환/역엣지 0) | C1 Pricing(87)/C2 Hold(70)/C3 OrderBookRegistration(60)/C4 Authorization(28) + OrderService 221줄. 생성자 그래프 DAG 실증(architect 45·QA 44) |
| public 5 흐름 + afterCommit + 홀딩정합 + 권한거부 + KIS폴백경계 + 취소예외 특성화 전량 green, 분해 전후 무수정 | 특성화 35 테스트(28 시나리오), Phase B 4커밋 전부 무수정 green |
| order service 스코프 라인 커버리지 ≥70% | (C-1 측정치 기록) — Phase A baseline 98.3%(order/service), order 전체 84.0% |
| Checkstyle 0 / ArchUnit green / suppressions·archunit_store·DTO 필드 diff 0 | Phase B 4커밋 checkstyle 0, 베이스라인 diff 0 |
| 변경목록 + 버그(a~f) 문서 | 본 문서 §1·§2 |
| 새 서비스 @Transactional 시맨틱스 보존 | 오케스트레이터 메서드레벨 유지, C1 readOnly, C2 REQUIRED, C3/C4 무트랜잭션(architect 45 §4 PASS) |
| 이동 메서드 본문 IDENTICAL | QA 44: 정규화 본문 diff 10/10 IDENTICAL(applyBuyHold=save경계 승격, registerAfterCommit=warn-log 부가 예외) |

## 4. 후속(범위 밖, 별도 작업)

- 버그 a~f 사용자 판정 후 별도 수정 커밋(수정 시 "의도된 동작 변경" 목록에 등재).
- 인접 결합: matching `LimitOrderExecutionService`의 체결-미션 동기 롤백 결함(별도 지적), `AssetService`(395줄) — 이번 범위 밖 연기.
- 조회 응집: `OrderQueryService`(getOrder/getPendingOrders 분리, Opt-2) 재평가 트리거.
- 필요 시 controller MockMvc 슬라이스로 order 패키지 전체 커버리지 확장.
