#!/usr/bin/env bash
# IS212 Week 9: smoke-test a *running* backend (the packaged jar in CI, or any
# local instance). A handful of fast checks that it is alive and roughly
# right: not a test suite, and it should never take long.
#
# Usage: bash .github/scripts/smoke.sh <base-url>     e.g. http://localhost:8080
# Needs the dev profile's seeded accounts (DevUserSeeder), curl and jq.
#
# Checks are chosen from High-priority stories (Week 9: smoke-test the
# journeys that matter most): every one of them starts with signing in and
# relies on role rules, and EO01/EO02/EO15 is where every event begins.
set -euo pipefail
BASE="${1%/}"

# Wait for a condition, not a duration: up to 90 s for Spring and Flyway.
for i in $(seq 1 90); do
  curl -fs "$BASE/actuator/health" > /dev/null 2>&1 && break
  [ "$i" = 90 ] && { echo "No answer from $BASE/actuator/health"; exit 1; }
  sleep 1
done

echo "1. Health is UP: the app started and reached its database (DEV05)"
curl -fsS "$BASE/actuator/health" | jq -e '.status == "UP"'

echo "2. A wrong password is refused with 401 (AU04/DEV05: sign-in guards every journey)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"eo1","password":"wrong"}')
[ "$code" = 401 ] || { echo "Expected 401, got $code"; exit 1; }

echo "3. A seeded organiser can sign in and gets an access token (AU04/DEV05)"
token=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"eo1","password":"123456"}' | jq -er '.accessToken')

echo "4. The organiser's request list loads from the database (EO15, EO01/EO02)"
curl -fsS "$BASE/api/event-requests" -H "Authorization: Bearer $token" | jq -e 'type == "array"'

echo "5. Role rules hold: an organiser cannot open a coordinator's My events (AU04, EC09)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/events/assigned" -H "Authorization: Bearer $token")
[ "$code" = 403 ] || { echo "Expected 403, got $code"; exit 1; }

echo "Smoke tests passed against $BASE"
