#!/usr/bin/env bash
set -euo pipefail
umask 077
cd /home/runner

if [[ ! -f .runner ]]; then
  if [[ ! -s .registration-token ]]; then
    echo '首次启动需要在 runner-state 卷中写入 .registration-token。' >&2
    exit 1
  fi
  registration_token="$(cat .registration-token)"
  ./config.sh --unattended \
    --url "$RUNNER_REPOSITORY_URL" \
    --token "$registration_token" \
    --name "$RUNNER_NAME" \
    --labels "$RUNNER_LABELS" \
    --work _work
  rm -f .registration-token
  unset registration_token
fi

exec ./run.sh
