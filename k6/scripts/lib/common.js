// 시나리오 A~D가 공유하는 인증·시딩·지표 코드.
//
// 부하 진입점은 POST /api/test/mock-execution 하나다. KIS 실시간 피드와 같은 메서드
// (LimitOrderEventPublisher#publish)를 타므로 시세 갱신 → 큐 적재 → 종목별 락 →
// 큐 소비 → 정산 경로가 그대로 실행된다.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ── 커스텀 지표 ─────────────────────────────────────────────────
// 소비를 워커가 전담하므로 응답에는 체결 결과가 없다. 요청이 아는 건 적재까지다.
//
//   queue_depth   적재 후 큐 길이 — 포화 판정의 핵심
//   enqueue_ms    수신 경로 비용 — 밀려도 오르지 않는다(오르면 적재 자체가 느린 것)
//
// 체결 건수와 체결 지연은 앱 지표에서 읽는다(setup/teardown의 appMetrics).
//
//   matching_executions_total         체결된 주문 수
//   matching_event_latency_seconds    이벤트 도착 -> 체결 완료  ★ 거래소 관점의 지연
export const queueDepth = new Trend('queue_depth');
export const enqueueMs = new Trend('enqueue_ms');

// ── 인증 ────────────────────────────────────────────────────────
// /api/test/** 는 SecurityConfig의 anyRequest().authenticated() 대상이라 JWT가 필요하다.
export function login(email, password) {
  const body = JSON.stringify({ email, password });
  const headers = { 'Content-Type': 'application/json' };

  // 이미 가입돼 있으면 400이 오는데 정상 흐름이다.
  http.post(`${BASE_URL}/api/members/signup`, body, { headers });

  const res = http.post(`${BASE_URL}/api/members/login`, body, { headers });
  if (res.status !== 200) {
    fail(`로그인 실패 (${res.status}): ${res.body}`);
  }
  const token = res.json('access_token');
  if (!token) {
    fail(`access_token 없음: ${res.body}`);
  }
  return token;
}

