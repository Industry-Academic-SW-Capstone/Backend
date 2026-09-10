// 시나리오 A — 한계점 탐색 (계획서 §5-A)
//
// 목적: 각 증분 구성(R0-/R0/R1/R2/R3)의 종목당 TPS 상한 확정. 나머지 모든 논의의 기준선.
// 종목: 단일 — 종목별 직렬화 구간을 격리한다. 락 키에 종목코드가 들어가므로
//       여러 종목을 섞으면 병렬 효과에 가려 직렬화 한계가 안 보인다.
//
// 개방형(ramping-arrival-rate)을 쓴다. 폐쇄형(stages+VU)은 시스템이 느려지면
// 요청 속도가 같이 떨어져 큐가 밀리는 현상을 관측할 수 없다.
//
// 판정: 처리량이 평탄해지고 queue_depth가 단조 증가하기 시작하는 지점 = 포화점

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote } from './lib/common.js';

const STOCK = __ENV.STOCK_CODE || '005930';
const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

const SEED_COUNT = Number(__ENV.SEED_COUNT || 300);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 1000);
const SEED_QTY = Number(__ENV.SEED_QTY || 100);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 1000);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

export const options = {
  scenarios: {
    saturation: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      // 각 계단 2분 이상. 30초는 워밍업·JIT·GC 때문에 신뢰할 수 없다.
      stages: [
        { duration: '2m', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '2m', target: 200 },
        { duration: '2m', target: 400 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 임계치 위반이 목적이 아니라 관측이 목적이므로 abortOnFail은 쓰지 않는다.
    'http_req_duration{name:mock-execution}': ['p(99)<5000'],
  },
  setupTimeout: '600s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  seedOrderBook(token, accountId, STOCK, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY });
  return { token };
}

export default function (data) {
  injectExecution(data.token, STOCK, EVENT_PRICE, EVENT_QTY);
}

export function teardown() {
  console.log(summaryNote('A 한계점 탐색'));
}
