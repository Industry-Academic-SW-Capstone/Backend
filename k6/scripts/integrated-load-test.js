import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 커스텀 메트릭
const orderSuccessRate = new Rate('order_success_rate');
const executionSuccessRate = new Rate('execution_success_rate');

// 환경 변수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL || 'test@example.com';
const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'test1234';
const ACCOUNT_ID = __ENV.ACCOUNT_ID || '1';
const STOCK_CODE = __ENV.STOCK_CODE || '005930'; // 삼성전자

// 테스트 설정
export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.05'],
    order_success_rate: ['rate>0.95'],
    execution_success_rate: ['rate>0.95'],
  },
};

// 로그인
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

  return null;
}

// UUID 생성
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// 지정가 주문 생성
function createLimitOrder(token) {
  const basePrice = 70000;
  const price = Math.round((basePrice + (Math.random() * 1000 - 500)) * 100) / 100;

  const orderPayload = JSON.stringify({
    account_id: parseInt(ACCOUNT_ID),
    stock_code: STOCK_CODE,
    price: price,
    quantity: Math.floor(Math.random() * 10) + 1,
    order_method: 'BUY',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
  };

  const res = http.post(`${BASE_URL}/api/orders/limit`, orderPayload, params);
  orderSuccessRate.add(res.status === 200);
  return res;
}

// 체결 데이터 주입
function injectExecution(token, stockCode, price) {
  const executionPayload = JSON.stringify({
    stock_code: stockCode,
    event_id: generateUUID(),
    order_method: 'BUY',
    price: price,
    quantity: Math.floor(Math.random() * 50) + 1,
    event_timestamp: Date.now(),
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
  };

  const res = http.post(
    `${BASE_URL}/api/test/mock-execution`,
    executionPayload,
    params
  );
  
  executionSuccessRate.add(res.status === 200 || res.status === 204);
  return res;
}

// 통합 테스트: 주문 생성 → 체결 주입
export default function () {
  const token = login();
  if (!token) return;

  // 1. 주문 생성
  const orderRes = createLimitOrder(token);
  
  if (orderRes.status === 200) {
    try {
      const orderData = JSON.parse(orderRes.body);
      sleep(0.2); // 주문 처리 대기
      
      // 2. 체결 데이터 주입 (주문 가격과 유사한 가격으로)
      const orderPrice = orderData.price || 70000;
      injectExecution(token, STOCK_CODE, orderPrice);
    } catch (e) {
      console.error('주문 데이터 파싱 실패:', e);
    }
  }

  sleep(Math.random() * 2 + 1);
}

export function setup() {
  console.log('=== 통합 부하 테스트 시작 ===');
  console.log(`Base URL: ${BASE_URL}`);
}



