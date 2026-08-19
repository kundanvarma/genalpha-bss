#!/usr/bin/env python3
"""Bind the demo tenant's signal connectors (signal-intelligence SI-P2):
  - 'support-desk' — the NAMED servicedesk adapter polling mock-servicedesk
  - 'call-transcripts' — the GENERIC webhook; telephony/STT pushes transcripts
    (its own shape, JSON pointers map it) with the shared secret
Idempotent (PUT upserts). Secrets are env-var NAMES on the insight service."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
INSIGHT = "http://localhost:8080/insight/v1"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def put(url, body):
    r = urllib.request.Request(
        url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method="PUT")
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


desk = put(f"{INSIGHT}/connector", {
    "name": "support-desk", "kind": "servicedesk", "source": "servicedesk", "mode": "poll",
    "baseUrl": "http://mock-servicedesk:8080", "secretRef": "SERVICEDESK_API_TOKEN",
})
print(f"connector: {desk['name']} ({desk['kind']}, {desk['mode']})")

calls = put(f"{INSIGHT}/connector", {
    "name": "call-transcripts", "kind": "http-webhook", "source": "call", "mode": "webhook",
    "webhookSecretRef": "SIGNAL_HOOK_SECRET",
    "config": {"textPointer": "/transcript", "refPointer": "/callId", "langPointer": "/language"},
})
print(f"connector: {calls['name']} ({calls['kind']}, {calls['mode']}) id={calls['id']}")

chat = put(f"{INSIGHT}/connector", {
    "name": "voc-alerts-chat", "kind": "slack-webhook", "source": "alert", "mode": "notify",
    "secretRef": "VOC_ALERT_WEBHOOK_URL",
})
print(f"connector: {chat['name']} ({chat['kind']}, {chat['mode']})")
print("done")
