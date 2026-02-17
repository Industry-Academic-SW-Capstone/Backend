import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ───
const matchingSuccessRate = new Rate('matching_success_rate');
const matchedOrderCount = new Counter('matched_order_count');
const matchingDuration = new Trend('matching_duration', true);

// ─── 환경 변수 ───
const BASE_URL = __ENV.BASE_URL || 'http://backend:8080';
const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'test1234';
const STOCK_CODE = __ENV.STOCK_CODE || '005930';
const NUM_ACCOUNTS = parseInt(__ENV.NUM_ACCOUNTS || '10');
const ORDERS_PER_ACCOUNT = parseInt(__ENV.ORDERS_PER_ACCOUNT || '50');

// 주문 가격 범위
const BASE_PRICE = 70000;
const PRICE_MIN = BASE_PRICE - 500;
const PRICE_MAX = BASE_PRICE + 500;

// ─── 테스트 설정 ───
export const options = {
  setupTimeout: '600s',
  stages: [
    { duration: '30s', target: 10 },   // 워밍업
    { duration: '1m', target: 50 },    // 증가
    { duration: '1m', target: 100 },   // 최대 부하
    { duration: '30s', target: 0 },    // 종료
  ],
  thresholds: {
    'http_req_duration{name:matching}': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed{name:matching}': ['rate<0.05'],
    matching_success_rate: ['rate>0.95'],
  },
};

// ─── 유틸 ───
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function jsonPost(url, body, token, tags) {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
  if (tags) params.tags = tags;
  return http.post(url, JSON.stringify(body), params);
}

