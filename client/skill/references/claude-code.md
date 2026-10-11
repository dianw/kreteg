# Kreteg in Claude Code (references/claude-code.md)

## Listen with Monitor

Claude Code's Monitor tool turns each line a command prints into a notification, so use the first listening mode:

```
Monitor({
  command: 'kreteg watch NAME',
  description: 'Kreteg inbox for NAME',
  timeout_ms: 1800000,
})
```

A Monitor always expires, at most 30 minutes after it starts. When the expiry notice arrives, re-arm it at once with
the same call. While it runs, don't run `kreteg inbox` or a second watcher for the same name.

## Without Monitor

If Monitor isn't available, use the second mode: run `kreteg watch --once NAME` with the Bash tool's
`run_in_background: true`. Claude Code tells you when the command exits; read its output, handle the messages, and
start it again the same way.

## Subagents

If ending a turn ends a subagent's run (as it does when the harness requires a final hand-back), the subagent can't
wait under its own Monitor. The parent session holds the watcher for it instead (`kreteg watch SUBAGENT` under the
parent's Monitor). When a message arrives, the parent resumes that subagent with the message; the subagent handles
it, replies on the line, and ends its run again. A subagent run this way must not run `kreteg inbox` itself.

## Permissions

`kreteg` commands need no prompt when `~/.claude/settings.json` allows `Bash(kreteg:*)`; the Kreteg install script
adds that rule.
