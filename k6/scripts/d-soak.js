// 시나리오 D — 지속 부하 soak (계획서 §5-D)
//
// 목적: 시간이 지나야 드러나는 문제. 시나리오 B의 60~70% 강도로 최소 2시간.
//
// 관측 대상은 처리량이 아니라 추세다.
//   dead tuple / autovacuum      RDB 백엔드 비용 판단
//   큐 누적                       처리 능력 초과 여부
//   커넥션 누수                    hikaricp_connections_active
//   GC 정지 ↔ p99 상관             단계 4(GC 대응) 진입 근거
//   힙 증가                        누수
//
// ★ 버스터블 인스턴스에서 돌리면 안 된다. CPU 크레딧이 소진되면 처리량이 서서히
//   무너지는데, 그 그래프가 "시간이 갈수록 성능 저하"로 읽혀 메모리 누수나
//   GC 열화로 오진하게 된다. 앱·RDS는 고정 성능(m7i/db.m7g)이어야 한다.
//
// ★ 시딩 소진 주의. 2시간 × rate × EVENT_QTY 만큼의 수량이 오더북에 있어야 한다.
//   부족하면 후반부 체결이 0건이 되고 그 구간이 통째로 무효다.

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote, appMetrics, reportAppMetrics } from './lib/common.js';

const STOCK = __ENV.STOCK_CODE || '005930';
const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

const SOAK_RATE = Number(__ENV.SOAK_RATE || 100);      // ← B 강도의 60~70%
const SOAK_DURATION = __ENV.SOAK_DURATION || '2h';

const SEED_COUNT = Number(__ENV.SEED_COUNT || 5000);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 1000);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 100);
const SEED_QTY = Number(__ENV.SEED_QTY || 100);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: SOAK_RATE,
      timeUnit: '1s',
      duration: SOAK_DURATION,
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  setupTimeout: '900s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  const needed = SOAK_RATE * EVENT_QTY * 7200;
  const seeded = SEED_COUNT * SEED_QTY;
  if (seeded < needed) {
    console.warn(`시딩 수량 부족 경고: 심은 ${seeded} < 필요 ${needed}. 후반부 체결이 0건이 될 수 있다`);
  }
  seedOrderBook(token, accountId, STOCK, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  return { token, before: appMetrics() };
}

export default function (data) {
  injectExecution(data.token, STOCK, EVENT_PRICE, EVENT_QTY);
}

export function teardown(data) {
  reportAppMetrics(data.before);
  console.log(summaryNote('D 지속 부하'));
}
