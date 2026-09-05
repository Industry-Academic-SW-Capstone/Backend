# stock 도메인(StockChartService) 순수 로직 추출 — 의도된 동작 변경 목록 및 결함 원장

브랜치: `refactor/stock-chart-pure-logic` (base `a08001b`)
근거 계획: ralplan 합의 계획 `pending-approval.md` (deep-interview 스펙 `stock-chart-pure-logic` 모호도 4.05% PASSED 기반), Architect CLEAR/APPROVE + Critic OKAY
특성화 기준 태그: `stock-charac-base` (= `d98a4f7`)

## 1. 의도된 변경 목록

> 원칙: 리팩토링 전후 관찰 가능한 동작(API 응답·캐시 키/TTL·KIS 호출 횟수와 순서·예외 타입/메시지)은 동일하다.
> 명백한 결함은 사용자 판정 후에만 별도 커밋으로 수정한다. 이번 사이클의 의미 변경은 승인된 결함 **a·b 두 건**, 그리고 승인 서명 이후 사용자 지시로 추가된 결함 **e** 한 건이다(편차 12).

| # | 지점 | 사용자 승인 근거 | 커밋 해시 |
|---|------|-----------------|----------|
| a | `requestDailyMinuteChunk` HTML 분기 | 빈 차트를 200으로 서빙하고 5분 캐시까지 태우는 무증상 실패 | `4cb54db` |
| b | `mapToStockChartDto` 등락률 계산 | 분모 0으로 `"Infinity"` 문자열이 응답에 실림 | `596f60a` |
| e | `mapToStockChartDto` 등락률 포맷 | 기본 로케일이 콤마 소수점이면 `"1,23"`이 응답·캐시에 실림 | `6032937` |

### 1.1 순수 구조 변경(관찰 동작 아님) — 협력자 0개 신규 4클래스

패키지 `grit.stockIt.domain.stock.service`. 전부 `@Component` + 인스턴스 메서드이며 **주입 필드 0개**(협력자 0)다. 원본 라인 번호는 base `a08001b`의 `StockChartService`(807줄) 기준이다.

| 클래스 | 줄 수 | 옮겨온 것 | 원본 라인 범위 |
|--------|------|----------|---------------|
| `ChartPeriodPolicy` | 68 | `normalize`(`toLowerCase()` 축자), `cacheKey`, `cacheTtl`(default 30분 분기 유지), `isOneDay`/`isOneWeek`/`isThreeMonth`/`isOneYear`/`isFiveYear`, `unsupportedPeriodMessage`, 상수 `CACHE_KEY_PREFIX`·`CACHE_TTL_1DAY`~`CACHE_TTL_5YEAR` | L51, L54-58, L72, L105-114, L263 |
| `KisValueParser` | 67 | `parseDate`(실패 시 `IllegalArgumentException`), `parseTime`(3경로: 가드 null 무로그 / catch null 로그 / 정상), `parseIntValue`, `parseLongValue`, `formatDate`(신설), 상수 `DATE_FORMATTER` | L48, L613-619, L624-637, L784-792, L797-805 |
| `ChartTimeline` | 105 | `isBeforeMarketOpen`, `intradayRequestTimes`(`MARKET_START` 가드 + `MARKET_END` 클램프 흡수), `calculateTimeRanges`(축자), `recentBusinessDays`(축자), `weekBusinessDayCount`, `splitIntoHalves`, `dailyMinuteWindows`, `record DateRange` | L331-341, L367-391, L396-410, L193-203, L416 |
| `ChartSampling` | 81 | `deduplicateAndSort`, `shouldKeepMinuteBar`(9분 판정식), `selectWeeklySampleIndexes`(7일 필터 루프 전체 승격, O4), private `shouldKeepDailyBar` | L509-528, L148-163, L224-237 |

`StockChartService`: **807줄 → 640줄** (167줄 감소). 리액티브 토폴로지는 무접촉 — `Flux.merge`(1day/1year), `concatMap` 2계층(1week), 1주 3일차 `Mono.delay(Duration.ofSeconds(1))` 전부 원본 그대로다.

서비스는 `calculateTimeRanges`를 **직접 호출하지 않는다**(CONF-01). 1day 호출 수의 단일 오라클은 `chartTimeline.intradayRequestTimes(now)`이며, 공집합 조기 반환은 `chartTimeline.isBeforeMarketOpen(now)`가 담당한다.

## 2. 계획 대비 편차 기록 (AC-9 원장, 12건)

