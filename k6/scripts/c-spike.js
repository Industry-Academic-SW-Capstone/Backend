// 시나리오 C — 급등주 스파이크 (계획서 §5-C) ★ 핵심
//
// 목적: 종목별 직렬화의 한계 노출. 구조 변경 근거의 핵심이다.
// 상한가 근처 종목에 체결이 몰리는 건 실제로 벌어지는 일이라 "왜 이 시나리오냐"에 답이 있다.
//
// A에서 찾은 포화점의 배수(SPIKE_RATE)를 constant-arrival-rate로 5분 인가한다.
// 판정: queue_depth가 시간에 따라 단조 증가하면 처리 능력 초과.
//
// Redis 오더북의 시간 우선 결함도 여기서 드러난다 — 같은 가격 후보가
// fetchSize(100)를 넘으면 ZSet이 orderId를 사전순 정렬해 먼저 접수된 주문이 잘린다.
// 그래서 SEED_COUNT를 100보다 크게 잡는다.

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote } from './lib/common.js';

const STOCK = __ENV.STOCK_CODE || '005930';
const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 300);   // ← A의 포화점 × 배수로 설정
const SPIKE_DURATION = __ENV.SPIKE_DURATION || '5m';

const SEED_COUNT = Number(__ENV.SEED_COUNT || 10000);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 1000);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 100);
const SEED_QTY = Number(__ENV.SEED_QTY || 100);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

export const options = {
  scenarios: {
    spike: {
      executor: 'constant-arrival-rate',
      rate: SPIKE_RATE,
      timeUnit: '1s',
      duration: SPIKE_DURATION,
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
  setupTimeout: '600s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  seedOrderBook(token, accountId, STOCK, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  return { token };
}

export default function (data) {
  injectExecution(data.token, STOCK, EVENT_PRICE, EVENT_QTY);
}

export function teardown() {
  console.log(summaryNote('C 급등주 스파이크'));
}
