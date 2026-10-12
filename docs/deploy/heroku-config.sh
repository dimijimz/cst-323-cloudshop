#!/usr/bin/env bash
# Sets CloudShop's config vars on a Heroku app that has the JawsDB MySQL add-on.
#
#   ./docs/deploy/heroku-config.sh <app>
#   APP_LOG_LEVEL=DEBUG ./docs/deploy/heroku-config.sh cst323-cloudshop-dj
#
# JawsDB hands the app one variable, JAWSDB_URL, in the form
#   mysql://user:password@host:port/schema
# CloudShop reads five separate ones, the same five on every platform, so that no
# platform's format is known to the application. This script splits the one into
# the five and sets the rest of what the application needs:
#
#   DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD   the parts of JAWSDB_URL
#   DB_SSL_MODE      REQUIRED, unless DB_SSL_MODE is set in the environment
#   DB_POOL_MAX      4, unless DB_POOL_MAX is set. JawsDB's free plan allows 10
#                    connections; 4 lets an old and a new dyno overlap during a
#                    restart (2 x 4) and still stay under the cap
#   APP_LOG_LEVEL    INFO, unless APP_LOG_LEVEL is set
#   OWNER_USERNAME   from the secrets file
#   OWNER_PASSWORD   from the secrets file
#   DISABLE_SPRING_DATASOURCE_URL=true
#
# That last one matters as much as the rest. Heroku's Java buildpack reads
# JAWSDB_URL too, and at dyno start exports SPRING_DATASOURCE_URL, _USERNAME and
# _PASSWORD from it. An environment variable outranks application.properties, so
# without this switch the five DB_* variables are silently ignored: the app
# connects with the buildpack's URL, which has no sslMode and carries the user
# and password in its query string, where anything that logs the URL logs them.
# The buildpack skips those exports only when this variable is exactly "true".
#
# The secrets file is ~/.cloudshop/heroku-secrets.env, or the path in SECRETS_FILE.
# If it does not exist it is created, with the username "owner" and a random
# 24-character password. It is outside the repository and is never committed.
#
# Nothing secret is printed. Heroku's own commands echo the values they read and
# set, so their output is captured or discarded here, and what this script reports
# is variable names only. Run it again after JawsDB rotates its credentials.
#
# Needs bash and a signed-in Heroku CLI.

set -u

APP="${1:-}"
if [ -z "$APP" ]; then
    echo "usage: $0 <heroku-app>" >&2
    exit 2
fi
SECRETS_FILE="${SECRETS_FILE:-$HOME/.cloudshop/heroku-secrets.env}"

stop() { echo "Stopped: $1" >&2; exit 1; }

# --- 1. Split JAWSDB_URL --------------------------------------------------------
URL="$(heroku config:get JAWSDB_URL --app "$APP" 2>/dev/null | tr -d '\r\n')"
case "$URL" in
    mysql://*:*@*:*/*) ;;
    *) stop "JAWSDB_URL on $APP is missing or not mysql://user:password@host:port/schema. Is the add-on still being provisioned?" ;;
esac

REST="${URL#mysql://}"
CREDENTIALS="${REST%@*}"            # user:password   (up to the last @)
LOCATION="${REST##*@}"              # host:port/schema
DB_USER="${CREDENTIALS%%:*}"
DB_PASSWORD="${CREDENTIALS#*:}"
HOST_AND_PORT="${LOCATION%%/*}"
DB_NAME="${LOCATION#*/}"
DB_NAME="${DB_NAME%%\?*}"           # drop any ?options
DB_HOST="${HOST_AND_PORT%%:*}"
DB_PORT="${HOST_AND_PORT##*:}"
unset URL REST CREDENTIALS LOCATION

# JawsDB issues plain letters and digits. Anything else would need URL-decoding,
# and guessing at that is how an application ends up with the wrong password.
plain() { case "$2" in ''|*[!A-Za-z0-9._-]*) stop "the $1 in JAWSDB_URL is empty or has characters this script does not handle" ;; esac; }
plain "user" "$DB_USER"
plain "password" "$DB_PASSWORD"
plain "host" "$DB_HOST"
plain "schema name" "$DB_NAME"
case "$DB_PORT" in ''|*[!0-9]*) stop "the port in JAWSDB_URL is not a number" ;; esac

# --- 2. Owner credentials -------------------------------------------------------
if [ ! -s "$SECRETS_FILE" ]; then
    mkdir -p "$(dirname "$SECRETS_FILE")"
    (
        umask 077
        {
            echo "OWNER_USERNAME=owner"
            printf 'OWNER_PASSWORD=%s\n' "$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
        } > "$SECRETS_FILE"
    )
    echo "Created $SECRETS_FILE with a generated owner password."
fi
OWNER_USERNAME="$(sed -n 's/^OWNER_USERNAME=//p' "$SECRETS_FILE" | tr -d '\r\n')"
OWNER_PASSWORD="$(sed -n 's/^OWNER_PASSWORD=//p' "$SECRETS_FILE" | tr -d '\r\n')"
[ -n "$OWNER_USERNAME" ] && [ -n "$OWNER_PASSWORD" ] || stop "$SECRETS_FILE has no OWNER_USERNAME or no OWNER_PASSWORD"

# --- 3. Set them ----------------------------------------------------------------
# config:set prints every value it sets, so its output goes nowhere.
if ! heroku config:set --app "$APP" \
        DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_NAME="$DB_NAME" \
        DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
        DB_SSL_MODE="${DB_SSL_MODE:-REQUIRED}" \
        DB_POOL_MAX="${DB_POOL_MAX:-4}" \
        APP_LOG_LEVEL="${APP_LOG_LEVEL:-INFO}" \
        DISABLE_SPRING_DATASOURCE_URL=true \
        OWNER_USERNAME="$OWNER_USERNAME" OWNER_PASSWORD="$OWNER_PASSWORD" > /dev/null 2>&1; then
    stop "heroku config:set failed for $APP"
fi
unset DB_PASSWORD OWNER_PASSWORD

# --- 4. Report names only -------------------------------------------------------
echo "Config vars now set on $APP (names only):"
heroku config --app "$APP" --json 2>/dev/null \
    | sed -n 's/^ *"\([A-Za-z0-9_]*\)" *:.*/  \1/p' | sort
echo "DB_PORT=$DB_PORT  DB_SSL_MODE=${DB_SSL_MODE:-REQUIRED}  DB_POOL_MAX=${DB_POOL_MAX:-4}  APP_LOG_LEVEL=${APP_LOG_LEVEL:-INFO}"
