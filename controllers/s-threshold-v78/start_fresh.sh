#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
EXTRA="-Dbitbrains.fresh=true" ./run_batch.sh
