#!/bin/sh
# Install Kreteg for the current user: the server binary, a background service, the agent skill with its watch
# script, and the MCP registration for Claude Code. Needs no administrator rights. Re-run it to upgrade.
#
#   curl -fsSL https://raw.githubusercontent.com/dianw/kreteg/main/install.sh | sh
#
# Env: KRETEG_VERSION     release tag to install (default: latest, e.g. v0.1.0)
#      KRETEG_BIN_DIR     where the binary goes (default: ~/.local/bin)
#      KRETEG_NO_SERVICE  set to 1 to skip the background service
#      KRETEG_NO_MCP      set to 1 to skip registering the MCP server with Claude Code
set -eu

REPO="dianw/kreteg"
PORT=5784
LABEL="io.kreteg.server"

say() { printf '%s\n' "[kreteg] $*"; }
warn() { printf '%s\n' "[kreteg] warning: $*" >&2; }
die() { printf '%s\n' "[kreteg] error: $*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

detect_platform() {
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux) os=linux ;;
    *) die "unsupported OS $(uname -s); on Windows download kreteg-windows-amd64.exe from https://github.com/$REPO/releases" ;;
  esac
  case "$(uname -m)" in
    x86_64 | amd64) arch=amd64 ;;
    arm64 | aarch64) arch=arm64 ;;
    *) die "unsupported CPU $(uname -m)" ;;
  esac
  asset="kreteg-$os-$arch"
}

sha256() {
  if has sha256sum; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

download() {
  version="${KRETEG_VERSION:-latest}"
  if [ "$version" = latest ]; then base="https://github.com/$REPO/releases/latest/download"
  else base="https://github.com/$REPO/releases/download/$version"
  fi
  say "downloading $asset ($version)"
  for f in "$asset" SHA256SUMS SKILL.md kreteg-watch; do
    curl -fsSL -o "$tmp/$f" "$base/$f" || die "cannot download $base/$f"
  done
  for f in "$asset" SKILL.md kreteg-watch; do
    expected=$(awk -v f="$f" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
    [ -n "$expected" ] || die "$f is missing from SHA256SUMS"
    [ "$(sha256 "$tmp/$f")" = "$expected" ] || die "checksum mismatch for $f"
  done
}

stop_service() {
  case "$os" in
    darwin) launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true ;;
    linux) has systemctl && systemctl --user stop kreteg.service >/dev/null 2>&1 || true ;;
  esac
}

install_binary() {
  mkdir -p "$bin_dir"
  # Move a fresh file into place instead of overwriting: macOS kills a process whose signed binary changes on disk
  cp "$tmp/$asset" "$bin_dir/.kreteg.new"
  chmod 755 "$bin_dir/.kreteg.new"
  mv -f "$bin_dir/.kreteg.new" "$bin_dir/kreteg"
  say "installed $bin_dir/kreteg"
  case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) warn "$bin_dir is not on PATH; add: export PATH=\"$bin_dir:\$PATH\"" ;;
  esac
}

install_launchd() {
  plist="$HOME/Library/LaunchAgents/$LABEL.plist"
  mkdir -p "$HOME/Library/LaunchAgents"
  cat >"$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$bin_dir/kreteg</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>KRETEG_DB_PATH</key><string>$data_dir/kreteg.db</string>
    <key>QUARKUS_HTTP_HOST</key><string>127.0.0.1</string>
  </dict>
  <key>WorkingDirectory</key><string>$data_dir</string>
  <key>StandardOutPath</key><string>$data_dir/kreteg.log</string>
  <key>StandardErrorPath</key><string>$data_dir/kreteg.log</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
EOF
  launchctl bootstrap "gui/$(id -u)" "$plist" || die "launchctl could not load $plist"
  say "started launchd agent $LABEL (log: $data_dir/kreteg.log)"
  logs="$data_dir/kreteg.log"
}

install_systemd() {
  if ! has systemctl || ! systemctl --user show-environment >/dev/null 2>&1; then
    warn "no systemd user session here; start the server yourself:"
    warn "  KRETEG_DB_PATH=\"$data_dir/kreteg.db\" QUARKUS_HTTP_HOST=127.0.0.1 \"$bin_dir/kreteg\""
    return 1
  fi
  unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$unit_dir"
  cat >"$unit_dir/kreteg.service" <<EOF
[Unit]
Description=Kreteg conversation hub for agent sessions

[Service]
ExecStart="$bin_dir/kreteg"
WorkingDirectory=$data_dir
Environment="KRETEG_DB_PATH=$data_dir/kreteg.db"
Environment=QUARKUS_HTTP_HOST=127.0.0.1
Restart=on-failure

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable kreteg.service >/dev/null 2>&1
  systemctl --user restart kreteg.service
  say "started systemd user unit kreteg.service (log: journalctl --user -u kreteg)"
  say "it stops when you log out unless lingering is on: loginctl enable-linger $(id -un)"
  logs="journalctl --user -u kreteg"
}

wait_until_up() {
  i=0
  while [ $i -lt 15 ]; do
    if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
      say "server is up at http://localhost:$PORT"
      return 0
    fi
    sleep 1; i=$((i + 1))
  done
  warn "server did not answer on port $PORT within 15s; check $logs"
}

install_skill() {
  skill_dir="$HOME/.claude/skills/kreteg"
  mkdir -p "$skill_dir"
  cp "$tmp/SKILL.md" "$skill_dir/SKILL.md"
  cp "$tmp/kreteg-watch" "$skill_dir/kreteg-watch"
  chmod 755 "$skill_dir/kreteg-watch"
  say "installed skill and watch script in $skill_dir"
  has jq || warn "kreteg-watch needs jq; install it with your package manager"
}

register_mcp() {
  if has claude; then
    claude mcp remove --scope user kreteg >/dev/null 2>&1 || true
    if claude mcp add --transport http --scope user kreteg "http://localhost:$PORT/mcp" >/dev/null; then
      say "registered the kreteg MCP server with Claude Code"
    else
      warn "could not register with Claude Code; run: claude mcp add --transport http --scope user kreteg http://localhost:$PORT/mcp"
    fi
  fi
  cat <<EOF
[kreteg] for other harnesses, add the MCP server to their config:
  OpenCode  ~/.config/opencode/opencode.jsonc:  "mcp": { "kreteg": { "type": "remote", "url": "http://localhost:$PORT/mcp" } }
  Codex     ~/.codex/config.toml:               [mcp_servers.kreteg]  url = "http://localhost:$PORT/mcp"
EOF
}

main() {
  has curl || die "curl is required"
  detect_platform
  bin_dir="${KRETEG_BIN_DIR:-$HOME/.local/bin}"
  data_dir="${XDG_DATA_HOME:-$HOME/.local/share}/kreteg"
  logs="$data_dir"
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  download
  [ "${KRETEG_NO_SERVICE:-0}" = 1 ] || stop_service
  install_binary
  mkdir -p "$data_dir"
  if [ "${KRETEG_NO_SERVICE:-0}" != 1 ]; then
    case "$os" in
      darwin) install_launchd && wait_until_up ;;
      linux) install_systemd && wait_until_up || true ;;
    esac
  fi
  install_skill
  [ "${KRETEG_NO_MCP:-0}" = 1 ] || register_mcp

  say "done. data: $data_dir"
  say "uninstall: curl -fsSL https://raw.githubusercontent.com/$REPO/main/uninstall.sh | sh"
}

# Everything runs from main, so a partially downloaded script does nothing
main "$@"
