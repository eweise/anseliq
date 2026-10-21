#!/usr/bin/env bash
# Sets up the settings the backend needs for local development and prints them as export lines.
# They're kept in .env (gitignored) so they stay the same from one run to the next: a new
# JWT_SECRET would invalidate every login token already issued.
#
# Load them into your shell (bash or zsh) with:
#   eval "$(scripts/dev-env.sh)"
# An IDE run configuration can read the same .env, which is plain NAME=value lines.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$root/.env"

touch "$env_file"
chmod 600 "$env_file"

# Adds NAME=value to .env, unless .env already sets NAME.
ensure() {
  local name=$1 value=$2
  if ! grep -q "^$name=" "$env_file"; then
    # Ends a last line left without a newline, so NAME doesn't run onto it.
    if [[ -s "$env_file" && -n "$(tail -c1 "$env_file")" ]]; then echo >>"$env_file"; fi
    echo "$name=$value" >>"$env_file"
    echo "Added $name to $env_file" >&2
  fi
}

ensure JWT_SECRET "$(openssl rand -base64 48)"
# LocalStack, which accepts any key (see `make start-localstack`).
ensure S3_ENDPOINT "http://localhost:4566"
ensure S3_USERNAME "test"
ensure S3_PASSWORD "test"

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  printf 'export %s=%q\n' "${line%%=*}" "${line#*=}"
done <"$env_file"
