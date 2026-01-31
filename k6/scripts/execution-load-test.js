import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 커스텀 메트릭
const executionSuccessRate = new Rate('execution_success_rate');
const executionErrorRate = new Rate('execution_error_rate');

// 환경 변수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_EMAIL = __ENV.TEST_EMAIL || 'test@example.com';
const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'test1234';
const STOCK_CODE = __ENV.STOCK_CODE || '005930'; // 삼성전자

// 테스트 설정
export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 30초 동안 20개로 증가
    { duration: '1m', target: 100 },  // 1분 동안 100개로 증가
    { duration: '1m', target: 200 },  // 1분 동안 200개 유지 (체결은 더 많은 부하)
    { duration: '30s', target: 0 },   // 30초 동안 0개로 감소
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'], // 체결은 더 복잡하므로 시간 여유
    http_req_failed: ['rate<0.05'],                   // 에러율 5% 미만
    execution_success_rate: ['rate>0.95'],            // 체결 성공률 95% 이상
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

// UUID 생성 (간단한 버전)
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// 가짜 체결 데이터 주입
function injectMockExecution(token, stockCode) {
  const basePrice = 70000; // 기준 가격
  const priceVariation = Math.random() * 1000 - 500; // ±500원 변동
  const price = Math.round((basePrice + priceVariation) * 100) / 100; // 소수점 2자리

  const executionPayload = JSON.stringify({
    stock_code: stockCode,
    event_id: generateUUID(),
    order_method: Math.random() > 0.5 ? 'BUY' : 'SELL',
    price: price,
    quantity: Math.floor(Math.random() * 50) + 1, // 1~50주 랜덤
    event_timestamp: Date.now(),
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    tags: { name: 'InjectMockExecution' },
  };

  const res = http.post(
    `${BASE_URL}/api/test/mock-execution`,
    executionPayload,
    params
  );

  const success = res.status === 200 || res.status === 204;
  executionSuccessRate.add(success);
  executionErrorRate.add(!success);

  check(res, {
    '체결 데이터 주입 성공': (r) => r.status === 200 || r.status === 204,
    '응답 시간 < 1초': (r) => r.timings.duration < 1000,
    '응답 시간 < 2초': (r) => r.timings.duration < 2000,
  });

  if (!success) {
    console.error(`체결 데이터 주입 실패: ${res.status} - ${res.body}`);
  }

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

  // 2. 가짜 체결 데이터 주입
  injectMockExecution(token, STOCK_CODE);

  // 요청 간 대기 (체결 처리는 빠르게)
  sleep(Math.random() * 0.5 + 0.1); // 0.1~0.6초 랜덤 대기
}

// 테스트 시작 전 실행
export function setup() {
  console.log('=== 체결 성능 부하 테스트 시작 ===');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Test Stock Code: ${STOCK_CODE}`);
  console.log('주의: /api/test/mock-execution 엔드포인트가 필요합니다.');
  
  // 헬스 체크
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  if (healthRes.status !== 200) {
    console.error('서버 헬스 체크 실패!');
  } else {
    console.log('서버 헬스 체크 성공');
  }
}



