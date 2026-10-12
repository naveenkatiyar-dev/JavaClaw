#!/usr/bin/env bash
# Started by systemd ON the instance. Fetches secrets from SSM Parameter Store into this
# process's environment only (never onto disk), then execs the real process.
#   run.sh app      the JavaClaw jar, listening on 127.0.0.1 only
#   run.sh tunnel   cloudflared, publishing it through Cloudflare (Access protects it)
set -euo pipefail

# shellcheck disable=SC1091
source /etc/javaclaw.conf
export AWS_REGION

param() { aws ssm get-parameter --name "$JAVACLAW_PARAM_PREFIX/$1" --with-decryption --query Parameter.Value --output text; }

case "${1:-}" in
    app)
        GOOGLE_API_KEY=$(param google-api-key)
        export GOOGLE_API_KEY
        exec "$JAVA_HOME/bin/java" -Xmx768m -XX:+UseSerialGC \
            -jar /opt/javaclaw/javaclaw.jar \
            --server.address=127.0.0.1 \
            --spring.config.additional-location=optional:file:./app/src/main/resources/application.private.yaml
        ;;
    tunnel)
        TUNNEL_TOKEN=$(param cloudflare-tunnel-token)
        export TUNNEL_TOKEN
        exec cloudflared tunnel --no-autoupdate run
        ;;
    *)
        echo "usage: $0 app|tunnel" >&2; exit 2 ;;
esac
