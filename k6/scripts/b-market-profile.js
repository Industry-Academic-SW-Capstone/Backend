// 시나리오 B — 실제 시장 프로파일 (계획서 §5-B)
//
// 목적: 실운영 부하에서의 전체 처리량, 종목 간 병렬 효과.
// 락 키에 종목코드가 들어가므로 종목 간에는 이미 병렬이다.
// 단일 종목 TPS(A)가 낮아도 전체 처리량은 확보될 수 있다 — 그 차이를 재는 시나리오다.
//
// ★ STOCKS와 WEIGHTS는 계획서 §1의 KRX 집중도 분포로 채워야 한다.
//   지금 값은 자리표시자다. 목표선 없이 이 시나리오를 돌리면
//   "실제 시장 프로파일"이라 부를 근거가 없다.
//
// 부하 프로파일도 시간대별 분포를 반영한다 — 장 시작 스파이크 → 소강 → 마감 스파이크.

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote } from './lib/common.js';

const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

// TODO(§1): KRX 일일 체결 건수 상위 종목과 집중도로 교체
const STOCKS = (__ENV.STOCK_CODES || '005930,000660,035420,051910,005380').split(',');
const WEIGHTS = (__ENV.STOCK_WEIGHTS || '40,25,15,12,8').split(',').map(Number);

const SEED_COUNT = Number(__ENV.SEED_COUNT || 200);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 1000);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 50);
const SEED_QTY = Number(__ENV.SEED_QTY || 100);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

const PEAK = Number(__ENV.PEAK_RATE || 300);

const CUMULATIVE = (() => {
  const total = WEIGHTS.reduce((a, b) => a + b, 0);
  let acc = 0;
  return WEIGHTS.map((w) => (acc += w / total));
})();

function pickStock() {
  const r = Math.random();
  for (let i = 0; i < CUMULATIVE.length; i++) {
    if (r <= CUMULATIVE[i]) return STOCKS[i];
  }
  return STOCKS[STOCKS.length - 1];
}

export const options = {
  scenarios: {
    market: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 800,
      stages: [
        { duration: '3m', target: PEAK },              // 장 시작 스파이크
        { duration: '2m', target: Math.round(PEAK * 0.3) },
        { duration: '5m', target: Math.round(PEAK * 0.3) },  // 소강
        { duration: '2m', target: PEAK },              // 마감 스파이크
        { duration: '3m', target: Math.round(PEAK * 0.2) },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:mock-execution}': ['p(99)<5000'],
  },
  setupTimeout: '900s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  for (const stock of STOCKS) {
    seedOrderBook(token, accountId, stock, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  }
  return { token };
}

export default function (data) {
  injectExecution(data.token, pickStock(), EVENT_PRICE, EVENT_QTY);
}

export function teardown() {
  console.log(summaryNote('B 실제 시장 프로파일'));
}
