# Hosted demo on AWS — one box, one script

The demo fleet (the same `fleet.sh demo` slice we run on a laptop) on a single
EC2 instance, with Caddy in front for HTTPS and the Taranga and ENet tenants on
their public hosts. Rebuildable from the repo in about 40 minutes; nothing on
the box is precious.

## 1. Account (once)

Root account on a `taranga.no` mailbox, MFA on root, then never use root.
Enable **AWS Organizations** and **IAM Identity Center**; one user per founder,
both admins. Create a member account `demo` and do everything below inside it.
Region **eu-north-1 (Stockholm)**. Set a **Budget** alarm before the first instance.

## 2. Instance

| Setting | Value |
|---|---|
| AMI | Ubuntu Server 24.04 LTS, x86_64 |
| Type | `r6i.2xlarge` (8 vCPU, 64 GB) — the whole fleet fits, demos never hit the memory wall; `m6i.2xlarge` (32 GB) runs the demo slice only |
| Disk | 120 GB gp3 |
| Security group | inbound **22** (your IPs only), **80**, **443**; nothing else — compose publishes many ports on the host, the SG is the fence |
| Elastic IP | allocate and attach; DNS points here |
| Key pair | yours; the script runs as `ubuntu` |

## 3. DNS (at the registrar or Route 53)

| Record | Value |
|---|---|
| `*.taranga.no` A | the Elastic IP |
| `taranga.no` A/ALIAS | the website, when there is one |
| MX etc. | from the mail provider |

The wildcard covers `shop`, `csr`, `console`, `biz`, `demo`, `id` and the
`*-enet` hosts. Certificates are per host via HTTP-01, so port 80 must be open
and DNS must resolve before step 5 (Caddy retries on its own if it does not).

## 4. Install

```bash
ssh ubuntu@<elastic-ip>
git clone --depth 1 git@github.com:kundanvarma/genalpha-bss.git /tmp/bootstrap   # or scp ops/cloud/aws-demo/ up
sudo -E DEMO_DOMAIN=taranga.no LETSENCRYPT_EMAIL=you@taranga.no /tmp/bootstrap/ops/cloud/aws-demo/install.sh
```

Alternative without a deploy key: `rsync` the checkout from a laptop to `/opt/taranga/bss` and set `GIT_URL=local` in the env file; the script then uses that checkout as-is.

**Rsync must exclude `.env`** (and build output): the box's `.env` holds the generated Postgres and Keycloak passwords; a laptop `.env` copied over it breaks every database login. Use
`rsync -az --delete --exclude .env --exclude 'services/*/target' --exclude 'apps/*/node_modules' --exclude 'apps/*/dist' ./ ubuntu@<ip>:/opt/taranga/bss/` and include `.git` so the build stamp sees the new commit.

The first run writes `/etc/taranga-demo.env` and stops — review it (ENet on or
off, console gate user/password, real-model AI keys) and re-run. The second run
prints a **deploy key**; add it to the GitHub repo as a read-only deploy key and
re-run. From there it is unattended: packages, clone, `.env` with generated
passwords, Maven build (10–20 min), image build, `fleet.sh up`, `fleet.sh demo`,
seeds (Taranga; ENet catalog, growth and Devi's history so the customer 360 is lived-in), Caddy. Re-running is always safe; it pulls, rebuilds only when the commit
changed, and restarts what is down.

Public doors when done:

| Host | What | Login |
|---|---|---|
| `shop.taranga.no` | Taranga storefront | `mira@taranga.example` / `mira` |
| `console.taranga.no`, `csr.taranga.no`, `biz.taranga.no` | staff desks | `demo` / `demo` (+ the console gate if set) |
| `shop-enet.taranga.no` … `console-enet.taranga.no` | ENet tenant | see `docs/demo-script.md` personas |
| `id.taranga.no` | Keycloak | master admin password is in `/opt/taranga/bss/.env` |

## 5. Run only when demoing

Compute is the whole bill. Stop the instance when nobody is presenting; disk and
the IP keep billing (~$15/month), compute stops. Boot to healthy is ~10 minutes
(`ops/fleet.sh status` on the box tells you when). A systemd oneshot
(`taranga-demo-slice.service`) sheds the fleet to the demo slice after every
start, so a plain start needs no hands.

```bash
aws ec2 stop-instances  --instance-ids i-…      # after the demo
aws ec2 start-instances --instance-ids i-…      # 15 min before the next one
```

A nightly safety stop with EventBridge Scheduler (23:00 Oslo):

```bash
aws scheduler create-schedule --name demo-nightly-stop \
  --schedule-expression "cron(0 23 * * ? *)" --schedule-expression-timezone Europe/Oslo \
  --flexible-time-window Mode=OFF \
  --target '{"Arn":"arn:aws:scheduler:::aws-sdk:ec2:stopInstances","RoleArn":"arn:aws:iam::<acct>:role/demo-scheduler","Input":"{\"InstanceIds\":[\"i-…\"]}"}'
```

(The role needs `ec2:StopInstances` on that instance and a trust policy for
`scheduler.amazonaws.com`.)

## 6. Update

```bash
ssh ubuntu@<elastic-ip> 'sudo -E /opt/taranga/bss/ops/cloud/aws-demo/install.sh'
```

Pulls `GIT_REF`, rebuilds jars and images only if the commit changed, restarts
what is down, regenerates the override and the Caddyfile. Seeds are idempotent;
re-run them by hand after a catalog change:
`cd /opt/taranga/bss && python3 ops/seed/seed_taranga.py`.

## What the script changes versus the laptop

* `docker-compose.cloud.yml` (generated): every tenant's issuer becomes
  `https://id.<domain>/realms/<tenant>` and Keycloak learns its public hostname
  behind the proxy. Service-to-Keycloak calls stay on the internal network.
* `.env`: generated Postgres, Keycloak and Grafana passwords; never `admin`.
* Caddy: one certificate per public host, gateway on 8080 behind all of them,
  Keycloak on 8085 behind `id.`, optional basic-auth gate on the staff consoles.
* `infra/tenants/tenants.yml` + `enet-realm.json`: the ENet tenant answers on
  `*-enet.taranga.no` in addition to `*.enet.localhost`.

## Cost (Stockholm, on-demand, ex VAT, approximate)

| | 24/7 | ~60 h/month |
|---|---|---|
| m6i.2xlarge + 120 GB + IP | ~$280 | ~$40 |
| r6i.2xlarge (64 GB) | ~$360 | ~$50 |
