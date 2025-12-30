#!/bin/bash
# GKE 성능 테스트 스크립트

echo "🚀 GKE 랭킹 시스템 성능 테스트"
echo "================================"
echo ""

# API 엔드포인트
API_URL="https://api.stockit.live"

# 색상 코드
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== 1️⃣ Pod 상태 확인 ===${NC}"
kubectl get pods -n default | grep stockit

echo ""
echo -e "${BLUE}=== 2️⃣ 서버 Health Check ===${NC}"
curl -s ${API_URL}/actuator/health | jq

echo ""
echo -e "${BLUE}=== 3️⃣ Main 계좌 랭킹 조회 (캐시 사용) ===${NC}"
time curl -s ${API_URL}/api/rankings/main | jq '.total_participants'

echo ""
echo -e "${BLUE}=== 4️⃣ Main 계좌 랭킹 조회 (캐시 미사용) ===${NC}"
time curl -s ${API_URL}/api/rankings/performance/main/no-cache | jq '.total_participants'

echo ""
echo -e "${YELLOW}=== 5️⃣ 성능 비교 테스트 (100회 요청) ===${NC}"
curl -s "${API_URL}/api/rankings/performance/main/compare?requestCount=100" | jq

echo ""
echo -e "${YELLOW}=== 6️⃣ 성능 비교 테스트 (1000회 요청) ===${NC}"
curl -s "${API_URL}/api/rankings/performance/main/compare?requestCount=1000" | jq

echo ""
echo -e "${GREEN}=== 7️⃣ 최근 로그 확인 (랭킹 관련) ===${NC}"
kubectl logs -n default $(kubectl get pods -n default | grep stockit-backend | awk '{print $1}') --tail=20 | grep -E "랭킹|스케줄러"

echo ""
echo -e "${GREEN}✅ 테스트 완료!${NC}"
echo ""
echo "📊 Grafana 대시보드에서 실시간 확인:"
echo "   https://grafana.stockit.live"

