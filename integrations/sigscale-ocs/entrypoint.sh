#!/bin/sh
# Fleet-friendly boot for SigScale OCS:
#   1. first run on an empty volume → initialise the mnesia tables
#      (upstream asks you to do this by hand with `bin/initialize`)
#   2. start the node
#   3. once it answers, make sure the REST/GUI admin user exists
#      (HTTP Basic, group "staff") and — if asked — a DIAMETER client
#      entry for the network element that will charge against it.
# Everything is idempotent: restarts leave a working, provisioned node.
set -u
cd "$HOME"

ADMIN_USER="${OCS_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${OCS_ADMIN_PASSWORD:-admin}"

# The release runs with a LONG node name, which Erlang only accepts on a
# dotted hostname. Containers usually get a bare one, so qualify it.
HOST="$(hostname)"
case "$HOST" in *.*) ;; *) HOST="$HOST.local" ;; esac
export NODENAME="ocs@$HOST"
NODE="$NODENAME"

if [ ! -f db/schema.DAT ]; then
  echo "[sigscale-ocs] empty database volume — initialising mnesia tables"
  bin/initialize
fi

# Remote calls go to the node's fixed distribution port (ERL_RPC), so they
# work whatever the container's hostname resolves to.
rpc() { erl_call -address "127.0.0.1:${ERL_RPC:-54861}" -c "$(cat "$HOME/.erlang.cookie")" -a "$1" 2>/dev/null; }

bootstrap() {
  for _ in $(seq 1 90); do
    sleep 2
    if rpc 'erlang node []' >/dev/null; then
      # {error, user_exists} on a restart — exactly what we want
      rpc "ocs add_user [\"$ADMIN_USER\", \"$ADMIN_PASSWORD\", [{locale, \"en\"}]]" >/dev/null
      # OCS_DIAMETER_CLIENTS=a.b.c.d,e.f.g.h — the P-GWs/SMFs/test clients allowed
      # to open Gy/Ro sessions against this node (comma separated)
      for C in $(echo "${OCS_DIAMETER_CLIENTS:-}" | tr ',' ' '); do
        rpc "ocs add_client [{$(echo "$C" | tr '.' ',')}, undefined, diameter, undefined, false]" >/dev/null
      done
      # OCS_THRESHOLD_BYTES: the OCS's own "running low" line — when a
      # subscriber's data balance drops below it, the balance hub notifies.
      if [ -n "${OCS_THRESHOLD_BYTES:-}" ]; then
        rpc "application set_env [ocs, threshold_bytes, $OCS_THRESHOLD_BYTES]" >/dev/null
      fi
      echo "[sigscale-ocs] admin user '$ADMIN_USER' ready; REST on :8080, DIAMETER on :3868"
      return
    fi
  done
  echo "[sigscale-ocs] node did not answer in time — admin user not bootstrapped" >&2
}
bootstrap &

# bin/start's default flags open an interactive Erlang shell, which halts the
# node the moment a container's stdin closes; pass the same flags + -noinput.
exec bin/start +K true +Bi -boot_var OTPHOME /usr/lib/erlang -name "$NODENAME" -noinput
