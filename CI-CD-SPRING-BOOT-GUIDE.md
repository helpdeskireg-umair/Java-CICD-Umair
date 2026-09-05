# CI/CD for Spring Boot — GitHub Actions + VPS (Nginx + systemd)

## Flow

```
Push to main → GitHub Actions → Build & Test → Deploy → VPS (Nginx → :8080)
```

## 1. Push Code to GitHub

```bash
git init
git remote add origin https://<token>@github.com/your-username/your-repo.git
git add . && git commit -m "Initial commit"
git branch -M main && git push -u origin main
```

## 2. VPS Setup (once only)

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-17-jdk maven nginx git

# Stop Apache if it's using port 80 (e.g. CloudPanel)
sudo systemctl stop apache2 && sudo systemctl disable apache2
sudo systemctl start nginx
```

## 3. Create systemd Service

```bash
sudo nano /etc/systemd/system/my-java-app.service
```

```ini
[Unit]
Description=My Java App
After=network.target

[Service]
User=root
WorkingDirectory=/opt/my-java-app
ExecStart=/usr/bin/java -jar /opt/my-java-app/app.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable my-java-app
```

## 4. Nginx Reverse Proxy

```bash
sudo nano /etc/nginx/sites-available/my-java-app.conf   # MUST end in .conf
```

```nginx
server {
    listen 80;
    server_name 145.223.79.115;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
    }
}
```

```bash
sudo ln -sf /etc/nginx/sites-available/my-java-app.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 5. SSH Key for GitHub Actions

```bash
# On VPS
ssh-keygen -t rsa -b 4096 -f ~/.ssh/deploy_key_rsa -N "" -C "deploy"
cat ~/.ssh/deploy_key_rsa.pub >> ~/.ssh/authorized_keys
cat ~/.ssh/deploy_key_rsa          # copy this → private key

# Passwordless restart
echo "root ALL=(ALL) NOPASSWD: /bin/systemctl restart my-java-app" > /etc/sudoers.d/my-java-app
```

## 6. First Manual Deploy

```bash
# Local
mvn clean package -DskipTests
scp target/my-java-app-1.0.0.jar root@145.223.79.115:/opt/my-java-app/app.jar

# VPS
sudo systemctl start my-java-app
```

## 7. GitHub Secrets (Settings → Secrets → Actions)

| Secret | Value |
|--------|-------|
| `VPS_HOST` | `145.223.79.115` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | private key from step 5 |

## 8. Workflow (`.github/workflows/ci.yml`)

```yaml
name: CI/CD Pipeline

on:
  push: { branches: [main] }
  pull_request: { branches: [main] }

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin', cache: 'maven' }
      - run: mvn clean verify --no-transfer-progress
      - uses: actions/upload-artifact@v4
        with: { name: app-jar, path: target/*.jar }

  deploy:
    needs: build-and-test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with: { name: app-jar, path: target/ }
      - run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.VPS_SSH_KEY }}" > ~/.ssh/id_rsa
          chmod 600 ~/.ssh/id_rsa
          ssh-keyscan -H "${{ secrets.VPS_HOST }}" >> ~/.ssh/known_hosts
      - run: |
          scp target/my-java-app-1.0.0.jar \
            ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/my-java-app/app.jar
      - run: |
          ssh ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} \
            "sudo systemctl restart my-java-app"
```

## Done — Live at http://145.223.79.115

Push to `main` → tests run → JAR auto-copied → service restarted → app updates automatically.