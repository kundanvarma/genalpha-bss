#!/usr/bin/env python3
"""The hosted box's account mail (#328, part of #320).

On the box, verify-email and reset-password mail goes out as
notifications@taranga.no through the operator's Microsoft 365. Keycloak signs in
to Microsoft's SMTP with an OAuth token it fetches itself (XOAUTH2, client-
credentials grant) — no person's mailbox password is anywhere in the system.

The Microsoft app's tenant, client and secret live in ONE SecureString parameter
in AWS Systems Manager (default /taranga/demo/m365-mail, eu-north-1). This
script, run by install.sh, reads it through the instance's own IAM role (which
may read that parameter and nothing else — ops/cloud/aws-demo/box-role.sh), and
sets every box realm's mail settings and email verification through Keycloak's
admin API. The secret is never printed, written to a file or put in git; it
lives in Keycloak's database, as every realm's mail settings do.

  python3 ops/cloud/aws-demo/box-mail.py                       # on the box
  python3 ops/cloud/aws-demo/box-mail.py --param-file x.json --realm probe --dry-run   # anywhere
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BOX_REALMS = ["bss", "nova", "enet", "taranga"]
SMTP_HOST, SMTP_PORT = "smtp.office365.com", "587"           # Microsoft 365 SMTP AUTH, STARTTLS
SCOPE = "https://outlook.office365.com/.default"              # client credentials for SMTP.SendAsApp


def call(method, url, token=None, body=None, form=None):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def credentials(args):
    if args.param_file:
        return json.load(open(args.param_file))
    import boto3   # python3-boto3; the instance role is picked up from the metadata service
    value = boto3.client("ssm", region_name=args.region).get_parameter(
        Name=args.parameter, WithDecryption=True)["Parameter"]["Value"]
    return json.loads(value)


def smtp_settings(m):
    for k in ("tenantId", "clientId", "clientSecret", "sender"):
        if not m.get(k):
            raise SystemExit(f"[box-mail] the parameter lacks '{k}' — nothing changed")
    return {
        "host": SMTP_HOST, "port": SMTP_PORT, "starttls": "true", "ssl": "false",
        "from": m["sender"], "fromDisplayName": "Taranga", "replyTo": "",
        "auth": "true", "authType": "token", "user": m["sender"],
        "authTokenUrl": f"https://login.microsoftonline.com/{m['tenantId']}/oauth2/v2.0/token",
        "authTokenScope": SCOPE,
        "authTokenClientId": m["clientId"], "authTokenClientSecret": m["clientSecret"],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--keycloak", default=os.environ.get("KEYCLOAK_BASE", "http://localhost:8085"))
    ap.add_argument("--admin-user", default=os.environ.get("KEYCLOAK_ADMIN_USER", "admin"))
    ap.add_argument("--parameter", default="/taranga/demo/m365-mail")
    ap.add_argument("--region", default="eu-north-1")
    ap.add_argument("--param-file", help="read the credentials from a local JSON file instead (tests)")
    ap.add_argument("--realm", action="append", help="instead of the box realms")
    ap.add_argument("--dry-run", action="store_true", help="read and validate, change nothing")
    a = ap.parse_args()

    settings = smtp_settings(credentials(a))
    realms = a.realm or BOX_REALMS
    if a.dry_run:
        shown = {k: ("(set)" if k == "authTokenClientSecret" else v) for k, v in settings.items()}
        print(f"[box-mail] dry run — would set on {', '.join(realms)}: verifyEmail=true, smtpServer={json.dumps(shown)}")
        return 0
    admin_pw = os.environ.get("KEYCLOAK_ADMIN_PASSWORD")
    if not admin_pw:
        print("[box-mail] KEYCLOAK_ADMIN_PASSWORD is not set", file=sys.stderr)
        return 2
    done = []
    for realm in realms:
        token = call("POST", f"{a.keycloak}/realms/master/protocol/openid-connect/token",
                     form={"grant_type": "password", "client_id": "admin-cli",
                           "username": a.admin_user, "password": admin_pw})["access_token"]
        try:
            call("GET", f"{a.keycloak}/admin/realms/{realm}", token)
        except urllib.error.HTTPError:
            continue   # a realm this box does not serve
        call("PUT", f"{a.keycloak}/admin/realms/{realm}", token,
             body={"smtpServer": settings, "verifyEmail": True})
        done.append(realm)
    print(f"[box-mail] {len(done)} realm(s) send as {settings['from']} through Microsoft 365 "
          f"(app token, no mailbox password); email verification on: {', '.join(done)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
