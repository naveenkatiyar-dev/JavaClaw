# JavaClaw on AWS (EC2 + Cloudflare Tunnel)

One EC2 instance in its own VPC, created by a single CloudFormation stack. Nothing listens on a
public port:

```
phone ── WhatsApp ──► wacli (outbound) ─┐
                                        ├─► JavaClaw on 127.0.0.1:8080 ──► Gemini API
browser ─► Cloudflare Access (login) ─► cloudflared (outbound tunnel) ─┘
you ─► AWS Systems Manager (no SSH) ─► shell on the instance
```

| File | Runs on | Purpose |
|---|---|---|
| `javaclaw.yaml` | CloudFormation | VPC, subnet, no-inbound security group, IAM role (reads only `/javaclaw/*` parameters), encrypted disk, instance |
| `bootstrap.sh` | instance (root) | installs JDK 25, wacli, cloudflared, Chromium deps; builds the jar from the repo; systemd services |
| `run.sh` | instance (systemd) | reads secrets from SSM Parameter Store into the process environment, then starts the app / tunnel |
| `deploy.sh` | your machine | secrets, stack create/update, WhatsApp pairing, logs, update, start/stop, delete |

Secrets (Gemini key, tunnel token, `AGENT.private.md`) live only in SSM Parameter Store as
`SecureString`s. They are not in the repo, the jar, the instance's disk or the stack.

## Isolation

Everything is in one stack named `javaclaw`, in its own VPC (`10.42.0.0/16`), tagged
`Project=javaclaw`; its IAM role can read only `/javaclaw/*` parameters. `deploy.sh down` removes
all of it. For separation from other projects, deploy it into its own member account in AWS
Organizations (`AWS_PROFILE=personal-apps ./deploy.sh up`) and keep experiments in another
member account. [`org/`](org/README.md) has the guardrail SCPs that cap what those accounts can create.

## Prerequisites (once)

- AWS CLI v2 and the Session Manager plugin:
  `brew install awscli && brew install --cask session-manager-plugin`, then sign in (`aws configure sso` or `aws configure`).
- A domain on Cloudflare (free plan; the domain's nameservers must point to Cloudflare).
- The repo pushed to the fork/branch the stack clones (`RepoUrl`/`RepoBranch` in `javaclaw.yaml`).

## Cloudflare (once, in the dashboard)

1. **Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared.** Name it `javaclaw` and
   copy the token from the install command (the long string after `--token`).
2. In the tunnel, **Public hostname → Add**: subdomain `assistant`, your domain, service
   `HTTP` → `localhost:8080`.
3. **Zero Trust → Access → Applications → Add → Self-hosted**: application domain
   `assistant.<your-domain>`, policy *Allow* → *Emails* → your email. With the default
   *One-time PIN* login, Cloudflare emails you a code. Without this step the UI would be public,
   and JavaClaw has no login of its own.

## Deploy

```bash
cd deploy/aws
./deploy.sh secrets   # Gemini key (from Keychain service 'javaclaw-google-api-key' or a hidden prompt), tunnel token, chat JID, model
./deploy.sh up        # ~3 min for the stack, then ~10-15 min first-boot build
./deploy.sh pair      # scan the QR code: WhatsApp → Linked devices → Link a device
./deploy.sh restart
```

Stop JavaClaw on your Mac before pairing the server; both would answer the same chat.
Open `https://assistant.<your-domain>`.

Default region is `ap-south-1` (Mumbai); override with `AWS_REGION=...`. Stack parameters can be
overridden on `up`, for example `./deploy.sh up UseSpot=false` or
`./deploy.sh up StartCron='cron(0 6 * * ? *)'`. Changes that replace the instance (instance type,
AMI, disk, Spot on/off, user data) lose its disk; `up` warns first.

## Day to day

| | |
|---|---|
| `./deploy.sh update` | pull the latest commit of the branch, rebuild, restart |
| `./deploy.sh logs` | follow the app log |
| `./deploy.sh shell` | shell on the instance (`sudo -iu javaclaw` for the app user) |
| `./deploy.sh stop` / `start` | stop or start now, outside the daily schedule; the disk (workspace, WhatsApp session) is kept. While stopped the assistant is offline; WhatsApp messages wait and scheduled tasks catch up on start |
| `./deploy.sh secrets` | rotate a secret, then `restart` |
| `./deploy.sh down` | delete everything, including the disk |

The config on the instance (`/opt/javaclaw/app/app/src/main/resources/application.private.yaml`)
is written once on first boot; after that the settings UI owns it, as on your Mac.

## Cost (rough, Mumbai; check current prices)

Defaults: `t4g.small` (ARM, 2 GiB) as a **persistent Spot** instance that stops (not terminates)
on interruption, a 20 GiB gp3 disk, and a daily schedule that runs it 07:00-23:00 IST.

| | 24/7 on-demand `t3.small`, 30 GiB | Defaults |
|---|---|---|
| Compute | ~USD 16 | ~USD 2-3 |
| Disk (billed even when stopped) | ~USD 2.7 | ~USD 1.8 |
| Public IPv4 (only while running) | ~USD 3.6 | ~USD 2.4 |
| **Total / month** | **~USD 22** | **~USD 6-7** |

Session Manager, Parameter Store (standard), EventBridge Scheduler, Budgets and Cloudflare
Tunnel/Access cost nothing at this volume.

**Kill switch:** `./deploy.sh up AlertEmail=you@example.com` adds a budget for this account that
stops the instance once the month's cost reaches `KillSwitchUsd` (default 10; set it above normal
spend) and emails you at 80%. Billing data lags by hours, so it limits damage rather than
guaranteeing a cap. Start the instance again with `./deploy.sh start` once you've checked the
bill. For the organization-wide counterpart (an SCP that blocks new spend), see
[`org/`](org/README.md). Turn the schedule off with `./deploy.sh up ScheduleEnabled=false`
(24/7, about USD 2 more). A Spot interruption stops the instance until capacity returns, which is
rare for small instance types; `UseSpot=false` avoids it for about USD 4-5 more per month.
