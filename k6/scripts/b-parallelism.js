// 시나리오 B — 종목 수 스윕 (계획서 §3 B트랙)
//
// 목적: 종목 병렬도가 올라갈 때 DB 자원 경합이 어디서 터지는지 본다.
//       단계 3(커넥션)·4(replica)·5(Redis)가 전부 이 축에서만 드러난다.
//
// 왜 종목 수가 변수인가
//   락 키에 종목코드가 들어가므로 종목 간에는 이미 병렬이다. 단일 종목에서는
//   직렬화가 병목이라 DB가 놀고 있어 저장소를 바꿔도 상한이 안 움직인다.
//   종목이 늘어야 DB가 공유 자원이 되고, 그때 저장소 차이가 드러난다.
//
// ★ 왜 계좌 수도 변수인가
//   정산은 findByIdWithLock 으로 계좌 행을 잠근다. 종목을 N개로 늘려도 주문이 전부 한 계좌
//   것이면 정산 N건이 같은 행 앞에 줄을 서므로, 종목 락을 흩은 만큼을 계좌 락이 도로 묶는다.
//   그 조건에서 나온 "종목을 늘려도 안 늘어난다"는 설계 한계가 아니라 픽스처 탓이다.
//   ACCOUNT_COUNT=1 을 대조군으로 함께 재서 둘을 가른다.
//
// 실행 (같은 arm에서 N을 바꿔가며 반복)
//   ./benchmark/prepare-accounts.sh 50      ← 계좌 먼저. prepare-stocks 가 이 계좌에 보유분을 준다
//   ./benchmark/prepare-stocks.sh 50
//   ./benchmark/reset.sh
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=1
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=5
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=20
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=50
//   ... run /scripts/b-parallelism.js --env STOCK_COUNT=50 --env ACCOUNT_COUNT=1   ← 대조군
//
// 판정: 종목 수 N별 포화 TPS를 기록한다.
//       두 곡선이 갈라지기 시작하는 N이 "Redis가 의미를 갖기 시작하는 지점"이다.
//
// 병목 구분 (§3 단계 3)
//   hikaricp_connections_pending > 0        → 커넥션 풀이 병목
//   pending 0 인데 RDS CPUUtilization 높음   → DB CPU가 병목 (진짜 오프로딩 효과)
//   다계좌인데 대조군과 같음                  → 계좌 락이 아니다. 위 둘로 다시 가른다

import { loginUsers, seedOrderBookAcross, injectExecution, summaryNote } from './lib/common.js';

// prepare-stocks.sh 가 만든 목록. SQL과 스크립트의 종목이 어긋나지 않게 파일로 공유한다.
const ALL_STOCKS = JSON.parse(open('./data/stocks.json'));

const STOCK_COUNT = Number(__ENV.STOCK_COUNT || 5);
const STOCKS = ALL_STOCKS.slice(0, STOCK_COUNT);

// ★ 종목 수에 묶지 않는다. 묶으면 한 회차에 변수가 둘 움직여서 "종목을 늘려서 올랐는지
//   계좌를 늘려서 올랐는지"에 답할 수 없다. 전 회차에 고정해두고 N 만 스윕한다.
//   50 인 근거: 계좌 하나가 지탱하는 정산이 약 200/s 이므로(1 ÷ 계좌 락 보유 시간)
//   50개면 약 10,000/s — 이 장비에서 도달 불가능한 값이라 확실히 천장이 아니다.
const ACCOUNT_COUNT = Number(__ENV.ACCOUNT_COUNT || 50);

const PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest1234';

