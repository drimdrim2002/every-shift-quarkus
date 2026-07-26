#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase8/pojo-only/fixed-$TIMESTAMP}"
EVALUATIONS="${EVALUATIONS:-5000}"
SEED="${SEED:-1701}"

exec "$ROOT_DIR/mvnw" -q \
  -Dtest=Phase8PojoOnlyBenchmarkTest \
  -Dphase8.benchmark.enabled=true \
  -Dphase8.benchmark.output-dir="$OUTPUT_DIR" \
  -Dphase8.benchmark.evaluations="$EVALUATIONS" \
  -Dphase8.benchmark.seed="$SEED" \
  test
