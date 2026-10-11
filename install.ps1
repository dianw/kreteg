# Install Kreteg on Windows for the current user: the binary (hub and command-line client in one), a background task
# started at logon, the agent skill, and a Claude Code permission rule for the client. Needs no administrator rights.
# Re-run it to upgrade. Works in Windows PowerShell 5.1 and PowerShell 7.
#
#   irm https://raw.githubusercontent.com/dianw/kreteg/main/install.ps1 | iex
#
# Env: KRETEG_VERSION     release tag to install (default: latest, e.g. v0.1.0)
#      KRETEG_BIN_DIR     where the binary goes (default: %LOCALAPPDATA%\Programs\kreteg)
#      KRETEG_NO_SERVICE  set to 1 to skip the background task
#      KRETEG_MCP         set to 1 to also register the MCP server with Claude Code, for agents without a shell

function Install-Kreteg {
  $ErrorActionPreference = 'Stop'
  $ProgressPreference = 'SilentlyContinue'  # the progress bar makes Invoke-WebRequest many times slower in 5.1

  $repo = 'dianw/kreteg'
  $port = 5784
  $task = 'Kreteg'
  $asset = 'kreteg-windows-amd64.exe'

  function Say($msg) { Write-Host "[kreteg] $msg" }
  function Warn($msg) { Write-Warning "[kreteg] $msg" }
  # throw, not exit: under `irm | iex` exit would close the user's shell
  function Die($msg) { throw "[kreteg] error: $msg" }
  function Has($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

  # Runs a native command, returning whether it succeeded. Windows PowerShell 5.1 turns a native command's stderr
  # into terminating errors under ErrorActionPreference Stop, so relax it for the call.
  function Invoke-Native([scriptblock]$cmd) {
    $ErrorActionPreference = 'Continue'
    & $cmd *> $null
    $LASTEXITCODE -eq 0
  }

  $arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
  # Windows on ARM runs the amd64 binary under emulation
  if ($arch -notin 'AMD64', 'ARM64') { Die "unsupported CPU $arch" }

  $binDir = if ($env:KRETEG_BIN_DIR) { $env:KRETEG_BIN_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\kreteg' }
  $dataDir = Join-Path $env:LOCALAPPDATA 'kreteg'
  $exe = Join-Path $binDir 'kreteg.exe'
  $log = Join-Path $dataDir 'kreteg.log'
  $noService = $env:KRETEG_NO_SERVICE -eq '1'
  $tmp = Join-Path ([IO.Path]::GetTempPath()) ("kreteg-" + [guid]::NewGuid())
  New-Item -ItemType Directory -Force $tmp | Out-Null

  try {
    # --- download and verify
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $version = if ($env:KRETEG_VERSION) { $env:KRETEG_VERSION } else { 'latest' }
    $base = if ($version -eq 'latest') { "https://github.com/$repo/releases/latest/download" }
            else { "https://github.com/$repo/releases/download/$version" }
    Say "downloading $asset ($version)"
    foreach ($f in $asset, 'SHA256SUMS', 'SKILL.md') {
      try { Invoke-WebRequest -UseBasicParsing -Uri "$base/$f" -OutFile (Join-Path $tmp $f) }
      catch { Die "cannot download $base/$f" }
    }
    $sums = @{}
    foreach ($line in Get-Content (Join-Path $tmp 'SHA256SUMS')) {
      $hash, $name = $line -split '\s+', 2
      if ($name) { $sums[$name.TrimStart('*')] = $hash }
    }
    foreach ($f in $asset, 'SKILL.md') {
      if (-not $sums.ContainsKey($f)) { Die "$f is missing from SHA256SUMS" }
      $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $f)).Hash
      if ($actual -ne $sums[$f]) { Die "checksum mismatch for $f" }  # -ne ignores case
    }

    # --- stop a running server: Windows cannot replace an executable that is running
    if (-not $noService) {
      if (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
      }
      Get-Process kreteg -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
      Start-Sleep -Milliseconds 500
    }

    # --- binary
    New-Item -ItemType Directory -Force $binDir | Out-Null
    Copy-Item -Force (Join-Path $tmp $asset) $exe
    Say "installed $exe"
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (($userPath -split ';') -notcontains $binDir) {
      [Environment]::SetEnvironmentVariable('Path', (@($userPath, $binDir) | Where-Object { $_ }) -join ';', 'User')
      $env:Path = "$env:Path;$binDir"
      Say "added $binDir to your PATH (new terminals pick it up)"
    }

    # --- background task
    New-Item -ItemType Directory -Force $dataDir | Out-Null
    if (-not $noService) {
      # A batch launcher sets the environment and captures the log; conhost --headless runs it without a window
      $launcher = Join-Path $dataDir 'kreteg-service.cmd'
      Set-Content -Encoding ASCII $launcher @"
@echo off
set "KRETEG_DB_PATH=$dataDir\kreteg.db"
set "QUARKUS_HTTP_HOST=127.0.0.1"
cd /d "$dataDir"
"$exe" serve >> "$log" 2>&1
"@
      $conhost = Join-Path $env:SystemRoot 'System32\conhost.exe'
      $arguments = "--headless cmd.exe /c `"$launcher`""
      $user = "$env:USERDOMAIN\$env:USERNAME"
      $registered = $false
      try {
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
          -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
          -MultipleInstances IgnoreNew
        Register-ScheduledTask -TaskName $task -Force -Description 'Kreteg conversation hub for agent sessions' `
          -Action (New-ScheduledTaskAction -Execute $conhost -Argument $arguments -WorkingDirectory $dataDir) `
          -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $user) `
          -Principal (New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited) `
          -Settings $settings | Out-Null
        Start-ScheduledTask -TaskName $task
        $registered = $true
        Say "started scheduled task $task (log: $log)"
      } catch {
        Warn "could not register a scheduled task ($($_.Exception.Message)); using the Startup folder instead"
      }
      if (-not $registered) {
        # Fallback without restart-on-failure: a shortcut in the Startup folder, run once now
        $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Kreteg.lnk'
        $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
        $shortcut.TargetPath = $conhost
        $shortcut.Arguments = $arguments
        $shortcut.WorkingDirectory = $dataDir
        $shortcut.Save()
        Start-Process -FilePath $conhost -ArgumentList $arguments -WorkingDirectory $dataDir -WindowStyle Hidden
        Say "started kreteg and added $lnk (log: $log)"
      }

      $up = $false
      for ($i = 0; $i -lt 15 -and -not $up; $i++) {
        try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://127.0.0.1:$port/" | Out-Null; $up = $true }
        catch { Start-Sleep -Seconds 1 }
      }
      if ($up) { Say "server is up at http://localhost:$port" }
      else { Warn "server did not answer on port $port within 15s; check $log" }
    }

    # --- skill
    $skillDir = Join-Path $HOME '.claude\skills\kreteg'
    New-Item -ItemType Directory -Force $skillDir | Out-Null
    Copy-Item -Force (Join-Path $tmp 'SKILL.md') $skillDir
    # The bash + curl + jq watch script was replaced by 'kreteg watch' in 0.3.0. Sessions that loaded the old skill
    # re-arm their watch with its path (Claude Code runs it in Git Bash), so on upgrade it becomes a stand-in for the
    # new command instead of vanishing. LF line endings: a CR after the shebang breaks it. Written to a new file and
    # moved into place, so a running copy keeps its own and a link is replaced rather than written through.
    # Remove in 0.5.0.
    $watch = Join-Path $skillDir 'kreteg-watch'
    if (Get-Item $watch -Force -ErrorAction SilentlyContinue) {
      $shim = "#!/bin/sh`n" +
        "# Kept for sessions that loaded the pre-0.3.0 skill; new sessions run 'kreteg watch' directly`n" +
        "exec `"$($exe -replace '\\', '/')`" watch `"`$@`"`n"
      [IO.File]::WriteAllText("$watch.new", $shim, (New-Object Text.UTF8Encoding $false))
      Move-Item -Force "$watch.new" $watch
    }
    Say "installed skill in $skillDir"

    # --- Claude Code permission rule, so agents can run the client without a prompt for every message
    $settings = Join-Path $HOME '.claude\settings.json'
    $rule = 'Bash(kreteg:*)'
    $hint = "to let Claude Code run kreteg without asking, add `"$rule`" to permissions.allow in $settings"
    $item = Get-Item $settings -Force -ErrorAction SilentlyContinue
    if ($item -and $item.LinkType) {
      Say "$hint (it is a link, so it was left alone)"
    } else {
      $json = $null
      if ($item) {
        try { $json = Get-Content -Raw $settings | ConvertFrom-Json } catch { $json = $null }
      } else {
        $json = New-Object PSObject
      }
      if ($json -isnot [Management.Automation.PSCustomObject]) {
        Warn "$settings is not a JSON object; left it alone"
        Say $hint
      } else {
        $allow = @()
        if ($json.permissions -and $null -ne $json.permissions.allow) { $allow = @($json.permissions.allow) }
        if ($allow -notcontains $rule) {
          $new = "$settings.kreteg-new"
          try {
            if ($null -eq $json.permissions) {
              Add-Member -InputObject $json -NotePropertyName permissions -NotePropertyValue (New-Object PSObject)
            }
            # Assign when present, so the keys keep their order; Add-Member -Force would move 'allow' to the end
            if ($json.permissions.PSObject.Properties['allow']) {
              $json.permissions.allow = [object[]]($allow + $rule)
            } else {
              Add-Member -InputObject $json.permissions -NotePropertyName allow `
                -NotePropertyValue ([object[]]($allow + $rule))
            }
            New-Item -ItemType Directory -Force (Split-Path $settings) | Out-Null
            # Without a BOM: Windows PowerShell's UTF8 encoding writes one, and JSON parsers reject it
            [IO.File]::WriteAllText($new, ($json | ConvertTo-Json -Depth 100), (New-Object Text.UTF8Encoding $false))
            Move-Item -Force $new $settings
            Say "allowed $rule in $settings"
          } catch {
            Remove-Item -Force $new -ErrorAction SilentlyContinue
            Warn "could not update $settings ($($_.Exception.Message))"
            Say $hint
          }
        }
      }
    }

    # --- MCP registration, for clients without a shell
    if ($env:KRETEG_MCP -ne '1') {
      Say 'agents use the kreteg command; for clients without a shell, re-run with KRETEG_MCP=1 to register MCP'
    } else {
      if (Has claude) {
        Invoke-Native { claude mcp remove --scope user kreteg } | Out-Null
        if (Invoke-Native { claude mcp add --transport http --scope user kreteg "http://localhost:$port/mcp" }) {
          Say 'registered the kreteg MCP server with Claude Code'
        } else {
          Warn "could not register with Claude Code; run: claude mcp add --transport http --scope user kreteg http://localhost:$port/mcp"
        }
      }
      Write-Host @"
[kreteg] for other harnesses, add the MCP server to their config:
  OpenCode  ~/.config/opencode/opencode.jsonc:  "mcp": { "kreteg": { "type": "remote", "url": "http://localhost:$port/mcp" } }
  Codex     ~/.codex/config.toml:               [mcp_servers.kreteg]  url = "http://localhost:$port/mcp"
"@
    }

    Say "done. data: $dataDir; try (in a new terminal): kreteg who"
    Say "uninstall: irm https://raw.githubusercontent.com/$repo/main/uninstall.ps1 | iex"
  } finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  }
}

# Everything runs from the function, so a partially downloaded script does nothing
Install-Kreteg
