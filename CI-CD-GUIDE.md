# CI/CD for Spring Boot using GitHub Actions and a VPS (Using Nginx)

## Prerequisites

| Required | What you need |
|----------|---------------|
| GitHub Repository | Your code must be hosted on GitHub |
| VPS (Virtual Private Server) | A Linux server (Ubuntu 22.04) with SSH access |
| SSH Key Pair | For secure authentication between GitHub and the VPS |
| Basic GitHub Actions Knowledge | Understanding workflows and YAML syntax |

---

## Setting Up GitHub for CI/CD Deployment

### 1. Create a GitHub Repository

If you don't have a GitHub repository for your project:

1. Go to **GitHub** and log in.
2. Click **New repository**.
3. Name your repository (e.g., `Java-CICD-Umair`).
4. Set it to public or private as needed.
5. Click **Create repository**.

If you already have a project directory on your local machine, push it to GitHub:

```bash
git init
git remote add origin https://github.com/<your-username>/Java-CICD-Umair.git
git add .
git commit -m "Initial commit"
git branch -M main
git push -u origin main
```

Once you push your code to GitHub, the next step is to configure the server.

### 2. Initial VPS Setup (first time only)

**OS:** Ubuntu 22.04

Update system:

```bash
sudo apt update && sudo apt upgrade -y
```

Install dependencies:

```bash
sudo apt install -y openjdk-17-jdk maven nginx git
```

Create the deployment directory:

```bash
sudo mkdir -p /opt/my-java-app
```

> Only do this once, on the first setup. The folder keeps the built JAR file.

### 3. Set Up the App as a Service (systemd)

The app must start on boot and auto-restart if it crashes. Create the systemd
service file:

```bash
sudo nano /etc/systemd/system/my-java-app.service
```

Paste the content below:

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

> **Why port 8081?** On this server, port **8080 is already used by a Docker
> mail container**, so don't touch it — the Java app runs on **8081** and binds
> to the localhost only (`127.0.0.1`), which keeps it safe from the internet.

Then enable and start it (once a JAR exists):

```bash
sudo systemctl daemon-reload
sudo systemctl enable my-java-app
```

### 4. Nginx Reverse Proxy Configuration

The same server already serves a React app on **port 80** with
`server_name 145.223.79.115`. So the Java app is exposed as a URL path:
**`http://145.223.79.115/java`**.

> **Important:** on this server, Nginx **only auto-loads files ending in
> `.conf`** from `/etc/nginx/sites-enabled/`. Your config file name must end
> with `.conf`.

Edit the vhost file (the React one, since it owns port 80):

```bash
sudo nano /etc/nginx/sites-available/my-react-app.conf
```

Add this block inside the `server { ... }` (before `location /`):

```nginx
location /java {
    proxy_pass         http://127.0.0.1:8081/;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
}
```

Test and reload Nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Check that everything is wired correctly:

```bash
curl -s http://127.0.0.1:8081/    # Java app directly
curl -s http://localhost/java      # Java app through Nginx
```

### 5. Set Up SSH Key for GitHub Actions

On the VPS, generate a dedicated RSA key (no passphrase — automation can't type
one):

```bash
ssh-keygen -t rsa -b 4096 -C "github-actions-java" -f /root/.ssh/java_github_actions -N ""
cat /root/.ssh/java_github_actions.pub >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
```

To get the private key (this goes into GitHub Secrets):

```bash
cat /root/.ssh/java_github_actions
```

> Copy the **whole** output, including the `-----BEGIN OPENSSH PRIVATE
> KEY-----` and `-----END OPENSSH PRIVATE KEY-----` lines.

### 6. Create GitHub Actions Workflow

In your repository, create `.github/workflows/ci.yml`:

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

> **Why the last step?** `systemctl restart` returns before the app is fully
> started. The health check retries for up to 60 seconds, so the pipeline only
> goes green if the app really is serving requests.

### 7. Secrets to add in GitHub repo

Go to **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name   | Value |
|---------------|-------|
| `VPS_HOST`    | `145.223.79.115`  (your VPS IP address) |
| `VPS_USER`    | `root`  (your host username) |
| `VPS_SSH_KEY` | your SSH private key from Step 5 |

### 8. First Manual Deploy (before automation)

Do this once so the service has a JAR to run:

```bash
# On your local machine
mvn clean package -DskipTests
scp target/*.jar root@145.223.79.115:/opt/my-java-app/app.jar

# On the VPS
sudo systemctl start my-java-app
curl -s http://127.0.0.1:8081/     # should print the greeting
```

---

## How CI/CD Works Now

1. **Push code to the `main` branch.**
2. **GitHub Actions starts automatically.**
3. **Job 1 (Build & Test):** installs Java 17, runs `mvn clean verify`, runs the
   tests, and packages the JAR.
4. **Job 1 must pass**, otherwise nothing is deployed (fail-fast).
5. **Job 2 (Deploy):** downloads the JAR, SSHes into the VPS, copies it to
   `/opt/my-java-app/app.jar`, and restarts the service.
6. **Health check** confirms the app is up.
7. **Visit `http://145.223.79.115/java`** to see the app live.

```
Push to main
     │
     ▼
┌──────────────────┐   runs on push + pull_request
│  Build & Test    │  (tests must pass)
└─────────┬────────┘
          │ passes
          ▼
┌──────────────────┐   runs on push to main ONLY
│    Deploy        │  scp JAR → restart service → health check
└─────────┬────────┘
          ▼
   http://145.223.79.115/java
```