| # | 편차 | 사유·영향 |
|---|------|----------|
| 1 | 추출된 `log.warn`의 **로거명이 `KisValueParser`로 변경**(AC-13) | `parseTime`의 `시간 파싱 실패: {}`, `parseIntValue`/`parseLongValue`의 파싱 실패 WARN이 이제 `grit.stockIt.domain.stock.service.KisValueParser` 이름으로 남는다. `application*.yml` 전체에 **클래스·패키지 스코프 로깅 레벨 설정이 0건**이라 설정 영향은 없다. 로그 수집기에서 로거명으로 필터링 중이라면 그쪽만 갱신이 필요하다 |
| 2 | **일별 분봉 4윈도 상수**(원본 L416 `getMinuteChartDataFromKisDaily`의 `List.of("153000","133000","113000","093000")`)를 `ChartTimeline.dailyMinuteWindows()`로 이관 | KIS 호출 횟수를 결정하는 인자를 `ChartTimeline` 한 곳에 모으기 위함. 1week 호출 수 20 = 영업일 5 × 윈도 4가 이제 단위 테스트로 고정된다 |
| 3 | `KisValueParser#formatDate(LocalDate)` **신설** | 계획의 PURE 9개에 없던 추가 메서드. `DATE_FORMATTER`를 `KisValueParser`로 옮기면서 서비스에 남는 `date.format(DATE_FORMATTER)` 4지점의 잔여 중복을 없애 AC-8(잔여 중복 0)을 성립시키기 위한 최소 확장이다. 출력 문자열은 `yyyyMMdd`로 동일 |
| 4 | `StockChartDefectFreezeTest`를 **AC-1 불변 목록에서 의도적으로 제외** | 결함 동결과 특성화를 같은 파일에 두면 결함 수정 커밋(C8·C9)이 특성화를 수정하게 되어 AC-1이 구조적으로 깨진다. 불변 목록은 `StockChartCacheCharacterizationTest`·`StockChartCallProfileCharacterizationTest` **정확히 2파일**로 한정하고, 동결 파일은 목록 밖에 두어 갱신하되 **삭제·개명하지 않는다**. 그 갱신 diff 자체가 사용자 영향 명세가 된다(파일 상단 javadoc에 이 정책을 명시) |
| 5 | **AC-6 부분 충족** — "마지막 원소를 포함할 때도 `lastSelectedDate`를 갱신" 조항은 **출력으로 관측 불가능** | `isLastItem` 분기의 `lastSelectedDate = currentDate` 대입 뒤에는 비교 대상이 없다. 루프 전체를 `selectWeeklySampleIndexes`로 승격(O4)해도 해소되지 않으며, 검증을 강제하려면 프로덕션이 내부 상태를 노출해야 해 동작 보존을 위반한다. 따라서 이 조항은 **코드 리뷰가 소유**하고(대입문 축자 유지 확인), 관측 불가능한 것을 관측 가능한 것처럼 단정하는 테스트는 만들지 않았다. **AC-6은 부분 충족이다.** 나머지 세 조항(9분 임계 `+09:00:30` 수용, 날짜 변경 시 첫 봉 유지, 단일 원소 1회 방출)은 S4·S6·D4·D5로 고정 |
| 6 | **영업일 수 `5`**(원본 L535 `getRecentBusinessDays(LocalDate.now(), 5)`)를 `ChartTimeline#weekBusinessDayCount()`로 이관 | 편차 2와 같은 동인(f22 일관 적용). 배선은 `chartTimeline.recentBusinessDays(LocalDate.now(), chartTimeline.weekBusinessDayCount())`. 덕분에 `recentBusinessDays(any, weekBusinessDayCount()).size() * dailyMinuteWindows().size() == 20`이 프로덕션 상수에서 유도되어 자기참조가 아니다 |
| 7 | 결함 a 수정 후 **동일 사건당 ERROR 로그가 1건 → 5건으로 증폭** | 발화 지점: (i) `requestDailyMinuteChunk`의 HTML `log.error`, (ii) 같은 메서드의 `doOnError`, (iii) `fetchStockChartFromApi` 1week 분기의 `doOnError`, (iv) `StockDetailController#getStockChart`의 `doOnError`, (v) `GlobalExceptionHandler#handleAll`의 `log.error`. 로그 볼륨 기반 운영 알람 임계를 재조정해야 한다 |
| 8 | 특성화 기준 태그명이 계획의 `charac-base`가 아니라 **`stock-charac-base`** | `charac-base`는 **직전 member 리팩토링 사이클이 이미 사용 중**이며 `8be73ed`를 가리킨다. 계획대로 이름을 재사용하면 그 사이클의 AC-1 증거(`git diff develop..charac-base`, `git log charac-base..HEAD`)가 파괴된다. 태그를 도메인 접두사 붙여 분리했고, 이 문서와 C11 게이트 명령의 모든 리비전 인자는 `stock-charac-base`를 쓴다 |
| 9 | **1year 서비스 레벨 내용 오라클이 계획대로 구현되지 않았다** — 계획 C2(a)와 8절 주 (b)는 1원소·3원소 1year 시나리오를 요구했으나 C2에서 누락됐다. 경계 리뷰 중 뮤테이션 테스트로 공백이 증명되어 `StockChartDefectFreezeTest` OY-1로 사후 추가했다 | 1year는 이번 사이클 **유일한 비축자 변환**(정렬 → `selectWeeklySampleIndexes` → 인덱스 역적용)인데, 불변 2파일은 호출 수 2회와 경로·tr_id만 고정하고 내용은 아무도 단정하지 않았다. 그 결과 다음 두 뮤테이션이 413건 스위트 전원 통과로 **생존**했다: **M1b** — `sortedList.get(index)`를 `allData.get(index)`로 바꿔 표본 인덱스를 정렬 **전** 병합 리스트에 적용(`Flux.merge` 도착 순서에 따라 응답이 갈리는 비결정 사고), **M8** — `sortedList.stream()`을 `sortedList.reversed().stream()`으로 바꿔 `selectWeeklySampleIndexes`의 문서화된 오름차순 입력 선행조건을 위반. OY-1은 두 절반 응답에 날짜를 교차 배치(전반기 `0120,0108,0106` / 후반기 `0122,0121,0113,0107`, 각각 KIS처럼 내림차순)하고 방출 시퀀스를 `(2025-01-06, 01-13, 01-20, 01-22)`로 **정확히** 단정한다. 표본이 실제로 판별력을 갖도록 `01-07`·`01-08`·`01-21`은 탈락하고 마지막 `01-22`는 강제 포함되는 날짜를 골랐다. 단정 하나가 두 뮤테이션을 모두 잡는다(M1b → `01-07`·`01-08`·`01-21`이 섞여 들어옴, M8 → 2건으로 축소)  이후 gen-2 리뷰에서 **M16**(`sortedList.get(index)`를 역순 리스트에 적용)이 픽스처 대칭 때문에 추가로 생존함이 드러나, 전반기에 `20250108`을 넣어 선택 인덱스 집합의 대칭을 깨고 닫았다(커밋 `1e253c0`). |
| 10 | **저장 JSON 왕복 단정(어서션 9)이 AC-1 불변 파일 안에 영구 동결됐다** — 계획 9.1은 이 단정을 삭제 대상으로 표시했으나 `StockChartCacheCharacterizationTest`에 남은 채 `stock-charac-base`로 고정되어 이제 수정·삭제할 수 없다 | 무해하다(직렬화 왕복은 실제 캐시 경로의 성질이고 통과 중이다). 다만 이 단정은 `ObjectMapper`로 `List<StockChartResponse>`를 역직렬화하므로 **오라클이 `StockChartResponse`의 필드명에 결합된다** — 필드명 변경은 컨벤션상 API 계약 변경이며, 이 불변 파일이 그 변경을 컴파일·역직렬화 양쪽에서 붙잡는다. **병합 정책**: 타 세션 브랜치 `refactor/stock-chart-testability`와 병합할 때 불변 2파일은 **이 브랜치 판본을 무조건 채택(ours)** 한다 — 타 세션의 수정을 받아들이면 AC-1 증거(`git log stock-charac-base..HEAD -- <불변 2파일>` = 0 커밋)가 파괴되기 때문이다. 저쪽이 `StockChartResponse` 필드명을 바꿨다면 그것은 계약 변경이므로 병합 자체를 보류하고, 사이클 종료(태그 확정) 이후 별도 커밋에서 문서와 함께 처리한다 |
| 11 | **C11 게이트 항목 5(동결 파일 정확히 2커밋) 이탈** — 실제 6커밋 | 계획은 동결 파일이 C8·C9 두 커밋만 갖는다고 전제했으나, 경계 리뷰 generation 1에서 1year 서비스 레벨 오라클 공백이 뮤테이션으로 증명되면서 OY-1 추가(`8a6a1f0`), 픽스처 대칭 제거(`1e253c0`), 자바독 정정(`4a4b4c1`) 3커밋이 더해졌고, 결함 e 수정과 함께 DF-6이 추가되며(`6032937`) 1커밋이 더 붙었다(편차 12). 동결 파일은 AC-1 불변 목록 밖(편차 4)이라 AC-1의 오라클 불변 증명은 손상되지 않는다 — 불변 2파일은 여전히 `stock-charac-base` 이후 **0커밋**이다 |
| 12 | **결함 e(등락률 포맷의 로케일 의존) 수정이 경계 코호트·터미널 크리틱의 승인 이후에 추가됐다** | 계획은 §2 "다음 사이클 후보"의 `tier-d-mappers` 항목에서 **"`String.format` 로케일 의존성은 건드리지 않는다"** 를 제약으로 명시했다. 이 추가는 그 제약을 **의도적으로 이탈한다** — 사용자의 명시적 지시에 따른 결정이며, 경계 코호트와 터미널 크리틱이 서명한 트리는 이 커밋 **이전** 상태다. 따라서 서명 범위 밖의 변경으로 읽어야 한다. 관찰 동작 변경은 **ko가 아닌(콤마 소수점) 기본 로케일에서만** 발생하는 의도된 변경이며, 배포 대상 ko-KR에서는 이전과 완전히 동일하다. 판별 케이스 `StockChartDefectFreezeTest` DF-6이 없으면 대조군 DF-5가 수정 유무와 무관하게 통과하므로, 픽스와 함께 기본 로케일을 `Locale.GERMANY`로 바꿔 실행하는 케이스를 동봉했다(실행 후 `finally`에서 이전 기본 로케일 복원) |

