
This file contains coding-agent instructions for this repository.

These guidelines are intended to reduce common AI coding mistakes. They bias toward caution over speed. For trivial tasks, use judgment and keep changes minimal.

## 1. Think Before Coding

Do not assume. Do not hide confusion. Surface tradeoffs.

Before implementing:

- State assumptions explicitly when the task is unclear.
- If multiple interpretations exist, explain them instead of silently choosing one.
- If a simpler approach exists, prefer it.
- If the requirement, file location, API behavior, or business logic is unclear, stop and ask before changing code.

## 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- Do not add features beyond what was requested.
- Do not create abstractions for single-use code.
- Do not add flexibility or configurability unless requested.
- Do not add unnecessary error handling for impossible scenarios.
- If the solution becomes much larger than necessary, simplify it.

Ask: "Would a senior engineer think this is overcomplicated?" If yes, rewrite it.

## 3. Surgical Changes

Touch only what is necessary. Clean up only the mess created by the current change.

When editing existing code:

- Do not refactor unrelated code.
- Do not improve adjacent code, comments, formatting, or naming unless required.
- Match the existing project style, even if another style seems better.
- If unrelated dead code is found, mention it in the final response but do not delete it.

When the current change creates unused code:

- Remove imports, variables, functions, or files made unused by this change.
- Do not remove pre-existing dead code unless explicitly asked.

Every changed line should directly relate to the user's request.

## 4. Goal-Driven Execution

Define success criteria and verify the result.

For multi-step tasks, first make a brief plan:

1. Locate the relevant files → verify by identifying exact files.
2. Make the smallest necessary change → verify by checking the affected logic.
3. Run the appropriate check → verify by confirming build, lint, test, or manual review result.

Examples:

- "Fix the bug" → reproduce or locate the cause, then fix it.
- "Add validation" → identify input location, add validation, then check invalid and valid cases.
- "Modify page text" → change only the related page or component.