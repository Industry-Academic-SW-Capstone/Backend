# mission 도메인 리팩토링 — 의도된 동작 변경 목록 및 버그 판정 요청

브랜치: `refactor/mission-testability` (develop 7b6cf03 기반)
근거 계획: ralplan 합의 계획(딥 인터뷰 스펙 `deep-interview-mission-testability-refactor.md` 기반)

## 1. 의도된 동작 변경 목록 (AC7)

> 원칙: 리팩토링 전후 관찰 가능한 동작(API 응답, DB 상태 변화, 발행 이벤트)은 동일하다.
> 명백한 버그는 사용자 판정 후에만 별도 커밋으로 수정한다.

| # | 지점(파일:라인) | 사용자 승인 근거 | 커밋 해시 |
|---|----------------|-----------------|----------|
| — | (없음) | 승인된 버그 수정 0건 — 프로덕션 동작 변경 없음 | — |

### 참고: 테스트 인프라 변경 (프로덕션 동작 아님, 투명성 기록)

| 지점 | 내용 | 커밋 |
|------|------|------|
| `IntegrationTestSupport` | `@Container` 확장 → 싱글턴 컨테이너 패턴(클래스별 컨테이너 재시작이 캐시된 컨텍스트를 깨는 결함 수정) | 8ce8198 |
| `LimitOrderMatchingServiceConcurrencyTest` | 자체 `@Container`(동일 설정) → `IntegrationTestSupport` 상속(reuse 활성 시 공유 컨테이너 stop 충돌 제거) | f3ff07c |
| (환경) `~/.testcontainers.properties` | `testcontainers.reuse.enable=true` 추가 — `withReuse(true)` 설계 의도 활성화 | 커밋 외(로컬 환경) |

## 2. 버그 의심 지점 — 사용자 판정 요청

> 아래 항목은 전부 **현재 동작 그대로 특성화 테스트로 고정**되어 있다(수정 안 함).
> 수정을 승인하면 해당 특성화 테스트를 기대값 갱신(변경 사유 주석 포함)과 함께 별도 커밋으로 처리한다.

| # | 지점 | 현재 동작 | 왜 의심스러운가 | 고정한 테스트 |
|---|------|----------|----------------|--------------|
| a | `MissionProgressService.updateMissionProgress` (구 MissionService L86~89) | AccountId가 null인 체결 이벤트도 경고 로그만 남기고 기본 계좌 검증 없이 집계 진행 | 보조 계좌 제외 로직을 우회하는 구멍 | MissionServiceIntegrationTest 4-1 |
| b | `MissionTrackPolicy.isFirstMissionInTrack` (구 L906~910) | 미션 ID 201/301/401 리터럴 하드코딩 | 시드 데이터 변경 시 조용히 무너지는 결합(현재 캡슐화만 완료, 값 판정은 보류) | MissionTrackPolicyTest |
| c | `MissionRewardService.checkLegendTier` (구 L158~) | 점수 3600 하드코딩으로 LEGEND 티어 판정 | 티어 테이블(getTierInfo의 15단계 if-else)과 이중 관리 | MissionQueryIntegrationTest 티어 3종 |
| d | `MissionQueryService.getMissionProgressList` (구 L996) | 호출자 0건인 dead 공개 API(엔티티 List 반환) | 삭제 시 커버리지 분모 감소 + API 표면 축소. 프론트 사용 여부 확인 필요 | (이동만, 테스트 없음) |
| e | `MissionController` L27 | 사용되지 않는 `MissionScheduler` 주입 | dead 의존 | — |
| f | `MissionService.applyForBankruptcy` | 파산 신청 기준이 코드상 총자산 100만원 미만(경계 100만원 정확히는 거절), 칭호/스웨거 문구는 "5만원 미만" | 문서·코드 불일치 — 어느 쪽이 맞는지 판정 필요 | MissionQueryIntegrationTest 파산 3종 |
| g | `LocalMemberService.signup` + `Member.name varchar(20)` | 이메일 로컬파트를 name으로 사용 → 로컬파트 20자 초과 이메일이면 가입 자체가 DataIntegrityViolationException으로 실패 | 실사용자 가입 실패 가능성 있는 잠재 프로덕션 버그 | (테스트 작성 중 실측, 픽스처는 회피) |
| h | `MissionRewardService.resetMissionTrack` (구 L890) | ADVANCED 미션 완료 시 자기 자신 포함 트랙 전체를 reset+deactivate — COMPLETED 기록·진행도 즉시 소실(보상 현금은 지급됨), 완료 카운트(901) 미반영 | 완료 이력이 사라지는 것이 의도인지 불명확 | MissionServiceIntegrationTest 6 |
| i | `MissionProgressService.processRankerAchievement` (구 L856) | 목표치 조회 없이 `setCurrentValue(10)` 하드코딩 | 미션 목표(10) 변경 시 어긋남 | MissionServiceIntegrationTest 8 |
| j | `MissionQueryService.getMissionsByTrack` (구 L610~617) | 잘못된 track 문자열 → 예외 삼키고 빈 목록 반환(경고 로그만) | 클라이언트 오타가 조용히 빈 응답으로 위장 | MissionQueryIntegrationTest |

## 3. 수용 기준 충족 요약 (AC1~AC10)

| AC | 기준 | 증빙 |
|----|------|------|
| AC1 | 핵심 경로 통합 특성화 green | MissionServiceIntegrationTest 13 시나리오 (fe7f923, 0a87046) |
| AC2 | 순수 계산 단위 특성화 green | Evaluator 51 / Calculator 30 / TrackPolicy 13 (2993f53에서 직접 호출 승격) |
| AC3 | 리팩토링 후 동일 스위트 전부 green | 전체 159/0 (skip 3 = 기존 외부 연동 수동 게이트) |
| AC4 | `./gradlew build` 통과 + 베이스라인 신규 0 | BUILD SUCCESSFUL ×2 연속, suppressions/archunit_store diff 0줄 |
| AC5 | mission 라인 커버리지 ≥70% | 76.7% (553/721, JaCoCo XML) — 기준선 70.5%에서 상승 |
| AC6 | 결과 클래스 단일 책임 | 6클래스 분해(199/282/274/235/92 + 순수 3), architect 리뷰 CLEAR/APPROVE ×3회 |
| AC7 | 의도된 동작 변경 목록 | 본 문서 §1 (빈 목록) |
| AC8 | API 응답 계약 무변경 | 조회 응답 값 특성화 18종 무수정 green, DTO 필드 무변경 |
| AC9 | 타 도메인 mission 서비스 직접 주입 제거 | import 전수 감사: 서비스 1건(RankingService→MissionQueryService) + 허용 잔존 3건 정확 일치 |
| AC10 | 이벤트 전환 전후 특성화 무수정 green | Phase D 구간 src/test diff 0줄, A-5 롤백 전파 포함 green |
