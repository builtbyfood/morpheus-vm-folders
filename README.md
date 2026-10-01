# morpheus-vm-folders-plugin

vCenter-style folder organization for HPE VM Essentials / Morpheus VMs.

A drop-in plugin that adds a **VM Folders** dashboard to Morpheus plus embedded
tabs on cluster, host and instance detail pages. No core modifications, no
Morpheus database schema changes, no appliance restart.

Current version: **1.4.1** · target platform: **Morpheus 9.0.2** · plugin API **1.3.0**

## What it does

- **Standalone dashboard** at `/plugin/vmFolders` — folder tree on the left, VM
  table on the right, with search, sort, multi-select and bulk move
- **Embedded tabs** (read-only) on Infrastructure → Clusters, Infrastructure →
  Compute → Hosts, and Provisioning → Instances detail pages
- **Datastore view** — a Datastore column, per-VM disk detail rows, and a
  read-only sidebar section for browsing VMs by the datastore their disks are on
- **Datastore scanning** (off by default) — lists what is actually on a datastore:
  VM directories Morpheus does not know about, disk-only directories, orphaned
  volume files, and other clutter
- **Folder from a tag at provisioning time** (off by default) — a VM tagged
  `vmfolder` = `/prod/web` is filed under `/prod/web`, so the provisioning wizard,
  the API and Terraform can all choose the folder up front
- **Auto-Organize** — builds a Cloud/Host folder hierarchy and assigns every VM
- **Re-sync Hosts** — updates Cloud/Host folder assignments after a live migration,
  and keeps using folders you have renamed instead of recreating the old ones
- **Power actions and console** links inline in the VM table
- **Backup / Restore / Export** of the folder database

## Features

- Nested folder paths (e.g. `/Production/Web`, `/Dev/Databases`)
- Create, rename and delete folders; move VMs singly or in bulk
- Cloud filter chips; host and cluster context applied automatically in tabs
- Datastore browsing with a **No datastore** bucket for VMs that have none
- `unmanaged` badge on VMs discovered on the hypervisor rather than provisioned
  by Morpheus — labelled only, all actions remain available
- Live search across name, IP, OS, cloud and datastore
- Configurable labels for the two root entries (`All VMs`, `Unorganized`)
- Dark mode that follows the Morpheus theme
- Tenant-scoped: users only ever see VMs their account can access

## How folder data is stored

Folder definitions and VM→folder assignments live in a **JSON file on the
appliance**, not in the Morpheus database and not as VM metadata:

```
/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json
/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json.bak
```

Writes are serialized through a single lock (read-modify-write under one
`mutateDb`), `fsync`ed, and committed with an atomic rename. `Restore` parses the
backup before replacing the live file. Removing the plugin leaves the JSON file
behind and touches nothing in Morpheus.

Trade-off: because assignments are keyed by compute server id in a plugin-owned
file, they are not visible to the Morpheus API or to other plugins. Use
**Export** for an external copy.

## Folder assignment from a `vmfolder` tag

**Off by default.** A VM carrying the tag `vmfolder` with a folder path as its value —
`vmfolder` = `/prod/web` — is filed under that folder. The tag can be set in the
provisioning wizard's Tags step, through `PUT /api/instances/:id`, or by Terraform, all of
which exist on VM Essentials, Advanced and Enterprise. (Inputs and option lists are
Enterprise-only, so they are not used.)

The tag name is matched case-insensitively and its value is normalized the same way as a
folder created by hand. An unusable value is logged and skipped, leaving both the VM and
the tag alone so the mistake can be seen and corrected. If a VM somehow carries several
`vmfolder` tags, the first is used and a warning is logged. The direction is one-way: the
plugin never writes a `vmfolder` tag, and moving a VM in the dashboard never creates one.

Reconciliation runs when the dashboard loads and when Re-sync is pressed. A VM that is
still being built is skipped, so a tag is never spent on a VM that may not finish.

### Modes

Set the mode in **⚙ Settings** on the dashboard, or as `vmfolder tag mode` in the plugin
settings form under Admin → Plugins (the form wins when it holds a value).

| Mode | Behaviour |
|---|---|
| `off` (default) | No tag is read. Nothing is logged. The feature is inert. |
| `keep` | The VM is filed and the tag stays. **Read-only — no Morpheus record is modified.** The tag remains the source of truth: tagged VMs show a `tagged` badge, moving one in the dashboard warns that the move will be reverted on the next reconcile, and Re-sync leaves tagged VMs where the tag put them. This is the mode to use for Terraform-managed tags. |
| `consume` | The VM is filed and the `vmfolder` tag is then **removed from the VM**. |

`consume` is the **only** place this plugin writes to a Morpheus record. It deletes nothing
but the `vmfolder` tag itself — other tags on the same record are untouched and no other
field is written. Every removal is audit-logged with the user, the VM id and the path:

