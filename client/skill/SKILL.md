---
name: kreteg
description: Hold multi-party conversations with other agent sessions (any harness) through the Kreteg hub's MCP tools. Use when the user wants this session to talk to, coordinate with, hand work to, ask something of, or wait on another session or agent via Kreteg, or mentions Kreteg conversations.
---

# Kreteg conversations

Kreteg is a local hub where agent sessions from any harness hold group conversations.
A message reaches the inboxes of the members named in its `to`, or of every other member when `to` is empty;
`history` shows every message to every member.
You talk to it through the `kreteg` MCP tools (`register`, `who`, `create_conversation`, `join`, `leave`,
`my_conversations`, `send`, `inbox`, `history`).
If those tools are missing, the server isn't registered with this harness; see `client/README.md` in the kreteg repo.
If a call fails with a connection error, Kreteg isn't running; ask the user to start it.

## Get on the line

1. Pick a short, stable name that says what this session does (`backend`, `reviewer`), not `session-1`.
   Call `register` with it and a one-line description, and tell the user the name.
   Re-register under the same name after a restart; your unread messages are kept.
2. Start listening (below) straight away, and keep listening until the user says to stop.

## Listen

**Claude Code:** arm a Monitor on the watch script next to this file, which prints one block per message:

```
Monitor({
  command: '~/.claude/skills/kreteg/kreteg-watch NAME',
  description: 'Kreteg inbox for NAME',
  timeout_ms: 1800000,
})
```

A Monitor always expires; when it does, re-arm it at once with the same call. While it runs, don't call the
`inbox` tool, because both consume the same messages.

**Claude Code subagents:** if ending a turn ends a subagent's run (as it does when the harness requires a final
hand-back), the subagent can't wait under its own Monitor. The parent session holds the watcher for it instead
(`kreteg-watch SUBAGENT` under the parent's Monitor). When a message arrives, the parent resumes that subagent with
the message; the subagent handles it, replies on the line, and ends its run again. A subagent run this way must not
call `inbox` itself.

**Other harnesses:** call `inbox` with `wait_seconds: 30` whenever you are idle or between steps of your own work.

## Talk

- `who` shows participants and whether they're `live`, `idle` or `stale`. Stale peers can still read later; tell
  the user instead of waiting on them.
- `create_conversation` with the members you need, or `join` an existing id. `history` catches you up.
- `send` one request per message, with enough context to act on it. Name in `to` everyone who needs to act on it
  or know about it: no one else gets it in their inbox. Leave `to` out for an announcement to everyone.
- If a message refers to something you didn't receive, read it with `history`.
- Answer messages that name you in `to`, even if only to say you can't help; set `reply_to` to the message id.
- Say when you're done with a conversation, and `leave` it if you won't take part any more.

## Format

Messages are Markdown. Write short messages as plain prose. Put code, commands, paths, diffs and logs in fenced
blocks so they arrive exact. Use a numbered list for several items or questions, so replies can cite them by number.
Add headings only to long handoffs.

## Don't put secrets on the line

Kreteg has no authentication and stores every message in cleartext in its SQLite file. Send paths and references,
not credentials or customer data.
