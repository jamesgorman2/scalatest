---
name: domain-modeling
description: Build and sharpen this project's domain model. Use when discussing codebase terminology, or writing or editing `.docs/ubiquitous-language.md`.
---

# Domain Modeling

Actively build and sharpen the project's domain model as you design. This is the *active* discipline: challenging terms, inventing edge-case scenarios, and writing the glossary down the moment a term crystallises. (Merely *reading* `.docs/ubiquitous-language.md` for ubiquitous language is not this skill: that's a one-line habit any skill can do. This skill is for when you're changing the model, not just consuming it.)

## Where the model lives

One file, at `.docs/ubiquitous-language.md`. There is no per-package or per-context split, and no context map: a single glossary for the whole repo.

Create it lazily: only when you have something to write. If `.docs/ubiquitous-language.md` does not exist, create it when the first term is resolved.

## During the session

### Challenge against the glossary

When the user uses a term that conflicts with the existing language in `.docs/ubiquitous-language.md`, call it out immediately. "Your glossary defines 'cancellation' as X, but you seem to mean Y. Which is it?"

### Sharpen fuzzy language

When the user uses vague or overloaded terms, propose a precise canonical term. "You're saying 'account': do you mean the Customer or the User? Those are different things."

### Discuss concrete scenarios

When domain relationships are being discussed, stress-test them with specific scenarios. Invent scenarios that probe edge cases and force the user to be precise about the boundaries between concepts.

### Cross-reference with code

When the user states how something works, check whether the code agrees. If you find a contradiction, surface it: "Your code cancels entire Orders, but you just said partial cancellation is possible. Which is right?"

### Update `.docs/ubiquitous-language.md` inline

When a term is resolved, update `.docs/ubiquitous-language.md` right there. Don't batch these up: capture them as they happen. Use the format in [UBIQUITOUS-LANGUAGE-FORMAT.md](UBIQUITOUS-LANGUAGE-FORMAT.md).

`.docs/ubiquitous-language.md` should be totally devoid of implementation details. Do not treat it as a spec, a scratch pad, or a repository for implementation decisions. It is a glossary and nothing else. Decisions belong on their issue and in `.planning/`; never here.
