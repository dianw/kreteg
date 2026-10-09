#!/bin/sh
# Remove what install.sh set up: the background service, the binary, the agent skill and the Claude Code MCP
# registration. The data directory (the SQLite message store) is kept unless KRETEG_PURGE=1.
#
#   curl -fsSL https://raw.githubusercontent.com/dianw/kreteg/main/uninstall.sh | sh
#
# Env: KRETEG_BIN_DIR  where install.sh put the binary (default: ~/.local/bin)
#      KRETEG_PURGE    set to 1 to also delete the data directory
set -eu

LABEL="io.kreteg.server"

say() { printf '%s\n' "[kreteg] $*"; }
has() { command -v "$1" >/dev/null 2>&1; }

remove_service() {
  plist="$HOME/Library/LaunchAgents/$LABEL.plist"
  if [ "$(uname -s)" = Darwin ]; then
    launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
    if [ -f "$plist" ]; then rm -f "$plist"; say "removed launchd agent $LABEL"; fi
  fi
  unit="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/kreteg.service"
  if [ -f "$unit" ]; then
    if has systemctl; then systemctl --user disable --now kreteg.service >/dev/null 2>&1 || true; fi
    rm -f "$unit"
    if has systemctl; then systemctl --user daemon-reload >/dev/null 2>&1 || true; fi
    say "removed systemd user unit kreteg.service"
  fi
}

main() {
  bin_dir="${KRETEG_BIN_DIR:-$HOME/.local/bin}"
  data_dir="${XDG_DATA_HOME:-$HOME/.local/share}/kreteg"
  skill_dir="$HOME/.claude/skills/kreteg"

  remove_service
  if [ -f "$bin_dir/kreteg" ]; then rm -f "$bin_dir/kreteg"; say "removed $bin_dir/kreteg"; fi
  if [ -d "$skill_dir" ]; then rm -rf "$skill_dir"; say "removed $skill_dir"; fi
  if has claude && claude mcp remove --scope user kreteg >/dev/null 2>&1; then
    say "removed the kreteg MCP server from Claude Code"
  fi
  say "remove the kreteg entry from OpenCode or Codex config yourself if you added one"

  if [ "${KRETEG_PURGE:-0}" = 1 ]; then
    rm -rf "$data_dir"
    say "deleted $data_dir"
  elif [ -d "$data_dir" ]; then
    say "kept messages in $data_dir (KRETEG_PURGE=1 deletes them)"
  fi
}

main "$@"
