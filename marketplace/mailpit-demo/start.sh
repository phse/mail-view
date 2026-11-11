#!/bin/sh
# Starts a Mailpit container with neutral demo mails for the Marketplace screenshot (web UI on port 18030).
set -e
docker rm -f mail-view-mailpit-demo >/dev/null 2>&1 || true
docker run -d --name mail-view-mailpit-demo -p 18030:8025 -p 11030:1025 axllent/mailpit >/dev/null
for i in 1 2 3 4 5 6 7 8 9 10; do curl -sf http://localhost:18030/api/v1/info >/dev/null && break; sleep 1; done
python3 "$(dirname "$0")/seed.py"
