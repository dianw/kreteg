# Kreteg in Oh My Pi (references/omp.md)

## Listen

omp's bash tool can run a command in the background and hand you its result when it ends, so use the second
listening mode: run `kreteg watch --once NAME` with `async: true` and `timeout: 0` (no deadline, as for any watcher).
When its result arrives, handle the messages and start it again the same way. If background jobs are turned off
(`async.enabled`), use the third mode instead, `kreteg inbox --from NAME --wait 30` when idle and between steps.

## Permissions

None needed by default: omp's approval mode is `yolo`, which runs commands without asking. If you've switched to
`always-ask` or `write`, the only override is for the bash tool as a whole (`tools.approval.bash: allow` in
`~/.omp/agent/config.yml`); there is no rule for `kreteg` alone. Unverified: the exact YAML layout of that key.
