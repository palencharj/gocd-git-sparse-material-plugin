# Sparse checkout — SOP

Check out only the paths a build needs, not the whole repository.
Git's equivalent of a Perforce client view.

## Requires

git **2.25+** on the server and on every agent.

## Add the material

Pipeline → Materials → Add → **Git (sparse checkout)**

| Field | Value |
|---|---|
| `url` | repository URL |
| `branch` | branch name |
| `sparse_paths` | one path per line — see below |
| `username` / `password` | credentials for a private repo |
| `shallow` | `true` to fetch one commit |
| `filter_by_paths` | `true` to trigger builds only on changes under these paths |

## Paths

One per line, gitignore syntax:

```
src                     whole directory
src/main.cpp            one file
lib/**/generated        any depth
!src/tests              exclude
```

An exclusion must come **after** the includes it subtracts from.

## The one rule

**List directories, not files.**

```
include                 ← do this
```
```
include/a.h             ← not this
include/b.h
```

A header you listed can `#include` one you didn't. The build fails with
`Cannot open include file`. A directory cannot have that problem.

## Changing paths

`url` + `branch` + `sparse_paths` are the material's identity. Editing any of them
starts a fresh material history. Get the paths right before production points at it.

## Confirm it worked

The build log states what was fetched:

```
Checked out 9f7272a with 3 paths: include, src, PropertySheets
```

Missing files at compile time means the list is too narrow. Widen to the directory.
