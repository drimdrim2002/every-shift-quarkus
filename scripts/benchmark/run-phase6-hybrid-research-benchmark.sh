#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORDER="${HYBRID_ORDER:-opta-then-alns}"

case "$ORDER" in
  opta-then-alns) CANDIDATE_ENGINE="POJO_HYBRID_OPTA_THEN_ALNS" ;;
  alns-then-opta) CANDIDATE_ENGINE="POJO_HYBRID_ALNS_THEN_OPTA" ;;
  *) echo "HYBRID_ORDER는 opta-then-alns 또는 alns-then-opta여야 합니다." >&2; exit 2 ;;
esac

export CANDIDATE_ENGINE
exec "$SCRIPT_DIR/run-phase6-benchmark.sh" "${1:-quick}"