```
VmFolders tag consumed: user=<user> vm=<id> path=</prod/web> owner=server#<id> tags=1 removed=true via=...
```

The tag is read from the compute server record and, when that carries none, from the
instance that owns it, and is removed from whichever record carried it.

### Setting the tag

The tag name is always `vmfolder`; the value is the folder path.

**Provisioning wizard.** On the **Tags** step (VM Essentials: *Configure* page), add a tag
with name `vmfolder` and value `/prod/web`.

**Existing VM, UI.** Instance detail → **Edit** → Tags → add `vmfolder` = `/prod/web`.
The next dashboard load files it.

**API.** `addTags` adds without touching the VM's other tags; `tags` replaces the whole
list, so prefer `addTags`:

```
curl -sk -X PUT "https://<appliance>/api/instances/<id>" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"instance":{"addTags":[{"name":"vmfolder","value":"/prod/web"}]}}'
```

**Terraform.** Put it in the instance resource's `tags` map — `tags = { vmfolder = "/prod/web" }`.
Use `keep` mode: in `consume` the plugin deletes the tag and the next `terraform apply`
puts it back.

> The `addTags` field name and the Terraform resource for VME/KVM instances are taken from
> the public Morpheus API and provider docs, not tested against 9.0.2 — confirm them with
> the first test below before relying on them.

### Tag values

Values go through the same normalizer as a folder made by hand. Missing folders are created.

| Tag value | Result |
|---|---|
| `/prod/web` | `/prod/web` |
| `prod/web` | `/prod/web` (leading slash added) |
| `/prod//web/` | `/prod/web` (duplicate and trailing slashes dropped) |
| `  /prod/web  ` | `/prod/web` (trimmed) |
| `/a/b/c/d/e/f/g/h` | accepted — 8 segments is the limit |
| `/a/b/c/d/e/f/g/h/i` | **rejected** — more than 8 segments |
| `../x`, `/a/./b` | **rejected** — `.` and `..` segments |
| `/a\b` | **rejected** — backslash in a segment |
| `/` or empty | **rejected** — not a folder |
| a segment over 64 chars, or a path over 512 | **rejected** |

A rejected value leaves the VM where it is and the tag in place, logs
`VmFolders tag: vm=<id> (<name>) has an unusable vmfolder value [...]`, and is counted as
`invalid` on the Re-sync toast.

### Testing the tag feature

Run these in order after upload; stop at the first one that fails. Watch the log with:

```
sudo tail -F /var/log/morpheus/morpheus-ui/current | grep --line-buffered "VmFolders"
```

1. **Loads.** `VM Folders Plugin <version> initialized`, no compile errors.
2. **Off does nothing.** With `tagMode` = `off`, provision a VM tagged
   `vmfolder=/tagtest/one` and load the dashboard. No `VmFolders tag` line, VM not filed,
   tag still on the VM.
3. **Probe.** Set `keep` in ⚙ Settings and reload. One line per load:
   `VmFolders tag/probe: mode=keep servers=.. serverTagged=.. instanceTagged=.. ...`
   `serverTagged` or `instanceTagged` > 0 → tags are readable, carry on. Both 0 on a VM
   you know is tagged → `list()` does not load tags; stop and report the line.
4. **Keep.** The VM is in `/tagtest/one`, shows the `tagged` badge, and the tag and its
   other tags are still on it in Morpheus.
5. **Keep wins.** Move it to `/b` in the dashboard (the dialog warns), press Re-sync → back
   in `/tagtest/one`.
6. **Invalid.** Tag a second VM `vmfolder=../x`, reload → VM unmoved, tag present, warning
   logged, `invalid: 1` on the Re-sync toast.
7. **API.** Add the tag to an existing untagged VM with the `curl` above → next load files it.
8. **Consume.** Switch to `consume`, tag a VM `vmfolder=/tagtest/two` alongside another
   tag, reload → filed in `/tagtest/two`, the `vmfolder` tag gone from the VM in the
   Morpheus UI, the other tag still there, and one line
   `VmFolders tag consumed: user=... vm=... path=</tagtest/two> ... removed=true`.
   `removed=false` means the platform refused the delete; the VM is still filed.
9. **Provision-time.** In `consume`, provision a new VM with the tag from the wizard and
   keep the dashboard open during the build → it is not touched while provisioning and is
   filed on the first load after it goes running.

## Folder renames and `origin`

An auto-created folder records where it came from — the cloud for a cloud folder, the cloud
and host for a host folder. Re-sync and Auto-Organize resolve their targets by that origin
rather than by name, so renaming `/managed-democracy` to `/Lab` keeps `/Lab` receiving the
same VMs instead of having the old tree recreated underneath it. Folders you created by
hand have no origin and Re-sync never touches them. Folders that predate 1.5.0 are matched
to their cloud and host once, on the first read.

