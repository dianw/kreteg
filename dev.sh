#!/bin/sh
# Run Kreteg for development: Quarkus dev mode (API and MCP on :5784) and the Nuxt dev server (UI with hot reload on
# :3000, /api proxied to :5784). Quarkus runs in the foreground with its interactive console; Ctrl-C stops both.
#
#   ./dev.sh                   # extra arguments go to Maven, e.g. ./dev.sh -Dkreteg.db.path=/tmp/k.db
#
# Env: KRETEG_UI_PORT  port for the Nuxt dev server (default: 3000)
set -eu

cd "$(dirname "$0")"

say() { printf '%s\n' "[kreteg] $*"; }
die() { printf '%s\n' "[kreteg] error: $*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

UI_PORT="${KRETEG_UI_PORT:-3000}"

# Prefer the Node that Maven installed (the version pinned in ui/pom.xml), then whatever is on PATH
if [ -x ui/target/node/node ]; then
  PATH="$PWD/ui/target/node:$PATH"
  export PATH
fi
has node || die "Node.js not found; install $(cat ui/.nvmrc) or run 'mvn -pl ui package' once to download it"
has mvn || die "Maven not found"
node_version="$(node -v)"
[ "$node_version" = "v$(cat ui/.nvmrc)" ] || say "using Node $node_version; ui/.nvmrc pins v$(cat ui/.nvmrc)"

if [ ! -d ui/node_modules ]; then
  say "installing UI dependencies"
  (cd ui && npm ci)
fi

nuxt_pid=""
stop() {
  # The UI server runs in its own process group (set -m below), so this also stops the workers Nuxt forks
  [ -n "$nuxt_pid" ] && kill -- "-$nuxt_pid" 2>/dev/null || true
}
trap stop EXIT INT TERM

say "UI on http://localhost:$UI_PORT (API proxied to :5784)"
set -m
# Without a terminal on stdin: a background process group that reads the TTY is stopped (SIGTTIN) while Quarkus owns
# the console, and Nuxt reads it for its keyboard shortcuts
(cd ui && exec node_modules/.bin/nuxt dev --port "$UI_PORT" </dev/null) &
nuxt_pid=$!
set +m

# The UI is served by Nuxt here, so Maven skips the npm build of the kreteg-ui jar
mvn -pl server -am quarkus:dev -Dskip.npm "$@"
