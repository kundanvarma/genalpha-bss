#!/bin/bash
# The hosted demo box, in one command.
#   ops/cloud/aws-demo/box.sh start    # sign in if needed, start, wait until sign-in really works
#   ops/cloud/aws-demo/box.sh stop     # stop it (billing stops with it)
#   ops/cloud/aws-demo/box.sh status   # state, health, what it is running
# The AWS session expires roughly daily; `start` asks you to approve a code when it does.
set -uo pipefail
# Which box, which account, which hostnames: local, never committed.
# Put them in ops/cloud/aws-demo/box.env (gitignored) or the environment:
#   BOX_INSTANCE=i-…        the EC2 instance
#   BOX_PROFILE=…           the AWS profile
#   BOX_HOST=…              a hostname the fleet serves (for the readiness check)
#   BOX_KEY=~/.ssh/….pem    the ssh key            (optional, for `status`)
#   BOX_IP=…                the public address      (optional, for `status`)
#   BOX_PATH=/opt/…         where the repo lives on the box (optional, default /opt/bss)
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$HERE/box.env" ] && . "$HERE/box.env"
INSTANCE=${BOX_INSTANCE:-}
PROFILE=${BOX_PROFILE:-}
HOST=${BOX_HOST:-}
KEY=${BOX_KEY:-}
IP=${BOX_IP:-}
if [ -z "$INSTANCE" ] || [ -z "$PROFILE" ] || [ -z "$HOST" ]; then
  echo "box.sh: set BOX_INSTANCE, BOX_PROFILE and BOX_HOST (see the header, or ops/cloud/aws-demo/box.env)"; exit 2
fi
READY_URL="https://$HOST/"

need_login() { aws sts get-caller-identity --profile "$PROFILE" >/dev/null 2>&1 && return 1 || return 0; }

login() {
  echo "AWS session expired — approve this, then this script continues on its own:"
  aws sso login --profile "$PROFILE" --use-device-code --no-browser 2>&1 | grep -E "https://.*user_code=|Then enter the code|^[A-Z]{4}-[A-Z]{4}$"
}

state() { aws ec2 describe-instances --instance-ids "$INSTANCE" --profile "$PROFILE" \
  --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null; }

case "${1:-status}" in
  start)
    need_login && login
    [ "$(state)" = running ] && echo "already running" || \
      aws ec2 start-instances --instance-ids "$INSTANCE" --profile "$PROFILE" --output text >/dev/null
    printf "starting"
    for _ in $(seq 1 60); do [ "$(state)" = running ] && break; printf "."; sleep 10; done
    echo " instance running"
    printf "waiting for sign-in to work"
    for _ in $(seq 1 90); do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 "$READY_URL")
      case "$code" in 200|301|302) echo; echo "READY — the fleet answers at https://$HOST/"; \
        echo "(the box stays busy for ~15 min after boot; pages are quick, background work settles)"; exit 0;; esac
      printf "."; sleep 10
    done
    echo; echo "sign-in still not answering (last HTTP $code) — check: $0 status"; exit 1 ;;
  stop)
    aws sts get-caller-identity --profile "$PROFILE" >/dev/null 2>&1 || login
    aws ec2 stop-instances --instance-ids "$INSTANCE" --profile "$PROFILE" --output text >/dev/null
    printf "stopping"
    for _ in $(seq 1 40); do [ "$(state)" = stopped ] && break; printf "."; sleep 10; done
    echo " $(state) — billing stopped" ;;
  status)
    aws sts get-caller-identity --profile "$PROFILE" >/dev/null 2>&1 || { echo "AWS session expired — run: $0 start"; exit 1; }
    echo "instance: $(state)"
    [ "$(state)" = running ] || exit 0
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8 -i "$KEY" ubuntu@"${IP:?set BOX_IP in box.env for status over ssh}" \
      "cd '${BOX_PATH:-/opt/bss}' && "'echo "running: $(git log --oneline -1)"; echo "containers: $(docker ps --format "{{.Status}}" | grep -c healthy) healthy of $(docker ps -q | wc -l)"; uptime | sed "s/.*load/load/"' 2>/dev/null \
      || echo "(still booting — ssh not answering yet)" ;;
  *) echo "usage: $0 {start|stop|status}"; exit 2 ;;
esac
