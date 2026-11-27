---
kind: agent-tooling
description: How the agent's own shell tools, such as the Bash and PowerShell tools, behave otherwise than requested. Read before running a long command or relying on a tool's report of how long a command took.
---

<learning>
date: 2026-11-27
---
On Windows, the Bash tool moves a foreground command to the background after about 180 s, whatever the request asks. The tool's report then gives no reliable duration for the command.

To time a long run, such as a full test run, stamp the start and end times inside the command itself (`date +%T; <command>; date +%T`) and read them from its output.
</learning>
