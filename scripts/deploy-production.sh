#!/usr/bin/env bash
#
# Build the Fineract production JAR locally and deploy it to the remote host.
#
# Prerequisites (one-time, already done for the current host):
#   - SSH key-based auth to $REMOTE (ssh-copy-id)
#   - Remote host prepared: Java 21, PostgreSQL with fineract_tenants +
#     fineract_default databases, /etc/fineract/fineract.env,
#     /etc/systemd/system/fineract.service, service user "fineract"
#
# Usage: ./scripts/deploy-production.sh [--skip-build]

set -euo pipefail

REMOTE="ubuntu@208.122.28.192"
JAR_GLOB="fineract-provider/build/libs/fineract-provider-*.jar"
REMOTE_JAR="/opt/fineract/fineract-provider.jar"
HEALTH_URL="http://localhost:8080/fineract-provider/actuator/health"

cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
    echo "==> Building production JAR (./gradlew clean bootJar)..."
    ./gradlew clean bootJar
fi

JAR_FILE=$(ls -t $JAR_GLOB 2>/dev/null | head -1)
if [[ -z "$JAR_FILE" ]]; then
    echo "ERROR: no JAR found matching $JAR_GLOB (build failed?)" >&2
    exit 1
fi
echo "==> Deploying $JAR_FILE to $REMOTE"

echo "==> Uploading JAR..."
scp -o BatchMode=yes "$JAR_FILE" "$REMOTE:/tmp/fineract-provider.jar.new"

echo "==> Installing JAR and restarting service..."
ssh -o BatchMode=yes "$REMOTE" "
    set -e
    sudo mv /tmp/fineract-provider.jar.new $REMOTE_JAR
    sudo chown fineract:fineract $REMOTE_JAR
    sudo systemctl restart fineract
"

echo "==> Waiting for health check..."
for i in {1..60}; do
    if ssh -o BatchMode=yes "$REMOTE" "curl -sf $HEALTH_URL" | grep -q '"status":"UP"'; then
        echo "==> Deployment successful — service is UP."
        exit 0
    fi
    sleep 5
done

echo "ERROR: service did not become healthy within 5 minutes." >&2
echo "Check logs with: ssh $REMOTE 'sudo journalctl -u fineract -n 100'" >&2
exit 1
