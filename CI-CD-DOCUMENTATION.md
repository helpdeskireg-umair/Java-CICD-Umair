# CI/CD Pipeline Documentation
## Java Spring Boot Application — `my-java-app`

**Author:** Umair Hassan
**Repository:** https://github.com/helpdeskireg-umair/Java-CICD-Umair
**Application type:** Spring Boot 3.2 (Java 17) REST API built with Maven
**CI/CD platform:** GitHub Actions
**Target environment:** Ubuntu 22.04 VPS (CloudPanel) — `145.223.79.115`

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Application Overview](#3-application-overview)
4. [Repository & Workflow Structure](#4-repository--workflow-structure)
5. [Continuous Integration (CI)](#5-continuous-integration-ci)
6. [Continuous Deployment (CD)](#6-continuous-deployment-cd)
7. [VPS / Server Environment](#7-vps--server-environment)
8. [Application Service (systemd)](#8-application-service-systemd)
9. [Reverse Proxy Configuration (Nginx)](#9-reverse-proxy-configuration-nginx)
10. [SSH Access for Automation](#10-ssh-access-for-automation)
11. [GitHub Secrets](#11-github-secrets)
12. [Deployment Flow — End to End](#12-deployment-flow--end-to-end)
13. [First-Time (Bootstrap) Deployment](#13-first-time-bootstrap-deployment)
14. [Verification & Proof of Success](#14-verification--proof-of-success)
15. [Troubleshooting Guide](#15-troubleshooting-guide)
16. [Known Issues Resolved](#16-known-issues-resolved)
17. [Security Considerations](#17-security-considerations)
18. [Best Practices & Future Improvements](#18-best-practices--future-improvements)
19. [Glossary](#19-glossary)
20. [Appendix — All Commands](#20-appendix--all-commands)

---

## 1. Executive Summary

A minimal Spring Boot REST API serves as the vehicle for proving a complete,
fully-automated CI/CD pipeline. The pipeline is triggered by a simple `git push`
and performs the following automatically, with **no human intervention**:

1. **Continuous Integration** — compile, run unit/integration tests
   (3 × MockMvc), and package an executable JAR.
2. **Continuous Deployment** — transfer the JAR to a production VPS over SSH and
   restart the application service hosted by `systemd`.

The pipeline is production-proven: every step is verified with a post-deploy
**health check** that fails the build if the application does not come up after a
restart. Two real-world incidents during setup (an occupied port and a
startup-race condition) were caught and fixed, and are documented in
[§16 Known Issues Resolved](#16-known-issues-resolved).

---

## 2. Architecture Overview

```
                DEVELOPER
                    │
                    │  git push origin main
                    ▼
        ┌─────────────────────────┐
        │      GitHub Actions      │
        │   .github/workflows/ci  │
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │  JOB 1: build-and-test   │   runs on: push + pull_request
        │  checkout (actions/checkout@v5)
        │  JDK 17 (actions/setup-java@v5, temurin, maven cache)
        │  mvn clean verify ──► 3 tests pass
        │  upload-artifact@v5 (target/*.jar)
        └────────────┬────────────┘
                     │ passes
        ┌────────────▼────────────┐
        │  JOB 2: deploy           │   runs on: push to main ONLY
        │  download-artifact@v5
        │  install SSH key (from secrets)
        │  scp app.jar → VPS:/opt/my-java-app/
        │  systemctl restart my-java-app
        │  HEALTH CHECK (retry until up)
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │         VPS              │  145.223.79.115
        │  systemd ──► java -jar   │  port 8081 (127.0.0.1)
        │  Nginx   ──► /java/proxy │  port 80  (public)
        └──────────────────────────┘
```

**High-level component table**

| Component                | Technology                          | Purpose                              |
|--------------------------|-------------------------------------|--------------------------------------|
| Application              | Spring Boot 3.2.5 / Java 17 / Maven | REST API returning a greeting        |
| CI                       | GitHub Actions (Job 1)              | Test + package on every push / PR    |
| CD                       | GitHub Actions (Job 2)              | Deploy + restart + verify on `main`  |
| Artifact transfer        | `scp` (OpenSSH)                     | Copy JAR to server                   |
| Process manager          | `systemd`                           | Auto-start on boot, auto-restart     |
| Reverse proxy            | `nginx`                             | Route public `/java` → app (8081)    |
| Secret storage           | GitHub Actions Secrets              | Store host, user, SSH private key    |

---

## 3. Application Overview

| Property        | Value                                                        |
|-----------------|--------------------------------------------------------------|
| Name            | `my-java-app`                                                |
| Group / artifact| `com.awais:my-java-app:1.0.0`                                |
| Packaging       | Executable JAR (fat jar via Spring Boot repackage)           |
| Endpoint        | `GET /` → `"Hello, Umair! This app was deployed with a CI/CD pipeline."` |
| Port            | `8081` (bound to `127.0.0.1`)                                |
| Tests           | 3 × JUnit 5 + MockMvc (HTTP 200, name, CI/CD message)        |

> Port note: the default Spring Boot port (8080) is **occupied** by a Docker
> mail-server container on this VPS, so the app runs on **8081** bound to
> loopback only. See [§8 Application Service](#8-application-service-systemd).

### Source Files

```
src/
├── main/java/com/awais/myapp/
│   ├── MyJavaAppApplication.java     # Spring Boot entry point
│   └── HelloController.java          # GET / endpoint
├── main/resources/application.properties
└── test/java/com/awais/myapp/
    └── HelloControllerTest.java      # 3 MockMvc tests
```

---

## 4. Repository & Workflow Structure

```
Java-CICD-Umair/
├── .github/
│   └── workflows/
│       └── ci.yml                    # The entire pipeline
├── src/
│   ├── main/java/com/awais/myapp/
│   ├── main/resources/
│   └── test/java/com/awais/myapp/
├── CI-CD-DOCUMENTATION.md            # This document
├── pom.xml                           # Maven build definition
└── README.md
```

---

## 5. Continuous Integration (CI)

### 5.1 Trigger Conditions

| Event                 | Branch   | CI runs | CD runs |
|-----------------------|----------|:-------:|:-------:|
| `push`                | `main`   | ✔       | ✔       |
| `pull_request`        | `main`   | ✔       | ✖       |

CD is intentionally **disabled for pull requests** — it only runs on pushes to
the protected `main` branch. This prevents unreviewed code from reaching
production.

### 5.2 Concurrency Guard

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

If two pushes happen quickly, the newest push **cancels the older run** on the
same branch. This avoids two deploys racing each other and wasting build time.

### 5.3 Job: `build-and-test`

| Step                      | Action                                                        | Why                                            |
|---------------------------|---------------------------------------------------------------|------------------------------------------------|
| 1. Checkout               | `actions/checkout@v5`                                         | Fetch repo onto the runner                     |
| 2. Set up Java 17         | `actions/setup-java@v5` (Temurin 17, `cache: maven`)          | Correct JDK; Maven cache speeds up builds      |
| 3. Build & test           | `mvn clean verify --no-transfer-progress`                     | Compile, test (3/3), package fat JAR           |
| 4. Upload JAR artifact    | `actions/upload-artifact@v5` → `target/*.jar` (1 day retention) | Hand the JAR to the deploy job                 |

If *any* test fails, `mvn clean verify` exits non-zero, the job fails, and the
deploy job never starts (**fail-fast behavior**).

---

## 6. Continuous Deployment (CD)

### 6.1 Job: `deploy`

Control flow:

```yaml
deploy:
  runs-on: ubuntu-latest
  needs: build-and-test                # only if CI passed
  if: github.ref == 'refs/heads/main' && github.event_name == 'push'
```

| Step                    | Action                                                                      |
|-------------------------|-----------------------------------------------------------------------------|
| 1. Checkout             | `actions/checkout@v5`                                                        |
| 2. Download JAR         | `actions/download-artifact@v5` → `target/`                                  |
| 3. Install SSH key      | Write `VPS_SSH_KEY` → `~/.ssh/id_rsa`, `chmod 600`, `ssh-keyscan` → known_hosts |
| 4. Copy JAR             | `scp target/my-java-app-*.jar root@145.223.79.115:/opt/my-java-app/app.jar` |
| 5. Restart app          | `ssh ... "sudo systemctl restart my-java-app"`                              |
| 6. Health check         | Poll `http://127.0.0.1:8081/` for up to 60s; fail the build if not ready     |

### 6.2 Health Check (with retry)

```yaml
- name: Verify app is up
  run: |
    ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} 'for i in $(seq 1 30); do \
      curl -sf http://127.0.0.1:8081/ >/dev/null && { echo "health check OK"; exit 0; }; \
      sleep 2; done; echo "app failed to come up" >&2; exit 1'
```

`systemctl restart` returns **before** Spring Boot has bound its port, so a
single immediate request can fail. The retry loop (30 attempts × 2s = up to 60s)
is what makes the check reliable. See [§16.2](#162-startup-race-condition).

### 6.3 Current Workflow File (`ci.yml`)

```yaml
name: CI/CD Pipeline

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v5
      - name: Set up Java 17
        uses: actions/setup-java@v5
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      - name: Build with Maven & run tests
        run: mvn clean verify --no-transfer-progress
      - name: Upload JAR artifact
        uses: actions/upload-artifact@v5
        with:
          name: app-jar
          path: target/*.jar
          retention-days: 1

  deploy:
    name: Deploy to VPS
    runs-on: ubuntu-latest
    needs: build-and-test
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - name: Checkout code
        uses: actions/checkout@v5
      - name: Download JAR artifact
        uses: actions/download-artifact@v5
        with:
          name: app-jar
          path: target/
      - name: Set up SSH key
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.VPS_SSH_KEY }}" > ~/.ssh/id_rsa
          chmod 600 ~/.ssh/id_rsa
          ssh-keyscan -H "${{ secrets.VPS_HOST }}" >> ~/.ssh/known_hosts
      - name: Copy JAR to VPS
        run: |
          scp target/my-java-app-*.jar \
            ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/my-java-app/app.jar
      - name: Restart app on VPS
        run: |
          ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} \
            "sudo systemctl restart my-java-app"
      - name: Verify app is up
        run: |
          ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} 'for i in $(seq 1 30); do curl -sf http://127.0.0.1:8081/ >/dev/null && { echo "health check OK"; exit 0; }; sleep 2; done; echo "app failed to come up" >&2; exit 1'
```

**Design points**

- The scp target uses a **wildcard** (`target/my-java-app-*.jar`) instead of a
  hard-coded version, so a future version bump does not silently break the CD
  path.
- The JAR is always written to the same name (`app.jar`) on the server, keeping
  the `systemd` unit unchanged.
- SSH host keys are pinned via `ssh-keyscan` to defeat host-key prompts in
  non-interactive mode.
- Secrets are only referenced inside the workflow; they are never committed.

---

## 7. VPS / Server Environment

| Setting        | Value                                                       |
|----------------|-------------------------------------------------------------|
| IP             | `145.223.79.115`                                            |
| OS             | Ubuntu 22.04 LTS                                            |
| Hostname       | `mail.chumflow.com`                                         |
| Control panel  | CloudPanel (admin UI at `https://145.223.79.115:8443`)      |
| SSH user       | `root` (key-based, non-interactive)                         |
| Runtime        | `openjdk-17-jdk`, `maven`                                   |
| Services       | `my-java-app` (systemd), `nginx`, a Docker mail container   |

### Port Allocation

| Port  | Owner                                        | Scope             | Purpose                         |
|-------|----------------------------------------------|-------------------|---------------------------------|
| `80`  | nginx (system)                               | public            | React app `/`, Java app `/java` |
| `8080`| Docker container (`docker-proxy`, mail UI)   | public            | **NOT ours** — pre-existing     |
| `8081`| `my-java-app` (Tomcat)                       | loopback only     | Java app (not directly exposed) |
| `8443`| CloudPanel admin panel                       | public            | server administration           |

---

## 8. Application Service (systemd)

### 8.1 Service Unit — `/etc/systemd/system/my-java-app.service`

```ini
[Unit]
Description=My Java App - CI/CD Demo
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/my-java-app
ExecStart=/usr/bin/java -jar /opt/my-java-app/app.jar \
  --server.port=8081 --server.address=127.0.0.1
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

### 8.2 Why this shape

- **`--server.port=8081`** — port 8080 is taken by the Docker mail container;
  we deliberately do **not** touch that container.
- **`--server.address=127.0.0.1`** — the app binds to loopback only. It is never
  directly exposed to the internet; only Nginx (via `/java`) reaches it. This
  shrinks the attack surface.
- **`Restart=on-failure` + `RestartSec=5`** — crash auto-restart, the
  self-healing behavior we expect from a production service.
- **`SuccessExitStatus=143`** — a clean shutdown because of a SIGTERM (128+15)
  is treated as a success, so normal restarts don’t report errors.
- **`After=network.target`** — start only once networking is available.
- **`WantedBy=multi-user.target`** → **starts on boot**.

### 8.3 Management Commands

```bash
systemctl daemon-reload        # after editing the unit file
systemctl enable my-java-app   # start on boot
systemctl start my-java-app    # do this once a JAR exists
systemctl restart my-java-app  # what the pipeline runs
systemctl status my-java-app   # verify
journalctl -u my-java-app -n 50 --no-pager   # logs
```

---

## 9. Reverse Proxy Configuration (Nginx)

### 9.1 Context

The same VPS already hosts a **React application** that owns HTTP traffic on
`80` for `server_name 145.223.79.115`. Because two server blocks cannot both own
a hostname, the Java app is exposed as a **URL path** of the same vhost:
`http://145.223.79.115/java`.

> Platform quirk discovered on this server: Nginx **only auto-loads files ending
> in `.conf`** from `/etc/nginx/sites-enabled/`. Config files must use that
> suffix (this came from the earlier React app setup).

### 9.2 Final Config — `/etc/nginx/sites-available/my-react-app.conf`

```nginx
server {
    listen 80;
    server_name 145.223.79.115;

    root /var/www/my-react-app/build;
    index index.html;

    # Java (Spring Boot) app — reverse proxy to localhost:8081
    location /java {
        proxy_pass         http://127.0.0.1:8081/;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }

    # React SPA
    location / {
        try_files $uri /index.html;
    }

    location /static/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

### 9.3 Routing Summary

| Request                      | Served by                     |
|------------------------------|-------------------------------|
| `GET /`                      | React static site (`index.html`) |
| `GET /java`                  | Java app via `proxy_pass` to `127.0.0.1:8081/` |
| `GET /java/**`               | Java app (path prefix removed by `proxy_pass /`) |

> `/etc/nginx/sites-enabled/my-react-app.conf` is a symlink to the
> `sites-available` file — the standard Ubuntu convention.

### 9.4 Nginx Operations

```bash
nginx -t                 # validate config before reload
systemctl reload nginx   # zero-downtime config reload
```

---

## 10. SSH Access for Automation

GitHub Actions connects to the VPS as `root` using a **dedicated RSA 4096
deploy key** (no passphrase — automation cannot type one).

### 10.1 Key Generation (performed on the VPS)

```bash
ssh-keygen -t rsa -b 4096 -C "github-actions-java" \
  -f /root/.ssh/java_github_actions -N ""

cat /root/.ssh/java_github_actions.pub >> /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys
```

### 10.2 Verify Root Key Login Is Allowed

Ubuntu 22.04’s default is `PermitRootLogin prohibit-password` — this **allows
key** login while **forbidding password** login, which is exactly what we want:

```bash
grep -i PermitRootLogin /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf
```

### 10.3 Test Non-interactive Login

```bash
ssh -i ~/.ssh/java_github_actions root@145.223.79.115 'echo OK; sudo -n true && echo NOPASSWD_OK'
```

### 10.4 NOPASSWD sudoers (redundant for root, but explicit)

```bash
printf 'root ALL=(ALL) NOPASSWD: /bin/systemctl restart my-java-app\n' \
  > /etc/sudoers.d/my-java-app
chmod 440 /etc/sudoers.d/my-java-app
visudo -c
```

---

## 11. GitHub Secrets

Configured at **Settings → Secrets and variables → Actions** on the repository.
Secrets are encrypted, masked in logs, and never exposed in checkout copies.

| Secret name   | Value                            | Used by                            |
|---------------|----------------------------------|------------------------------------|
| `VPS_HOST`    | `145.223.79.115`                 | `ssh` / `scp` / `ssh-keyscan`      |
| `VPS_USER`    | `root`                           | SSH account for deploy             |
| `VPS_SSH_KEY` | Full RSA private key (`BEGIN…END`) | Written to `~/.ssh/id_rsa` on runner |

**Non-negotiables**

- The private key must never be committed, pasted into logs, emails, or e.g. an
  unencrypted chat/channel.
- GitHub never passes secrets to workflows triggered by **pull requests from
  forks**; combined with the `push → main` deploy guard, this is defence in
  depth for a public repo.

---

## 12. Deployment Flow — End to End

```
1.  Developer pushes code to main
2.  GitHub Actions run starts (job 1 + job 2 queued)
3.  Job 1 (CI): checkout → JDK 17 → mvn clean verify (tests) → upload JAR
4.  Job 1 must pass; otherwise the pipeline stops with no deploy
5.  Job 2 (CD): download JAR → install SSH key from secrets
6.  scp  app.jar → 145.223.79.115:/opt/my-java-app/app.jar
7.  ssh  systemctl restart my-java-app
8.  systemd launches: java -jar app.jar --server.port=8081 --server.address=127.0.0.1
9.  Health check polls http://127.0.0.1:8081/ (up to 60s) → run green
10. Visitors reach the app via http://145.223.79.115/java → Nginx → 127.0.0.1:8081
```

Total wall time ≈ 1 minute.

---

## 13. First-Time (Bootstrap) Deployment

Before automation can work, the server needs one JAR present so `systemctl
start` and the health check pass. Do this **once**, manually:

```bash
# Local machine
mvn clean package -DskipTests
scp target/my-java-app-1.0.0.jar root@145.223.79.115:/opt/my-java-app/app.jar

# On the VPS
systemctl start my-java-app
curl -s http://127.0.0.1:8081/    # expect the greeting
```

After this point, every push to `main` is fully automated.

---

## 14. Verification & Proof of Success

### 14.1 Automated Gate

- `mvn clean verify` runs **3 tests**: HTTP 200, contains `"Hello, Umair!"`,
  contains `"CI/CD pipeline"` — all pass on every run.
- The pipeline was executed and observed **green end-to-end**, including the
  post-deploy health check (see [§16.2](#162-startup-race-condition) for how it
  caught a real race during setup).

### 14.2 Live Checks

```bash
curl -s http://145.223.79.115/java        # Java app through Nginx
# HTTP 200
# Hello, Umair! This app was deployed with a CI/CD pipeline.

curl -s http://145.223.79.115/             # React app unchanged
# HTTP 200
```

### 14.3 GitHub Actions Status

Latest run on `main`: **Build & Test ✅ · Deploy to VPS ✅** (all steps green,
including `Verify app is up → success`).

---

## 15. Troubleshooting Guide

| Symptom | Likely cause | Fix |
|---|---|---|
| `deploy` fails, `Permission denied (publickey)` | Deploy key not in `authorized_keys`, or `PermitRootLogin no` | Re-add pub key; ensure perms `700/600`; `grep PermitRootLogin /etc/ssh/sshd_config*` |
| `ERROR: Port XXXX was already in use` | Port occupied by another process | Check `ss -ltnp | grep :PORT`; move app via `--server.port=8081 --server.address=127.0.0.1` |
| App crash-loops | JAR missing or bad JAR | `systemctl status` + `journalctl -u my-java-app -n 50` |
| Nginx ignores config | File not `*.conf` in `sites-enabled/` | Rename to `my-*.conf` — the platform only loads `.conf` |
| `conflicting server name … ignored` | Two `server_name` blocks for same host | Merge into ONE vhost; use a `location` path per app |
| Health-check step flaky right after restart | Spring Boot still starting | Use the retry loop (30×2s) instead of one shot |
| `scp … Permission denied` | Deploy dir not writable | `mkdir -p /opt/my-java-app`; check ownership |
| Tests fail in CI but pass locally | Wrong JDK on local machine | Pin JDK 17; explicit `JAVA_HOME`; verify with `mvn -v` |
| `API rate limit exceeded` (GitHub) | Unauthenticated REST calls | Use `gh`/PAT or GitHub UI — not pipeline-related |

---

## 16. Known Issues Resolved

### 16.1 Port Conflict — Docker occupies 8080

**Symptom:** application failed to start with `Port 8080 already in use`.
**Diagnosis:** `ss -ltnp | grep :8080` → `docker-proxy` (a mail-server UI
container that predates our setup).
**Resolution:** run the app on **8081**, bound to loopback only, and expose it
through Nginx. The Docker container was intentionally left untouched.

### 16.2 Startup Race Condition — health check fired too early

**Symptom:** deploy job failed at the health-check step even though the app was
healthy moments later.
**Diagnosis:** `systemctl restart` returns before Spring Boot binds its port; a
single immediate request gets `connection refused`.
**Resolution:** replaced the one-shot check with a retry loop polling up to 60 s.
This exact scenario is now **caught by the pipeline itself**, which is the whole
point of a post-deploy health check.

### 16.3 Nginx `server_name` conflict

**Symptom:** our Java `server` block was ignored
(`conflicting server name "145.223.79.115" … ignored`).
**Diagnosis:** the existing React vhost already declared that `server_name`.
**Resolution:** removed the duplicate `server` block and added a
`location /java` proxy rule inside the existing React vhost, so both apps share
port 80 — React at `/`, Java at `/java`.

---

## 17. Security Considerations

- **Deploy as root, key-only** — password root login is disabled
  (`prohibit-password`); automation uses a dedicated RSA-4096 key with no
  passphrase. Guard the private key like a credential.
- **App not internet-exposed** — Tomcat binds `127.0.0.1:8081`; only Nginx can
  reach it. (Recommended follow-up: a dedicated, non-root service user.)
- **Secrets never committed** — keys/hosts live in encrypted GitHub Secrets,
  masked in logs, invisible to the repo.
- **PR guard** — deployment only on `push` to `main`, never on PRs (and fork PRs
  cannot read secrets anyway).
- **SSH hardening** — `ssh-keyscan` pins host keys before `scp`/`ssh`, and the
  deploy key is stored only in the encrypted secret.

---

## 18. Best Practices & Future Improvements

| Area | Practice applied | Suggested next step |
|------|------------------|---------------------|
| CI | Tests gate every change | Add coverage reporting (JaCoCo) |
| CD | Health check after deploy | Add rollback (keep previous JAR, restart on failure) |
| Versioning | Path uses `*.jar` glob, server always `app.jar` | Build with hash/Git-SHA labels for traceability |
| Access | Dedicated deploy key | Create a non-root `myapp` system user; restrict to needed dirs |
| Caching | `setup-java` Maven cache | Cache NPM/Yarn too (once a frontend is added) |
| Protection | PR-only CI, main-only CD | Branch protection rules + required status checks |
| Observability | systemd + journald logs | Centralised logging, uptime monitoring, Slack/email notifications |
| Actions | Pinned `@v5`, Node 20+ | Consider Dependabot for action upgrades |

---

## 19. Glossary

| Term | Meaning |
|------|---------|
| **CI** | Continuous Integration — test & package every change automatically |
| **CD** | Continuous Deployment — ship changes to production automatically |
| **Artifact** | Built output (the packaged JAR) passed between jobs |
| **Job** | A unit of work in a GitHub Actions workflow |
| **Runner** | A machine that executes workflow jobs (here: GitHub-hosted Ubuntu) |
| **systemd** | Linux init/service manager that supervises the app process |
| **Reverse proxy** | nginx forwarding requests to an internal upstream server |
| **Fat JAR** | Self-contained JAR with dependencies (Spring Boot repackage) |
| **Health check** | Automated verification that the app accepts requests post-deploy |

---

## 20. Appendix — All Commands

### VPS one-time setup

```bash
apt update && apt install -y openjdk-17-jdk maven
mkdir -p /opt/my-java-app

# Deploy key
ssh-keygen -t rsa -b 4096 -C "github-actions-java" -f /root/.ssh/java_github_actions -N ""
cat /root/.ssh/java_github_actions.pub >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys

# sudoers (explicit NOPASSWD for restart)
printf 'root ALL=(ALL) NOPASSWD: /bin/systemctl restart my-java-app\n' > /etc/sudoers.d/my-java-app
chmod 440 /etc/sudoers.d/my-java-app && visudo -c
```

### systemd unit

```bash
cat > /etc/systemd/system/my-java-app.service <<'EOF'
[Unit]
Description=My Java App - CI/CD Demo
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/my-java-app
ExecStart=/usr/bin/java -jar /opt/my-java-app/app.jar --server.port=8081 --server.address=127.0.0.1
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable my-java-app
```

### Nginx (inside the React vhost — file `my-react-app.conf`)

```
location /java {
    proxy_pass         http://127.0.0.1:8081/;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
}
```

```bash
nginx -t && systemctl reload nginx
```

### GitHub secrets

`VPS_HOST=145.223.79.115` · `VPS_USER=root` · `VPS_SSH_KEY=<RSA private key>`

### First deploy / day-to-day

```bash
mvn clean package -DskipTests                 # local build
scp target/*.jar root@145.223.79.115:/opt/my-java-app/app.jar
ssh root@145.223.79.115 'systemctl restart my-java-app'
curl -s http://145.223.79.115/java            # verify
```

---

*Document reflects the pipeline as deployed and verified on 2026-09-04.*