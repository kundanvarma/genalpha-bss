#!/usr/bin/env python3
"""The password policy keeps the standard's promise, and actually reaches the realms (#320).

infra/keycloak/security/password-policy is the one source of every tenant's
password rules. The docs say they follow NIST SP 800-63B rev 4; this checks it:

  - at least 15 characters (a password is the customer's only factor) and at
    least 64 allowed; not the username, not the email
  - NO composition rules and NO forced expiry, which the standard forbids
  - every rule in the policy has plain-language words in every messages file
  - the rules reach the realms: the converge service exists in docker-compose,
    runs converge.sh and mounts the settings; onboarding is pointed at them
  - no realm fixture carries a policy (a file with a policy and short demo
    passwords is refused whole by Keycloak — the fleet would not boot)

Prints a sentinel line 'password-policy: ok' or 'password-policy: FAIL — n
problem(s)' on stdout; exits non-zero on any problem.
"""
import glob
import json
import os
import re
import sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SEC = "infra/keycloak/security"
problems = []

try:
    policy = open(os.path.join(SEC, "password-policy")).read().strip()
except OSError as e:
    print("password-policy: FAIL — 1 problem(s)")
    print(f"  no policy file: {e}")
    sys.exit(1)

rules = {}
for part in [p.strip() for p in policy.split(" and ") if p.strip()]:
    m = re.fullmatch(r"([A-Za-z]+)(?:\((.*)\))?", part)
    if not m:
        problems.append(f"unreadable policy part '{part}'")
        continue
    rules[m.group(1)] = m.group(2)

def number(rule):
    try:
        return int(rules.get(rule) or "")
    except ValueError:
        return None

if number("length") is None or number("length") < 15:
    problems.append(f"length must be at least 15 (single-factor passwords, NIST 800-63B rev 4): policy is '{policy}'")
if number("maxLength") is None or number("maxLength") < 64:
    problems.append(f"maxLength must allow at least 64 characters: policy is '{policy}'")
for required in ("notUsername", "notEmail"):
    if required not in rules:
        problems.append(f"'{required}' is missing from the policy")
for banned in ("digits", "upperCase", "lowerCase", "specialChars", "regexPattern", "forceExpiredPasswordChange"):
    if banned in rules:
        problems.append(f"'{banned}' is a composition or expiry rule — NIST 800-63B rev 4 forbids it")

WORDS = {
    "length": "invalidPasswordMinLengthMessage",
    "maxLength": "invalidPasswordMaxLengthMessage",
    "notUsername": "invalidPasswordNotUsernameMessage",
    "notEmail": "invalidPasswordNotEmailMessage",
    "passwordBlacklist": "invalidPasswordBlacklistedMessage",
}
message_files = sorted(glob.glob(os.path.join(SEC, "messages-*.json")))
if not message_files:
    problems.append("no messages-<locale>.json: the rules would show Keycloak's raw error keys")
for f in message_files:
    words = json.load(open(f))
    for rule in rules:
        key = WORDS.get(rule)
        if key and not (words.get(key) or "").strip():
            problems.append(f"{os.path.basename(f)} has no words for '{rule}' ({key})")
    for key, text in words.items():
        # Keycloak formats these with java.text.MessageFormat, where a straight
        # apostrophe is an escape: "can't" reaches the page as "cant". Use ’.
        if "'" in text:
            problems.append(f"{os.path.basename(f)} {key} has a straight apostrophe — the page would drop it; use ’")

compose = open("docker-compose.yml").read()
block = re.search(r"^  keycloak-converge:\n((?:    .*\n|\s*\n)+)", compose, re.M)
if not block:
    problems.append("docker-compose.yml has no keycloak-converge service: the policy would never reach a realm")
else:
    body = block.group(1)
    for needle in ("./ops/keycloak/converge.sh:/converge/converge.sh", "./infra/keycloak/security:/security:"):
        if needle not in body:
            problems.append(f"keycloak-converge does not mount {needle}")
if "BSS_ONBOARDING_SECURITY_DIR" not in compose:
    problems.append("onboarding is not pointed at the security settings: a new tenant would be born without the rules")
converge = open("ops/keycloak/converge.sh").read()
if "password-policy" not in converge or "passwordPolicy" not in converge:
    problems.append("converge.sh does not apply the password-policy file")

# a blocklist the policy names must be IN the image Keycloak runs, or setting the
# policy fails on every realm (#325)
m = re.search(r"passwordBlacklist\((.+?)\)", policy)
if m:
    name = m.group(1)
    dockerfile = open("infra/keycloak/Dockerfile").read() if os.path.isfile("infra/keycloak/Dockerfile") else ""
    if f"password-blacklists/{name}" not in dockerfile:
        problems.append(f"the policy names blocklist '{name}' but infra/keycloak/Dockerfile does not build it into password-blacklists/")
    kc = re.search(r"^  keycloak:\n((?:    .*\n)+)", compose, re.M)
    if not kc or "build: ./infra/keycloak" not in kc.group(1):
        problems.append("the keycloak service does not run the image built from infra/keycloak — the blocklist would be missing")

for f in sorted(glob.glob("infra/keycloak/*-realm.json")):
    if json.load(open(f)).get("passwordPolicy"):
        problems.append(f"{f} carries a passwordPolicy: with its short demo passwords Keycloak refuses the whole realm")

if problems:
    print(f"password-policy: FAIL — {len(problems)} problem(s)")
    for p in problems:
        print(f"  {p}")
    sys.exit(1)
print(f"password-policy: ok — \"{policy}\", words in {len(message_files)} locale(s), converged on every start")
