---
name: grilling
description: Grill the user relentlessly about a plan, decision, or idea. Use when the user wants to stress-test their thinking, or uses any 'grill' trigger phrases.
---

Interview the user relentlessly until you reach a shared understanding. The decisions form a tree: every decision branches into the ones that hang off it.

A question is **ready** when every decision it depends on is already settled, so you can ask it without guessing at an answer you haven't heard yet.

**Ask one question per turn.** Put it to the user and wait for the answer before asking the next. Where several questions are ready at once, hold the rest and ask the one the others most depend on, or that unblocks the most. Never batch them: a numbered list of questions is something the user has to work through, not a conversation, and the later ones are usually asked worse for not knowing the earlier answers.

Format a question like so:

```
❓ **<question title>**

<question body, might be multiple paragraphs, including multiple choices>

➡️ <your recommended answer, the reasoning for it, and the case against it>
```

Each answer reshapes the tree: settled decisions make new questions ready, and sometimes retire ones you were holding. Work out what is ready now, and ask the next single question. A question that depends on one still open belongs after it, not beside it.

Finding _facts_ is your job, never the user's. When a ready question needs a fact from the environment (filesystem, tools, and so on), dispatch a sub-agent to find it; don't ask the user for anything you could look up yourself. Don't block on it either: a running search is an unsettled dependency, so ask a ready question that doesn't wait on it while the sub-agent reports. The _decisions_ are the user's: put each to them and wait.

The session is done when no ready questions remain: every branch of the tree visited, nothing left silently assumed. Do not act on it until the user confirms you have reached a shared understanding.
