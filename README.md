# morpheus-vm-folders-plugin

vCenter-style folder organization for HPE VM Essentials / Morpheus VMs.

A drop-in plugin that adds a **VM Folders** dashboard to Morpheus plus embedded
tabs on cluster, host and instance detail pages. No core modifications, no
Morpheus database schema changes, no appliance restart.

Current version: **1.5.10** · developed on **Morpheus 9.0.2**, verified on **9.1.0** · plugin API **1.3.0**

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
  ([details and demo](#assigning-a-folder-at-provisioning))
- **Auto-Organize** — builds a Cloud/Host folder hierarchy and assigns every VM
- **Re-sync Hosts** — updates Cloud/Host folder assignments after a live migration,
  and keeps using folders you have renamed instead of recreating the old ones
- **Power actions and console** links inline in the VM table
- **Backup / Restore / Export** of the folder database

## Installing the plugin

1. Download `morpheus-vm-folders-plugin-<version>-all.jar` from the
   [Releases](https://github.com/builtbyfood/morpheus-vm-folders/releases) page.
2. Log into Morpheus as an administrator and go to **Administration → Plugins**.
3. Click **+ Add Plugin**, choose the `.jar` and upload it. No restart is needed. To
   upgrade, upload the newer jar the same way.
4. Check the version shown in the plugin list. **VM Folders** appears in the
   Infrastructure and Provisioning sub-navigation, and the dashboard is at
   `/plugin/vmFolders`.
5. Optional: click the plugin's edit (pencil) icon to set **vmfolder tag mode**,
   **Enable datastore scanning** and the two root labels. All are off or default until
   you change them, and this form is the only place they are set. The dashboard's
   ⚙ Settings button only shows the current values.

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

## Embedded tabs: loaded on open, and the off switch

Morpheus renders every plugin tab while it builds a detail page, whether or not the tab is
opened. Since 1.5.10 the VM Folders tabs are static shells: nothing is requested from the
plugin, and no tab markup or CSS is added to the page, until the tab is opened.

- **On open** (default): the tab loads the first time it becomes visible. While it loads it
  shows a spinner and elapsed seconds; afterwards "Loaded in N.N s" and a **Refresh** button.
  If the load fails it shows the error and a **Retry** button.
- **Don't auto-load this tab**: a per-browser checkbox in the tab. When ticked, the tab waits
  for the **Load** button.
- A tab's first open may reuse a VM list up to 30 seconds old; **Refresh** always fetches
  fresh data. The standalone dashboard always loads fresh.

Two plugin settings (Administration → Integrations → Plugins → VM Folders → edit) control
the tabs for everyone. A change takes up to 60 seconds to apply.

| Setting | Values | Default | Effect |
|---|---|---|---|
| Show the VM Folders tab | checkbox | on | Off hides the tab on host, cluster and instance pages. The dashboard, the folder data and `vmfolder` tag handling are unaffected. |
| Tab load mode | `onOpen`, `manual` | `onOpen` | `manual` makes every tab wait for its **Load** button. |

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

## Assigning a folder at provisioning

When creating an instance, open **Configure → Advanced Options → Tags** and add a tag named
`vmfolder` with the folder path as its value, e.g. `vmfolder` = `/prod/web`. The folder is
created if it doesn't exist. This works on VM Essentials, Advanced and Enterprise, and
through the API or Terraform by setting the same tag.

The feature is **off by default**. Enable it under **Administration → Plugins → VM Folders →
vmfolder tag mode**:

- **`off`** (default) — tags are ignored. Nothing is read or logged.
- **`keep`** — assigns the VM to the folder and leaves the tag in place. Read-only; nothing
  in Morpheus is modified. Use this if Terraform manages your tags.
- **`consume`** — assigns the VM and then removes the `vmfolder` tag from the instance. No
  other tag is touched. This is the plugin's only write to a Morpheus record.

<!--
  VIDEO — replace this comment and the quote below with the recording.
  On GitHub: edit README.md in the web editor and drag the .mp4 (≤ 100 MB) onto this spot.
  GitHub uploads it and inserts a https://github.com/user-attachments/assets/<id> URL on
  its own line, which renders as an inline player. Or link it:
  [▶ Demo: assigning a folder at provisioning](<url>)
-->

> 📹 **Demo video:** provisioning a VM with a `vmfolder` tag and watching it land in its
> folder. *(link to be added)*

The recording shows, in `consume` mode: the tag mode set in Administration → Plugins; the
wizard's Configure → Advanced Options → Tags with `vmfolder` = `/tagtest/demo` plus one
other tag; the VM left alone while it builds; the VM under `/tagtest/demo` on the first
dashboard load after it finishes; the instance page, hard-refreshed, with the `vmfolder`
tag gone and the other tag still there; and the one `VmFolders tag consumed:` log line.

### Keep or consume?

Pick **`keep`** when something else owns the tag. With Terraform, a tag the plugin deletes
comes back on the next `terraform apply`. In `keep` the tag stays the source of truth:
tagged VMs show a `tagged` badge, moving one in the dashboard warns that the move will be
reverted on the next reconcile, and Re-sync leaves tagged VMs where the tag put them.

Pick **`consume`** when the tag is a one-time instruction from the wizard or a script.
After it is used, the VM can be moved freely in the dashboard.

### Other ways to set the tag

- **Existing VM:** Instance → **Edit** → Tags → add `vmfolder` = `/prod/web`. The next
  dashboard load files it.
- **API:** `addTags` adds the tag and leaves the VM's other tags alone. `tags` replaces the
  whole list, so prefer `addTags`:

  ```
  curl -sk -X PUT "https://<appliance>/api/instances/<id>" \
    -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
    -d '{"instance":{"addTags":[{"name":"vmfolder","value":"/prod/web"}]}}'
  ```
- **Terraform:** `tags = { vmfolder = "/prod/web" }` on the instance resource, with `keep`
  mode.

The tag name is matched case-insensitively, and `vmfolders` is accepted as well as
`vmfolder`. Tags are name:value pairs in the Tags field. The **Labels** field on the
wizard's Group step is a different thing and is not read.

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

### How it works

Reconciliation runs when the dashboard loads and when Re-sync is pressed. It is one-way:
the plugin never writes a `vmfolder` tag, and moving a VM in the dashboard never creates
one. A VM that is still being built is skipped, so a tag is never spent on a VM that may
not finish. If a VM carries more than one `vmfolder` tag, the first is used and a warning
is logged.

**Where the tag lives.** A tag set in the wizard, the instance edit form or the API belongs
to the **instance**. Morpheus copies it onto the VM's server record, but the instance holds
the original and the Morpheus UI shows the instance's tag. The plugin reads the instance
first, then the server copy, which is the only place a VM without an instance (a discovered
VM) can carry one. The instance is located through the VM's workload. In `consume` mode the
plugin finds the instance first, removes its tag, then removes the server copy. Other tags on either record are left alone and no other field is written.
Every removal writes one audit line:

```
VmFolders tag consumed: user=<user> vm=<serverId> path=</prod/web> ok=true instance#<id> found=workload(instance.id)#<id> tags=instance.metadata removed=true (async.remove(tags, Instance)) server#<id> removed=true (async.remove(tags, ComputeServer))
```

`found=` says how the instance was located. `ok=false` means the instance tag was not
removed, and `instance#-` means no instance was found. In both cases a `WARN` line gives
the reason, and the VM stays filed either way.

### Testing the tag feature

Watch the log while testing:

```
sudo tail -F /var/log/morpheus/morpheus-ui/current | grep --line-buffered "VmFolders"
```

1. **Off does nothing.** With `vmfolder tag mode` left empty or set to `off`, provision a
   VM tagged `vmfolder` = `/tagtest/one`. No `VmFolders tag` line appears, the VM is not
   filed, and the tag stays.
2. **Keep.** Set `keep` in Administration → Plugins and reload the dashboard. The VM is in
   `/tagtest/one` with the `tagged` badge, and its tags are unchanged in Morpheus.
3. **Keep wins.** Move it to `/b` in the dashboard (the dialog warns), then press Re-sync.
   It goes back to `/tagtest/one`.
4. **Invalid.** Tag a second VM `vmfolder` = `../x` and reload. The VM doesn't move, the
   tag stays, a warning is logged, and the Re-sync toast shows `invalid: 1`.
5. **Consume at provisioning.** Set `consume`. Provision a new VM with `vmfolder` =
   `/tagtest/two` and one other tag in Configure → Advanced Options → Tags. On the first
   dashboard load after it finishes, it is in `/tagtest/two`. After a hard refresh, the
   `vmfolder` tag is gone from the instance page and from the VM's server page, the other
   tag is still there, and there is one `VmFolders tag consumed: … ok=true` line.

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

Administration → Plugins → VM Folders → **Enable datastore scanning**. It is off by
default, and scanning stays off if the setting can't be read.

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
| Morpheus / VM Essentials | 9.0.2 (developed against) and 9.1.0 (verified); 8.x untested since 1.1.0 |
| `morpheus-plugin-api` | 1.3.0 |
| Java (build) | 17 (source/target 11) |
| Gradle | 8.8 — Shadow 8.1.1 requires Gradle 8+ |

## Build

The Gradle wrapper is checked in and pins Gradle 8.8.

```bash
./gradlew clean shadowJar
```

Output: `build/libs/morpheus-vm-folders-plugin-1.5.10-all.jar`

Morpheus compiles Groovy plugins when it loads them, so a successful Gradle build
does not prove the plugin compiles against the running appliance. Always check the
log after uploading:

```bash
sudo grep -i -A15 "VmFolders" /var/log/morpheus/morpheus-ui/current | tail -80
```

Expect `VM Folders Plugin 1.5.10 initialized`.

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
  Morpheus record, only to delete the `vmfolder` tag (from the instance and its server copy), only on VMs the caller's tenant can
  access, and always with an audit line. `keep` mode is read-only.
- **Reconciliation does not run on an unauthenticated-looking GET.** It can write, and it
  runs from `/vms`, which is a GET, so it only runs for a request carrying
  `X-VMF-Request` — a header a cross-origin GET cannot set without a CORS preflight. A
  plain browser GET of `/vms` stays strictly read-only.
- **Settings cannot be changed from the dashboard.** They are read only from the plugin
  settings form in Administration → Plugins, which is protected by Morpheus's own admin
  permissions. No plugin route writes a setting. An unrecognised `tagMode` is treated as
  `off`, and the labels are sanitized display strings.

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
  VmFoldersSettings.groovy           tab settings, cached 60 s for the tab providers
src/assets/javascript/vmFolders.js   SPA: tree, table, datastore view, scan panel, settings, mutations
src/main/resources/renderer/hbs/     tab shells (static; vmFolders.js builds the tab on open)
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
- **Tag reconciliation adds queries** when it is not `off`. Every dashboard load runs one
  instance list. Each tagged instance not already linked to its VM adds one workload
  lookup. An estate with nothing tagged pays only for the instance list.
- No automated tests; verification is a manual checklist run against a live appliance.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Notable releases: **1.1.5** (security
hardening, core CSRF, embedded-tab fixes), **1.2.0** (datastore visibility and
browsing), **1.3.6** (unmanaged labelling, datastore bucket fix, embedded-tab state fixes), **1.4.1** (datastore scanning), **1.5.6** (`vmfolder` tag at provisioning time, durable folder renames, plugin settings form as the single source of settings).
