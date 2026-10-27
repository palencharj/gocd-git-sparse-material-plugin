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
| `partial_clone` | `true` to fetch file contents only under the checked-out paths (`--filter=blob:none`); applies on the next clone |

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

## As code (config repo)

Declare the material inline; do not reference an `scm:` id that is not already registered
(that rejects the whole config repo with a 422).

```yaml
materials:
  code:
    scm: "billing-sparse"
    plugin_configuration:
      id: "com.github.palencharj.gocd.git-sparse-material"
      version: "1"
    options:
      url: "https://github.com/acme/monorepo.git"
      branch: "main"
      sparse_paths: |
        include
        src
      username: "build-bot"
      password: "{{SECRET:[store][GIT_TOKEN]}}"
      shallow: "true"
      partial_clone: "true"
    destination: "code"
```

- `sparse_paths` is a `|` block, never `>` (a folded block becomes one pattern).
- Quote the flags: `"true"` / `"false"`. Anything other than `true` means false, silently.
- Secrets go under `options:` as `{{SECRET:[store][key]}}`, not under `secure_options:`.
- A misspelt option is rejected, and the error names the option you probably meant.
- Several pipelines that want the same url + branch + paths must share one `scm:` id.
- All options: `docs/options.schema.json`.
