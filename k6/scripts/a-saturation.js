// 시나리오 A — 한계점 탐색 (계획서 §3 A트랙, §5-A)
//
// 목적: 사다리 0·1·2의 효과를 잰다. 임계 구역 길이가 곧 종목당 TPS 상한이므로,
//       인덱스·쿼리 튜닝의 효과가 여기서만 드러난다.
//
// 종목: 단일 — 락 키에 종목코드가 들어가 종목 간에는 병렬이다. 여러 종목을 섞으면
//       병렬 효과에 가려 직렬화 한계가 안 보인다.
//
// 개방형(ramping-arrival-rate)을 쓴다. 폐쇄형(stages + VU)은 시스템이 느려지면
// 요청 속도가 같이 떨어져 큐가 밀리는 현상을 관측할 수 없다.
//
// 판정: 처리량이 평탄해지고 queue_depth가 단조 증가하기 시작하는 지점 = 포화점

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote, appMetrics, reportAppMetrics } from './lib/common.js';

const STOCK = __ENV.STOCK_CODE || '005930';
const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

const SEED_COUNT = Number(__ENV.SEED_COUNT || 10000);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 100);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 100);
const SEED_QTY = Number(__ENV.SEED_QTY || 1000);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

// 계단을 예상 포화점 주변에 모은다.
//
// 기존 25/50/100/200/400은 1·2·4·8·16배라 너무 넓었다. 스모크에서 임계 구역이
// 20ms로 나와 포화점이 50 TPS 근처로 예상되는데, 그 계단으로는 첫 두 칸 사이에서
// 이미 넘어가 "50~100 어딘가"까지밖에 말할 수 없다.
//
// 0.4/0.7/1.0/1.4/2.0배면 계단 간격이 약 1.4배씩이라 포화점을 ±20% 안으로 좁힌다.
// 5칸 10분 — arm 수 × 반복 횟수로 곱해지는 시간을 감안한 타협점이다.
//
// STAGE_BASE는 arm마다 다르게 준다. 첫 회차에서 대략을 보고 다음 회차 기준값을 정한다.
//   --env STAGE_BASE=50    R3 예상치
//   --env STAGE_BASE=15    R0-(인덱스 없음)가 느리면
const STAGE_BASE = Number(__ENV.STAGE_BASE || 50);
const STAGE_DURATION = __ENV.STAGE_DURATION || '2m';
const MULTIPLIERS = [0.4, 0.7, 1.0, 1.4, 2.0];

export const options = {
  scenarios: {
    saturation: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(STAGE_BASE * 0.2)),
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      // 각 계단을 2분 이상 유지해 안정 상태를 관측한다.
      // 30초는 워밍업·JIT·GC 때문에 신뢰할 수 없다.
      stages: MULTIPLIERS.map((m) => ({
        duration: STAGE_DURATION,
        target: Math.max(1, Math.round(STAGE_BASE * m)),
      })),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 임계치 위반이 목적이 아니라 관측이 목적이므로 abortOnFail은 쓰지 않는다.
    'http_req_duration{name:mock-execution}': ['p(99)<5000'],
  },
  setupTimeout: '900s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  console.log(`계단: ${MULTIPLIERS.map((m) => Math.round(STAGE_BASE * m)).join(' -> ')} (STAGE_BASE=${STAGE_BASE})`);
  seedOrderBook(token, accountId, STOCK, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  return { token, before: appMetrics() };
}

export default function (data) {
  injectExecution(data.token, STOCK, EVENT_PRICE, EVENT_QTY);
}

export function teardown(data) {
  reportAppMetrics(data.before);
  console.log(summaryNote(`A 한계점 탐색 (STAGE_BASE=${STAGE_BASE})`));
}