### 참고: 다음 사이클 후보

- `ChartSampling`은 *KIS 원시 DTO 정규화*(`deduplicateAndSort`)와 *`java.time` 값 기반 솎아내기*(`shouldKeepMinuteBar`, `selectWeeklySampleIndexes`) 두 책임을 안고 있다. 4클래스 확정(f20)을 지키기 위해 이번 사이클에는 분리하지 않으며 `ChartBarMerger`를 다음 사이클 후보로 남긴다.
- `tier-d-mappers`(`mapMinuteToStockChartDto`, `mapToStockChartDto` 추출) — 계획은 이 항목에 "`String.format` 로케일 의존성은 건드리지 않는다"는 제약을 달았으나, 결함 e 수정은 그 제약에서 의도적으로 이탈했다(편차 12). 매퍼 **추출** 자체는 여전히 별도 사이클로 연기한다.

## 3. 결함 원장 (5건)

| ID | 위치 | 수정 전 | 수정 후 | 동결 케이스 소유자 | 커밋 |
|---|------|---------|---------|-------------------|------|
| a | `requestDailyMinuteChunk` HTML 분기 (base L464-467) | 응답이 `<`로 시작하면 `log.error` 후 `Mono.just(List.of())` → **불완전하거나 빈 차트가 200으로 나가고 5분 TTL로 캐시** | `log.error` 유지, 반환만 `Mono.error(new RuntimeException("KIS API 오류: HTML 응답 (인증/세션 이슈 가능)"))`. 두 계층 `concatMap`을 거쳐 `onErrorResume`이 `RuntimeException("주식 분봉 데이터 조회 실패: 005930", e)`로 감싸고 `handleAll`이 500을 낸다. **캐시에 저장되지 않는다** | `StockChartDefectFreezeTest` DF-1·DF-2 (DF-0 대조 실행과 HTML `log.error` 어서션은 불변) | `4cb54db` |
| b | `mapToStockChartDto` 등락률 폴백 (base L670-672) | `closePrice == changeAmount`면 `(changeAmount / (double)(closePrice - changeAmount)) * 100`의 분모가 0 → `changeRate`가 **`"Infinity"`** | 조건에 `&& closePrice != changeAmount` 추가 → 기존 폴백 경로로 떨어져 **`"0"`** | DF-4 (대조군 DF-5는 `"10.00"` 불변) | `596f60a` |
| c | `getRecentBusinessDays` (base L396-410) | 토·일만 제외하고 **공휴일을 영업일로 취급**한다. `recentBusinessDays(2026-01-02, 5)`는 신정 `2026-01-01`을 포함하며, 그날의 4회 KIS 호출은 데이터 없는 응답을 받는다 | **영구 동결.** 수정하려면 공휴일 달력이라는 신규 데이터 소스(또는 협력자)가 필요해 "협력자 0개 순수 클래스 추출"이라는 이번 사이클 목적과 정면 충돌한다. 승인 범위도 a·b 두 건뿐이다. 호출 수 20은 공휴일 여부와 무관하게 항상 20이라 사용자 영향은 "빈 봉 구간"에 한정된다 | `ChartTimelineTest` W5 | 없음 |
| e | `mapToStockChartDto` 등락률 포맷 (base L671 / 수정 시점 L530) | `String.format("%.2f", rate)`가 **JVM 기본 로케일**을 읽는다. 콤마 소수점 로케일(예: `de-DE`)이면 `changeRate`가 `"1.23"`이 아니라 **`"1,23"`** 으로 응답 본문과 Redis 캐시에 실려, 숫자로 파싱하는 소비자가 깨진다 | `String.format(Locale.ROOT, "%.2f", rate)`로 로케일을 고정. 출력은 항상 점 소수점이다. **배포 대상 서버가 Seoul(ko-KR)이라 운영에서는 한 번도 발현하지 않았다** — 잠재 함정 제거이며 ko-KR 관찰 동작은 이전과 완전히 동일하다 | `StockChartDefectFreezeTest` DF-6 (대조군 DF-5는 ko-KR 기본 로케일에서 `"10.00"` 불변) | `6032937` |
| d | `calculateTimeRanges` 초 비대칭 (base L373 vs L385) | 30분 격자 항목은 `String.format("%02d%02d%02d", hour, minute, 0)`으로 **초를 0으로 고정**하는 반면, 말미 항목만 `end.getSecond()`를 그대로 쓴다. 그래서 `09:00:01` 요청은 `[090000, 090001]` 2건이 되어 **KIS 호출이 1회 더 나간다**(정각 `09:00:00`은 1건) | **영구 동결.** 수정은 KIS 호출 횟수·순서를 바꾸는데, 그 불변식이 이번 사이클의 최상위 보존 제약이다. 축자 이동 원칙(P2)상 추출 커밋이 의미를 함께 나를 수 없다 | `ChartTimelineTest` T4·T5 (+ T10이 "이 함수는 어떤 입력에서도 비지 않는다"를 봉인) | 없음 |

