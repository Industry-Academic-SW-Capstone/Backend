# 코드 컨벤션

이 문서는 이 저장소의 목표 코드 컨벤션을 정의합니다.
일부 규칙은 도구(Checkstyle, ArchUnit)로 자동 검증되며, 기존 코드의 위반은
baseline으로 억제되어 있습니다 — **신규/변경 코드부터 적용**됩니다.

## 네이밍

### 파일/클래스

| 대상 | 규칙 | 예시 |
|------|------|------|
| 컨트롤러 | `{Domain}Controller` | `OrderController` |
| 서비스 | `{Domain}{역할}Service` | `RankingService`, `LimitOrderExecutionService` |
| 리포지토리 | `{Entity}Repository` | `MemberRepository` |
| 요청 DTO | `{Action}Request` | `CreateOrderRequest` |
| 응답 DTO | `{Domain}Response` | `StockDetailResponse` |
| 이벤트 | `{과거형 사건}Event` | `LimitOrderFillEvent` |
| 설정 | `{대상}Config` / `{대상}Properties` | `RedisConfig`, `KisApiProperties` |

- **`Dto`, `ResponseDto` 접미사는 사용하지 않습니다.** 기존 `*Dto` 클래스는
  마이그레이션 대상입니다 (ArchUnit freeze로 추적).
- 파일명은 public 클래스명과 일치해야 하며, 파일당 최상위 클래스는 1개만 둡니다.

### 메서드명 예외 (Checkstyle 정책 suppression)

- **리포지토리**: Spring Data JPA 연관 속성 탐색 문법 허용 (`findAllByMission_Track`)
- **테스트**: 언더스코어 구분 네이밍 허용 (`distributeEvent_BuyOrder_Success`)

### DTO 구현

- **record 우선**: 새 DTO는 Java record로 작성합니다. 빌더가 꼭 필요한 경우에만
  class + `@Builder`를 사용합니다.
- DTO 필드명 변경은 API 계약 변경입니다 — Jackson SNAKE_CASE 전역 설정 때문에
  Java 필드명이 그대로 API 응답 키가 됩니다. **클래스명 변경은 안전하지만 필드명
  변경은 프론트엔드와 조율 후에만** 진행합니다.

## 길이 가이드라인 (권장치 — 도구 강제 없음)

| 항목 | 권장 | 초과 시 |
|------|------|---------|
| 파일 길이 | 400줄 이내 | 클래스 책임 분리를 검토 (리뷰에서 논의) |
| 메서드 길이 | 50줄 이내 | private 메서드 추출을 검토 |
| 중첩 깊이 | 3단계 이내 | early return, 메서드 추출 검토 |

길이 자체보다 **복잡도와 책임 개수**가 본질입니다. 단순 매핑처럼 길지만 평이한
코드는 허용될 수 있지만, 짧아도 여러 책임이 섞인 메서드는 분리 대상입니다.

## 패키지 구조 (ArchUnit 검증)

```
domain/{도메인}/
  controller/  service/  repository/  dto/  entity/  ...
global/   # 횡단 관심사 (config, jwt, exception, websocket)
job/      # 배치
```

- 컨트롤러는 리포지토리를 직접 참조하지 않습니다 (반드시 서비스를 경유).
- `dto` 패키지의 최상위 클래스는 `Request`/`Response`/`Event`로 끝나야 합니다.

## Spring / JPA

- **의존성 주입**: 생성자 주입만 사용 (`@RequiredArgsConstructor`).
  `@Autowired` 필드 주입 금지.
- **엔티티**: `@Setter` 금지. 상태 변경은 의도가 드러나는 도메인 메서드로.
  `@NoArgsConstructor(access = PROTECTED)` + `@Builder` 조합을 따릅니다.
- **트랜잭션**: 조회 전용 메서드/서비스에는 `@Transactional(readOnly = true)`.
  클래스 레벨 `@Transactional`은 쓰기 로직이 지배적인 서비스에만.
- **로깅**: `@Slf4j` 사용. `System.out.println` 금지.

## 주석

- **주석도 코드입니다** — 유지보수 대상이므로 쓸데없는 주석은 달지 않습니다.
- 코드로 자명한 내용을 반복하는 주석(`// 총자산 계산`, `// 반환`, 단계 번호 `// 1.` 등) 금지.
- diff 흔적 주석(`// [추가]`, `// [수정됨]`, `// 버그 X 수정`, 삭제된 코드를 설명하는 묘비 주석 등) 금지 — 변경 이력은 git·PR·docs가 담당.
- 주석은 코드로 표현할 수 없는 제약·의도(왜 이렇게 했는가)만 기록합니다.

## 품질 도구

| 도구 | 역할 | 실행 |
|------|------|------|
| Checkstyle | 네이밍·import 규칙 (길이 규칙 없음) | `./gradlew checkstyleMain checkstyleTest` (build에 포함) |
| ArchUnit | 패키지 의존·DTO 네이밍 규칙 | `./gradlew test --tests "ArchitectureTest"` |
| JaCoCo | 커버리지 측정 (게이트 없음) | `./gradlew test jacocoTestReport` → `build/reports/jacoco/test/html/index.html` |

- Checkstyle baseline: `config/checkstyle/suppressions.xml` — 기존 위반 억제용.
  **새 항목을 추가하지 않는 것이 원칙**이며, 해당 코드를 정리하면 항목을 제거합니다.
- ArchUnit freeze store: `src/test/resources/archunit_store/` — 기존 위반이
  기록되어 있으며, 위반을 해소하면 store에서 자동 정리됩니다. **새 위반은
  테스트 실패**로 나타납니다.
