#!/usr/bin/env bash
# Starts, tests and stops Module 21's lab database under the Compose project name seatwave-filters,
# its one port on 127.0.0.1 only, and runs Lab 2 and the study against it.
#
# Usage: scripts/stack.sh <up|down|test|lab2|study|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      generate the two lab passwords into .secrets, start PostgreSQL, wait until healthy
#   down    remove the project's containers, network and volumes (down -v) and the passwords of
#           the database that is now gone, then check none is left
#   test    up, ./gradlew stackTest stackCoverage, then down, on every path
#   lab2    on a running stack: load fan_1 to fan_1000000, then ./gradlew readsAvoided --postgres
#   study   on a running stack: load STUDY_NAMES names (default 50000000), then ./gradlew usernameStudy
#   status  list the project's containers (read-only)
set -euo pipefail

readonly PROJECT="seatwave-filters"
readonly WAIT_SECONDS=60
readonly DOCKER_TIMEOUT_SECONDS=10
readonly PASSWORD_BYTES=24
readonly LAB2_NAMES=1000000
readonly MOST_STUDY_NAMES=200000000
study_names="${STUDY_NAMES:-50000000}"

usage() {
  echo "usage: $0 <up|down|test|lab2|study|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|lab2|study|status) ;; *) usage ;; esac
# Read as a decimal number even with leading zeros; UsernameLoader checks the range again.
if [[ ! "$study_names" =~ ^[0-9]+$ ]] || (( 10#$study_names < 1 || 10#$study_names > MOST_STUDY_NAMES )); then
  echo "STUDY_NAMES must be a whole number from 1 to $MOST_STUDY_NAMES, was '$study_names'" >&2
  exit 2
fi
study_names=$((10#$study_names))

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  write .secrets/{postgres,owner}.password if missing; ${compose[*]} up -d --wait --quiet-pull" ;;
  down) echo "  ${compose[*]} down -v --remove-orphans; remove the passwords; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest stackCoverage, then down -v (always)" ;;
  lab2) echo "  replace customers with fan_1 to fan_$LAB2_NAMES, then ./gradlew readsAvoided --args=--postgres" ;;
  study) echo "  replace customers with fan_1 to fan_$study_names, then ./gradlew usernameStudy" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
# "docker info --format" exits 0 even when the engine does not answer (Docker 24.0.6), so the
# check also needs the version it printed.
docker_answers() {
  local answer_file="build/stack/docker-version"
  mkdir -p build/stack
  rm -f "$answer_file"
  docker info --format '{{.ServerVersion}}' > "$answer_file" 2> /dev/null &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker" || return 1
      grep -q "[0-9]" "$answer_file"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

secrets() {
  # Not under build/: "./gradlew clean" would delete the passwords while the database uses them.
  mkdir -p .secrets
  local name
  for name in postgres owner; do
    if [[ ! -s ".secrets/$name.password" ]]; then
      # Readable by all, not owner-only: on a Linux host a Compose secret is a bind mount that keeps
      # the file's owner and mode, and 10-roles.sh reads it as the container's postgres user. Any
      # account on this machine can read these throwaway lab passwords; down deletes them.
      ( umask 022; openssl rand -hex "$PASSWORD_BYTES" > ".secrets/$name.password" )
    fi
  done
}

up() {
  secrets
  # --wait returns when the healthcheck passes, or fails after the timeout.
  # Called in "up || status=$?", where set -e is off, so each failure returns on its own.
  "${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS" --quiet-pull || return 1
  audit "up: started $PROJECT"
}

down() {
  "${compose[@]}" down -v --remove-orphans || return 1
  # The passwords belonged to the database in the volume just removed; the next up makes new ones.
  rm -f .secrets/postgres.password .secrets/owner.password
  audit "down: removed $PROJECT and its passwords"
  # Each listing must succeed: an engine that does not answer lists nothing, which is not "clean".
  local left="" listed
  listed="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

running() {
  if [[ -z "$("${compose[@]}" ps -q postgres 2> /dev/null)" ]]; then
    echo "the stack is not up; run scripts/stack.sh up --run first" >&2
    exit 1
  fi
}

case "$action" in
  up) up ;;
  down) down ;;
  lab2)
    running
    ./gradlew -q loadUsernames --args="$LAB2_NAMES --run --yes"
    audit "lab2: loaded $LAB2_NAMES names"
    ./gradlew -q readsAvoided --args=--postgres
    ;;
  study)
    running
    ./gradlew -q loadUsernames --args="$study_names --run --yes"
    audit "study: loaded $study_names names"
    ./gradlew -q usernameStudy
    ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest stackCoverage || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac
