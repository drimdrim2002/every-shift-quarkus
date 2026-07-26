#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-smoke-wall}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

DATASETS="${DATASETS:-fairness.json,preceptor.json,request.json,sample.json}"
CANDIDATES="ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN"
WARMUP="${WARMUP:-true}"

case "$MODE" in
  smoke-fixed)
    DELEGATE_MODE="fixed"
    PARTITION="${PARTITION:-phase7-shadow-fixed-smoke}"
    SEEDS="${SEEDS:-1601,1602}"
    OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
    POJO_EVALUATIONS="${POJO_EVALUATIONS:-5000}"
    OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase7/shadow-smoke/fixed-$TIMESTAMP}"
    ;;
  smoke-wall)
    DELEGATE_MODE="pilot"
    PARTITION="${PARTITION:-phase7-shadow-wall-smoke}"
    SEEDS="${SEEDS:-1601,1602}"
    WALL_SECONDS="${WALL_SECONDS:-3}"
    OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase7/shadow-smoke/wall-${WALL_SECONDS}s-$TIMESTAMP}"
    ;;
  validation-60)
    DELEGATE_MODE="wall-60"
    PARTITION="${PARTITION:-phase7-phase6-candidate-reproduction-60s}"
    SEEDS="${SEEDS:-1501,1502}"
    WALL_SECONDS=60
    OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase7/candidate-reproduction/wall-60s-$TIMESTAMP}"
    ;;
  locked-holdout)
    if [[ "${PHASE7_LOCKED_HOLDOUT_APPROVED:-false}" != "true" ]]; then
      echo "잠긴 101..110 holdout은 PHASE7_LOCKED_HOLDOUT_APPROVED=true 명시 승인 없이는 실행하지 않습니다." >&2
      exit 3
    fi
    DELEGATE_MODE="wall-60"
    PARTITION="phase7-locked-holdout-post-evaluation-only"
    SEEDS="101,102,103,104,105,106,107,108,109,110"
    WALL_SECONDS=60
    OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/benchmark-artifacts/phase7/locked-holdout/wall-60s-$TIMESTAMP}"
    ;;
  *)
    echo "사용법: $0 {smoke-fixed|smoke-wall|validation-60|locked-holdout}" >&2
    exit 2
    ;;
esac

export PARTITION DATASETS SEEDS CANDIDATES WARMUP OUTPUT_DIR
export OPTA_EVALUATIONS="${OPTA_EVALUATIONS:-50000}"
export POJO_EVALUATIONS="${POJO_EVALUATIONS:-50000}"
export WALL_SECONDS="${WALL_SECONDS:-60}"

exec "$SCRIPT_DIR/run-phase6-hybrid-vnd-long-benchmark.sh" "$DELEGATE_MODE"
