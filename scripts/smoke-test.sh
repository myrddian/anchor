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
# Env (or copy .env.example to .env and edit — auto-sourced below):
#   LM_STUDIO_BASE_URL       e.g. http://mac-studio.local:1234/v1
#   LM_STUDIO_CHAT_MODEL     e.g. gemma-3-4b-it
#   LM_STUDIO_EMBEDDING_MODEL e.g. nomic-embed-text-v1.5
#   ANCHOR_DB_URL            (default jdbc:postgresql://localhost:5433/anchor)
#   ANCHOR_BASE_URL          (default http://localhost:8090)
#   ANCHOR_SKIP_COMPOSE=1    skip `docker compose up -d postgres`
#   ANCHOR_SKIP_BOOT=1       skip starting the server (assume it's already running)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Auto-source .env if present so the operator only has to fill in LM Studio /
# DB key parameters once. Standard dotenv semantics: values in the file
# overwrite shell exports (it's the config-of-record, not a fallback). For
# one-off overrides edit .env, or pass on the command line *after* clearing
# the .env value.
if [[ -f "${REPO_ROOT}/.env" ]]; then
  set -a; source "${REPO_ROOT}/.env"; set +a
fi

PDF_PATH="${1:-}"
QUERY="${2:-What is the central claim of this paper?}"
BASE_URL="${ANCHOR_BASE_URL:-http://localhost:8090}"

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
  # Refuse to start if port 8090 is already taken — otherwise bootRun fails
  # silently in the background and the script ends up polling a stale server
  # from a previous run, which is genuinely confusing to debug.
  ANCHOR_PORT="${ANCHOR_PORT:-8090}"
  if lsof -nP -iTCP:"${ANCHOR_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "✗ port ${ANCHOR_PORT} is already in use:" >&2
    lsof -nP -iTCP:"${ANCHOR_PORT}" -sTCP:LISTEN >&2
    echo "  Stop the existing process or set ANCHOR_SKIP_BOOT=1 if it's the server you want." >&2
    exit 1
  fi
  echo "==> 2/5 starting server (./gradlew :anchor-server:bootRun)"
  ( cd "${REPO_ROOT}" && ./gradlew :anchor-server:bootRun --console=plain ) \
    > /tmp/anchor-smoke.log 2>&1 &
  SERVER_PID=$!
  echo "  server pid=${SERVER_PID}; logs at /tmp/anchor-smoke.log"
fi

echo "==> waiting for /actuator/health"
# Poll for ANY HTTP response — Spring Boot returns 503 when overall health is
# DOWN (e.g. LM Studio probe failing), but that still means the server is up
# and ready to tell us *which* component is broken. Treating 200 and 503 the
# same here so we can show that detail in the next stage.
for i in $(seq 1 60); do
  HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health" 2>/dev/null || echo 000)"
  if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "503" ]]; then
    echo "  ✓ /actuator/health responding (HTTP ${HTTP_CODE})"
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
# No -f flag: a 503 still has a body listing per-component status. Without
# the body we'd lose the "why is LM Studio down" detail and abort blind.
HEALTH="$(curl -sS "${BASE_URL}/actuator/health" 2>/dev/null || echo '{}')"

if ! echo "${HEALTH}" | grep -q '"components"'; then
  echo "✗ /actuator/health returned no component breakdown:" >&2
  echo "${HEALTH}" | jq . 2>/dev/null || echo "${HEALTH}" >&2
  echo "  This usually means an older build is running. Try:" >&2
  echo "    pkill -f 'anchor-server' && ./gradlew :anchor-server:clean" >&2
  exit 1
fi
# Component name is "LMStudio" (capital L) — Spring's Introspector.decapitalize
# preserves both caps when the first two chars are uppercase.
if echo "${HEALTH}" | jq -e '.components.LMStudio.status == "UP"' >/dev/null 2>&1; then
  echo "  ✓ LM Studio reachable"
else
  echo "✗ LM Studio probe failed:" >&2
  echo "${HEALTH}" | jq '.components.LMStudio' >&2 2>/dev/null || echo "${HEALTH}" >&2
  echo >&2
  echo "  Last 30 lines of /tmp/anchor-smoke.log:" >&2
  tail -30 /tmp/anchor-smoke.log 2>/dev/null | sed 's/^/    /' >&2
  exit 1
fi
if echo "${HEALTH}" | jq -e '.components.db.status == "UP"' >/dev/null 2>&1; then
  echo "  ✓ database reachable"
fi
if echo "${HEALTH}" | jq -e '.status == "UP"' >/dev/null 2>&1; then
  echo "  ✓ overall status UP"
else
  # One or more non-LM-Studio components are DOWN — surface them rather than
  # press on into ingest with broken infrastructure.
  echo "✗ overall status not UP:" >&2
  echo "${HEALTH}" | jq . >&2
  exit 1
fi

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
