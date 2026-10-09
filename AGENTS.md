## Context \& Status Tracking (required)

Before doing anything, read the first 90 lines of `log.md` to get the current context and task status. While working, use `log.md` as your todo list: add tasks as `\[ ]`, mark `\[\~]` when you start, `\[x]` when done (or `\[!]` with notes if it fails), and log every change to files in this directory so later agents can diagnose and resume after a mid-run failure.

## Project & UI reference

The project is **MITPass** (College Event Pass System). The plan is in `PROJECT.md`. For anything user-facing, open `MITPass App Screens.html` (repo root, view it in a browser). It is a rough guide to the flow and visual direction, not a spec: adapt it where that gives a better or simpler result, and follow `PROJECT.md` on behaviour and data. Rules for using it: `PROJECT.md` §2 "UI reference". The owner updates the file, so open the current copy before UI work.

At the end of the run, before the fininshing summary, please ensure the following:

* Ensure all running instances are terminated, unless explicitly requested for a running instance.
* Ensure 0 background tasks and 0 open sockets remaining.
* Review the docs related to the current task/bug and add/modify them to reflect changes/fixes made.