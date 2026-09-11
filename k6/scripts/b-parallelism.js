// 시나리오 B — 종목 수 스윕 (계획서 §3 B트랙)
//
// 목적: 종목 병렬도가 올라갈 때 DB 자원 경합이 어디서 터지는지 본다.
//       단계 3(커넥션)·4(replica)·5(Redis)가 전부 이 축에서만 드러난다.
//
// 왜 종목 수가 변수인가
//   락 키에 종목코드가 들어가므로 종목 간에는 이미 병렬이다. 단일 종목에서는
//   직렬화가 병목이라 DB가 놀고 있어 저장소를 바꿔도 상한이 안 움직인다.
//   종목이 늘어야 DB가 공유 자원이 되고, 그때 R0와 R3가 갈라진다.
//
// 실행 (같은 arm에서 N을 바꿔가며 반복)
//   ./benchmark/prepare-stocks.sh 50
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=1
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=5
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=20
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=50
//
// 판정: N별 포화 TPS를 R0와 R3에 대해 각각 기록한다.
//       두 곡선이 갈라지기 시작하는 N이 "Redis가 의미를 갖기 시작하는 지점"이다.
//
// 병목 구분 (§3 단계 3)
//   hikaricp_connections_pending > 0        → 커넥션 풀이 병목
//   pending 0 인데 RDS CPUUtilization 높음   → DB CPU가 병목 (진짜 오프로딩 효과)

import { login, defaultAccountId, seedOrderBook, injectExecution, summaryNote } from './lib/common.js';

// prepare-stocks.sh 가 만든 목록. SQL과 스크립트의 종목이 어긋나지 않게 파일로 공유한다.
const ALL_STOCKS = JSON.parse(open('./data/stocks.json'));

const STOCK_COUNT = Number(__ENV.STOCK_COUNT || 5);
const STOCKS = ALL_STOCKS.slice(0, STOCK_COUNT);

const EMAIL = __ENV.LOAD_EMAIL || 'loadtest@stockit.local';
const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

// 종목당 시딩 주문 수. 종목이 늘면 setup 시간도 늘어난다(주문 1건당 HTTP 1회).
const SEED_COUNT = Number(__ENV.SEED_COUNT || 200);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 100);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 50);
const SEED_QTY = Number(__ENV.SEED_QTY || 1000);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

const PEAK = Number(__ENV.PEAK_RATE || 800);

export const options = {
  scenarios: {
    parallelism: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1500,
      // 각 계단 2분 이상. 30초는 워밍업·JIT·GC 때문에 신뢰할 수 없다.
      stages: [
        { duration: '2m', target: Math.round(PEAK * 0.1) },
        { duration: '2m', target: Math.round(PEAK * 0.25) },
        { duration: '2m', target: Math.round(PEAK * 0.5) },
        { duration: '2m', target: Math.round(PEAK * 0.75) },
        { duration: '2m', target: PEAK },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  setupTimeout: '1800s',
};

export function setup() {
  const token = login(EMAIL, PASSWORD);
  const accountId = defaultAccountId(token);
  console.log(`종목 ${STOCKS.length}개, 종목당 ${SEED_COUNT}건 시딩`);
  for (const stock of STOCKS) {
    seedOrderBook(token, accountId, stock, { count: SEED_COUNT, price: SEED_PRICE, quantity: SEED_QTY, priceLevels: SEED_LEVELS });
  }
  return { token, stocks: STOCKS };
}

export default function (data) {
  // 균등 분배. 종목 간 병렬 효과만 보려면 가중치를 넣지 않는다
  // (거래대금 집중도 반영은 목표선이 나온 뒤 b-market-profile.js에서 한다).
  const stock = data.stocks[Math.floor(Math.random() * data.stocks.length)];
  injectExecution(data.token, stock, EVENT_PRICE, EVENT_QTY);
}

export function teardown() {
  console.log(summaryNote(`B 종목 수 스윕 (N=${STOCK_COUNT})`));
}
