# Backend package layout — design

**Date:** 2026-11-02 · **Size:** small change (mechanical file moves) · **Part 1** of a bit-by-bit backend tidy

## Intent

Tidy the backend so every feature package is organised the same way. Behaviour
must not change: same API, same database, same tests passing. This is the
first step of a step-by-step refactor; splitting big services, shared time
handling and dead-code removal come later, each as its own change.

## Goals

- The 8 flat feature packages use D3's layered layout, like the other 11.
- The empty `filter/` folder is deleted.

## Non-goals

- No change to API URLs/JSON, the database schema or Flyway migrations.
- No renaming of classes, no splitting of classes, no logic changes.
- `config/` stays flat (app-wide setup, not a feature).
- `common/` and `mock/` are left as they are.
- `venuebooking/hold/` stays a sub-feature folder (it already sits inside a layered package).

## Anti-goals (a failure even if it compiles)

- Any behaviour-level test changing what it asserts.
- A test file losing tests or assertions (CI `test-protection.py` would fail).

## Approaches considered

- **A — layer the 8 flat packages (chosen).** Follows D3 and the majority;
  about 135 files move. Cost: package-private members used across the new
  subfolders must become `public`.
- B — flatten everything into feature folders. Keeps package-private access,
  but moves ~190 files and goes against D3. Dropped.
- C — layer only the big flat packages. Less churn, still inconsistent. Dropped.

## Packages that move

`attendee`, `changerequest`, `equipment`, `equipmentrequest`,
`equipmentreview`, `safety`, `venueavailability`, `venuemap`.

## Where each kind of file goes

Taken from what the layered packages already do:

| Kind | Folder | Example |
|---|---|---|
| `@RestController` | `controller/` | `ChangeRequestController` |
| request/response records, `*Dto`, `*Response`, `*Request` | `dto/` | `ReserveBatchRequest`, `ReadinessDto` |
| `@Entity`, embedded IDs, entity enums/statuses | `entity/` | `EquipmentStatusPeriod`, `SerialisedEquipmentId`, `ChangeRequestStatus` |
| Spring Data repositories, JDBC `@Repository` classes | `repository/` | `AvailabilityRepository` |
| `@Service`/`@Component`, exceptions, policies, seeders, published event records, other helpers | `service/` | `ChangeRequestException`, `DevEquipmentSeeder`, `ChangeApproved` |

Files that don't fit cleanly (e.g. `ChangeFields`, `AvailabilityModels`,
`EquipmentChangeImpact`, `OperatingHours`) are the builder's call; the default
is `service/` unless the file is only a data shape returned by the API (then
`dto/`).

## Tests

- Test classes move into the matching sub-package so same-package access keeps
  working; file renames are detected by `test-protection.py`.
- Assertions are not touched. Only `package`/`import` lines and, where needed,
  visibility in main code change.

## Visibility

Package-private types and members (~4 types, ~26 methods) become `public` only
where the compiler requires it after the move. Nothing is made public "just in case".

## Done when

- The 8 packages have no `.java` files at their root; `filter/` is gone.
- The backend compiles and the full backend suite (unit + integration) passes
  in the Docker test container.
- `python .github/scripts/traceability.py --check` reports no untraced files.
- `git diff --stat -M` shows moves, plus `package`/`import`/visibility edits only.

## Working setup

A new branch `refactor/backend-package-layout` off `main`, in its own
worktree, so the uncommitted work on `docs/ac-test-mapping` is untouched.

## Left to the builder

The exact folder for the borderline files above, and the order the packages are moved in.
