#!/bin/bash
# 뉴스 RAG(Python AI 서버)용 DB. 서비스 DB 와 같은 PostgreSQL 에 두되 데이터베이스와 계정을 나눈다.
# 데이터 디렉터리가 빈 첫 기동에만 실행된다. 테이블은 AI 서버가 기동하며 만든다(pgvector_client.SCHEMA_SQL).
# vector 확장은 슈퍼유저만 만들 수 있어 여기서 만든다.
set -euo pipefail
: "${NEWS_DB_PASSWORD:?NEWS_DB_PASSWORD 가 필요합니다}"

# 기본으로 모든 계정이 모든 데이터베이스에 접속할 수 있다. 서로의 데이터베이스에는 붙지 못하게 막는다.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v news_password="$NEWS_DB_PASSWORD" -v service_db="$POSTGRES_DB" <<'EOSQL'
CREATE ROLE stockai LOGIN PASSWORD :'news_password';
CREATE DATABASE stock_news OWNER stockai;
REVOKE CONNECT ON DATABASE :"service_db" FROM PUBLIC;
REVOKE CONNECT ON DATABASE stock_news FROM PUBLIC;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname stock_news <<'EOSQL'
CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
