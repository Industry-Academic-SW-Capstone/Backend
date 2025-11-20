#!/bin/bash

# 포트폴리오 분석 테스트 스크립트
# 사용법: ./test_portfolio_analyze.sh

echo "=== 포트폴리오 분석 API 테스트 ==="
echo ""

curl -X POST http://localhost:8000/portfolio/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "stocks": [
      {
        "stock_code": "005930",
        "stock_name": "삼성전자",
        "market_cap": 6363611000000.0,
        "per": 21.72,
        "pbr": 1.86,
        "roe": 6.64,
        "debt_ratio": 26.36,
        "dividend_yield": 370.0,
        "investment_amount": 700000
      },
      {
        "stock_code": "000660",
        "stock_name": "SK하이닉스",
        "market_cap": 4069533000000.0,
        "per": 20.57,
        "pbr": 5.35,
        "roe": 37.52,
        "debt_ratio": 48.13,
        "dividend_yield": 7.5,
        "investment_amount": 600000
      }
    ]
  }' 2>&1 | python3 -m json.tool 2>/dev/null || echo "JSON 파싱 실패 또는 응답 없음"

echo ""
echo "=== 테스트 완료 ==="
echo ""
echo "확인 사항:"
echo "1. stock_name 필드가 '삼성전자', 'SK하이닉스'로 정확히 표시되는지"
echo "2. '알 수 없는 종목' 메시지가 없는지"
echo "3. stock_details 배열에 각 종목의 분석 결과가 포함되는지"