### 3.1 결함 a 수정은 "해소"가 아니라 **부분 차단**이다

정직하게 적는다. `4cb54db`는 **동형(isomorphic) 삼킴 분기 네 개 중 하나**만 막은 트리거 차단이며 근본 원인 수정이 아니다. `requestDailyMinuteChunk`에는 같은 사용자 영향 — **빈 차트가 200으로 서빙되고 그대로 캐시됨** — 을 재현하는 분기가 셋 남아 있다.

1. **원본 응답이 null이거나 공백** → `log.warn` 후 `Mono.just(List.<KisMinuteChartDataDto>of())`.
2. **`response.rtCd()`가 null이거나 공백** → `log.warn` 후 `Mono.just(List.<KisMinuteChartDataDto>of())`. `rtCd`가 있고 `"0"`이 아닐 때만 `Mono.error`가 나가는 비대칭이다. DF-7이 이 경로를 **영구 동결 케이스**로 남겨 근거를 보존한다.
3. **`response.output2()`가 null** → `log.warn` 후 `Mono.just(List.<KisMinuteChartDataDto>of())`.

스펙 승인 범위가 HTML 분기 하나이므로 확대하지 않았다. 세 분기 처리는 후속 판정 대상이다.

## 4. 수용 기준 충족 요약 (AC-1 ~ AC-13)

