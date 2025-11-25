# Company Describe API 명세서

## 1. 기업 설명 생성

기업명을 받아 Gemini LLM을 활용하여 두 문장 요약을 생성합니다. Redis 캐시를 사용하여 API 호출을 최소화합니다 (TTL: 3시간).

### 엔드포인트

```
POST /api/company/describe
```

### Request Headers

- `Content-Type`: `application/json`
- `Authorization`: `Bearer {AccessToken}` (선택사항 - 현재 모든 요청 허용)

### Request Parameters

- 없음 (POST 요청)

### Request Body

```json
{
  "company_name": "삼성전자"
}
```

**필드 설명:**

| 필드명         | 타입   | 필수 | 설명            | 예시       |
| -------------- | ------ | ---- | --------------- | ---------- |
| `company_name` | String | 필수 | 기업명 (한글명) | "삼성전자" |

### Response Body

**성공 응답 (200 OK):**

```json
{
  "company_name": "삼성전자",
  "description": "삼성전자(Samsung Electronics)는 메모리 반도체, 스마트폰, TV, 가전 등 광범위한 분야의 전자제품을 생산하는 세계적인 종합 전자 기업입니다. 특히 메모리 반도체와 스마트폰 시장에서 압도적인 글로벌 선두 지위를 유지하며 기술 혁신을 선도하고 있습니다.",
  "cached": false
}
```

**필드 설명:**

| 필드명         | 타입    | 설명                                       | 예시                                 |
| -------------- | ------- | ------------------------------------------ | ------------------------------------ |
| `company_name` | String  | 요청한 기업명                              | "삼성전자"                           |
| `description`  | String  | Gemini LLM이 생성한 기업 설명 (2문장 이내) | "삼성전자는..."                      |
| `cached`       | Boolean | Redis 캐시 히트 여부 (TTL: 3시간)          | `false`: API 호출, `true`: 캐시 조회 |

### 성능 특성

- **캐시 미스 (첫 요청)**: 평균 3.6초 소요 (Gemini API 호출)
- **캐시 히트 (재요청)**: 평균 0.5ms 소요 (Redis 조회)
- **성능 개선**: 99.98% 개선 (3.6초 → 0.5ms)

### 에러 응답

**400 Bad Request:**

```json
{
  "error": "기업명은 필수입니다"
}
```

**500 Internal Server Error:**

```json
{
  "error": "기업 설명 생성에 실패했습니다: {에러 메시지}"
}
```

---

## 2. 캐시 성능 비교 테스트

캐시 없이 조회한 경우와 캐시로 조회한 경우의 성능을 비교합니다. 먼저 캐시를 삭제하고 API를 호출하여 시간을 측정한 후, 같은 요청을 다시 호출하여 캐시로 조회한 시간을 측정합니다.

### 엔드포인트

```
POST /api/company/describe/cache-performance
```

### Request Headers

- `Content-Type`: `application/json`
- `Authorization`: `Bearer {AccessToken}` (선택사항)

### Request Parameters

- 없음 (POST 요청)

### Request Body

```json
{
  "company_name": "삼성전자"
}
```

**필드 설명:**

| 필드명         | 타입   | 필수 | 설명            | 예시       |
| -------------- | ------ | ---- | --------------- | ---------- |
| `company_name` | String | 필수 | 기업명 (한글명) | "삼성전자" |

### Response Body

**성공 응답 (200 OK):**

