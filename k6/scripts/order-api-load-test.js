import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 커스텀 메트릭
const orderSuccessRate = new Rate('order_success_rate');
const orderErrorRate = new Rate('order_error_rate');

// 환경 변수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL || 'test@example.com';
const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'test1234';
const ACCOUNT_ID = __ENV.ACCOUNT_ID || '1';
const STOCK_CODE = __ENV.STOCK_CODE || '005930'; // 삼성전자

// 테스트 설정
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // 30초 동안 10명으로 증가
    { duration: '1m', target: 50 },    // 1분 동안 50명으로 증가
    { duration: '1m', target: 100 },   // 1분 동안 100명 유지
    { duration: '30s', target: 0 },    // 30초 동안 0명으로 감소
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'], // 95%는 500ms 이하, 99%는 1초 이하
    http_req_failed: ['rate<0.05'],                  // 에러율 5% 미만
    order_success_rate: ['rate>0.95'],              // 주문 성공률 95% 이상
  },
};

// 로그인하여 JWT 토큰 발급
function login() {
  const loginPayload = JSON.stringify({
    email: TEST_EMAIL,
    password: TEST_PASSWORD,
  });

  const loginRes = http.post(`${BASE_URL}/api/members/login`, loginPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  if (loginRes.status === 200) {
    const tokenData = JSON.parse(loginRes.body);
    return tokenData.access_token;
  }

  console.error(`로그인 실패: ${loginRes.status} - ${loginRes.body}`);
  return null;
}

// 시장가 주문 생성
function createMarketOrder(token, orderMethod) {
  const orderPayload = JSON.stringify({
    account_id: parseInt(ACCOUNT_ID),
    stock_code: STOCK_CODE,
    quantity: Math.floor(Math.random() * 10) + 1, // 1~10주 랜덤
    order_method: orderMethod, // 'BUY' or 'SELL'
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: 'CreateMarketOrder' },
  };

  const res = http.post(`${BASE_URL}/api/orders/market`, orderPayload, params);
  
  const success = res.status === 200;
  orderSuccessRate.add(success);
  orderErrorRate.add(!success);

  check(res, {
    '시장가 주문 생성 성공': (r) => r.status === 200,
    '응답 시간 < 500ms': (r) => r.timings.duration < 500,
    '응답 시간 < 1초': (r) => r.timings.duration < 1000,
  });

  if (!success) {
    console.error(`주문 생성 실패: ${res.status} - ${res.body}`);
  }

  return res;
}

// 지정가 주문 생성
function createLimitOrder(token, orderMethod) {
  const basePrice = 70000; // 기준 가격
  const priceVariation = Math.random() * 1000 - 500; // ±500원 변동
  const price = basePrice + priceVariation;

  const orderPayload = JSON.stringify({
    account_id: parseInt(ACCOUNT_ID),
    stock_code: STOCK_CODE,
    price: price,
    quantity: Math.floor(Math.random() * 10) + 1, // 1~10주 랜덤
    order_method: orderMethod, // 'BUY' or 'SELL'
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: 'CreateLimitOrder' },
  };

  const res = http.post(`${BASE_URL}/api/orders/limit`, orderPayload, params);
  
  const success = res.status === 200;
  orderSuccessRate.add(success);
  orderErrorRate.add(!success);

  check(res, {
    '지정가 주문 생성 성공': (r) => r.status === 200,
    '응답 시간 < 500ms': (r) => r.timings.duration < 500,
    '응답 시간 < 1초': (r) => r.timings.duration < 1000,
  });

  if (!success) {
    console.error(`주문 생성 실패: ${res.status} - ${res.body}`);
  }

  return res;
}

// 주문 조회
function getOrder(token, orderId) {
  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: 'GetOrder' },
  };

  const res = http.get(`${BASE_URL}/api/orders/${orderId}`, params);

  check(res, {
    '주문 조회 성공': (r) => r.status === 200,
  });

  return res;
}

// 메인 테스트 함수
export default function () {
  // 1. 로그인
  const token = login();
  if (!token) {
    console.error('로그인 실패로 테스트 중단');
    return;
  }

  // 2. 주문 생성 (시장가 또는 지정가 랜덤 선택)
  const orderType = Math.random() > 0.5 ? 'market' : 'limit';
  const orderMethod = Math.random() > 0.5 ? 'BUY' : 'SELL';

  let orderRes;
  if (orderType === 'market') {
    orderRes = createMarketOrder(token, orderMethod);
  } else {
    orderRes = createLimitOrder(token, orderMethod);
  }

  // 3. 주문 조회 (주문 생성 성공 시)
  if (orderRes.status === 200) {
    try {
      const orderData = JSON.parse(orderRes.body);
      if (orderData.order_id) {
        sleep(0.1); // 100ms 대기
        getOrder(token, orderData.order_id);
      }
    } catch (e) {
      console.error('주문 데이터 파싱 실패:', e);
    }
  }

  // 요청 간 대기 (사용자 행동 시뮬레이션)
  sleep(Math.random() * 2 + 1); // 1~3초 랜덤 대기
}

// 테스트 시작 전 실행
export function setup() {
  console.log('=== 주문 API 부하 테스트 시작 ===');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Test Account ID: ${ACCOUNT_ID}`);
  console.log(`Test Stock Code: ${STOCK_CODE}`);
  
  // 헬스 체크
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  if (healthRes.status !== 200) {
    console.error('서버 헬스 체크 실패!');
  } else {
    console.log('서버 헬스 체크 성공');
  }
}



