# ConnectSphere — see AI_Context.md

This project's context, rules, and conventions live in **`AI_Context.md`** at the repository root. That file is the single source of truth, kept tool-agnostic because the team uses different AI tools.

**Read `AI_Context.md` before starting any task.** Before writing or changing any test, follow its "Writing or changing a test — every time" checklist (under *Testing rules*). CI fails the PR if a test file names no user story.

This file exists only because Gemini CLI automatically loads a file named `GEMINI.md` as project instructions. Its only job is to point here. Don't duplicate content into this file: edit `AI_Context.md` instead, and every tool stays in sync.