```json
{
  "company_name": "삼성전자",
  "without_cache": {
    "duration_ms": 3757,
    "description": "삼성전자(Samsung Electronics)는 메모리 반도체, 스마트폰, TV, 가전 등 광범위한 분야의 전자제품을 생산하는 세계적인 종합 전자 기업입니다. 특히 메모리 반도체와 스마트폰 시장에서 압도적인 글로벌 선두 지위를 유지하며 기술 혁신을 선도하고 있습니다.",
    "cached": false
  },
  "with_cache": {
    "duration_ms": 0,
    "description": "삼성전자(Samsung Electronics)는 메모리 반도체, 스마트폰, TV, 가전 등 광범위한 분야의 전자제품을 생산하는 세계적인 종합 전자 기업입니다. 특히 메모리 반도체와 스마트폰 시장에서 압도적인 글로벌 선두 지위를 유지하며 기술 혁신을 선도하고 있습니다.",
    "cached": true
  },
  "time_saved": 3757,
  "time_saved_description": "원래라면 똑같은걸 검색하면 이렇게 3초 걸리던걸 캐시 걸려서 0초 걸렸다. 3초를 단축할 수 있었다."
}
```

**필드 설명:**

| 필드명                      | 타입    | 설명                                  |
| --------------------------- | ------- | ------------------------------------- |
| `company_name`              | String  | 요청한 기업명                         |
| `without_cache`             | Object  | 캐시 없이 조회한 경우의 성능 결과     |
| `without_cache.duration_ms` | Long    | 소요 시간 (밀리초)                    |
| `without_cache.description` | String  | 기업 설명                             |
| `without_cache.cached`      | Boolean | 캐시 여부 (항상 `false`)              |
| `with_cache`                | Object  | 캐시로 조회한 경우의 성능 결과        |
| `with_cache.duration_ms`    | Long    | 소요 시간 (밀리초)                    |
| `with_cache.description`    | String  | 기업 설명                             |
| `with_cache.cached`         | Boolean | 캐시 여부 (항상 `true`)               |
| `time_saved`                | Long    | 캐시로 인해 절약된 시간 (밀리초)      |
| `time_saved_description`    | String  | 시간 절약 설명 (사용자 친화적 메시지) |

### 실제 측정 결과 (프로덕션 환경)

**테스트 환경**: `api.stockit.live`  
**측정 일시**: 2025년 11월 25일

| 기업명     | 캐시 없음 | 캐시 있음 | 절약 시간 |
| ---------- | --------- | --------- | --------- |
| 삼성전자   | 3,757ms   | 0ms       | 3,757ms   |
| SK하이닉스 | 2,191ms   | 1ms       | 2,190ms   |
| NAVER      | 6,652ms   | 0ms       | 6,652ms   |
| 카카오     | 3,464ms   | 1ms       | 3,463ms   |
| 현대자동차 | 3,781ms   | 1ms       | 3,780ms   |

**통계:**

- 캐시 없음 평균: 3,600ms (3.6초)
- 캐시 있음 평균: 0.5ms
- 성능 개선: **99.98%**
- 처리량 증가: **약 7,200배**

---

## 3. 사용 예시

### cURL 예시

**기업 설명 생성:**

```bash
curl -X POST "https://api.stockit.live/api/company/describe" \
  -H "Content-Type: application/json" \
  -d '{
    "company_name": "삼성전자"
  }'
```

**캐시 성능 비교:**

```bash
curl -X POST "https://api.stockit.live/api/company/describe/cache-performance" \
  -H "Content-Type: application/json" \
  -d '{
    "company_name": "삼성전자"
  }'
```

### JavaScript 예시

```javascript
// 기업 설명 생성
const response = await fetch("https://api.stockit.live/api/company/describe", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    company_name: "삼성전자",
  }),
});

const data = await response.json();
console.log(data.description); // 기업 설명
console.log(data.cached); // 캐시 여부
```

---

## 4. 기술 사양

- **캐시 전략**: Write-Through 패턴
- **캐시 저장소**: Redis (인메모리)
- **TTL**: 3시간 (10,800초)
- **캐시 키 패턴**: `company_desc:{기업명}`
- **LLM 모델**: Google Gemini 2.5 Flash
- **응답 형식**: JSON (snake_case)

---

## 5. 주의사항

- 캐시는 3시간 후 자동 만료됩니다.
- 동일한 기업명에 대한 재요청 시 캐시에서 조회됩니다.
- 캐시 실패 시에도 Gemini API를 호출하여 응답합니다 (폴백 처리).
