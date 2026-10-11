# Remove what install.ps1 set up: the background task, the binary, the agent skill, the Claude Code permission rule
# and MCP registration. The data directory (the SQLite message store) is kept unless KRETEG_PURGE=1.
#
#   irm https://github.com/dianw/kreteg/releases/latest/download/uninstall.ps1 | iex
#
# Env: KRETEG_BIN_DIR  where install.ps1 put the binary (default: %LOCALAPPDATA%\Programs\kreteg)
#      KRETEG_PURGE    set to 1 to also delete the data directory

function Uninstall-Kreteg {
  $ErrorActionPreference = 'Stop'

  $task = 'Kreteg'

  function Say($msg) { Write-Host "[kreteg] $msg" }
  function Has($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

  $binDir = if ($env:KRETEG_BIN_DIR) { $env:KRETEG_BIN_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\kreteg' }
  $dataDir = Join-Path $env:LOCALAPPDATA 'kreteg'
  $exe = Join-Path $binDir 'kreteg.exe'
  $skillDir = Join-Path $HOME '.claude\skills\kreteg'

  if (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $task -Confirm:$false
    Say "removed scheduled task $task"
  }
  $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Kreteg.lnk'
  if (Test-Path $lnk) { Remove-Item -Force $lnk; Say "removed $lnk" }
  Get-Process kreteg -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
  Start-Sleep -Milliseconds 500

  Remove-Item -Force (Join-Path $dataDir 'kreteg-service.cmd') -ErrorAction SilentlyContinue
  if (Test-Path $exe) { Remove-Item -Force $exe; Say "removed $exe" }
  if ((Test-Path $binDir) -and -not (Get-ChildItem $binDir -Force)) { Remove-Item -Force $binDir }
  $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  if ($userPath -and ($userPath -split ';') -contains $binDir) {
    [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and $_ -ne $binDir }) -join ';', 'User')
    Say "removed $binDir from your PATH"
  }
  if (Test-Path $skillDir) { Remove-Item -Recurse -Force $skillDir; Say "removed $skillDir" }
  # Undo install.ps1's permission rule, with the same care: never a link or a file that isn't a JSON object
  $settings = Join-Path $HOME '.claude\settings.json'
  $rule = 'Bash(kreteg:*)'
  $item = Get-Item $settings -Force -ErrorAction SilentlyContinue
  if ($item -and -not $item.LinkType) {
    try { $json = Get-Content -Raw $settings | ConvertFrom-Json } catch { $json = $null }
    if ($json -is [Management.Automation.PSCustomObject] -and $json.permissions -and
        @($json.permissions.allow) -contains $rule) {
      $new = "$settings.kreteg-new"
      try {
        $json.permissions.allow = [object[]]@(@($json.permissions.allow) | Where-Object { $_ -ne $rule })
        [IO.File]::WriteAllText($new, ($json | ConvertTo-Json -Depth 100), (New-Object Text.UTF8Encoding $false))
        Move-Item -Force $new $settings
        Say "removed $rule from $settings"
      } catch {
        Remove-Item -Force $new -ErrorAction SilentlyContinue
      }
    }
  }
  if (Has claude) {
    $ErrorActionPreference = 'Continue'  # see Invoke-Native in install.ps1
    claude mcp remove --scope user kreteg *> $null
    if ($LASTEXITCODE -eq 0) { Say 'removed the kreteg MCP server from Claude Code' }
    $ErrorActionPreference = 'Stop'
  }
  Say 'remove the kreteg entry from OpenCode or Codex config yourself if you added one'

  if ($env:KRETEG_PURGE -eq '1') {
    if (Test-Path $dataDir) { Remove-Item -Recurse -Force $dataDir }
    Say "deleted $dataDir"
  } elseif (Test-Path $dataDir) {
    Say "kept messages in $dataDir (KRETEG_PURGE=1 deletes them)"
  }
}

Uninstall-Kreteg
