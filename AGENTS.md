## Context \& Status Tracking (required)

Before doing anything, read till the latest record of Change log of `log.md` to get the current context and task status. While working, use `log.md` as your todo list: add tasks as `\[ ]`, mark `\[\~]` when you start, `\[x]` when done (or `\[!]` with notes if it fails), and log every change to files in this directory so later agents can diagnose and resume after a mid-run failure.

**`archive/` (skip by default):** old CI and error logs live here. Don't read, search or list it unless the current task needs a past failure (a recurring or similar error, or a regression). Token saving is key.

## Project & UI reference

The project is **MITPass** (College Event Pass System). The plan is in `PROJECT.md`. For anything user-facing, open `MITPass App Screens.html` (repo root, view it in a browser). It is a rough guide to the flow and visual direction, not a spec: adapt it where that gives a better or simpler result, and follow `PROJECT.md` on behaviour and data. Rules for using it: `PROJECT.md` §2 "UI reference". The owner updates the file, so open the current copy before UI work.

## Dependencies (strict)

Keep every dependency install local to the project: Maven/Gradle dependencies through the project build (use the committed wrapper, `./mvnw`), npm packages without `-g` (run tools with `npx`), and services through `docker compose`. Never install anything globally (`npm -g`, system packages, a JDK/Maven/Node on the machine, global PATH or registry changes) unless it is absolutely necessary, and then only after asking the user and getting explicit permission. Log any approved global install in `log.md`. Maven's shared download cache (`~/.m2`) doesn't count as a global install.

## Git & Commits

* **Prompt to commit before and after each phase.** Before starting a phase (T03–T10), and again when it is finished, ask the user whether to commit the current state. Commit only with their explicit permission, for that commit only (one approval doesn't cover the next). Don't push unless they ask for that too. If they decline, record it in `log.md` and carry on.
* Commit messages: `Phase N: <summary>` for phase work, `chore: <summary>` for setup and docs.
* **Executable bit:** Git on Windows doesn't record it (`core.filemode=false`). A new shell script that must run on Linux/CI (like `mvnw`) is staged with `git add --chmod=+x <file>`. For a file that is already tracked, use `git update-index --chmod=+x <file>`. Check with `git ls-files -s <file>`, which should show `100755`.

## Mid-run Behavior
* Do not leave any download or dependancy artifact unhandled
* Always go through .gitignore after every install and update if necessary
* No dependany shall be tracked or pushed, unless absolutely necessary

## End of Run Behavior
At the end of the run, before the fininshing summary, please ensure the following:

* Ensure all running instances are terminated, unless explicitly requested for a running instance.
* Ensure 0 background tasks and 0 open sockets remaining.
* Review the docs related to the current task/bug and add/modify them to reflect changes/fixes made.