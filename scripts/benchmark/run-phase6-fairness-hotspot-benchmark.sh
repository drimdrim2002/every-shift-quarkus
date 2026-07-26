#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export CANDIDATE_ENGINE=POJO_ALNS_FAIRNESS_HOTSPOT
exec "$SCRIPT_DIR/run-phase6-benchmark.sh" "${1:-quick}"
