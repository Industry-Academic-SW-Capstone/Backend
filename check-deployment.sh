#!/bin/bash

echo "=== GKE 클러스터 연결 ===" && \
gcloud container clusters get-credentials stockit-cluster --zone asia-northeast3-a --project stockit-421709 && \
echo "" && \
echo "=== 현재 Pod 상태 ===" && \
kubectl get pods -n default -l app.kubernetes.io/component=spring-backend -o wide && \
echo "" && \
echo "=== Redis Pod 상태 ===" && \
kubectl get pods -n default -l app.kubernetes.io/component=stockit-redis -o wide && \
echo "" && \
echo "=== Redis Service 확인 ===" && \
kubectl get svc -n default | grep redis && \
echo "" && \
echo "=== Deployment 정보 ===" && \
kubectl get deployment -n default stockit-release-stockit-backend-chart-spring-backend && \
echo "" && \
echo "=== 최신 Pod 로그 (마지막 20줄) ===" && \
LATEST_POD=$(kubectl get pods -n default -l app.kubernetes.io/component=spring-backend --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1].metadata.name}' 2>/dev/null) && \
if [ -n "$LATEST_POD" ]; then
  echo "Pod: $LATEST_POD" && \
  kubectl logs "$LATEST_POD" -n default --tail=20
fi

