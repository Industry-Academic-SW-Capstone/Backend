// 스모크 — 전 경로가 도는지 30초로 확인한다.
//
// 두 용도가 있다.
//   1. 구성 변경 후 검증 — 인증·시딩·체결까지 실제로 이어지는지
//   2. 세션 워밍업 — 인스턴스를 켠 직후에는 shared_buffers·페이지 캐시·JIT이 모두 차갑다.
//      계획서 §6대로 워밍업 1회는 돌리고 그 결과는 버린다.
//
// 판정: execution_count > 0 이어야 한다. 0이면 시딩이 안 됐거나 가격 조건이 안 맞은 것이다.

import { login, defaultAccountId, seedOrderBook, injectExecution, appMetrics, reportAppMetrics } from './lib/common.js';

const STOCK = __ENV.STOCK_CODE || '005930';
const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

const SEED_COUNT = Number(__ENV.SEED_COUNT || 50);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 100);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 10);
const SEED_QTY = Number(__ENV.SEED_QTY || 1000);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 적재 자체가 느려지면 수신 경로에 문제가 있는 것이다.
    enqueue_ms: ['p(95)<1000'],
  },
  setupTimeout: '300s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  console.log(`시딩 계좌: account_id=${accountId}`);
  seedOrderBook(token, accountId, STOCK, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  return { token, before: appMetrics() };
}

export default function (data) {
  injectExecution(data.token, STOCK, EVENT_PRICE, EVENT_QTY);
}

// 체결이 한 건도 없으면 측정 자체가 성립하지 않는다. 워커가 큐를 비웠는지 여기서 확인한다.
export function teardown(data) {
  const consumed = reportAppMetrics(data.before);
  if (consumed === 0) {
    throw new Error('소비된 이벤트가 0건이다. 워커 기동과 시딩을 확인할 것');
  }
}