| 기준 | 판정 | 증빙 |
|------|------|------|
| **AC-1** 특성화 선행 + 이후 무수정 | 충족 | `git diff --stat develop..stock-charac-base -- src/main/` 빈 출력, `git log --oneline stock-charac-base..HEAD -- <불변 2파일>` 0 커밋. 동결 파일은 **6커밋**(`4cb54db`·`596f60a` = C8·C9, 경계 리뷰 remediation 3건 `8a6a1f0`·`1e253c0`·`4a4b4c1`, 결함 e 판별 케이스 DF-6 `6032937`)이며 파일 존재. 계획 C11 게이트 항목 5는 정확히 2커밋을 요구했으나 경계 리뷰가 OY-1 오라클 보강을 요구해 3커밋이 추가됐다(편차 11) |
| **AC-2** 신규 4클래스 라인 커버리지 100% | 충족 | `clean build` 후 무필터 `test jacocoTestReport` 단일 실행의 `jacocoTestReport.xml`에서 4클래스 전부 LINE `missed=0` |
| **AC-3** 서비스 레벨 얇은 특성화 | 충족 | `StockChartCacheCharacterizationTest` — 캐시 키 `stock:chart:{code}:{normalized}`, TTL 5종(60/300/1800/3600/43200초), 무효 기간 타입의 구독 없는 동기 throw + 메시지 완전 일치, throw 전 Redis GET 발생(깨진 JSON + WARN 로그로 직접 증명), 파싱 실패 시 `delete` 없이 API 폴백, 캐시 저장 실패 삼킴 |
| **AC-4** PURE 9개 시그니처 무변경 이동 | 충족 | 파라미터 타입·순서, 반환 타입(박싱 `Integer`/`Long` 포함), 예외 타입·메시지 동일. `parseDate`의 `Invalid date format: ... (expected: yyyyMMdd)` + 원인 체이닝 유지. 접근 제어자(`private`→`public`)와 수신자 변경은 정의상 제외 |
| **AC-5** `ChartTimeline` 호출 수 경계 고정 | 충족 | T1~T10: 08:59 → 0개, 09:00:00 → 1개, 15:30 이후 → 14개, `splitIntoHalves` → 정확히 2개(윤년 포함), 1week 20개(영업일 5 × 윈도 4) |
| **AC-6** `ChartSampling` 경계 검증 | **부분 충족** | S1~S8·D0~D6로 9분 임계의 `+09:00:30` 수용, 날짜 변경 시 첫 봉 유지, 마지막 원소 항상 포함, 단일 원소 1회 방출, 빈 입력 → 빈 결과를 고정. **"마지막 원소 포함 시에도 `lastSelectedDate` 갱신" 조항만 출력으로 관측 불가능해 코드 리뷰가 소유한다**(편차 5). 또한 **계획 8절 주 (b)가 지정한 서비스 레벨 시나리오는 원안대로 구현되지 않았다** — 이 사이클의 서비스 레벨 1year 검증은 계획이 정한 형태가 아니라, 경계 리뷰에서 뮤테이션(M1b·M8) 생존이 확인된 뒤 사후 추가한 `StockChartDefectFreezeTest` OY-1(방출 날짜 시퀀스 완전 일치)이 담당한다(편차 9) |
| **AC-7** `KisValueParser` 비대칭 보존 | 충족 | `parseDate`는 throw, `parseTime`은 null 반환(광의 catch), `parseIntValue`/`parseLongValue`는 `NumberFormatException`만 잡고 0/0L 반환 |
| **AC-8** 잔여 중복 0 | 충족 | `StockChartService.java` 대상 정의 grep 0건 + 리터럴·심볼 grep 0건, 배선 카운트 grep 2/1/1/1 |
| **AC-9** 결함 5건 전량 기재 | 충족 | 본 문서 §3 (a·b·e는 수정 전후 + 커밋 해시, c·d는 동결 사유) + §3.1 부분 차단 명시. e는 승인 서명 이후 사용자 지시로 추가됐다(편차 12) |
| **AC-10** a 수정 | 충족 | HTML 응답 시 빈 리스트 대신 `RuntimeException`, 기존 `rt_cd != "0"` 경로와 동일하게 `onErrorResume`에 흡수됨을 DF-1이 확인. 총 요청 수는 `htmlOrdinal + 1`(1/11/20)로 조기 종료 |
| **AC-11** b 수정 | 충족 | `closePrice == changeAmount`일 때 `changeRate`가 `"0"`(DF-4), 대조군 `"10.00"` 불변(DF-5) |
| **AC-12** 정적 게이트 | 충족 | `clean build` green, Checkstyle main·test 각 0 error, ArchUnit 전량 통과, `config/checkstyle/suppressions.xml`·`src/test/resources/archunit_store/` 신규 항목 0(diff 빈 출력) |
| **AC-13** 로거명 변경 문서화 | 충족 | 본 문서 §2 편차 1 |

## 5. 후속(범위 밖)

- 결함 c(공휴일 미고려)·d(초 비대칭으로 KIS 1회 추가 호출) 수정 가부 — 동결 상태이며 판정은 사용자 몫.
- 잔존 삼킴 3분기(§3.1) 처리 — DF-7이 그중 하나를 영구 동결로 남긴다.
- `tier-d-mappers`(매퍼 2개 추출), `ChartBarMerger` 분리 — 다음 사이클 후보.
- 타 세션 브랜치 `refactor/stock-chart-testability`와의 `build.gradle` MockWebServer 중복 줄 병합.
