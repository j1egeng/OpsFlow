#!/bin/sh
# Generates the private .env.docker file. Linux/macOS equivalent of Initialize-Docker.ps1.
# Usage: ./deploy/init-docker.sh [--admin-username NAME] [--admin-email EMAIL] [--http-port PORT]
set -eu

admin_username=dockeradmin
admin_email=dockeradmin@example.test
http_port=8088

while [ $# -gt 0 ]; do
    case "$1" in
        --admin-username) admin_username=${2:?--admin-username needs a value}; shift 2 ;;
        --admin-email) admin_email=${2:?--admin-email needs a value}; shift 2 ;;
        --http-port) http_port=${2:?--http-port needs a value}; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

printf '%s' "$admin_username" | grep -Eqx '[A-Za-z0-9][A-Za-z0-9._-]{2,49}' \
    || { echo 'Admin username must be 3-50 letters, digits, dots, underscores or hyphens.' >&2; exit 2; }
printf '%s' "$admin_email" | grep -Eqx '[^[:space:]=]+@[^[:space:]=]+\.[^[:space:]=]+' \
    || { echo 'Admin email is not valid.' >&2; exit 2; }
case "$http_port" in
    ''|*[!0-9]*) echo 'HTTP port must be a number between 1024 and 65535.' >&2; exit 2 ;;
esac
if [ "$http_port" -lt 1024 ] || [ "$http_port" -gt 65535 ]; then
    echo 'HTTP port must be a number between 1024 and 65535.' >&2; exit 2
fi

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
environment_file="$project_root/.env.docker"
if [ -e "$environment_file" ]; then
    echo 'Existing .env.docker was preserved; no credentials were changed.'
    exit 0
fi

random_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
database_password=$(random_hex 24)
admin_password=$(random_hex 18)
signing_key=$(head -c 32 /dev/urandom | base64 | tr -d '\n')

# Owner-only permissions; noclobber makes creation fail instead of overwriting a concurrent run.
umask 077
set -C
cat > "$environment_file" <<ENV
# PRIVATE: generated for the isolated Docker database. Do not commit or share.
OPSFLOW_HTTP_PORT=$http_port
OPSFLOW_DB_PASSWORD=$database_password
JWT_SECRET_BASE64=$signing_key
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=$admin_username
BOOTSTRAP_ADMIN_EMAIL=$admin_email
BOOTSTRAP_ADMIN_PASSWORD=$admin_password
ENV

echo 'Created private .env.docker with random database/admin passwords and a stable JWT key.'
echo "Local URL after successful deployment: http://127.0.0.1:$http_port/"
echo 'Read BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD locally from .env.docker to log in.'