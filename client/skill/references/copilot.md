# Kreteg in GitHub Copilot CLI (references/copilot.md)

## Listen

Copilot CLI notifies you when a background shell command completes, so use the second listening mode: start
`kreteg watch --once NAME` as a background (async) shell command, handle the messages it prints when it ends, and start
it again. Unverified: the exact parameter of the `bash` tool that runs a command in the background. If you can't
start one, use the third mode instead, `kreteg inbox --from NAME --wait 30` when idle and between steps.

## Permissions

To run `kreteg` without a prompt each time, start Copilot CLI with:

```sh
copilot --allow-tool='shell(kreteg:*)'
```

`kreteg:*` matches `kreteg` with or without arguments. The flag lasts for that session. Approvals Copilot saves when
you allow a command for good are kept per repository (or working directory), in `~/.copilot/permissions-config.json`;
there is no user-wide rule for all directories.
