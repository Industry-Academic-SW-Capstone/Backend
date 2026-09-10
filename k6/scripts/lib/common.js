// 시나리오 A~D가 공유하는 인증·시딩·지표 코드.
//
// 부하 진입점은 POST /api/test/mock-execution 하나다. KIS 실시간 피드와 같은 메서드
// (LimitOrderEventPublisher#publish)를 타므로 시세 갱신 → 큐 적재 → 종목별 락 →
// 큐 소비 → 정산 경로가 그대로 실행된다.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ── 커스텀 지표 ─────────────────────────────────────────────────
// http_reqs만 보면 "TPS는 높은데 체결은 안 되는" 상태를 놓친다.
// consumeNextEvent는 락 획득에 실패하면 이벤트를 큐에 남긴 채 빈 리스트를 반환하고
// 호출자는 200 OK를 받기 때문이다(계획서 §6).
export const executions = new Counter('execution_count');       // 실제 체결 건수 누적
export const queueDepth = new Trend('queue_depth');             // 처리 후 남은 큐 길이 ★ 포화 판정
export const matchDuration = new Trend('match_duration_ms');    // 큐 적재~정산 완료
export const zeroExecution = new Rate('zero_execution_rate');   // 체결 0건 응답 비율

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
// 이벤트를 SELL로 보내면 매수 후보(price >= 체결가)를 훑으므로 BUY 주문을 심는다.
// 주문 생성은 OrderBookRegistrationService가 orderBookStore.addOrder를 호출하므로
// redis·jpa 백엔드 양쪽에 모두 반영된다. SQL로 직접 넣으면 Redis ZSet에는 안 들어간다
// (스케줄러를 껐으므로 RedisDBSyncService의 복구도 돌지 않는다).
//
// ★ 소진 방지: 이벤트 수량 × 지속시간보다 심은 총 수량이 커야 한다.
//   부족하면 중간부터 체결이 0건이 되고 그 구간 수치가 통째로 무효다.
//   첫 실행 후 execution_count와 zero_execution_rate로 반드시 확인할 것.
export function seedOrderBook(token, accountId, stockCode, opts) {
  const count = opts.count;
  const price = opts.price;
  const quantity = opts.quantity;
  const headers = authHeaders(token);

  let placed = 0;
  for (let i = 0; i < count; i++) {
    const res = http.post(
      `${BASE_URL}/api/orders/limit`,
      JSON.stringify({
        account_id: accountId,
        stock_code: stockCode,
        price: price,
        quantity: quantity,
        order_method: 'BUY',
      }),
      { headers },
    );
    if (res.status === 200 || res.status === 201) {
      placed++;
    } else if (i === 0) {
      // 첫 주문이 실패하면 나머지도 실패한다. 잔고 부족·종목 미적재가 대부분이다.
      fail(`시딩 주문 실패 (${res.status}): ${res.body}`);
    }
  }
  console.log(`오더북 시딩: ${placed}/${count}건 (종목=${stockCode} 가격=${price} 수량=${quantity})`);
  if (placed === 0) {
    fail('시딩된 주문이 0건이다. 종목 마스터 적재와 계좌 잔고를 확인할 것');
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
      order_method: 'SELL',        // 매수 후보(BUY)를 훑는다
      price: price,
      quantity: quantity,
      event_timestamp: Date.now(),
    }),
    { headers: authHeaders(token), tags: { name: 'mock-execution' } },
  );

  check(res, { 'status 200': (r) => r.status === 200 });

  if (res.status === 200) {
    const count = res.json('execution_count');
    const depth = res.json('queue_depth');
    const ms = res.json('duration_ms');
    executions.add(count);
    queueDepth.add(depth);
    matchDuration.add(ms);
    zeroExecution.add(count === 0);
  }
  return res;
}

// 계획서 §10에 옮겨 적을 값을 실행 끝에 남긴다.
export function summaryNote(scenario) {
  return `
[${scenario}] 기록할 것
  - execution_count 합계 / http_reqs — 요청 대비 실제 체결 비율
  - queue_depth 추이 — 단조 증가 시작 지점이 포화점
  - zero_execution_rate — 높으면 락 경합으로 밀리는 중
  - dropped_iterations — 0이 아니면 부하 생성기가 목표 rate를 못 따라간 것
  - 앱 서버 node-exporter CPU 여유 — 생성기가 병목이 아니었음의 근거
`;
}