Re-sync will also place a VM that is in no folder into its Cloud/Host folder when that
folder already exists. It never creates a folder for an unassigned VM.

## Datastore scanning

**Off by default.** When an administrator enables it, a Scan button appears on each
scannable datastore in the sidebar. A scan runs three read-only commands on one
hypervisor host through the Morpheus agent and reports:

| Group | What it means |
|---|---|
| **On disk, not in Morpheus** | A directory holding a libvirt domain XML whose name matches no VM in your tenant — imported, migrated or hand-built VMs. These can be filed into folders like any VM. |
| **Disk-only directories** | Disk-sized files with no domain XML. Not VMs by the detection rule, but worth seeing. |
| **Orphaned volumes** | uuid-named files with no matching `StorageVolume`. Morpheus *virtual images* (ISOs such as virtio-win) are not StorageVolumes and always appear here. |
| **Other files** | ISOs, OVAs, zips, backup directories — collapsed by default. |

A directory holding more than three domain XMLs, or XMLs that do not match its name,
is labelled `backup/collection` rather than treated as one VM.

Scans are **manual only** — nothing runs on page load. Results are cached in the plugin
database under `scans.<datastoreId>` with the timestamp and scanning host, and the view
shows the cached result and its age until someone rescans.

### Enabling it

Administration → Plugins → VM Folders → **Enable datastore scanning**. On appliances
where the plugin settings form is unavailable, a permission-gated route does the same:

```js
vmfPost('/scanEnable', { enabled: true })   // from the browser console on the dashboard
```

Both default to off, and scanning fails closed if neither can be read.

### What it runs, and as whom

- A readability test, one bounded `find` (`-maxdepth 2`, regular files only, larger than
  1 MB or `*.xml`, no symlink following, no `du`), and a `head -c 512` pass to identify
  libvirt domain XMLs. Nothing is written to the datastore.
- 30 second timeout — a store that times out reports a timeout, never partial data.
- Commands run through the Morpheus agent as **`morpheus-node`**. Whether that account can
  read a given store depends on the store's ownership and any NFS squash, which vary per
  deployment: a store it cannot read is reported per store as *not readable by the agent
  user*, not as a failed scan.
- The scanned path is never client-supplied. It comes from `Datastore.externalPath` and
  must match `/mnt/<uuid>` or `/var/morpheus/kvm/<name>`; Morpheus-internal stores
  (`externalId` starting `morpheus-`) and offline stores are never scanned.

Shared stores (`libvirt-netfs-nfs`, `libvirt-dir-gfs2`) are scanned once on any host that
lists them; host-local stores (`libvirt-dir`) are scanned on their own host.

## Requirements

| Component | Version |
|---|---|
| Morpheus / VM Essentials | 9.0.2 (developed against; 8.x untested since 1.1.0) |
| `morpheus-plugin-api` | 1.3.0 |
| Java (build) | 17 (source/target 11) |
| Gradle | 8.8 — Shadow 8.1.1 requires Gradle 8+ |

## Build

No Gradle wrapper is checked in; use a local Gradle 8.x.

```bash
gradle clean shadowJar
```

Output: `build/libs/morpheus-vm-folders-plugin-1.4.1-all.jar`

Morpheus compiles Groovy plugins when it loads them, so a successful Gradle build
does not prove the plugin compiles against the running appliance. Always check the
log after uploading:

```bash
sudo grep -i -A15 "VmFolders" /var/log/morpheus/morpheus-ui/current | tail -80
```

Expect `VM Folders Plugin 1.4.1 initialized`.

## Install

1. Log into Morpheus as an administrator
2. **Administration → Plugins → + Add Plugin**, upload the `.jar`
3. **VM Folders** appears in the Infrastructure and Provisioning sub-navigation,
   and the dashboard is reachable at `/plugin/vmFolders`

## Security model

- **Mutations are POST only.** Every state-changing route passes through
  `requireMutation()`: POST verb, a required `X-VMF-Request` header, an
  Origin/Referer host match when either header is present, and an authenticated
  user. GET on a mutating route returns `405 {"success":false,"error":"POST required"}`.
- **Morpheus core CSRF is honored.** Core runs Spring Security CSRF in front of
  `/plugin/*`; the plugin sends the session token as `X-XSRF-TOKEN`, read from
  `<meta name="_csrf">` on core pages or, for the standalone dashboard, by
  fetching one core page once per load (all core cookies are HttpOnly).
- **Tenant scoping.** VM queries use `DataQuery(user)` *and* an explicit
  per-record `canAccess()` filter; the master account is exempt. Acting on
  another tenant's VM id returns 404.
