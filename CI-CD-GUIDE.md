# CI/CD for Spring Boot using GitHub Actions and a VPS (Using Nginx)

## Prerequisites

- GitHub repository
- VPS (Ubuntu 22.04) with SSH access
- SSH key pair
- Basic knowledge of GitHub Actions / YAML

---

## 1. Create a GitHub Repository

1. Go to **GitHub** → **New repository**.
2. Name it (e.g., `Java-CICD-Umair`), set public/private, click **Create repository**.
3. Push your existing project:

```bash
git init
git remote add origin https://github.com/<your-username>/Java-CICD-Umair.git
git add .
git commit -m "Initial commit"
git branch -M main
git push -u origin main
```

## 2. Initial VPS Setup (first time only)

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-17-jdk maven nginx git
sudo mkdir -p /opt/my-java-app
```

## 3. Set Up the App as a Service (systemd)

Auto-starts on boot and auto-restarts on crash.

```bash
sudo nano /etc/systemd/system/my-java-app.service
```

```ini
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
```

> **Why 8081?** Port 8080 is taken by a Docker mail container on this server.
> The app binds only to `127.0.0.1`, so it's not exposed to the internet.

```bash
sudo systemctl daemon-reload
sudo systemctl enable my-java-app
```

## 4. Nginx Reverse Proxy

The React app owns port 80, so the Java app lives at **`http://145.223.79.115/java`**.

> **Note:** this server's Nginx only auto-loads `.conf` files from `/etc/nginx/sites-enabled/`.

```bash
sudo nano /etc/nginx/sites-available/my-react-app.conf
```

Add this inside `server { ... }` (before `location /`):

```nginx
location /java {
    proxy_pass         http://127.0.0.1:8081/;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
curl -s http://127.0.0.1:8081/   # app directly
curl -s http://localhost/java    # app via Nginx
```

## 5. Set Up SSH Key for GitHub Actions

```bash
ssh-keygen -t rsa -b 4096 -C "github-actions-java" -f /root/.ssh/java_github_actions -N ""
cat /root/.ssh/java_github_actions.pub >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
cat /root/.ssh/java_github_actions   # copy the whole private key for the secret
```

> Copy the **full** output, including the `BEGIN` and `END` lines.

## 6. Create GitHub Actions Workflow

Create `.github/workflows/ci.yml`:

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
          scp target/my-java-app-*.jar ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/my-java-app/app.jar
      - name: Restart app on VPS
        run: |
          ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} "sudo systemctl restart my-java-app"
      - name: Verify app is up
        run: |
          ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} 'for i in $(seq 1 30); do curl -sf http://127.0.0.1:8081/ >/dev/null && { echo "health check OK"; exit 0; }; sleep 2; done; echo "app failed to come up" >&2; exit 1'
```

> **Health check:** `systemctl restart` returns before the app is ready, so the check retries up to 60s until the app responds.

## 7. Add GitHub Secrets

**Settings → Secrets and variables → Actions → New repository secret**

| Secret name   | Value |
|---------------|-------|
| `VPS_HOST`    | `145.223.79.115` (your VPS IP) |
| `VPS_USER`    | `root` |
| `VPS_SSH_KEY` | your SSH private key from Step 5 |

## 8. First Manual Deploy (once, before automation)

```bash
# Local machine
mvn clean package -DskipTests
scp target/*.jar root@145.223.79.115:/opt/my-java-app/app.jar
# VPS
sudo systemctl start my-java-app
curl -s http://127.0.0.1:8081/   # should print the greeting
```

---

## How CI/CD Works Now

1. Push to `main`.
2. GitHub Actions starts.
3. **Job 1** — installs Java 17, runs `mvn clean verify` (tests), packages JAR.
4. If tests fail → pipeline stops, nothing deploys.
5. **Job 2** — downloads JAR, SSHes to VPS, copies to `/opt/my-java-app/app.jar`, restarts service.
6. Health check confirms the app is up.
7. Visit `http://145.223.79.115/java` to see the app live.