export function authHeaders(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export function defaultAccountId(token) {
  const res = http.get(`${BASE_URL}/api/members/me/accounts`, { headers: authHeaders(token) });
  if (res.status !== 200) {
    fail(`계좌 조회 실패 (${res.status}): ${res.body}`);
  }
  const accounts = res.json();
  if (!accounts || accounts.length === 0) {
    fail('계좌가 없다. 회원 가입 시 기본 계좌가 생성되는지 확인할 것');
  }
  return accounts[0].account_id;
}

// ── 오더북 시딩 ──────────────────────────────────────────────────
// 반대 방향 주문이 없으면 체결이 일어나지 않아 측정이 무의미해진다(계획서 §5-A).
//
// ★ 매도(SELL)로 심는 이유. 매수 주문은 OrderService가 validateStockTradeable을 호출해
//   외부 Python 분석 서버(PYTHON_ANALYSIS_URL)에 동기 요청을 보낸다. 그 서버가 없으면
//   UntradeableStockException으로 400이 떨어진다. 매도 경로에는 이 검증이 없다.
//   측정 대상은 mock-execution 경로이고 주문 생성은 시딩에만 쓰이므로, 검증을 타지 않는
//   방향을 고르는 것이 분석 서버를 띄우는 것보다 변수가 적다.
//
// 전제: 계좌에 해당 종목 보유분이 있어야 한다(applySellHold가 account_stock을 요구).
//   INSERT INTO account_stock (account_id, stock_code, quantity, hold_quantity, average_price, created_at, updated_at)
//   VALUES (1, '005930', 1000000000, 0, 100, now(), now());
//
// 매수 이벤트(taker BUY)는 매도 후보를 price <= 체결가 조건으로 훑으므로
// EVENT_PRICE >= (SEED_PRICE + priceLevels - 1) 이어야 전 구간이 매칭 대상이 된다.
//
// ★ 가격을 분산하는 이유. 전부 같은 가격이면 정렬할 대상이 없어 인덱스의 price 컬럼이
//   무의미해진다. 블로그 1편의 주장("정렬 후 조회가 매 체결마다 실행된다")은 오더북이
//   여러 호가에 깊게 쌓였을 때만 성립하므로, 그 조건을 만들어야 검증이 된다.
//
// ★ 깊이도 중요하다. PostgreSQL에게 수백 행은 Seq Scan이 0.1ms라 인덱스를 걸어도
//   옵티마이저가 쓰지 않는다. R0-와 R0가 같게 나오면 데이터가 작아서지 인덱스가
//   무의미해서가 아니다(계획서 §4).
//
// API로 심는 이유: 주문 생성은 OrderBookRegistrationService가 orderBookStore.addOrder를
// 호출해 redis·jpa 양쪽에 반영된다. SQL로 직접 넣으면 Redis ZSet에는 안 들어간다
// (스케줄러를 껐으므로 RedisDBSyncService의 복구도 돌지 않는다).
//
// ★ 소진 방지: 이벤트 수량 × 지속시간보다 심은 총 수량이 커야 한다.
//   부족하면 중간부터 체결이 0건이 되고 그 구간 수치가 통째로 무효다.
//   첫 실행 후 execution_count와 zero_execution_rate로 반드시 확인할 것.
export function seedOrderBook(token, accountId, stockCode, opts) {
  const count = opts.count;
  const basePrice = opts.price;
  const quantity = opts.quantity;
  const levels = opts.priceLevels || 1;      // 가격 호가 수
  const batchSize = opts.batchSize || 10;    // 동시 요청 수
  const headers = authHeaders(token);
  const url = `${BASE_URL}/api/orders/limit`;

  let placed = 0;
  let firstError = null;

  for (let i = 0; i < count; i += batchSize) {
    const requests = [];
    for (let j = 0; j < batchSize && i + j < count; j++) {
      requests.push({
        method: 'POST',
        url: url,
        // 가격을 여러 호가에 분산한다. 전부 같은 가격이면 정렬할 게 없어
        // 인덱스의 price 컬럼이 의미를 갖지 못한다.
        body: JSON.stringify({
          account_id: accountId,
          stock_code: stockCode,
          price: basePrice + ((i + j) % levels),
          quantity: quantity,
          order_method: 'SELL',
        }),
        params: { headers },
      });
    }

    const responses = http.batch(requests);
    for (const res of responses) {
      if (res.status === 200 || res.status === 201) {
        placed++;
      } else if (firstError === null) {
        firstError = `${res.status}: ${res.body}`;
      }
    }

    if (placed > 0 && placed % 2000 === 0) {
      console.log(`  시딩 진행: ${placed}/${count}`);
    }
  }

  console.log(
    `오더북 시딩: ${placed}/${count}건 ` +
      `(종목=${stockCode} 가격=${basePrice}~${basePrice + levels - 1} 수량=${quantity})`,
  );
  if (placed === 0) {
    fail(`시딩된 주문이 0건이다 (${firstError})\n보유분(account_stock)과 종목 적재를 확인할 것`);
  }
  return placed;
}

// ── 부하 ────────────────────────────────────────────────────────
let seq = 0;

export function injectExecution(token, stockCode, price, quantity) {
  seq += 1;
  const res = http.post(
    `${BASE_URL}/api/test/mock-execution`,
    JSON.stringify({
      stock_code: stockCode,
      event_id: `k6-${__VU}-${seq}`,
      order_method: 'BUY',         // 매도 후보(SELL)를 price <= 체결가로 훑는다
      price: price,
      quantity: quantity,
      event_timestamp: Date.now(),
    }),
    { headers: authHeaders(token), tags: { name: 'mock-execution' } },
  );

  check(res, { 'status 200': (r) => r.status === 200 });

  if (res.status === 200) {
    queueDepth.add(res.json('queue_depth'));
    enqueueMs.add(res.json('enqueue_ms'));
  }
  return res;
}

// ── 앱 지표 ─────────────────────────────────────────────────────
// /actuator/** 는 무인증으로 열려 있다(프로메테우스 스크레이프용).
export function appMetrics() {
  const res = http.get(`${BASE_URL}/actuator/prometheus`);
  if (res.status !== 200) {
    console.warn(`앱 지표 조회 실패 (${res.status})`);
    return null;
  }
  return {
    executions: sumMetric(res.body, 'matching_executions_total'),
    latencyCount: sumMetric(res.body, 'matching_event_latency_seconds_count'),
    latencySum: sumMetric(res.body, 'matching_event_latency_seconds_sum'),
  };
}

function sumMetric(body, name) {
  let total = 0;
  for (const line of body.split('\n')) {
    if (line.startsWith(name)) {
      const value = Number(line.trim().split(/\s+/).pop());
      if (!Number.isNaN(value)) {
        total += value;
      }
    }
  }
  return total;
}

/** setup에서 받은 기준값과 비교해 이번 실행의 체결 결과를 출력한다. */
export function reportAppMetrics(before) {
  const after = appMetrics();
  if (!before || !after) {
    return 0;
  }
  const executions = after.executions - before.executions;
  const consumed = after.latencyCount - before.latencyCount;
  const latencySec = after.latencySum - before.latencySum;
  const avgMs = consumed > 0 ? (latencySec / consumed) * 1000 : 0;
  console.log(
    `체결 ${executions}건 · 이벤트 소비 ${consumed}건 · 평균 체결 지연 ${avgMs.toFixed(1)}ms`,
  );
  return consumed;
}

// 계획서 §10에 옮겨 적을 값을 실행 끝에 남긴다.
export function summaryNote(scenario) {
  return `
[${scenario}] 기록할 것
  - queue_depth 추이 — 단조 증가 시작 지점이 포화점
  - 체결 건수 / 이벤트 소비 수 — 유입 대비 처리 비율
  - 평균 체결 지연 — 밀리면 이 값이 오른다 (enqueue_ms 는 안 오른다)
  - dropped_iterations — 0이 아니면 부하 생성기가 목표 rate를 못 따라간 것
  - 앱 서버 node-exporter CPU 여유 — 생성기가 병목이 아니었음의 근거
`;
}
