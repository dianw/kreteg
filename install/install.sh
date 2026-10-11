#!/bin/sh
# Install Kreteg for the current user: the binary (hub and command-line client in one), a background service, the
# agent skill (written by the binary), and a Claude Code permission rule for the client. Needs no administrator rights. Re-run it to upgrade.
#
#   curl -fsSL https://github.com/dianw/kreteg/releases/latest/download/install.sh | sh
#
# Each release publishes its own copy of this script with its tag stamped in, so it installs the binary it shipped
# with. Run from a checkout, it installs the latest release.
#
# Env: KRETEG_VERSION     release tag to install (default: the release this script came from, e.g. v0.3.0)
#      KRETEG_BIN_DIR     where the binary goes (default: ~/.local/bin)
#      KRETEG_NO_SERVICE  set to 1 to skip the background service
#      KRETEG_MCP         set to 1 to also register the MCP server with Claude Code, for agents without a shell
set -eu

REPO="dianw/kreteg"
# Replaced with the tag by release.yml; an unreplaced placeholder means a checkout, which installs the latest release
VERSION="${KRETEG_VERSION:-@VERSION@}"
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
    *) die "unsupported OS $(uname -s); on Windows run this in PowerShell instead: irm $base/install.ps1 | iex" ;;
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

resolve_version() {
  case "$VERSION" in
    @*@ | latest) version=latest; base="https://github.com/$REPO/releases/latest/download" ;;
    *) version="$VERSION"; base="https://github.com/$REPO/releases/download/$VERSION" ;;
  esac
}

download() {
  say "downloading $asset ($version)"
  curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" || die "cannot download $base/SHA256SUMS"
  # Releases before 0.3.0 have no client commands, so the units and skill written below would not work with them.
  # They are recognised by name, without running the binary: a 0.2.0 binary given any argument starts a hub.
  awk '$2 == "install.sh" || $2 == "*install.sh" { found = 1 } END { exit !found }' "$tmp/SHA256SUMS" ||
    die "release $version predates the kreteg command (0.3.0); install v0.3.0 or later"
  curl -fsSL -o "$tmp/$asset" "$base/$asset" || die "cannot download $base/$asset"
  expected=$(awk -v f="$asset" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
  [ -n "$expected" ] || die "$asset is missing from SHA256SUMS"
  [ "$(sha256 "$tmp/$asset")" = "$expected" ] || die "checksum mismatch for $asset"
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
    *) warn "$bin_dir is not on PATH, and agents run kreteg from PATH; add: export PATH=\"$bin_dir:\$PATH\"" ;;
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
  <key>ProgramArguments</key><array><string>$bin_dir/kreteg</string><string>serve</string></array>
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
    warn "  KRETEG_DB_PATH=\"$data_dir/kreteg.db\" QUARKUS_HTTP_HOST=127.0.0.1 \"$bin_dir/kreteg\" serve"
    return 1
  fi
  unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$unit_dir"
  cat >"$unit_dir/kreteg.service" <<EOF
[Unit]
Description=Kreteg conversation hub for agent sessions

[Service]
ExecStart="$bin_dir/kreteg" serve
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
  # The binary carries the skill that matches it (SKILL.md and references/), the same text 'kreteg guide' prints.
  # ~/.claude/skills is read by Claude Code (and OpenCode); ~/.agents/skills by Codex, Copilot CLI and OpenCode.
  # Real copies, not links: --write-skill replaces each file atomically and prunes stale ones.
  for dir in "$HOME/.claude/skills/kreteg" "$HOME/.agents/skills/kreteg"; do
    "$bin_dir/kreteg" guide --write-skill "$dir" >/dev/null || die "could not write the skill to $dir"
    say "installed skill in $dir"
  done
  skill_dir="$HOME/.claude/skills/kreteg"
  # The bash + curl + jq watch script was replaced by 'kreteg watch' in 0.3.0. Sessions that loaded the old skill
  # re-arm their watch with its path, so on upgrade it becomes a stand-in for the new command instead of vanishing.
  # A new file moved into place: a running copy keeps its inode (bash reads scripts while running them), and a
  # symlink is replaced rather than written through. Remove in 0.5.0.
  if [ -e "$skill_dir/kreteg-watch" ] || [ -L "$skill_dir/kreteg-watch" ]; then
    cat >"$skill_dir/kreteg-watch.new" <<EOF
#!/bin/sh
# Kept for sessions that loaded the pre-0.3.0 skill; new sessions run 'kreteg watch' directly
exec "$bin_dir/kreteg" watch "\$@"
EOF
    chmod 755 "$skill_dir/kreteg-watch.new"
    mv -f "$skill_dir/kreteg-watch.new" "$skill_dir/kreteg-watch"
  fi
  say "harnesses without skills: add this line to their global AGENTS.md: Before using Kreteg, run \`kreteg guide\` and follow it."
}

# Lets Claude Code agents run the client without a permission prompt for every message
allow_cli() {
  settings="$HOME/.claude/settings.json"
  rule='Bash(kreteg:*)'
  hint="to let Claude Code run kreteg without asking, add \"$rule\" to permissions.allow in $settings"
  if ! has jq; then
    say "$hint"
    return 0
  fi
  if [ -L "$settings" ]; then
    say "$hint (it is a symlink, so it was left alone)"
    return 0
  fi
  if [ -f "$settings" ]; then
    if ! jq -e 'type == "object"' "$settings" >/dev/null 2>&1; then
      warn "$settings is not a JSON object; left it alone"
      say "$hint"
      return 0
    fi
    jq -e --arg r "$rule" '(.permissions.allow // []) | index($r)' "$settings" >/dev/null 2>&1 && return 0
  fi
  mkdir -p "$HOME/.claude"
  new="$settings.kreteg-new"
  if { [ -f "$settings" ] && jq --arg r "$rule" '.permissions.allow = ((.permissions.allow // []) + [$r])' \
        "$settings" >"$new"; } || { [ ! -f "$settings" ] && jq -n --arg r "$rule" '{permissions: {allow: [$r]}}' >"$new"; }
  then
    mv -f "$new" "$settings"
    say "allowed $rule in $settings"
  else
    rm -f "$new"
    warn "could not update $settings"
    say "$hint"
  fi
}

# How to let each other harness found here run kreteg without a prompt; their configs are theirs to edit
harness_hints() {
  if has opencode; then
    say "OpenCode: to run kreteg without asking, add \"permission\": {\"bash\": {\"kreteg *\": \"allow\"}} to ~/.config/opencode/opencode.json"
  fi
  if has codex; then
    say "Codex: to run kreteg without asking (and outside its sandbox, to reach the hub), add" \
      "prefix_rule(pattern = [\"kreteg\"], decision = \"allow\") to ~/.codex/rules/default.rules"
  fi
  if has copilot; then
    say "Copilot CLI: to run kreteg without asking, start it with: copilot --allow-tool='shell(kreteg:*)'"
  fi
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
  resolve_version
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
  allow_cli
  harness_hints
  if [ "${KRETEG_MCP:-0}" = 1 ]; then
    register_mcp
  else
    say "agents use the kreteg command; for clients without a shell, re-run with KRETEG_MCP=1 to register MCP"
  fi

  say "done. data: $data_dir; try: kreteg who"
  say "uninstall: curl -fsSL https://github.com/$REPO/releases/latest/download/uninstall.sh | sh"
}

# Everything runs from main, so a partially downloaded script does nothing
main "$@"
