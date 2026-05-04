#!/usr/bin/env bash
# Remove test-fixture documents that integration tests leaked into the local
# pgvector. Each integration test class seeds documents with known titles and
# (until the @AfterEach cleanup landed) never deleted them — running
# `./gradlew test` repeatedly accumulates dozens of these in the dropdown.
#
# What gets deleted (matched by title; cascade FK takes care of children):
#   - "Phase 2 paper", "Phase 3 ask paper", "Phase 4 retrieve paper"
#   - "paper" (IngestServiceIntegrationTest's tiny generated PDF)
#   - "Roundtrip paper <UUID>", "Multi-chapter <UUID>", "Thread test <UUID>"
#
# Usage:
#   scripts/clean-test-docs.sh              # dry run — print what'd be deleted
#   scripts/clean-test-docs.sh --apply      # actually delete
#   scripts/clean-test-docs.sh --all        # nuke EVERY document (dangerous)
#
# Connects through the running anchor-postgres container on docker.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="${ANCHOR_PG_CONTAINER:-anchor-postgres}"
DB="${ANCHOR_DB_NAME:-anchor}"
USER="${ANCHOR_DB_USER:-anchor}"

if [[ -f "${REPO_ROOT}/.env" ]]; then
  set -a; source "${REPO_ROOT}/.env"; set +a
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "✗ container '${CONTAINER}' is not running. Start it with: docker compose up -d postgres" >&2
  exit 1
fi

# What to match. Read-only, used by both dry-run and --apply.
SELECT_FIXTURES=$(cat <<'SQL'
SELECT id, title, ingested_at
FROM documents
WHERE title IN ('Phase 2 paper', 'Phase 3 ask paper', 'Phase 4 retrieve paper', 'paper')
   OR title LIKE 'Roundtrip paper %'
   OR title LIKE 'Multi-chapter %'
   OR title LIKE 'Thread test %'
ORDER BY ingested_at DESC;
SQL
)

DELETE_FIXTURES=$(cat <<'SQL'
DELETE FROM documents
WHERE title IN ('Phase 2 paper', 'Phase 3 ask paper', 'Phase 4 retrieve paper', 'paper')
   OR title LIKE 'Roundtrip paper %'
   OR title LIKE 'Multi-chapter %'
   OR title LIKE 'Thread test %';
SQL
)

run_psql() {
  docker exec -i "${CONTAINER}" psql -U "${USER}" -d "${DB}" -P pager=off "$@"
}

case "${1:-}" in
  --all)
    echo "⚠️  --all: this will TRUNCATE every document in the '${DB}' database."
    read -r -p "Type 'YES' to confirm: " confirm
    if [[ "${confirm}" != "YES" ]]; then
      echo "aborted." >&2
      exit 1
    fi
    run_psql -c "TRUNCATE documents CASCADE;"
    echo "✓ documents truncated."
    ;;
  --apply)
    echo "==> deleting test-fixture documents…"
    run_psql -c "${DELETE_FIXTURES}"
    REMAINING=$(run_psql -t -A -c "SELECT COUNT(*) FROM documents")
    echo "✓ done. ${REMAINING} document(s) remain."
    ;;
  ""|--dry-run|-n)
    echo "==> would delete the following test-fixture documents (use --apply to actually delete):"
    run_psql -c "${SELECT_FIXTURES}"
    COUNT=$(run_psql -t -A -c "SELECT COUNT(*) FROM (${SELECT_FIXTURES//;/}) AS m")
    echo "==> ${COUNT} match(es). Re-run with --apply to delete them."
    ;;
  -h|--help)
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "✗ unknown option: $1" >&2
    echo "  $0 [--dry-run|--apply|--all|-h]" >&2
    exit 64
    ;;
esac
