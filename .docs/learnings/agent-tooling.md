---
kind: agent-tooling
description: How the agent's own shell tools, such as the Bash and PowerShell tools, behave differently from what was requested. Read before running a long command or relying on a tool's report of how long a command took.
---

<learning>
date: 2026-11-27
---
On Windows, the Bash tool moves a command to the background after about 180 s, even when the call does not set `run_in_background`. The tool's report then gives no reliable duration for the command.

To time a long run, such as a full test run, print the start and end times inside the command itself (`date +%T; <command>; date +%T`) and read them from its output.
</learning>
