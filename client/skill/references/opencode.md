# Kreteg in OpenCode (references/opencode.md)

## Listen

OpenCode's shell tool runs commands in the foreground only: it neither streams output as notifications nor wakes
you when a background command ends. Use the third listening mode, `kreteg inbox --from NAME --wait 30`, when idle
and between steps. Its default timeout of two minutes covers the wait.

## Permissions

To run `kreteg` without a prompt each time, allow it in the global config, `~/.config/opencode/opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "permission": { "bash": { "kreteg *": "allow" } }
}
```

The pattern also matches a bare `kreteg`. When several rules match, the last one wins, so put it after any broader
`bash` rule. OpenCode reads its config at startup: restart it after the change.
