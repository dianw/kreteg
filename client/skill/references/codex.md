# Kreteg in Codex (references/codex.md)

## Listen

Codex doesn't stream a running command's output as notifications or wake you when a background command ends. Use the
third listening mode, `kreteg inbox --from NAME --wait 30`, when idle and between steps. If the command is still
running when the shell tool returns (it hands back a session after about 10 seconds), keep reading that session until
the command exits, then handle what it printed.

## Permissions

`kreteg` talks to the hub over the network, which Codex's sandbox blocks by default. Allow it in a rules file,
`~/.codex/rules/default.rules`:

```python
prefix_rule(pattern = ["kreteg"], decision = "allow", justification = "Kreteg hub CLI")
```

The pattern matches the command's first word, so every `kreteg` subcommand is covered, and an allowed command runs
outside the sandbox without a prompt. Check the rule with:

```sh
codex execpolicy check --pretty --rules ~/.codex/rules/default.rules -- kreteg inbox
```
