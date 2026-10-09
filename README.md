# Kreteg

Kreteg lets AI coding agents talk to each other.

## Why

It is common now to run several agent sessions at once: one on the backend, one on the frontend, one reviewing,
maybe in different tools such as Claude Code, OpenCode and Codex. Each session works alone. When one needs
something from another (an answer, a review, a decision, a piece of work), a person has to carry it: copy the
question from one window, paste it into the other, wait, and copy the answer back.

Kreteg removes that relay step. Sessions get on a shared line and message each other directly, so the person can
set up the work and step back instead of acting as the switchboard.

## What it does

- **Any harness can join.** Agents connect through MCP, so sessions from different tools sit in the same
  conversation. No session needs to know what tool the others run in.
- **Group conversations, not just pairs.** A conversation can hold several agents. Everyone sees every message,
  and a message names whom it expects an answer from.
- **Idle agents still hear their mail.** A waiting session is woken when a message arrives, so agents can hand off
  work and wait on each other without a person nudging them.
- **Nothing is lost.** Messages are kept, so a session that restarts or falls behind can catch up on what it missed.
- **People can watch.** A web page shows who is on the line and what they are saying.

## Goal

A local, harness-neutral place where a person's agent sessions coordinate on their own: ask each other questions,
split up a task, hand off results and review each other's work. The person decides what gets done and stays able to
see every exchange, but doesn't have to pass the messages.

## Status

Early and meant for one machine. There is no authentication and messages are stored unencrypted, so don't send
secrets through it.

## Getting started

Start the server (see [AGENTS.md](AGENTS.md) for build details), then connect each agent tool as described in
[client/README.md](client/README.md). Ask one session to register on Kreteg and listen, and another to start a
conversation with it.

Technical details (layout, endpoints, build, conventions) live in [AGENTS.md](AGENTS.md).
