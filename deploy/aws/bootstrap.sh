#!/usr/bin/env bash
# Runs ON the EC2 instance, as root.
#   bootstrap.sh install   first boot (called from user data): packages, build, services
#   bootstrap.sh update    pull the latest code, rebuild, restart
set -euo pipefail

APP_DIR=/opt/javaclaw/app
APP_USER=javaclaw
JAVA_HOME_DIR=/opt/java/jdk-25
WACLI_VERSION=v0.21.0   # github.com/openclaw/wacli release
CONFIG_FILE=$APP_DIR/app/src/main/resources/application.private.yaml   # where ConfigurationManager writes

# shellcheck disable=SC1091
source /etc/javaclaw.conf   # JAVACLAW_PARAM_PREFIX, AWS_REGION (written by user data)
export AWS_REGION

log() { echo "[javaclaw-bootstrap] $*"; }
as_app() { sudo -u "$APP_USER" -H env JAVA_HOME="$JAVA_HOME_DIR" PATH="$JAVA_HOME_DIR/bin:/usr/local/go/bin:/usr/local/bin:/usr/bin:/bin" "$@"; }
param() { aws ssm get-parameter --name "$JAVACLAW_PARAM_PREFIX/$1" --with-decryption --query Parameter.Value --output text 2>/dev/null || true; }

install_packages() {
    log "packages"
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y git curl jq build-essential ca-certificates
    command -v aws >/dev/null || snap install aws-cli --classic
    local arch jdk_arch
    arch=$(dpkg --print-architecture)               # amd64 | arm64
    case "$arch" in amd64) jdk_arch=x64 ;; arm64) jdk_arch=aarch64 ;; *) log "unsupported arch $arch"; exit 1 ;; esac

    if [ ! -f /swapfile ]; then
        log "2 GiB swap (2 GiB RAM is tight for Gradle + JVM + Chromium)"
        fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
        echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi

    if [ ! -x "$JAVA_HOME_DIR/bin/java" ]; then
        log "JDK 25 (Eclipse Temurin)"
        mkdir -p "$JAVA_HOME_DIR"
        curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/${jdk_arch}/jdk/hotspot/normal/eclipse \
            | tar -xz -C "$JAVA_HOME_DIR" --strip-components=1
    fi

    if [ ! -x /usr/local/bin/wacli ]; then
        log "Go + wacli"
        go_version=$(curl -fsSL 'https://go.dev/VERSION?m=text' | head -1)
        rm -rf /usr/local/go
        curl -fsSL "https://go.dev/dl/${go_version}.linux-${arch}.tar.gz" | tar -xz -C /usr/local
        GOBIN=/usr/local/bin GOPATH=/root/go GOCACHE=/root/.cache/go-build /usr/local/go/bin/go install "github.com/openclaw/wacli/cmd/wacli@${WACLI_VERSION}"
    fi

    if ! command -v cloudflared >/dev/null; then
        log "cloudflared"
        curl -fsSL -o /tmp/cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${arch}.deb
        dpkg -i /tmp/cloudflared.deb && rm /tmp/cloudflared.deb
    fi

    id "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /bin/bash "$APP_USER"
    chown -R "$APP_USER:$APP_USER" /opt/javaclaw
}

build() {
    log "build (takes several minutes on a small instance)"
    as_app bash -c "cd '$APP_DIR' && ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx768m :app:bootJar -x test"
    jar=$(find "$APP_DIR/app/build/libs" -name 'app-*.jar' ! -name '*-plain.jar' | head -1)
    ln -sfn "$jar" /opt/javaclaw/javaclaw.jar
}

install_browser() {
    log "Chromium + its system libraries for Playwright"
    local cli=(-cp /opt/javaclaw/javaclaw.jar -Dloader.main=com.microsoft.playwright.CLI
               org.springframework.boot.loader.launch.PropertiesLauncher)
    "$JAVA_HOME_DIR/bin/java" "${cli[@]}" install-deps chromium \
        || log "WARNING: 'playwright install-deps' failed; the browser tool may not start"
    as_app "$JAVA_HOME_DIR/bin/java" "${cli[@]}" install chromium
}

write_config() {
    # Written once; afterwards the onboarding/settings UI owns this file.
    if [ -f "$CONFIG_FILE" ]; then
        log "config exists, leaving it alone"
    else
        log "server config"
        local model digest_model chat_jid
        model=$(param gemini-model); model=${model:-gemini-3.5-flash-lite}
        digest_model=$(param gemini-digest-model); digest_model=${digest_model:-gemini-3.1-flash-lite}
        chat_jid=$(param whatsapp-chat-jid)
        cat > "$CONFIG_FILE" <<EOF
agent:
  onboarding:
    completed: true
  llm:
    providers:
      default:
        provider: google.genai
        model: $model
        api-key: \${GOOGLE_API_KEY}   # injected from SSM at start, never stored on disk
      digest:                         # per-group WhatsApp digest summaries: many small calls, cheapest model
        provider: google.genai
        model: $digest_model
        api-key: \${GOOGLE_API_KEY}
  channels:
    whatsapp:
      enabled: $([ -n "$chat_jid" ] && echo true || echo false)
      allowed-chat-jid: ${chat_jid:-unset}
  tasks:
    notification-channel: $([ -n "$chat_jid" ] && echo whatsapp || echo "")   # reminders reach the phone even when asked in the web chat
  tools:
    playwright:
      enabled: true
  subagents:
    enabled: false
javaclaw:
  tools:
    dynamic-discovery:
      enabled: false
EOF
        chown "$APP_USER:$APP_USER" "$CONFIG_FILE"
    fi

    local agent_md="$APP_DIR/workspace/AGENT.private.md" content
    if [ ! -f "$agent_md" ]; then
        content=$(param agent-md)
        if [ -n "$content" ]; then
            log "AGENT.private.md from SSM"
            printf '%s\n' "$content" > "$agent_md"
            chown "$APP_USER:$APP_USER" "$agent_md"
            chmod 600 "$agent_md"
        fi
    fi
}

install_services() {
    log "systemd services"
    cat > /etc/systemd/system/javaclaw.service <<EOF
[Unit]
Description=JavaClaw assistant
After=network-online.target
Wants=network-online.target

[Service]
User=$APP_USER
WorkingDirectory=$APP_DIR
Environment=JAVA_HOME=$JAVA_HOME_DIR
Environment=PATH=/usr/local/bin:/usr/bin:/bin:/snap/bin
ExecStart=$APP_DIR/deploy/aws/run.sh app
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    cat > /etc/systemd/system/javaclaw-tunnel.service <<EOF
[Unit]
Description=Cloudflare Tunnel for JavaClaw
After=network-online.target
Wants=network-online.target

[Service]
User=$APP_USER
Environment=PATH=/usr/local/bin:/usr/bin:/bin:/snap/bin
ExecStart=$APP_DIR/deploy/aws/run.sh tunnel
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable javaclaw.service javaclaw-tunnel.service
}

case "${1:-}" in
    install)
        install_packages
        build
        install_browser
        write_config
        install_services
        systemctl restart javaclaw-tunnel.service javaclaw.service
        log "done. Next: pair WhatsApp ('deploy.sh pair'), then 'deploy.sh restart'."
        ;;
    update)
        as_app git -C "$APP_DIR" pull --ff-only
        build
        install_services
        systemctl restart javaclaw.service
        log "updated to $(as_app git -C "$APP_DIR" log -1 --format='%h %s')"
        ;;
    *)
        echo "usage: $0 install|update" >&2; exit 2 ;;
esac
