#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export CANDIDATE_ENGINE=POJO_OPTA_STYLE_CHANGE_SWAP
exec "$SCRIPT_DIR/run-phase6-benchmark.sh" "${1:-quick}"
