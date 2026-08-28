# Headless Hermes worker — the packaged runtime of the digital workforce.
# Everything a worker needs is baked in (Hermes via the official installer,
# the BSS MCP server, the job-card skills); everything that identifies or
# empowers it arrives as ENV at start: the badge, the gateway, the brain.
# Rebuild nothing to rehire, rebrain or refire — that's the closed loop.
FROM python:3.12-slim

# Node comes from NodeSource, not Debian: the Hermes browser tools refuse
# the distro's aging runtime, and the installer's own Node download needs xz
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates git xz-utils \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Pre-clone in a layer of its own: the big clone is the flaky network step
# (mid-transfer resets in the build VM), so retry it here and cache it —
# the installer sees a valid checkout and takes its update path instead
RUN git config --global http.version HTTP/1.1 \
    && for i in 1 2 3 4 5; do \
         git clone --depth 1 https://github.com/NousResearch/hermes-agent.git \
             /usr/local/lib/hermes-agent && break; \
         rm -rf /usr/local/lib/hermes-agent; sleep 10; \
       done \
    && git -C /usr/local/lib/hermes-agent rev-parse --verify HEAD

# Pre-warm the node deps too — cold npm over this link outruns the
# installer's time-box; warmed, its own npm pass is a fast no-op
RUN cd /usr/local/lib/hermes-agent \
    && for i in 1 2 3; do npm install --silent && break; sleep 10; done \
    && test -d node_modules

# Hermes via the official installer (no official image exists — verified
# July 2026); --skip-setup because config is rendered at runtime from env;
# --skip-browser because workers act through the BSS MCP server, not a
# browser; generous NODE_DEPS_TIMEOUT for the VM's slow link
RUN curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/i.sh \
    && NODE_DEPS_TIMEOUT=1800 bash /tmp/i.sh --skip-setup --skip-browser \
    && rm /tmp/i.sh
ENV PATH="/root/.local/bin:${PATH}"

# The BSS MCP server (the worker's hands) + the job cards (its instructions)
COPY mcp-server /opt/bss-mcp
RUN cd /opt/bss-mcp && npm install --omit=dev
COPY hermes-worker/skills /root/.hermes/skills

COPY hermes-worker/worker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
