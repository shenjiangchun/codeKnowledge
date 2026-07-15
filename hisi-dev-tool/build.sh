#!/usr/bin/env bash
# Read Maven build config from src/main/resources/application.yml, then run mvn.
# Config keys (all optional, with env override + defaults baked into application.yml):
#   maven.settings                  (env MAVEN_SETTINGS,        default D:/setting/settings-saas.xml)
#   maven.repo                      (env MAVEN_REPO,            default D:/repository)
#   maven.java-home                 (env MAVEN_JAVA_HOME,      default C:/Program Files/Java/jdk-17.0.3.1)
#   maven.ssl.insecure              (env MAVEN_SSL_INSECURE,    default true)
#   maven.ssl.allowall              (env MAVEN_SSL_ALLOWALL,    default true)
#   maven.ssl.ignore-validity-dates (env MAVEN_SSL_IGNORE_VALIDITY_DATES, default true)
#
# Usage:
#   bash build.sh [mvn args...]
#   bash build.sh clean compile
#   bash build.sh test -Dtest=FixChatServiceTest
#   bash build.sh spring-boot:run
set -euo pipefail

cd "$(dirname "$0")"
APP_YML="src/main/resources/application.yml"

extract() {
    local key="$1"
    # Match "  <key>: <value>"  lines (any indentation). Return value after colon+spaces.
    grep -E "^[[:space:]]*${key}:[[:space:]]+" "$APP_YML" | tail -1 \
        | sed -E "s|^[[:space:]]*${key}:[[:space:]]+||"
}

resolve_expr() {
    # If value looks like ${ENV:default}, return ENV value or fallback default.
    local raw="$1"
    if echo "$raw" | grep -qE '^\$\{[^}]+\}'; then
        local env_name def_val
        env_name=$(echo "$raw" | sed -E 's|^\$\{([^:}]+).*|\1|')
        def_val=$(echo "$raw" | sed -E 's|^\$\{[^:}]+:([^}]*)\}|\1|')
        # If no :default (e.g. ${ENV}), def_val will be the literal raw — fall back to empty
        [ "$def_val" = "$raw" ] && def_val=""
        echo "${!env_name:-$def_val}"
    else
        echo "$raw"
    fi
}

SETTINGS_RAW=$(extract "settings")
REPO_RAW=$(extract "repo")
JAVA_HOME_RAW=$(extract "java-home")
SSL_INSECURE_RAW=$(extract "insecure")
SSL_ALLOWALL_RAW=$(extract "allowall")
SSL_IGNORE_DATES_RAW=$(extract "ignore-validity-dates")

SETTINGS=$(resolve_expr "$SETTINGS_RAW")
REPO=$(resolve_expr "$REPO_RAW")
JAVA_HOME_VAL=$(resolve_expr "$JAVA_HOME_RAW")
SSL_INSECURE=$(resolve_expr "$SSL_INSECURE_RAW")
SSL_ALLOWALL=$(resolve_expr "$SSL_ALLOWALL_RAW")
SSL_IGNORE_DATES=$(resolve_expr "$SSL_IGNORE_DATES_RAW")

# Env vars override config file
SETTINGS="${MAVEN_SETTINGS:-$SETTINGS}"
REPO="${MAVEN_REPO:-$REPO}"
JAVA_HOME_VAL="${MAVEN_JAVA_HOME:-$JAVA_HOME_VAL}"
SSL_INSECURE="${MAVEN_SSL_INSECURE:-$SSL_INSECURE}"
SSL_ALLOWALL="${MAVEN_SSL_ALLOWALL:-$SSL_ALLOWALL}"
SSL_IGNORE_DATES="${MAVEN_SSL_IGNORE_VALIDITY_DATES:-$SSL_IGNORE_DATES}"

# Convert Windows path C:/... -> /c/... for bash
java_home_unix=$(echo "$JAVA_HOME_VAL" | sed -E 's|^([A-Za-z]):|/\L\1|')

if [ ! -f "$SETTINGS" ]; then
    echo "ERROR: settings file not found: $SETTINGS" >&2
    exit 1
fi
if [ ! -d "$REPO" ]; then
    echo "ERROR: repo dir not found: $REPO" >&2
    exit 1
fi
if [ ! -d "$java_home_unix" ]; then
    echo "ERROR: JAVA_HOME dir not found: $java_home_unix" >&2
    exit 1
fi

export JAVA_HOME="$java_home_unix"
export PATH="$JAVA_HOME/bin:$PATH"

echo "[build] settings=$SETTINGS"
echo "[build] repo=$REPO"
echo "[build] java-home=$JAVA_HOME_VAL"
echo "[build] ssl.insecure=$SSL_INSECURE ssl.allowall=$SSL_ALLOWALL ignore.validity.dates=$SSL_IGNORE_DATES"
echo "[build] mvn -s $SETTINGS -Dmaven.repo.local=$REPO ... $*"
echo

mvn \
    -s "$SETTINGS" \
    -Dmaven.repo.local="$REPO" \
    -Dmaven.wagon.http.ssl.insecure="$SSL_INSECURE" \
    -Dmaven.wagon.http.ssl.allowall="$SSL_ALLOWALL" \
    -Dmaven.wagon.http.ignore.validity.dates="$SSL_IGNORE_DATES" \
    "$@"