- **Output escaping.** Every user- or platform-supplied string rendered into the
  page goes through `esc()`. Handlebars templates pass model values via `data-*`
  attributes and never interpolate them into JavaScript string literals.

- **Host commands are fixed templates.** The only value interpolated into a scan command
  is the datastore path, taken from the platform record and allow-listed by pattern; the
  request is refused with 400 before any command runs if it does not match. `/scan` is a
  mutation and passes through `requireMutation()`; `/scans` and `/config` are
  read-only and filtered to datastores the caller can see. One audit line is written per
  scan with user, datastore, host, elapsed time and counts.
- **Folder paths are normalized and bounded.** One normalizer serves `saveFolder`,
  `assign` and `renFolder`: control characters, `.` / `..` segments and a backslash inside
  a segment are rejected, and the path is capped at 8 segments, 64 characters per segment
  and 512 overall. Angle brackets and quotes are accepted on purpose — a hostile name is
  allowed and is made safe by `esc()` on output.
- **The one Morpheus write is opt-in and narrow.** Only `consume` tag mode modifies a
  Morpheus record, only to delete the `vmfolder` tag, only on VMs the caller's tenant can
  access, and always with an audit line. `keep` mode is read-only.
- **Reconciliation does not run on an unauthenticated-looking GET.** It can write, and it
  runs from `/vms`, which is a GET, so it only runs for a request carrying
  `X-VMF-Request` — a header a cross-origin GET cannot set without a CORS preflight. A
  plain browser GET of `/vms` stays strictly read-only.
- **Settings writes are allow-listed.** `POST /setConfig` accepts three keys; `tagMode` is
  validated against the three legal values and the labels are sanitized display strings.

Routes are currently gated on the `admin-cm: full` permission. To widen access,
change the `Permission.build(...)` call in `VmFoldersController.getRoutes()` —
note that read and write routes presently share one permission.

## Support helper

On any VM Folders page, run this in the browser console for the full client state
(counts, active filters, scraped vs. applied cloud, host context, script instance
count). It reads state only:

```js
vmfState()
```

## Project layout

```
src/main/groovy/com/morpheusdata/vmfolders/
  VmFoldersPlugin.groovy             plugin entry; registers providers
  VmFoldersController.groovy         all routes, JSON DB layer, dashboard HTML/CSS
  VmFoldersNavProvider.groovy        injects vmFolders.js into every core page
  VmFoldersClusterTabProvider.groovy
  VmFoldersServerTabProvider.groovy
  VmFoldersInstanceTabProvider.groovy
src/assets/javascript/vmFolders.js   SPA: tree, table, datastore view, scan panel, settings, mutations
src/main/resources/renderer/hbs/     tab templates
```

`vmFolders.js` is an IIFE with a single-instance guard; only functions assigned to
`window.*` are reachable from templates or the console.

## Known limitations

- **One permission for read and write.** All routes require `admin-cm: full`;
  there is no separate read-only role, and no per-folder RBAC — every user who can
  open the page sees every folder in their tenant.
- **Folder names are not tenant-scoped** in the folder list: assignments are
  filtered by tenant, but stored folder *names* are returned to any caller.
- **Cloud detail tab and sub-nav links are DOM-injected**, because plugin API
  1.3.0 has no cloud tab provider. They depend on Morpheus markup and can break
  on a UI change.
- **Datastore view is read-only** — it filters the table; it never creates folders
  or writes assignments.
- **`usedStorage` is reported as 0** by VME/KVM volumes, so disk detail rows show
  `—` for used space.
- **SPA navigation** does not always reset client state between tabs.
- **Scanning reads only what the agent user can see** — a store `morpheus-node` cannot
  read is reported as unreadable rather than scanned.
- **Scan results are a snapshot**, cached until someone rescans; they do not track
  changes on the datastore.
- A plugin-declared `vm-folders-scan` permission is not used, because whether Morpheus
  9.0.2 honors plugin-declared permissions is unverified; scan routes share the same
  `admin-cm` permission as everything else.
- **`vmfolder` tags are read one way only.** Moving a VM in the dashboard never creates or
  updates a tag, so in `keep` mode the tag and the dashboard can disagree — and the tag
  wins on the next reconcile. There is no alias table for a renamed folder: a tag naming
  the old path moves the VM back, and the rename dialog says how many VMs that affects.
- **Tag reconciliation costs one extra query per load** when it is not `off`: the instance
  list, so a tag the platform put on the instance rather than the server is still found.
- No automated tests; verification is the manual checklist in `CLAUDE.md`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Notable releases: **1.1.5** (security
hardening, core CSRF, embedded-tab fixes), **1.2.0** (datastore visibility and
browsing), **1.3.6** (unmanaged labelling, datastore bucket fix, embedded-tab state fixes), **1.4.1** (datastore scanning), **1.5.0** (`vmfolder` tag at provisioning time, durable folder renames).
