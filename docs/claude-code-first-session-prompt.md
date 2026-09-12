# Claude Code — First Session Prompt

Paste this as your first message to Claude Code after cloning/opening the `cSphere` repository.

---

You are joining the ConnectSphere project as an engineering collaborator. Before proposing or making any code change, do the following, in order:

1. **Read context first, completely**: `AI_Context.md` at the repository root (the project's tool-agnostic source of truth — `CLAUDE.md` is just a one-line pointer to it, kept only because Claude Code auto-loads that filename), then every file in `docs/` (`product-context.md`, `implementation-roadmap.md`, `decision-log.md`). Also open the live product backlog Google Sheet linked in `AI_Context.md` and skim the `User Stories`, `Sprint Backlog 1`, and `Read Me` tabs — this backlog is edited live by the team and is the authoritative source for scope, priority, and current assignment, not a cached snapshot.

2. **Inspect the full repository before proposing anything.** Don't take `AI_Context.md`'s description of the codebase on faith — verify it. Specifically:
   - Read `backend/pom.xml`, `backend/src/main/resources/application*.properties`, and every file under `backend/src/main/resources/db/migration/` (including `SCHEMA.md`) to confirm the actual schema and Spring Boot configuration.
   - Read `backend/src/main/java/**` to see what's actually implemented (expect only the `mock` demo package — confirm whether that's still true).
   - Read `frontend/package.json`, `frontend/src/App.tsx`, and the rest of `frontend/src/` to confirm what's actually built (expect the untouched Vite starter template — confirm whether that's still true, since the backlog currently claims a frontend app-shell story, `DEV11`, is "In Progress").
   - Check for a CI configuration (`.github/workflows/` or similar) — confirm whether one now exists (it did not as of the last full read).
   - Run `git log --oneline -20`, `git branch -a`, and `git status` to see actual history, branches, and current state — don't assume `feature/event_request` is still the right branch or that it's still empty.
   - Check whether a "Login as [Role]" selector exists yet on the frontend, and whether the selected role is validated anywhere server-side — the team has decided (see `docs/decision-log.md` D6a) to defer real account creation/credential login in favour of this selector, but whether server-side role enforcement applies during this interim phase is still open (Q2).

3. **Produce a gap analysis before writing any plan or code.** Structure it as: (a) what `docs/` claims vs. what the repository actually contains right now, with any drift called out explicitly; (b) which Slice-0 stories from `docs/implementation-roadmap.md` are genuinely not yet started, in progress, or already done, cross-checked against real commits/files rather than the backlog's status labels; (c) which items in `docs/decision-log.md`'s "Awaiting Team Confirmation" section are still unresolved and now blocking work.

4. **Propose a phased plan, not a single leap.** Base it on `docs/implementation-roadmap.md`'s slices, but adjust for whatever the gap analysis found. Identify the single next slice you'd start with and why, and name the specific backlog story IDs it covers.

5. **Ask targeted questions only where a real decision blocks implementation** — for example: whether the "Login as [Role]" selector needs server-side role enforcement during this interim phase, or front-end routing only (`docs/decision-log.md` Q2); how to resolve the `event_request_status` enum missing a literal "draft" value against EO01/EO02's acceptance criteria; whether `DEV11`'s "In Progress" status reflects real, findable work; and anything else you found genuinely ambiguous or contradictory between `docs/`, the backlog, and the repository. Do not ask about things you can determine yourself by reading further.

6. **Do not modify code, install dependencies, create files, commit, or push until you have shown this assessment (steps 2–5) and the specific implementation slice you propose has been explicitly approved.** This is a hard stop — present, then wait.

When you do get approval to implement, follow `AI_Context.md`'s "Engineering Workflow" and "Definition of Done" sections exactly: smallest coherent change, existing conventions, tests with every behavioural change, and a concise before-commit summary of what changed and what was tested.
