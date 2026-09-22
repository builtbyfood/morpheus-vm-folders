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
- **Auto-Organize** — builds a Cloud/Host folder hierarchy and assigns every VM
- **Re-sync Hosts** — updates Cloud/Host folder assignments after a live migration
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
  mutation and passes through `requireMutation()`; `/scans` and `/scanConfig` are
  read-only and filtered to datastores the caller can see. One audit line is written per
  scan with user, datastore, host, elapsed time and counts.

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
src/assets/javascript/vmFolders.js   SPA: tree, table, datastore view, scan panel, mutations
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
- No automated tests; verification is the manual checklist in `CLAUDE.md`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Notable releases: **1.1.5** (security
hardening, core CSRF, embedded-tab fixes), **1.2.0** (datastore visibility and
browsing), **1.3.6** (unmanaged labelling, datastore bucket fix, embedded-tab state fixes), **1.4.1** (datastore scanning).
