# my-java-app — CI/CD Demo with Spring Boot & GitHub Actions

A minimal Spring Boot REST API whose only purpose is to prove that a full
CI/CD pipeline works end-to-end: code pushed to GitHub → tests run
automatically → JAR deployed to a Linux VPS via SSH.

---

## Table of Contents

1. [What the App Does](#what-the-app-does)
2. [Project Structure](#project-structure)
3. [Run Locally](#run-locally)
4. [Run the Tests](#run-the-tests)
5. [CI/CD Pipeline Overview](#cicd-pipeline-overview)
6. [GitHub Secrets You Must Add](#github-secrets-you-must-add)
7. [VPS Setup — Step by Step](#vps-setup--step-by-step)
8. [How Deployment Works](#how-deployment-works)

---

## What the App Does

One endpoint:

```
GET /  →  "Hello, Umair! This app was deployed with a CI/CD pipeline."
```

That's it. The point is the pipeline, not the app.

---

## Project Structure

```
my-java-app/
├── .github/
│   └── workflows/
│       └── ci.yml                  ← GitHub Actions pipeline
├── src/
│   ├── main/
│   │   ├── java/com/awais/myapp/
│   │   │   ├── MyJavaAppApplication.java   ← Spring Boot entry point
│   │   │   └── HelloController.java        ← REST controller
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/com/awais/myapp/
│           └── HelloControllerTest.java    ← JUnit 5 tests
└── pom.xml                         ← Maven build file
```

---

## Run Locally

**Prerequisites:** Java 17 and Maven installed on your machine.

```bash
# 1. Clone the repo
git clone https://github.com/<your-username>/my-java-app.git
cd my-java-app

# 2. Build and run
mvn spring-boot:run
```

The app starts on **http://localhost:8080**.  
Test it:

```bash
curl http://localhost:8080/
# Hello, Umair! This app was deployed with a CI/CD pipeline.
```

---

## Run the Tests

```bash
mvn test
```

Three tests run:
- `GET /` returns HTTP 200
- Response contains `"Hello, Umair!"`
- Response contains `"CI/CD pipeline"`

---

## CI/CD Pipeline Overview

The pipeline lives in `.github/workflows/ci.yml` and has two jobs.

### Job 1 — Build & Test (runs on every push and PR)

| Step | What happens |
|------|-------------|
| Checkout code | Downloads the repo |
| Set up Java 17 | Installs Temurin JDK 17 |
| Build & test | Runs `mvn clean verify` |
| Upload JAR | Saves the built JAR as a GitHub artifact |

### Job 2 — Deploy (runs only on push to `main`, after Job 1 passes)

| Step | What happens |
|------|-------------|
| Download JAR | Retrieves the artifact from Job 1 |
| Set up SSH | Loads the private key from GitHub Secrets |
| Copy JAR to VPS | `scp` uploads `app.jar` to `/opt/my-java-app/` |
| Restart service | `sudo systemctl restart my-java-app` |

Flow diagram:

```
Push to main
     │
     ▼
┌─────────────────┐
│  build-and-test │  ← also runs on PRs (deploy does NOT run on PRs)
└────────┬────────┘
         │ passes
         ▼
┌─────────────────┐
│     deploy      │
└─────────────────┘
         │
         ▼
  VPS running new JAR
```

---

## GitHub Secrets You Must Add

Go to your repository on GitHub:  
**Settings → Secrets and variables → Actions → New repository secret**

Add these three secrets:

| Secret name   | What to put there |
|---------------|-------------------|
| `VPS_HOST`    | Your VPS IP address, e.g. `203.0.113.42` |
| `VPS_USER`    | The SSH username on your VPS, e.g. `ubuntu` or `root` |
| `VPS_SSH_KEY` | The **entire** private key file content (see below) |

### How to get your SSH private key

If you already have an SSH key pair for your VPS, run this on your local machine:

```bash
cat ~/.ssh/id_rsa
```

Copy everything including `-----BEGIN OPENSSH PRIVATE KEY-----` and
`-----END OPENSSH PRIVATE KEY-----`.

If you need a new key pair:

```bash
# Generate a new key pair (no passphrase — GitHub Actions can't type a passphrase)
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/github_actions_key -N ""

# Print the private key → paste this into the VPS_SSH_KEY secret
cat ~/.ssh/github_actions_key

# Print the public key → you will add this to the VPS (see VPS setup below)
cat ~/.ssh/github_actions_key.pub
```

---

## VPS Setup — Step by Step

Run all of these commands on your Ubuntu 22.04 VPS as a user with `sudo`.

### 1. Update the system

```bash
sudo apt update && sudo apt upgrade -y
```

### 2. Install Java 17

```bash
sudo apt install -y openjdk-17-jdk

# Verify
java -version
# openjdk version "17.x.x" ...
```

### 3. Install Maven (only needed if you build on the VPS; not required for this setup since we deploy a pre-built JAR)

```bash
sudo apt install -y maven

# Verify
mvn -version
```

### 4. Install Nginx

```bash
sudo apt install -y nginx

# Start and enable Nginx on boot
sudo systemctl start nginx
sudo systemctl enable nginx
```

### 5. Create the app folder and a dedicated user

```bash
# Create a system user to run the app (more secure than root)
sudo useradd -r -m -s /bin/bash myapp

# Create the deployment folder
sudo mkdir -p /opt/my-java-app
sudo chown myapp:myapp /opt/my-java-app
```

### 6. Add the GitHub Actions public key to the VPS

```bash
# On your LOCAL machine, print the public key
cat ~/.ssh/github_actions_key.pub

# On the VPS, add it to the myapp user's authorized_keys
sudo mkdir -p /home/myapp/.ssh
sudo nano /home/myapp/.ssh/authorized_keys
# Paste the public key, save and exit

sudo chmod 700 /home/myapp/.ssh
sudo chmod 600 /home/myapp/.ssh/authorized_keys
sudo chown -R myapp:myapp /home/myapp/.ssh
```

> If you deploy as `ubuntu` or `root` instead, add the public key to that
> user's `~/.ssh/authorized_keys` instead.

### 7. Give the deploy user permission to restart the service without a password

```bash
sudo visudo
# Add this line at the bottom (replace myapp with your VPS_USER if different):
myapp ALL=(ALL) NOPASSWD: /bin/systemctl restart my-java-app
```

### 8. Create the systemd service

```bash
sudo nano /etc/systemd/system/my-java-app.service
```

Paste this content:

```ini
[Unit]
Description=My Java App - CI/CD Demo
After=network.target

[Service]
User=myapp
WorkingDirectory=/opt/my-java-app
ExecStart=/usr/bin/java -jar /opt/my-java-app/app.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable my-java-app

# You can start it once you deploy the first JAR:
# sudo systemctl start my-java-app
```

### 9. Configure Nginx as a reverse proxy

```bash
sudo nano /etc/nginx/sites-available/my-java-app
```

Paste this content (replace `your-domain.com` with your domain or VPS IP):

```nginx
server {
    listen 80;
    server_name your-domain.com;   # or use _; to match any hostname

    location / {
        proxy_pass         http://localhost:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
```

Enable the site and reload Nginx:

```bash
sudo ln -s /etc/nginx/sites-available/my-java-app /etc/nginx/sites-enabled/
sudo nginx -t          # test the config — should say "syntax is ok"
sudo systemctl reload nginx
```

### 10. Open firewall ports

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'    # opens port 80 and 443
sudo ufw enable
sudo ufw status
```

---

## How Deployment Works

Once everything is set up, this is what happens every time you push to `main`:

```
1. You push code to GitHub (main branch)
2. GitHub Actions starts automatically
3. Job 1: checks out code, installs Java 17, runs mvn clean verify
          → if any test fails, the pipeline stops here, nothing is deployed
4. Job 1 passes → built JAR is saved as an artifact
5. Job 2: downloads the JAR, SSH into the VPS, copies the JAR to
          /opt/my-java-app/app.jar, restarts the systemd service
6. systemd starts the new JAR with java -jar (on port 8081, bound to localhost)
7. Nginx forwards HTTP requests on port 80 → the app on localhost:8081 at /java
8. Visit http://145.223.79.115/java and see the greeting
```

> **As-deployed facts for THIS server (145.223.79.115):**
> - The VPS also hosts the React app, which owns `http://145.223.79.115/`.
>   The Java app shares port 80 via a `location /java` block in Nginx.
> - The systemd unit runs the app on **port 8081** bound to localhost because
>   port 8080 is occupied by a Docker container (mail server UI).
> - Deploy user is **root**; deploy key was generated on the VPS at
>   `/root/.ssh/java_github_actions`, and its public half was added to
>   `/root/.ssh/authorized_keys`.
> - Nginx only auto-loads `*.conf` files from `/etc/nginx/sites-enabled/`
>   (CloudPanel quirk) — configs must use that suffix.

### First deployment

Before the pipeline can deploy, the VPS needs at least one JAR to exist so
`systemctl start` doesn't fail. Do a manual first deploy:

```bash
# On your local machine
mvn clean package -DskipTests
scp target/my-java-app-1.0.0.jar root@145.223.79.115:/opt/my-java-app/app.jar

# On the VPS
systemctl start my-java-app
systemctl status my-java-app   # should show "active (running)"
curl -s http://localhost:8081/ # should show the greeting
```

After this, every push to `main` will automatically update the running app.
