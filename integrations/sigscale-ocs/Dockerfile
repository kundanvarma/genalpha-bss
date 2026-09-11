# SigScale OCS — the open-source 3GPP Online Charging System (Apache-2.0,
# https://github.com/sigscale/ocs) packaged for THIS fleet.
#
# Why not `sigscale/ocs` straight from Docker Hub? The upstream image is
# linux/amd64 only and the BEAM's JIT segfaults under QEMU on Apple-silicon
# hosts. Erlang bytecode is architecture-neutral, so this image takes the
# upstream release files as they are and runs them on the distro's own
# Erlang/OTP packages (Debian bookworm ships the same OTP 25.2.3 / erts
# 13.1.5 the release was built against). The two EAP native libraries are
# rebuilt from SigScale's own sources (c_src/, Apache-2.0) for the target
# CPU, so EAP-AKA/EAP-PWD work on arm64 and amd64 alike.
#
# The result is one Dockerfile that builds natively on the laptop AND on
# the x86 demo box, and stays a faithful SigScale OCS — nothing forked.
ARG OCS_IMAGE=sigscale/ocs:latest

FROM ${OCS_IMAGE} AS upstream

# --- native EAP libraries for the target architecture ---------------------
FROM debian:bookworm-slim AS nifs
RUN apt-get update \
 && apt-get install -y --no-install-recommends gcc libc6-dev erlang-dev libssl-dev \
 && rm -rf /var/lib/apt/lists/*
COPY c_src /build
RUN cd /build \
 && gcc -O2 -shared -fPIC -I/usr/lib/erlang/usr/include -o ocs_eap_aka.so ocs_eap_aka.c \
 && gcc -O2 -shared -fPIC -I/usr/lib/erlang/usr/include -o ocs_eap_pwd.so ocs_eap_pwd.c -lcrypto

# --- the runtime -----------------------------------------------------------
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      erlang-base erlang-asn1 erlang-crypto erlang-diameter erlang-ftp \
      erlang-inets erlang-mnesia erlang-mochiweb erlang-os-mon erlang-parsetools \
      erlang-public-key erlang-runtime-tools erlang-snmp erlang-ssl \
      erlang-syntax-tools erlang-tftp erlang-xmerl \
      erlang-dev \
      ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -u 1000 -s /bin/bash otp

COPY --from=upstream --chown=otp:otp /home/otp /home/otp

# OTP applications: run this architecture's build, not the amd64 copies the
# upstream release carries (crypto/asn1 have native code). SigScale's own
# apps (ocs, radius, sigscale_mibs, mochiweb) are pure Erlang and stay.
RUN cd /home/otp/lib \
 && for d in *; do \
      if [ -d "/usr/lib/erlang/lib/$d" ]; then rm -rf "$d" && ln -s "/usr/lib/erlang/lib/$d" "$d"; fi; \
    done \
 && rm -f /home/otp/*.core

COPY --from=nifs --chown=otp:otp /build/ocs_eap_aka.so /build/ocs_eap_pwd.so /tmp/nifs/
RUN cp /tmp/nifs/*.so /home/otp/lib/ocs-*/priv/lib/ && rm -rf /tmp/nifs

COPY --chown=otp:otp entrypoint.sh /home/otp/bin/entrypoint.sh
RUN chmod +x /home/otp/bin/entrypoint.sh

USER otp
WORKDIR /home/otp
ENV OTPHOME=/home/otp EPMD_PORT=4369 ERL_RPC=54861 ERLARGS=
# 8080 REST + GUI · 3868 DIAMETER (Ro/Gy/Gx) · 1812/1813 RADIUS
EXPOSE 8080/tcp 3868/tcp 3869/tcp 1812/udp 1813/udp
VOLUME ["/home/otp/db"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fs http://localhost:8080/health >/dev/null || exit 1
ENTRYPOINT ["bin/entrypoint.sh"]