// ─── setup ───
export function setup() {
  console.log('=== 매칭 엔진 부하 테스트 (현실적 시나리오) setup 시작 ===');
  console.log(`계좌 수: ${NUM_ACCOUNTS}, 계좌당 주문: ${ORDERS_PER_ACCOUNT}`);
  console.log(`총 주문 수: ${NUM_ACCOUNTS * ORDERS_PER_ACCOUNT}`);

  // 헬스 체크
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  if (healthRes.status !== 200) {
    console.error('서버 헬스 체크 실패!');
    return null;
  }

  // ── 1. 계좌 생성 ──
  const accounts = [];

  for (let i = 0; i < NUM_ACCOUNTS; i++) {
    const email = `lt${i}_${Date.now() % 100000}@t.com`;

    // 회원가입
    const signupRes = http.post(
      `${BASE_URL}/api/members/signup`,
      JSON.stringify({ email: email, password: TEST_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup_signup' } }
    );

    if (signupRes.status !== 200 && signupRes.status !== 201) {
      console.error(`회원가입 실패 [${i}]: ${signupRes.status} - ${signupRes.body}`);
      continue;
    }

    // 로그인
    const loginRes = http.post(
      `${BASE_URL}/api/members/login`,
      JSON.stringify({ email: email, password: TEST_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup_login' } }
    );

    if (loginRes.status !== 200) {
      console.error(`로그인 실패 [${i}]: ${loginRes.status}`);
      continue;
    }

    const token = JSON.parse(loginRes.body).access_token;

    accounts.push({ email, token });
    console.log(`계좌 생성 [${i + 1}/${NUM_ACCOUNTS}]: ${email}`);
  }

  if (accounts.length === 0) {
    console.error('생성된 계좌 없음!');
    return null;
  }

  // ── 2. account_id 확보: /api/members/me/accounts API 호출 ──
  const validAccounts = [];

  for (let a = 0; a < accounts.length; a++) {
    const acc = accounts[a];
    const meRes = http.get(`${BASE_URL}/api/members/me/accounts`, {
      headers: { Authorization: `Bearer ${acc.token}` },
      tags: { name: 'setup_accounts' },
    });

    if (meRes.status !== 200) {
      console.error(`계좌 조회 실패 [${a}]: ${meRes.status}`);
      continue;
    }

    const accountList = JSON.parse(meRes.body);
    if (!accountList || accountList.length === 0) {
      console.error(`계좌 없음: ${acc.email}`);
      continue;
    }

    const accountId = accountList[0].account_id;
    validAccounts.push({ ...acc, accountId });
    console.log(`계좌 확보 [${a + 1}]: accountId=${accountId}`);
  }

  if (validAccounts.length === 0) {
    console.error('유효한 계좌 없음!');
    return null;
  }

  // ── 3. 계좌별 BUY 주문 생성 ──
  let totalSuccess = 0;
  let totalFail = 0;

  for (let a = 0; a < validAccounts.length; a++) {
    const acc = validAccounts[a];
    let accSuccess = 0;

    for (let i = 0; i < ORDERS_PER_ACCOUNT; i++) {
      const price = Math.round(PRICE_MIN + Math.random() * (PRICE_MAX - PRICE_MIN));
      const quantity = randomInt(1, 10);

      const res = jsonPost(
        `${BASE_URL}/api/orders/limit`,
        {
          account_id: acc.accountId,
          stock_code: STOCK_CODE,
          price: price,
          quantity: quantity,
          order_method: 'BUY',
        },
        acc.token,
        { name: 'setup_order' }
      );

      if (res.status === 200) {
        accSuccess++;
      } else {
        totalFail++;
        if (totalFail <= 3) {
          console.warn(`주문 실패 [계좌${a} 주문${i}]: ${res.status} - ${res.body}`);
        }
      }

      if ((i + 1) % 50 === 0) sleep(0.3);
    }

    totalSuccess += accSuccess;
    const accFail = ORDERS_PER_ACCOUNT - accSuccess;
    console.log(`  계좌 [${a + 1}/${validAccounts.length}] accountId=${acc.accountId}: 성공 ${accSuccess}건, 실패 ${accFail}건`);
  }

  console.log(`\n=== setup 완료 ===`);
  console.log(`  계좌: ${validAccounts.length}개`);
  console.log(`  주문: 성공 ${totalSuccess}건 / 실패 ${totalFail}건`);
  console.log(`==================\n`);

  return {
    token: validAccounts[0].token,
    totalOrders: totalSuccess,
    numAccounts: validAccounts.length,
  };
}

// ─── default: SELL 이벤트로 다중 매칭 유도 ───
export default function (data) {
  if (!data || !data.token) {
    console.error('setup 데이터 없음');
    return;
  }

  const price = Math.round(PRICE_MIN + Math.random() * (PRICE_MAX - PRICE_MIN));
  // 수량 10~50주: 여러 BUY 주문(1~10주)과 동시 매칭 → N+1, 락 경합 유도
  const quantity = randomInt(10, 50);

  const payload = JSON.stringify({
    stock_code: STOCK_CODE,
    event_id: generateUUID(),
    order_method: 'SELL',
    price: price,
    quantity: quantity,
    event_timestamp: Date.now(),
  });

  const res = http.post(`${BASE_URL}/api/test/mock-execution`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.token}`,
    },
    tags: { name: 'matching' },
  });

  const success = res.status === 200;
  matchingSuccessRate.add(success);

  if (success) {
    matchingDuration.add(res.timings.duration);
    try {
      const match = res.body.match(/(\d+)건/);
      if (match) matchedOrderCount.add(parseInt(match[1]));
    } catch (e) { /* ignore */ }
  }

  check(res, {
    '매칭 처리 성공': (r) => r.status === 200,
    '응답 시간 < 500ms': (r) => r.timings.duration < 500,
    '응답 시간 < 1s': (r) => r.timings.duration < 1000,
  });

  // 매칭 실패 로그는 샘플링 (1% 확률로만 출력)
  if (!success && Math.random() < 0.01) {
    console.error(`매칭 실패 (샘플): ${res.status} - ${res.body}`);
  }

  sleep(Math.random() * 0.3 + 0.1);
}

// ─── teardown ───
export function teardown(data) {
  if (data) {
    console.log('=== 매칭 엔진 부하 테스트 완료 ===');
    console.log(`계좌 수: ${data.numAccounts || 0}`);
    console.log(`setup 주문 수: ${data.totalOrders || 0}`);
  }
}
