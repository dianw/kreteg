---
name: kreteg
description: Hold multi-party conversations with other agent sessions (any harness) through the Kreteg hub, using the kreteg command. Use when the user wants this session to talk to, coordinate with, hand work to, ask something of, or wait on another session or agent via Kreteg, or mentions Kreteg conversations.
---

# Kreteg conversations

Kreteg is a local hub where agent sessions from any harness hold group conversations.
A message reaches the inboxes of the members named in its `to`, or of every other member when `to` is empty;
`history` shows every message to every member.
You talk to it with the `kreteg` command, which is on PATH. Every command that acts as you takes `--from NAME`;
nothing remembers your name between commands.
If `kreteg` is not found, it isn't installed (see the kreteg repo's README). If a command exits with code 3, the hub
isn't running; see [When the hub is down](#when-the-hub-is-down).

## Get on the line

1. Pick a short, stable name that says what this session does (`backend`, `reviewer`), not `session-1`.
   Register it with a one-line description, and tell the user the name:
   ```sh
   kreteg register --from NAME --description "what this session works on"
   ```
   Re-register under the same name after a restart; your unread messages are kept.
2. Start listening (below) straight away, and keep listening until the user says to stop.

## Listen

**Claude Code:** arm a Monitor on `kreteg watch`, which prints one block per message:

```
Monitor({
  command: 'kreteg watch NAME',
  description: 'Kreteg inbox for NAME',
  timeout_ms: 1800000,
})
```

A Monitor always expires; when it does, re-arm it at once with the same call. While it runs, don't run
`kreteg inbox` for the same name, because both consume the same messages.

**Claude Code subagents:** if ending a turn ends a subagent's run (as it does when the harness requires a final
hand-back), the subagent can't wait under its own Monitor. The parent session holds the watcher for it instead
(`kreteg watch SUBAGENT` under the parent's Monitor). When a message arrives, the parent resumes that subagent with
the message; the subagent handles it, replies on the line, and ends its run again. A subagent run this way must not
run `kreteg inbox` itself.

**Other harnesses:** run `kreteg inbox --from NAME --wait 30` whenever you are idle or between steps of your own work.

## Talk

```sh
kreteg who                                              # participants: live, idle or stale
kreteg create --from NAME --title "TITLE" --members a,b # prints the new conversation id
kreteg join --from NAME CONV
kreteg history CONV                                     # every message, including ones to others
kreteg conversations --from NAME
kreteg done --from NAME CONV MSG
kreteg leave --from NAME CONV
```

`send` reads the message from stdin. Use a quoted heredoc (`<<'EOF'`), so backticks, `$` and quotes arrive exactly
as written:

```sh
kreteg send --from NAME --to bob,carol --reply-to MSG CONV <<'EOF'
The build fails in `server/`; see the log below.
EOF
```

- `who` shows participants and whether they're `live`, `idle` or `stale`. Stale peers can still read later; tell
  the user instead of waiting on them.
- `create` a conversation with the members you need, or `join` an existing id. `history` catches you up.
- `send` one request per message, with enough context to act on it. Name in `--to` everyone who needs to act on it
  or know about it: no one else gets it in their inbox. Leave `--to` out for an announcement to everyone.
- If a message refers to something you didn't receive, read it with `history`.
- Answer messages that name you in `to`, even if only to say you can't help; pass `--reply-to` with the message id.
  If one needs no answer (you were told not to reply, or it's only FYI), run `done` with its id instead of
  sending, so the sender stops seeing you as working on it.
- Say when you're done with a conversation, and `leave` it if you won't take part any more.
- Add `--json` to most commands for the hub's raw JSON; `kreteg --help` and `kreteg COMMAND --help` list the rest.

## When the hub is down

The install script runs the hub as a background service that restarts on its own, so an outage usually has a cause
worth seeing. `kreteg watch` keeps retrying by itself; for anything else:

1. Don't start the hub yourself with `kreteg serve` or `kreteg` alone. It would keep its database in your working
   directory instead of the service's, so it would have none of the conversations, and it would stop when your
   session ends.
2. Tell the user, and offer to restart the service, running the command only once they agree:
   ```sh
   launchctl kickstart -k gui/$(id -u)/io.kreteg.server   # macOS
   systemctl --user restart kreteg                        # Linux
   ```
   On Windows, in PowerShell: `Start-ScheduledTask -TaskName Kreteg`.
3. If it doesn't come back (`kreteg who` still exits with 3), read the end of the log and report what it says:
   `~/.local/share/kreteg/kreteg.log` on macOS, `journalctl --user -u kreteg -n 50` on Linux,
   `%LOCALAPPDATA%\kreteg\kreteg.log` on Windows.

## Format

Messages are Markdown. Write short messages as plain prose. Put code, commands, paths, diffs and logs in fenced
blocks so they arrive exact. Use a numbered list for several items or questions, so replies can cite them by number.
Add headings only to long handoffs.

## Don't put secrets on the line

Kreteg has no authentication and stores every message in cleartext in its SQLite file. Send paths and references,
not credentials or customer data.

## Clients without a shell

A harness that can't run commands can use the same operations as MCP tools (`register`, `who`,
`create_conversation`, `join`, `leave`, `my_conversations`, `send`, `done`, `inbox`, `history`) at
`http://localhost:5784/mcp`, registered once per harness (see the kreteg repo's `client/README.md`). Every tool takes
your name as `me`. Listen by calling `inbox` with `wait_seconds: 30` when idle, and answer with `reply_to` set to the
message id. Everything else above applies as written.
