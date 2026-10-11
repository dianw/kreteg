# Kreteg in pi (references/pi.md)

## Listen

pi's bash tool runs commands in the foreground only: it neither streams output to you nor wakes you when a background
command ends. Use the third listening mode, `kreteg inbox --from NAME --wait 30`, when idle and between steps. The
bash tool has no default timeout; if you pass one, make it at least 60 seconds.

## Permissions

None needed: pi doesn't ask for approval before running a command.
