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

# guessing is throttled (#326) — temporary lockout only: permanent lockout hands
# anyone who knows an address a way to lock that customer out for good
bf_path = os.path.join(SEC, "brute-force.json")
if not os.path.isfile(bf_path):
    problems.append("no brute-force.json: nothing throttles password guessing")
else:
    bf = json.load(open(bf_path))
    if bf.get("bruteForceProtected") is not True:
        problems.append("brute-force.json: bruteForceProtected must be true")
    if bf.get("permanentLockout") is not False or bf.get("maxTemporaryLockouts", 0) != 0:
        problems.append("brute-force.json: lockout must be temporary only (permanentLockout false, maxTemporaryLockouts 0)")
    if not (1 <= int(bf.get("failureFactor", 0)) <= 10):
        problems.append(f"brute-force.json: failureFactor {bf.get('failureFactor')} — lock after 1..10 failures")
    if not (0 < int(bf.get("maxFailureWaitSeconds", 0)) <= 900):
        problems.append(f"brute-force.json: maxFailureWaitSeconds {bf.get('maxFailureWaitSeconds')} — the wait is capped at 15 minutes")
    if '"$SEC/brute-force.json"' not in open("ops/keycloak/converge.sh").read():
        problems.append("converge.sh does not apply brute-force.json")
for f in message_files:
    w = json.load(open(f))
    if not (w.get("invalidUserMessage") or "").strip():
        problems.append(f"{os.path.basename(f)} has no plain words for a failed (or paused) sign-in (invalidUserMessage)")
    # a paused account must read EXACTLY like any failed sign-in, or the page tells a
    # guesser which addresses have accounts (Keycloak's own default keeps them equal)
    if w.get("accountTemporarilyDisabledMessage") != w.get("invalidUserMessage"):
        problems.append(f"{os.path.basename(f)}: accountTemporarilyDisabledMessage differs from invalidUserMessage — a locked account would be revealed")

# account mail (#327): the laptop's stand-in mail server exists and the converge
# step points the realms at it; a fleet that names none would send no reset mail
if not re.search(r"^  mailpit:\n(?:    .*\n)*?    image: axllent/mailpit:v[0-9.]+\n", compose, re.M):
    problems.append("docker-compose.yml has no pinned mailpit service: the laptop's account mail has nowhere to go")
if block and "KC_SMTP_HOST" not in block.group(1):
    problems.append("keycloak-converge is not told where account mail goes (KC_SMTP_HOST)")
if "smtpServer.host" not in open("ops/keycloak/converge.sh").read():
    problems.append("converge.sh never sets the realms' mail server")

# the doc's numbers are the settings' numbers (#329): a claim is checked, not typed
doc_path = "docs/password-security.md"
if os.path.isfile(doc_path) and os.path.isfile(bf_path):
    doc = open(doc_path).read()
    bf = json.load(open(bf_path))
    for said, why in ((f"at least **{number('length')} characters**", "the minimum length"),
                      (f"After {bf.get('failureFactor')} wrong tries", "the failures before a pause"),
                      (f"up to **{int(bf.get('maxFailureWaitSeconds', 0)) // 60} minutes**", "the longest pause")):
        if said not in doc:
            problems.append(f"{doc_path} does not say \"{said}\" — {why} in the settings and the doc disagree")

# the hosted box has no published login (#323): its installer rotates them, and a
# re-run of the installer must not silently skip that step
install = open("ops/cloud/aws-demo/install.sh").read() if os.path.isfile("ops/cloud/aws-demo/install.sh") else ""
if "rotate-logins.py" not in install:
    problems.append("ops/cloud/aws-demo/install.sh no longer rotates the published demo logins — the box would accept demo/demo")

for f in sorted(glob.glob("infra/keycloak/*-realm.json")):
    if json.load(open(f)).get("passwordPolicy"):
        problems.append(f"{f} carries a passwordPolicy: with its short demo passwords Keycloak refuses the whole realm")

if problems:
    print(f"password-policy: FAIL — {len(problems)} problem(s)")
    for p in problems:
        print(f"  {p}")
    sys.exit(1)
print(f"password-policy: ok — \"{policy}\", words in {len(message_files)} locale(s), converged on every start")
