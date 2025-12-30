#!/bin/bash

# 종목분석 API 테스트 스크립트

echo "=== 종목분석 API 테스트 ==="
echo ""

# 1. 서버 헬스 체크
echo "1. 서버 헬스 체크..."
HEALTH=$(curl -s -w "\n%{http_code}" http://localhost:8080/actuator/health 2>&1)
HTTP_CODE=$(echo "$HEALTH" | tail -1)
if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 서버 정상"
else
    echo "❌ 서버 응답 없음 (HTTP: $HTTP_CODE)"
    echo "서버가 실행 중인지 확인하세요."
    exit 1
fi

echo ""
echo "2. 종목분석 API 테스트 (000660 - SK하이닉스)..."
echo ""

RESPONSE=$(curl -s -X POST http://localhost:8080/api/stocks/000660/analyze \
  -H "Content-Type: application/json" \
  -w "\n%{http_code}" \
  --max-time 60 2>&1)

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "HTTP Status: $HTTP_CODE"
echo ""
echo "Response:"
if [ "$HTTP_CODE" = "200" ]; then
    echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
    echo ""
    echo "✅ 종목분석 성공!"
else
    echo "$BODY"
    echo ""
    echo "❌ 종목분석 실패"
    echo ""
    echo "가능한 원인:"
    echo "1. Python 서버가 실행되지 않음 (http://localhost:8000)"
    echo "2. Python 서버 URL 설정 오류"
    echo "3. 네트워크 연결 문제"
fi

