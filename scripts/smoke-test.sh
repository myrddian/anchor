#!/usr/bin/env bash
# Anchor end-to-end smoke test.
#
# Walks: postgres up → server reachable → /actuator/health green →
# LM Studio probe green → ingest a PDF → list → ask a question → print
# the deliberation transcript. Designed for the first real run on a fresh
# clone so an operator can tell in one invocation whether the v0 stack
# actually works against their LM Studio instance.
#
# Usage:
#   scripts/smoke-test.sh /path/to/paper.pdf "your question here"
#
# Env:
#   ANCHOR_BASE_URL          (default http://localhost:8080)
#   LM_STUDIO_BASE_URL       passed through to the server if it isn't already set
#   ANCHOR_SKIP_COMPOSE=1    skip `docker compose up -d postgres`
#   ANCHOR_SKIP_BOOT=1       skip starting the server (assume it's already running)

set -euo pipefail

PDF_PATH="${1:-}"
QUERY="${2:-What is the central claim of this paper?}"
BASE_URL="${ANCHOR_BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "${PDF_PATH}" ]]; then
  echo "usage: $0 /path/to/paper.pdf [\"question\"]" >&2
  exit 64
fi
if [[ ! -f "${PDF_PATH}" ]]; then
  echo "✗ PDF not found: ${PDF_PATH}" >&2
  exit 66
fi

echo "==> Anchor smoke test"
echo "  paper:    ${PDF_PATH}"
echo "  query:    ${QUERY}"
echo "  base-url: ${BASE_URL}"
echo

# 1. Postgres -----------------------------------------------------------------
if [[ "${ANCHOR_SKIP_COMPOSE:-}" != "1" ]]; then
  echo "==> 1/5 starting postgres (docker compose up -d postgres)"
  ( cd "${REPO_ROOT}" && docker compose up -d postgres >/dev/null )
fi
for _ in $(seq 1 30); do
  if docker exec anchor-postgres pg_isready -U anchor -d anchor >/dev/null 2>&1; then
    echo "  ✓ postgres ready"
    break
  fi
  sleep 1
done

# 2. Server -------------------------------------------------------------------
SERVER_PID=""
cleanup() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    echo
    echo "==> stopping server (pid=${SERVER_PID})"
    kill "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ "${ANCHOR_SKIP_BOOT:-}" != "1" ]]; then
  echo "==> 2/5 starting server (./gradlew :anchor-server:bootRun)"
  ( cd "${REPO_ROOT}" && ./gradlew :anchor-server:bootRun --console=plain ) \
    > /tmp/anchor-smoke.log 2>&1 &
  SERVER_PID=$!
  echo "  server pid=${SERVER_PID}; logs at /tmp/anchor-smoke.log"
fi

echo "==> waiting for /actuator/health"
for i in $(seq 1 60); do
  if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    echo "  ✓ /actuator/health responding"
    break
  fi
  if [[ ${i} -eq 60 ]]; then
    echo "✗ server did not come up within 60s — check /tmp/anchor-smoke.log" >&2
    exit 1
  fi
  sleep 1
done

# 3. Health check -------------------------------------------------------------
echo "==> 3/5 checking /actuator/health components"
HEALTH="$(curl -fsS "${BASE_URL}/actuator/health")"
echo "${HEALTH}" | grep -q '"status":"UP"' \
  && echo "  ✓ overall status UP" \
  || { echo "✗ overall status not UP:"; echo "${HEALTH}"; exit 1; }
echo "${HEALTH}" | grep -q '"lMStudio":{"status":"UP"' \
  && echo "  ✓ LM Studio reachable" \
  || { echo "✗ LM Studio probe failed:"; echo "${HEALTH}"; exit 1; }
echo "${HEALTH}" | grep -q '"db":{"status":"UP"' \
  && echo "  ✓ database reachable"

# 4. Ingest -------------------------------------------------------------------
echo "==> 4/5 ingesting ${PDF_PATH}"
INGEST_BODY="$(jq -n --arg path "${PDF_PATH}" '{source_path:$path}')"
INGEST_RESPONSE="$(curl -fsS -X POST -H 'Content-Type: application/json' \
  --data "${INGEST_BODY}" "${BASE_URL}/ingest")"
DOC_ID="$(echo "${INGEST_RESPONSE}" | jq -r '.document_id')"
echo "  ✓ ingested document_id=${DOC_ID}"
echo "${INGEST_RESPONSE}" | jq '{title, chapter_count, section_count, paragraph_count, chunk_count, token_usage}'

# 5. Ask ----------------------------------------------------------------------
echo
echo "==> 5/5 asking: \"${QUERY}\""
ASK_BODY="$(jq -n --arg q "${QUERY}" '{query:$q}')"
ASK_RESPONSE="$(curl -fsS -X POST -H 'Content-Type: application/json' \
  --data "${ASK_BODY}" "${BASE_URL}/documents/${DOC_ID}/ask")"
JOB_ID="$(echo "${ASK_RESPONSE}" | jq -r '.job_id')"
echo "  job_id=${JOB_ID}; polling…"

for _ in $(seq 1 600); do
  JOB="$(curl -fsS "${BASE_URL}/jobs/${JOB_ID}")"
  STATUS="$(echo "${JOB}" | jq -r '.status')"
  if [[ "${STATUS}" == "COMPLETED" || "${STATUS}" == "FAILED" || "${STATUS}" == "CANCELLED" ]]; then
    break
  fi
  printf "."
  sleep 1
done
echo

echo "==> deliberation transcript"
echo "${JOB}" | jq '{status, proposer:.proposer.response, critic:.critic.challenges, final_response, error}'

echo
echo "✓ smoke test complete"