// 종목당 시딩 주문 수. 종목이 늘면 setup 시간도 늘어난다(주문 1건당 HTTP 1회).
//
// ★ 한 종목에서 실제로 쓰이는 계좌 수는 ACCOUNT_COUNT 가 아니라 SEED_COUNT / SEED_LEVELS 다.
//   같은 호가에 쌓인 주문에만 서로 다른 계좌가 배정되기 때문이다(common.js 참고).
//   2000/100 = 20개. ACCOUNT_COUNT 를 올려도 이 값이 작으면 계좌가 안 흩어진다.
//
// ★ 깊이도 100 호가 × 20단으로 잡는다. 수백 행이면 PostgreSQL 이 Seq Scan 을 골라
//   시나리오 A(10,000건 × 100호가)와 쿼리 계획이 달라져 N=1 끼리도 비교가 안 된다.
const SEED_COUNT = Number(__ENV.SEED_COUNT || 2000);
const SEED_PRICE = Number(__ENV.SEED_PRICE || 100);
const SEED_LEVELS = Number(__ENV.SEED_LEVELS || 100);
const SEED_QTY = Number(__ENV.SEED_QTY || 1000);
const EVENT_PRICE = Number(__ENV.EVENT_PRICE || 200);
const EVENT_QTY = Number(__ENV.EVENT_QTY || 1);

const PEAK = Number(__ENV.PEAK_RATE || 800);

// 'api' 면 setup 이 HTTP 로 심고, 'sql' 이면 benchmark/seed-orderbook.sql 이 이미 심었다고 본다.
const SEED_MODE = (__ENV.SEED_MODE || 'api').toLowerCase();

export const options = {
  scenarios: {
    parallelism: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      // ★ 다종목은 상한이 종목 수만큼 올라가므로 VU 도 그만큼 필요하다. 포화 구간에서
      //   VU 가 maxVUs 에 붙으면 k6 가 iteration 을 떨구는데, 그 누락은 서버 상한이 아니라
      //   부하 생성기 한계다 — 상한 역산이 통째로 틀어진다. 회차마다 vus 패널을 확인할 것.
      preAllocatedVUs: 500,
      maxVUs: 5000,
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
  const users = loginUsers(ACCOUNT_COUNT, { password: PASSWORD });

  // SQL 로 미리 심었으면 건너뛴다. API 시딩은 주문 1건당 HTTP 1회라
  // "이벤트 하나가 주문 하나를 비우는" 조건(주문 수 = 이벤트 수)을 만들 수 없다.
  //   ./benchmark/seed-orderbook.sql 참고
  if (SEED_MODE === 'sql') {
    console.log(`종목 ${STOCKS.length}개 × 계좌 ${users.length}개 — 시딩은 SQL 로 이미 완료`);
    return { tokens: users.map((u) => u.token), stocks: STOCKS };
  }

  console.log(`종목 ${STOCKS.length}개 × 계좌 ${users.length}개, 종목당 ${SEED_COUNT}건 시딩`);

  STOCKS.forEach((stock, index) => {
    seedOrderBookAcross(users, stock, {
      count: SEED_COUNT,
      price: SEED_PRICE,
      quantity: SEED_QTY,
      priceLevels: SEED_LEVELS,
      // 종목마다 시작 계좌를 옮긴다. 안 옮기면 전 종목이 같은 계좌부터 체결돼
      // 계좌를 나눈 효과가 첫 구간에서 사라진다.
      accountOffset: index,
    });
  });

  return { tokens: users.map((u) => u.token), stocks: STOCKS };
}

export default function (data) {
  // 균등 분배. 종목 간 병렬 효과만 보려면 가중치를 넣지 않는다
  // (거래대금 집중도 반영은 목표선이 나온 뒤 b-market-profile.js에서 한다).
  const stock = data.stocks[Math.floor(Math.random() * data.stocks.length)];

  // 이벤트 주입자는 체결에 관여하지 않는다(taker 는 가상). 토큰은 인증만 통과하면 되므로
  // VU 별로 나눠 써서 단일 JWT 검증이 변수로 끼어들 여지만 없앤다.
  const token = data.tokens[__VU % data.tokens.length];
  injectExecution(token, stock, EVENT_PRICE, EVENT_QTY);
}

export function teardown(data) {
  console.log(summaryNote(`B 종목 수 스윕 (종목=${STOCK_COUNT} 계좌=${ACCOUNT_COUNT})`));
}
