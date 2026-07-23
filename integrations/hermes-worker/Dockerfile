# Headless Hermes worker — the packaged runtime of the digital workforce.
# Everything a worker needs is baked in (Hermes via the official installer,
# the BSS MCP server, the job-card skills); everything that identifies or
# empowers it arrives as ENV at start: the badge, the gateway, the brain.
# Rebuild nothing to rehire, rebrain or refire — that's the closed loop.
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates git nodejs npm \
    && rm -rf /var/lib/apt/lists/*

# Hermes via the official installer (no official image exists — verified
# July 2026); --skip-setup because config is rendered at runtime from env
RUN curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/i.sh \
    && bash /tmp/i.sh --skip-setup && rm /tmp/i.sh
ENV PATH="/root/.local/bin:${PATH}"

# The BSS MCP server (the worker's hands) + the job cards (its instructions)
COPY mcp-server /opt/bss-mcp
RUN cd /opt/bss-mcp && npm install --omit=dev
COPY hermes-worker/skills /root/.hermes/skills

COPY hermes-worker/worker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
