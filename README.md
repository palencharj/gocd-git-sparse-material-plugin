# GoCD Git Sparse Checkout Material

A GoCD SCM plugin providing a Git material whose working directory contains **only the paths you
ask for**, using git's own `sparse-checkout`.

If you have ever wanted a Perforce client view for a Git monorepo — a pipeline that checks out
`services/billing` and nothing else — this is that.

```
services/billing
libs/shared/*
build.gradle
```

Everything you do not list is never written to disk. Not cloned then deleted: never written.

## Why

GoCD's built-in Git material always checks out the whole repository. On a large monorepo that means
every pipeline pays for every other team's code on every build — disk, I/O, and time.

Perforce users have always had this via the client view. Git has supported it since 2.25 with
`git sparse-checkout`. This plugin connects the two.

## Requirements

| | |
|---|---|
| GoCD server | 21.4 or newer |
| `git` on each **agent** | **2.25 or newer** (when `sparse-checkout` arrived) |
| `git` on the server | any recent version (polling only) |
| Java | whatever your GoCD ships; the jar targets 17 |

The `git` version on your **agents** is the one that matters, because that is where checkout
happens. `git --version` on an agent box will tell you.

## Install

1. Download `gocd-git-sparse-material-plugin-<version>.jar` from
   [Releases](https://github.com/palencharj/gocd-git-sparse-material-plugin/releases), or build it
   yourself (below).
2. Drop it in the server's external plugin directory — `$GO_SERVER_DIR/plugins/external`, or
   `/godata/plugins/external` in the Docker images.
3. Restart the GoCD server.

**You do not need to touch your agents.** GoCD ships plugins to agents itself and keeps them in
sync, so the checkout code arrives wherever it is needed. No custom GoCD build, no fork, no agent
rollout.

Confirm it loaded under **Admin → Plugins**; you should see *Git Sparse Checkout Material*.

## Configure

Add a material to a pipeline, choose **Git (sparse checkout)**, and fill in:

| Field | Notes |
|---|---|
| **Repository URL** | Required. `https://…` or SSH. |
| **Branch** | Defaults to `master`. |
| **Paths to check out** | Required. One pattern per line. |
| **Username** / **Password** | HTTP(S) only. Leave empty for SSH keys. The password is stored encrypted and never appears in logs. |
| **Shallow clone** | Fetch only the tip commit. Deepens automatically if a build needs an older revision. |
| **Only trigger on changes under these paths** | Off by default. See below. |

### Patterns

Same syntax as `.gitignore`, applied by `git sparse-checkout set --no-cone`:

```
services/billing        # a directory and everything under it
libs/shared/*           # a glob
build.gradle            # a single file
!services/billing/e2e   # exclude something matched above
```

Non-cone mode is deliberate. Cone mode is faster but can only select whole directories; non-cone can
select individual files, which is what makes this a real equivalent of a Perforce view.

One pattern per line, because real repositories contain paths with spaces and commas in them.

### Triggering vs. checking out

These are separate settings on purpose.

By default the pipeline triggers on **any** commit to the branch, even one that only touched files
you do not check out. That matches the built-in Git material, so nothing surprises you when you
migrate.

Turn on **Only trigger on changes under these paths** to get monorepo behaviour: the pipeline builds
only when its own code changes, and the changelog shows only relevant commits.

### As config-repo / XML

```xml
<scm id="billing-sparse" name="billing" pluginId="com.github.palencharj.gocd.git-sparse-material">
  <configuration>
    <property><key>url</key><value>https://github.com/acme/monorepo.git</value></property>
    <property><key>branch</key><value>main</value></property>
    <property><key>sparse_paths</key><value>services/billing
libs/shared/*</value></property>
    <property><key>filter_by_paths</key><value>true</value></property>
  </configuration>
</scm>
```

## Design notes

A few decisions that are load-bearing:

- **The paths are not part of the material's identity.** GoCD derives a material fingerprint from
  the properties marked `part-of-identity`, and that fingerprint is what ties a pipeline to its
  material history. Only the URL and branch identify the material here. If the paths were included,
  editing them would orphan the pipeline's history — so they aren't.
- **Patterns are re-applied on every checkout**, which makes the working copy self-healing.
  Widening, narrowing or removing them repairs an existing directory in place. There is no
  "detect the change and re-clone" path to get wrong.
- **Polling never fetches file contents.** The server's copy is a bare repository cloned with
  `--filter=blob:none`, so polling a monorepo copies commits and trees but no blobs. Servers that
  don't support filtering just send everything, so this is safe to ask for.
- **Nothing can prompt.** Every git invocation runs with terminal prompting and credential helpers
  disabled, so bad credentials fail immediately instead of hanging a job until it times out.
- **The password is redacted everywhere.** It is necessarily embedded in a remote URL on the command
  line, so every string returned or thrown is scrubbed first.

## Known limitations

Worth knowing before you roll this out:

- **Migrating an existing pipeline resets its material history.** Switching a pipeline from the
  built-in Git material to this one changes the material fingerprint, so GoCD treats it as a
  different material. Expect a fresh history for that material.
- **Sparse patterns do not restrict submodule contents.** Submodules are separate repositories
  checked out by `git submodule update`, so excluding a submodule's path in the parent will not stop
  its files appearing. This is how git works, not something the plugin can fix.
- **Submodules are not initialised** by this plugin at all currently. If you need them, the built-in
  Git material handles them and this one does not.
- **A pattern set that matches nothing is rejected at save time.** Git happily accepts one and
  produces an empty working directory, which then fails the build confusingly, so the plugin
  validates against it. A pattern that is merely *misspelled* still checks out nothing — the build
  log prints the patterns actually applied, which is the fastest way to spot it.

## Alternatives

Pick the one that matches your problem:

- **[gocd-git-path-material-plugin](https://github.com/TWChennai/gocd-git-path-material-plugin)** —
  Thoughtworks' plugin. Watches sub-paths so a monorepo doesn't over-trigger, and gives filtered
  changelogs. It still checks out the whole repository. If over-triggering is your problem and
  workspace size is not, use it — it is mature and well maintained, and reading it taught me the
  shape of the SCM extension.
- **This plugin** — restricts the working directory, and can optionally filter triggering too.
- **[gocd/gocd#14540](https://github.com/gocd/gocd/pull/14540)** — my open PR adding `sparseCheckout`
  to GoCD's *built-in* Git material. If it lands, you get this natively, keep submodules and
  refspecs, and don't change material identity. Until then, that requires running a patched server
  **and** patched agents; this plugin needs neither.

## Build

```bash
./gradlew build
```

Produces `build/libs/gocd-git-sparse-material-plugin-<version>.jar`. Any JDK 17 or newer works — the
build targets 17 bytecode via `options.release` rather than demanding one exact JDK.

The test suite drives the real `git` binary against real repositories, so `git` must be on your
`PATH`. There are no mocks of git behaviour, on the grounds that the interesting bugs live in what
git actually does.

## Contributing

Issues and pull requests welcome. Please keep the tests passing on both Linux and Windows — CI runs
both, because path handling and line endings differ in ways that matter here.

## Credits

The GoCD SCM extension contracts were read from GoCD's own `JsonMessageHandler1_0`, and
[TWChennai/gocd-git-path-material-plugin](https://github.com/TWChennai/gocd-git-path-material-plugin)
(Apache-2.0) was an invaluable reference for how an SCM plugin is wired together and how its config
view is rendered. Thanks to its authors.

## License

[Apache License 2.0](LICENSE).
