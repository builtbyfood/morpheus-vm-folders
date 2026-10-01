# Changelog

Released versions: **1.0.0**, **1.1.0**, **1.1.5**, **1.2.0**, **1.3.6**, **1.4.1**, **1.5.4**.
Intermediate versions (1.1.1–1.1.4, 1.3.0–1.3.5, 1.3.7–1.3.8, 1.4.0, 1.5.0, 1.5.1, 1.5.2, 1.5.3) were internal builds — several were
diagnostic-only — and their net effect is folded into the release that followed them.

## [1.5.4] - 2026-09-30

(1.5.3 was built with the first fix below and superseded before upload.)

### Fixed
- **A tag on the instance only was never seen.** Discovery read the server copy first and
  fell back to `instance.containers`, which is not hydrated — so once the server copy was
  gone (as 1.5.2 left instance 32) the VM was never reconciled again. A tagged instance
  with no hydrated containers is now mapped to its server with one workload query
  (`instance.id` filter). Only tagged instances pay for it; a failure is logged.
- **`consume` left the tag visible.** A tag set on an instance (wizard, `instances#update`)
  is copied onto its server's metadata; the instance is the source and the Morpheus UI
  shows it. 1.5.0–1.5.2 removed only the server copy, logged `removed=true`, and the tag
  stayed. Consume now removes the tag from **both** records: the server copy, then the
  instance, found through its workload (`workload` filtered by `server.id` →
  `workload.instance` → `instance.get()`), because `instance.list()` does not hydrate
  `containers` on 9.0.2. The instance tag is removed with the `Instance` overload of
  `metadataTag.remove`. A VM counts as consumed when the instance tag is gone, or — for a
  VM with no instance — when the server tag is.
- The `VmFolders tag consumed:` line now reports each record separately: server id, tags
  found, result and call used; instance id, how it was found (or why not), result and call
  used. An unresolved instance also logs a warning saying the tag is still visible.

## [1.5.2] - 2026-09-30

### Fixed
- **Every Morpheus-provisioned VM was treated as still building** and its `vmfolder` tag
  ignored. The in-flight status check used substring matches, and `provision` matched
  `provisioned` — the normal status of a finished VM. It is now an exact list:
  provisioning, pending, queued, deploying, creating, resizing, removing, deprovisioning.

### Diagnostics (temporary)
- 1.5.1 in `keep` mode wrote no `VmFolders tag/probe` line at all. Two more lines now say
  why: `tag/probe: enter mode=… source=…` when reconcile starts, and
  `tag/probe: SKIPPED …` (info, was debug) when `/vms` arrives without `X-VMF-Request`.

## [1.5.1] - 2026-09-30

1.5.0 plus cleanup; this is the release build (1.5.0 was built but a jar with that version
already exists, so the cleanup takes a new number).

### Removed
- The `TEMPORARY 1.4.1` `VmFolders scan/match` diagnostic in `annotateScan()`. It shipped in
  the published 1.4.1 jar; the matching it was checking is unchanged.

### Docs
- README: how to set the tag (wizard, UI, API, Terraform), a table of accepted and
  rejected values, and a nine-step test sequence.
- CLAUDE.md rule 6 now names reconcile-on-load in `vms()` as the single gated exception to
  "GET is read-only".

## [1.5.0] - 2026-09-30

Folder assignment at provisioning time, and folder renames that survive Re-sync.

**This release can modify a Morpheus record for the first time.** In `consume` mode, and
only in `consume` mode, the plugin deletes the `vmfolder` tag from a VM after it has used
it to file that VM. It deletes nothing else: other tags on the same record are untouched,
and no other field is written. Tag reconciliation is **off by default** and an
administrator must choose a mode before any tag is read. `keep` mode is fully read-only
and is the right choice for Terraform-managed tags, where the tag must stay in place or
the next `terraform apply` will put it back. Disclose this write surface when reviewing
the plugin, alongside the host-command surface from 1.4.0.

### Added
- **`vmfolder` tag → folder.** A VM tagged `vmfolder` = `/prod/web` is filed under
  `/prod/web`, so the provisioning wizard's Tags step, `PUT /api/instances/:id` and
  Terraform can all set a folder at create time. These exist on VM Essentials, Advanced
  and Enterprise; Inputs and option lists are Enterprise-only and are therefore not used.
  - The tag name is matched case-insensitively. The value is normalized with the same
    rules as a folder created by hand (see **Changed**). An unusable value is logged and
    skipped with the VM and the tag both left alone, so it can be seen and corrected.
  - One tag per VM. If several `vmfolder` tags are present the first is used and a warning
    is logged.
  - Direction is one-way. The plugin never writes a `vmfolder` tag, and moving a VM in the
    dashboard never creates or updates one.
  - Reconciliation runs on a dashboard load and on Re-sync. All assignments are written in
    a single database transaction per pass, and tag removal happens after it, so a failed
    removal can never lose the assignment it was made for.
  - **`tagMode` setting: `off` (default) | `keep` | `consume`.** In `off` no tag is read
    and nothing is logged. In `keep` the tag stays and remains the source of truth: a
    tagged VM shows a `tagged` badge, the Move dialog says the move will be reverted on the
    next reconcile, and Re-sync leaves tagged VMs alone. `consume` removes the tag once the
    VM has been filed.
  - The tag is read from the `ComputeServer` and, when the server carries none, from the
    `Instance` that owns it, and is removed from whichever record carried it — which of the
    two a provisioning-time tag lands on is platform behaviour, so both are handled.
  - A VM whose status says it is still being built is skipped, so a tag is never consumed
    against a VM that may not finish.
- **Folder `origin`.** An auto-created folder now records where it came from —
  `{cloud: 'managed-democracy'}` for a cloud folder, `{cloud: 'managed-democracy',
  host: 'defender'}` for a host folder. Folders created before 1.5.0 are migrated on the
  first read by matching their path against the current cloud and host names.
- **Settings dialog** on the dashboard (⚙ Settings): tag mode, both root labels, and the
  datastore-scanning switch, which previously had no UI at all and could only be set
  through the plugin settings form. The plugin settings form still wins when it holds a
  value, and the dialog says so when it does.
- **Configurable root labels.** `All VMs` and `Unorganized` are settings (`labelAll`,
  `labelUnorganized`), display-only and escaped on output. They apply to the tree, the
  breadcrumb, the VM table and the embedded cluster, host and instance tabs.
- `GET /config` (read-only) replaces `GET /scanConfig`: it carries the scan state and
  scannable datastores plus the tag mode, the labels, and which store each value came from.
  `POST /setConfig` writes one allow-listed setting. `POST /autoOrganize` is new.

### Fixed
- **Renaming an auto-organized folder no longer undoes itself.** `resync` recomputed
  `/<cloud>/<host>` from the current names, so renaming `/managed-democracy` to `/Lab` and
  pressing Re-sync recreated the old tree and dragged every VM back into it. Targets are
  now resolved by `origin`, so a renamed folder stays the target and keeps receiving its
  VMs. A folder with no origin was made by hand and Re-sync never touches it — that is now
  enforced by the record rather than inferred from the path depth.
- **Auto-Organize recreated renamed folders** for the same reason, and issued one request
  per folder and per VM from the browser. It is now a single `POST /autoOrganize` that
  resolves every target by origin.
- **Rename is now offered on any folder**, including an auto-created cloud folder that has
  no stored record of its own. Previously the pencil appeared only on folders with a
  record, which is exactly the set that excluded the top-level folders this release is
  about. Delete is unchanged.

### Changed
- **Folder paths are validated.** `saveFolder`, `assign` and `renFolder` now put the path
  through one normalizer: trim, force a leading slash, collapse duplicate slashes, drop a
  trailing slash, reject control characters, `.` / `..` segments and a backslash inside a
  segment, and cap the path at 8 segments, 64 characters per segment and 512 overall.
  Angle brackets and quotes are deliberately still accepted — a hostile folder name is
  allowed and is made safe by `esc()` at render time, which is what the verification
  checklist tests. `renFolder` also refuses to move a folder inside itself.
- **Re-sync places VMs that are in no folder** into their cloud/host folder when that
  folder already exists. It never creates a folder for an unassigned VM, and the confirm
  dialog says what it will do. Its toast now reports moved, placed, and the tag counts.
- `GET /vms` carries `X-VMF-Request` from the dashboard. Reconciliation can write, and
  `/vms` is a GET, so it only runs for a request carrying that header — a header a
  cross-origin GET cannot set without a CORS preflight. A plain browser GET of `/vms`
  stays strictly read-only, which keeps the "GET is read-only" rule intact.
- Re-sync and Auto-Organize write one history entry per call rather than one per VM, and
  tag assignments write at most 25 before summarizing. The history ring holds 100 entries
  and a first pass over a large estate was able to flush it. Per-VM detail goes to the
  appliance log.
- The plugin settings form is read at most once every five seconds. A single request asks
  for the tag mode and both labels, which was three platform calls. The plugin's own
  database is still re-read every time, so a change made in the dashboard is immediate.

### Platform facts verified against the plugin-api 1.3.0 jar (2026-09-30)
- `ComputeServer.metadata` and `Instance.metadata` are `List<MetadataTag>`. `MetadataTag`
  carries `name` and `value`; `name`, `refType` and `refId` come from
  `MetadataTagIdentityProjection`. **`ComputeServer.tags` is a separate `String` field and
  is not the tag list.**
- `MorpheusMetadataTagService` (`morpheusContext.async.metadataTag`) has
  `remove(List<MetadataTagIdentityProjection>, ComputeServerIdentityProjection)` and an
  `Instance` equivalent; the synchronous service only has `remove(List)`. `ComputeServer`
  is a `ComputeServerIdentityProjection`, so the server can be passed straight in and
  Groovy selects the overload by runtime type. The ref-aware call is preferred and the
  plain synchronous remove is the fallback.
- `Instance.containers` is `Collection<Workload>` and `Workload.getServer()` gives the
  `ComputeServer`, which is how a server is mapped back to its instance.

### Still to confirm on the appliance
`gradle shadowJar` passes, which does not prove the controller compiles inside Morpheus.
After upload, check the log and the `VmFolders tag/probe` line it writes once per
reconcile (temporary; still present in 1.5.1, strip in the build after it is answered):
- whether `computeServer.list()` hydrates `metadata` the way it hydrates `volumes`;
- whether a tag set in the provisioning wizard arrives on the `ComputeServer`, the
  `Instance`, or both, and whether it is present before provisioning completes;
- whether the ref-aware `remove` works, that the other tags on the VM survive it, and that
  the removal shows in the Morpheus UI.

## [1.4.1] - 2026-09-22

Follow-up to the first live scans.

### Fixed
- **Every Morpheus-provisioned volume was listed as orphaned.** On a store holding
  Morpheus volumes (cargo-bay), all eight uuid files were reported orphaned even though
  four belong to VMs with disks there. 1.4.0 indexed only the raw values of
  `volumePath` / `uuid` / `externalId` / `internalId` and looked them up by full path or
  file name, so any difference in form (path vs. basename, case) missed. The index now
  holds each identifier **and its basename, lowercased** — including `uniqueId` and
  `name` — and lookups try both forms. Until confirmed on the appliance, a temporary log
  line reports what is still unmatched alongside the identifier forms actually stored for
  volumes on that datastore.

### Added
- **Disk-only directories** — a third group for directories holding disk-sized files but
  no libvirt domain XML. They were correct to exclude from "On disk, not in Morpheus"
  (the rule requires a domain XML) but wrong to bury in Other: a directory with a 30 GB
  disk and no XML is exactly what an operator needs to see. Expected members on
  supply-line: `android`, `hellpod`, `gpu-vm`, `manager`, `vme-manager`.
- **`backup/collection` label** on a VM directory holding more than three domain XMLs, or
  whose XML names do not match the directory name — `converted/` with 14
  `ova-test_backup_*.xml` files is a backup dump, not one VM. A label only; the scan
  cannot know more than that.
- A note in the Orphaned volumes section that Morpheus virtual images (ISOs such as
  virtio-win, e.g. the 753 MB `4a08eb13-…` present on both stores) are not
  `StorageVolume` records and will always appear there.

### Verified in the first live run (1.4.0)
Scans completed on all three stores; shared stores scanned once on `defender`;
`ms-dos-6.22` reported 2 disks and 2 XMLs; `photos`, `flightaware` and `veeam` correctly
matched managed VMs and stayed out of the orphan list. The `homeassistant01test` split —
2 XMLs and no disks on supply-line, 2 disks and 2 XMLs on cargo-bay — is real: the disks
live on GFS2 and the NFS copy is XML-only.

## [1.4.0] - 2026-09-22

Datastore scanning: see what is actually on a datastore, not only what Morpheus knows.

**This release can run read-only commands on hypervisor hosts.** It is **off by default**
and must be enabled by an administrator before any Scan control appears. Disclose this
surface when reviewing the plugin.

### Added
- **Datastore scan.** In the datastore view, a Scan button per store lists what is on disk:
  - **On disk, not in Morpheus** — directories holding a libvirt domain XML whose name
    matches no VM in the caller's tenant: disk count, total size, XML file names, newest
    mtime. These can be assigned to folders like any VM.
  - **Orphaned volumes** — uuid-named files with no matching `StorageVolume`.
  - **Other files** — everything else, collapsed by default.
  - Header shows the scanning host and the age of the result, with Rescan.
- **Scans are manual and cached.** Nothing runs on page load; results are stored in the
  plugin database under `scans.<datastoreId>` with the timestamp and scanning host, and the
  view renders the cached result until someone rescans.
- **On-disk entries can be filed into folders**, keyed `ds:<datastoreId>:<dirname>`. The
  server validates such a key against the caller's visible datastores and that store's
  cached scan before writing it, so the strict numeric check on compute-server ids is
  unchanged.
- **Plugin setting `Enable datastore scanning`** (checkbox, default off), plus a
  permission-gated `scanEnable` route as a fallback for appliances where the plugin
  settings form is unavailable. Both default to off and scanning fails closed if neither
  can be read.

### Security
- The scanned path is never client-supplied: it comes from `Datastore.externalPath` and
  must match `^/mnt/<uuid>$` or `^/var/morpheus/kvm/[a-z0-9-]+$`, or the request is refused
  with 400 before any command runs. Morpheus-internal stores (`externalId` starting
  `morpheus-`) and offline stores are not scannable.
- Commands are fixed templates: a readability test, one bounded `find`
  (`-maxdepth 2`, regular files only, >1 MB or `*.xml`, no symlink following, no `du`), and
  a `head -c 512` pass to identify libvirt domain XMLs on the host. 30 s timeout — a store
  that times out reports a timeout rather than partial data.
- `/scan` is a mutation and goes through `requireMutation()`; `/scans` and `/scanConfig`
  are read-only. Results are filtered to datastores the caller can see.
- Every directory and file name is rendered through `esc()` — these strings come from a
  filesystem.
- One audit line per scan: user, datastore, host, elapsed ms, and the counts found.

### Notes
- Routes are gated on the existing `admin-cm` permission. A plugin-declared
  `vm-folders-scan` permission was not used because whether Morpheus 9.0.2 honors
  plugin-declared permissions is still unverified.
- A store the agent user cannot read is reported per store as *not readable by the agent
  user (morpheus-node)* rather than as a failed scan: readability depends on the store's
  ownership and any NFS squash, which vary per deployment.

### Platform facts (9.0.2, verified 2026-09-22)
- `executeCommandOnServer(server, cmd)` (2-arg) runs through the Morpheus agent in
  ~50–80 ms. **stdout arrives in `TaskResult.data`; `TaskResult.output` is always null on
  that path.** The long overloads are not needed.
- The agent runs commands as `morpheus-node` (uid 996; groups libvirt, kvm, libvirt-qemu).
- `Datastore.externalPath` holds the path: `/mnt/<uuid>` for NFS and GFS2 stores,
  `/var/morpheus/kvm/<name>` for host-local directory stores.
- Shared vs local comes from `datastoreType`: `libvirt-netfs-nfs` and `libvirt-dir-gfs2`
  are shared (scan once on any host that lists them); `libvirt-dir` is per-host and scanned
  on its `hostId`. `Datastore.locations` cannot be used for this — it lists every host for
  every store.

### Removed
- All 1.3.7/1.3.8 probe code.

## [1.3.8] - 2026-09-22

Second probe build for 1.4.0. **Internal — do not publish.**

### Diagnostics (temporary)
- **Host exec probe, round 2.** The 2-arg `executeCommandOnServer` returned
  `success=true exitCode=0` in 54 ms with `output=null` — too fast for SSH, so it ran
  through the Morpheus agent and stdout is not in `TaskResult.output`. This build runs the
  same command through four call shapes (2-arg; 10-arg with the position-3 Boolean false
  and true; 11-arg) and dumps the entire `TaskResult` for each — `output`, `data`,
  `results`, `customOptions`, `toMap()` and `properties`, with each field's runtime class —
  plus elapsed ms, to find which shape carries stdout.
- The command now also performs the read test in the same round trip:
  `echo vmf-probe && id && ls /mnt && ls -la <nfs path> | head -5`, where `<nfs path>` is
  taken from the `Datastore.externalPath` of the first mount-based store rather than a
  literal.
- The datastore probe is unchanged (its question is answered; kept so one upload re-confirms).

### Note
The plugin-api jar carries no `MethodParameters`, so the long overloads' parameter *names*
are unknowable from the jar; position 3 is assumed to be the rpc flag. Every other
parameter is passed null, and the command is read-only, so a wrong assumption only toggles
an unintended Boolean.

## [1.3.7] - 2026-09-22

Probe build for the 1.4.0 datastore scan. **Internal — do not publish.**

### Added
- **Stronger power confirmation for hypervisor hosts.** `/vms` returns `isHost`
  (`ComputeServerType.vmHypervisor == true`); Stop/Restart on a host row now warns that
  it is a hypervisor host and that every VM running on it will go down, instead of
  showing the same dialog as a guest VM.

### Diagnostics (temporary — remove before 1.4.0)
- **Datastore probe** — logs every visible datastore's `externalPath`, `internalId`,
  `externalId`, `uniqueId`, `refType`/`refId`, `hostId`, `storageServer`, `zonePool`,
  `datastoreType`, size/free, and its `locations` (`refType`/`refId`/`refUUID`). Decides
  the `/mnt/<uuid>` path field and the shared-vs-host-local test.
- **Host exec probe** — runs `echo vmf-probe && id && ls /mnt` once on a hypervisor host
  (prefers `defender`) via the 2-arg `executeCommandOnServer`, logging elapsed ms,
  `success`, `exitCode`, `error`, `msg` and the raw output. Answers whether it
  authenticates with stored host credentials, which user it runs as, and whether output
  returns intact.
- Both run **once per plugin load**, not per request, and the exec probe has a 20 s timeout.

## [1.3.6] - 2026-09-21

First released 1.3.x build. Versions 1.3.0–1.3.5 were internal iterations; their
net effect is described here.

### Added
- **`unmanaged` badge** on VMs Morpheus inventoried from the hypervisor rather than
  provisioned — `serverType == 'unmanaged'` or `discovered == true`, verified on 9.0.2
  against a host's Discovered VMs tab. `/vms` reports `unmanaged` per VM and
  `meta.unmanaged` as a count. The badge is a **label only**: power, console, actions,
  Move and folder assignment all remain available, because the platform decides what it
  accepts and its own error beats a hidden control.
- **Type column in the disk detail row**, from `StorageVolume.type`
  (`displayName`/`code`/`volumeType`, falling back to `volumeType`/`diskType`), with
  `(removable)` appended when `StorageVolume.removable` is set. This is what identifies
  disks that legitimately have no datastore: volume types with `hasDatastore = false`
  (mounted ISO, CD/DVD) never carry one.
- **`vmfState()`** — a read-only console helper returning the full client state
  (instance count, VM/scoped/filtered/folder-path counts, active folder and datastore,
  cloud filter vs. the cloud actually applied, host context, read-only flag).

### Fixed
- **"No datastore" collected VMs that plainly had datastores.** Membership was "has any
  disk without a datastore", so a VM mixing a datastore-backed disk with one that has
  none landed in the bucket alongside its real datastores. Membership is now
  `datastores.length === 0` — no datastore at all, VMs with no disks included — and
  `datastoreStats()` shares the rule, so a sidebar count can never disagree with what
  clicking it lists.
- **Folder clicks on embedded tabs wiped the view.** Clicking a folder on a host or
  cluster tab emptied the VM table, the folder tree and the VM count. Two causes, both
  fixed: `vmFolders.js` could run twice (the nav provider injects it on every core page
  and each tab template re-injects it when `vmfReload` is undefined), leaving two
  instances with separate state over one DOM — now guarded to a single instance; and the
  cloud name scraped from the Morpheus DOM was latched as a filter without validation,
  so any non-matching value silently emptied every view, folder tree included.
- **Cloud name scrape picked up the Group row.** Detection is now label-aware
  (`vmfDetectContextCloud()`, shared by the host and cluster templates): it reads the row
  whose label is "Cloud" rather than any link pointing at `/infrastructure/clouds/`. A
  scraped value that matches no loaded VM is discarded with a console warning.
- **Table click handlers were bound on every render.** `#vmf-vlist` persists across
  renders, so `renderVms()` stacked another listener each time and every click fired
  repeatedly: the disk chevron toggled open-then-closed and looked dead, and sort headers
  flipped direction twice. Bound once and fully delegated.
- **Counts disagreed with the table on embedded tabs.** The All VMs badge, the
  Unorganized count and the footer used the unscoped VM set while the table was filtered
  to the host — a host tab listing nine VMs reported "21 VMs loaded". All now use the
  scoped set; the footer names the total when scoping narrows it (`9 VMs of 21`).
- **Backup and Restore buttons on the dashboard were dead.** Both issued a plain GET and
  were refused with `405 POST required` by the 1.1.1 mutation guard; they now go through
  the same POST + CSRF path as every other mutation.

### Notes
- `ComputeServer.managed` is the **agent** flag, not a managed-vs-discovered signal.
  Internal builds 1.3.0–1.3.2 keyed the badge off it, which mislabelled VMs and removed
  power controls from running instances; 1.3.3 reverted that and 1.3.4 replaced it with
  the verified field. Recorded in `CLAUDE.md` so it is not repeated.

## [1.2.0] - 2026-09-20

Datastore visibility and browsing. No JSON DB schema change; no controller guard change.

### Added
- **Datastore column** on the VM table (after Cloud): unique datastore names across the VM's disks, `—` when none. Sortable; included in search.
- **Disk detail row** — a chevron on any VM with disks expands a per-disk sub-table: Disk · Datastore · Used · Total · Root. Sizes formatted TB/GB/MB via `fmtMemSmall()`; `—` for used when the platform reports 0. Detail rows are collapsed on every re-render.
- **Datastores section in the sidebar** (standalone dashboard only): one read-only entry per datastore plus **No datastore** for VMs with a disk that has no datastore relationship, with a VM-count badge (tooltip shows VM and disk counts and notes that a VM spanning datastores counts in each). Selecting one filters the table and sets the breadcrumb to `Datastore: <name>`; selecting a folder clears it and vice versa. Cloud chips and host context still narrow on top. No context menu, rename, delete or drop targets — Move/assign on rows behave exactly as before and write folder assignments only.
- `/vms` now returns two extra keys per VM: `disks: [{name, datastore|null, total, used, root}]` (bytes, root volume first, then `displayOrder`) and `datastores: [sorted unique names]`. Derived from the already tenant-scoped server list, so datastore names never leak across tenants. The sidebar aggregate is computed client-side from `/vms`; there is no separate datastore endpoint.
- Sidebar selection now goes through a single `resetSelection()` helper so folder and datastore selection cannot both be active.

### Changed
- Embedded host/cluster/instance tabs pick up the Datastore column and chevron automatically via the shared JS; the sidebar section only exists on the standalone page.

### Platform facts (9.0.2, verified 2026-09-20)
- `computeServer.list(DataQuery)` **hydrates `volumes`** (Path A) — no per-server fetch needed. `computeServer.get()` and `listById()` also hydrate but return volumes in a different order; the plugin sorts root-first then `displayOrder`.
- `StorageVolume.usedStorage` is `0` (not null) on VME/KVM; `datastore` is null on some VME volumes and populated (`cargo-bay`) on others — "no datastore" is a real bucket.
- `StorageVolume` carries only `refType`/`refId` (cloud-scoped); there is no server-id filter on `services.storageVolume`, so `ComputeServer.volumes` is the only server→disk path.

## [1.1.5] - 2026-09-20

Closes the v1.1 series. Power actions confirmed working end to end on 9.0.2 with 1.1.4; this release fixes the embedded tabs and removes all diagnostics.

### Fixed
- **Embedded tabs rendered an empty model** — instance tab showed a blank instance name and no server id (so it could never look up the VM's folder and always showed "Unorganized"); cluster tab's cloud name was empty; host tab's `vmfHostId` was empty so it never filtered to the host. Root cause, verified in the plugin-api 1.3.0 jar: `HandlebarsRenderer.applyModel()` calls `template.apply(model.object)`, so the template's root context **is** the model map and `{{object.*}}` never resolves. All templates now use root-level names (`{{instanceName}}`, `{{serverId}}`, `{{serverName}}`, `{{cloudName}}`, `{{pluginUrl}}`).
- **Host and cluster tabs no longer interpolate model values into JS string literals** — `serverId` / `cloudName` are `data-*` attributes on `#vmf-st` / `#vmf-ct`, read via `dataset` (same pattern as the instance tab since 1.1.1).
- Instance tab provider: `buildInstanceConfig` failures are logged instead of swallowed, and the workload fallback runs whenever the primary path yields no server id (on 9.0.2 it is the fallback that resolves it).

### Removed
- All temporary diagnostics from 1.1.3 / 1.1.4: `getCsrf()` console line, instance-tab provider model log, `#vmf-inst-dbg` span, request-class log.

### Platform facts confirmed on Morpheus 9.0.2 during 1.1.2–1.1.4
- Core CSRF is Spring Security: token in `<meta name="_csrf">`, accepted as `X-XSRF-TOKEN` header. All core cookies are HttpOnly; the standalone dashboard obtains the token by fetching `/operations/dashboard` and reading the meta tag.
- `ViewModel.request` is not an instance of `javax.servlet.http.HttpServletRequest` under the plugin classloader; access it dynamically.
- `ViewModel.user` and `user.account` are populated on controller calls.

## [1.1.4] - 2026-09-19

CSRF is confirmed solved on 9.0.2 (1.1.3: the standalone dashboard's scrape ran and `/power` reached the controller). This build fixes the controller-side rejection that followed.

### Fixed
- **Every POST returned 400 `invalid request`** — `requireMutation()` did `req instanceof javax.servlet.http.HttpServletRequest`, which is false for the request object Morpheus 9.0.2 hands the plugin. The guard now accesses the request dynamically (`getMethod()`, `getHeader()`, `getServerName()` via Groovy dispatch, guarded with `respondsTo`) and no longer imports `HttpServletRequest`. Behaviour of the checks (POST only, `X-VMF-Request`, Origin/Referer host match, user present) is unchanged.

### Diagnostics (temporary, in addition to the 1.1.3 ones)
- Logs the concrete class of `model.request` once per plugin load: `VmFolders: model.request class on this platform = …`.

(The instance-tab rendering issue left open here was root-caused and fixed in 1.1.5.)

## [1.1.3] - 2026-09-19

Debug build — diagnostics only, no behaviour intended to ship. Remove the three `TEMPORARY 1.1.3` blocks in 1.1.4.

### Changed
- **CSRF cookie branch removed** — all core cookies are HttpOnly on 9.0.2 (`document.cookie` is empty on the standalone page even though the request carries `XSRF-TOKEN`), so `getCsrf()` can never read the cookie. Token sources are now: `<meta name="_csrf">` (embedded tabs) or a same-origin fetch of `/operations/dashboard` / `/` parsed for the meta tag (standalone dashboard).
- `buildInstanceConfig` failure in the instance tab provider is now logged instead of swallowed, and the containers fallback runs whenever the primary path yields no server id (previously only on exception).

### Diagnostics (temporary)
- `getCsrf()` logs one line per resolution: `vmf csrf <source> <tokenLength> <htmlLength> <htmlHasCsrf> <page -> status> <headerName>`.
- `VmFoldersInstanceTabProvider` logs the instance class, id, name, container count, resolved serverId and its source, and the model map, on every render.
- `vmFoldersInstanceTab.hbs` renders a hidden `#vmf-inst-dbg` span with `{{object.instanceName}}`, `{{instanceName}}`, `{{object}}`, `{{this}}` as data attributes, to show which Handlebars context shape 9.0.2 passes to tab templates. Open item: instance tab renders with an empty instance name and no server id on 9.0.2; the provider's `?: 'Instance'` fallback cannot produce an empty string, so the model is suspected not to reach the template — which would also explain the cluster tab's empty `{{object.cloudName}}`.

## [1.1.2] - 2026-09-18

Fixes the v1.1.1 regression where every POST was rejected by Morpheus core.

### Fixed
- **POSTs blocked by core CSRF** — Morpheus core (9.0.2) runs Spring Security CSRF in front of `/plugin/*`: a POST without the session token is answered `302 → /error/invalid-csrf` before the plugin controller runs. Core exposes the token on its own pages as `<meta name="_csrf">` / `<meta name="_csrf_header" content="X-XSRF-TOKEN">`. `post()` in `vmFolders.js` and `vmfPost()` in the instance tab now send it as `X-XSRF-TOKEN` on every mutation. The standalone dashboard, which is not a core page, resolves the token from the meta tag if present, else the `XSRF-TOKEN` cookie, else by fetching one core page (`/operations/dashboard`, then `/`) and reading its meta tag; the token is cached per page load and refreshed once on an `invalid-csrf` redirect.
- `log.info` startup line now includes the plugin version.

### Notes
- The `X-VMF-Request` header and Origin/Referer check in `requireMutation()` are kept as defense in depth. They are a same-origin guard, not a CSRF token; core's token is the enforced layer. The 1.1.1 entry below overstated this.
- No controller change was needed: core validates the token before the request reaches the plugin.

## [1.1.1] - 2026-09-09

Security hardening release. Addresses P0 items from the v1.1.0 internal audit and the HPE security review.

### Security
- **State-changing endpoints require POST** — `saveFolder`, `delFolder`, `renFolder`, `assign`, `unassign`, `backup`, `restore`, `power`, `executeAction`, `resync` now reject GET with 405. (`Route.method` in plugin-api 1.3.0 is the handler name, not an HTTP verb, so enforcement is in the controller.)
- **CSRF protection** — mutating requests must carry the `X-VMF-Request` header and, when `Origin`/`Referer` is present, its host must match the request host. Cross-origin browsers cannot satisfy this without a CORS preflight, which Morpheus does not answer.
- **Tenant scoping** — `/vms` and `/resync` now query with `DataQuery(user)` and filter results by the caller's account; `power`, `serverActions`, `executeAction`, `assign`, `unassign` verify the target server belongs to the caller's tenant (master account exempt). Requests with no authenticated user are refused.
- **HBS script-literal interpolation removed** — instance tab reads `serverId`/`instanceName`/`pluginUrl` from `data-*` attributes instead of interpolating model values into JS string literals.

### Fixed
- **Lost updates in the JSON database** — every read-modify-write now runs under a single lock (`mutateDb`); `backup`/`restore` share the same lock.
- **Durability** — DB writes `fsync` the temp file before the atomic rename.
- **Silent write failures** — a failed write now propagates as an error response instead of reporting `success: true`.
- **Restore sanity check** — a backup that does not parse as JSON can no longer replace a good database.
- `unassign` accepts stale IDs for VMs no longer in Morpheus (still refuses VMs owned by another tenant).

## [1.1.0] - 2026-05-12

### Added
- **Cloud tabs** above the folder tree (All | cloud1 | cloud2) — auto-populated from loaded VMs, click to filter the folder tree and VM list to a single cloud
- **Cloud context auto-detection** — when viewing from a server or cluster tab, reads the Morpheus 8.x DOM (`.info-column a[href*="clouds"]`) to auto-select the correct cloud tab
- **AbstractClusterTabProvider** — VM Folders tab on Infrastructure → Clusters → detail pages
- **AbstractServerTabProvider** — VM Folders tab on Infrastructure → Compute → Hosts → detail pages with Re-sync button
- **AbstractInstanceTabProvider** — VM Folders tab on Provisioning → Instances → detail pages showing folder membership with quick-assign
- **Infrastructure sub-nav injection** — VM Folders link added after Boot in the Infrastructure sub-navigation bar
- **Provisioning sub-nav injection** — VM Folders link added after Code in the Provisioning sub-navigation bar
- **Provisioning hover dropdown injection** — VM Folders link in the flyout menu that appears when hovering over Provisioning
- **Cloud detail page tab** — VM Folders tab injected into Infrastructure → Clouds → (cloud) detail pages via GlobalUIComponentProvider (no `AbstractCloudTabProvider` in API 1.3.0)
- **Auto-Organize** — creates Cloud/Host folder hierarchy and assigns all VMs automatically (available from the standalone page)
- **Re-sync Hosts** (`GET /resync`) — updates folder assignments to match VMs' current host after VME live migration; also available as a button in the host tab header
- **Read-only embedded tabs** — cluster/server/instance tabs hide folder edit, delete, move, and remove controls; power and console actions remain
- **Dark mode auto-sync** — `window.vmfSyncDark()` called on page load and on every `vmfReload()`, injects `:root` CSS variable overrides that survive React re-renders
- **`hostId` and `hostName`** fields in `/vms` response from `parentServer`
- **`serverActions` endpoint** (`GET /plugin/vmFolders/serverActions?vmId=X`) — returns available actions for a server
- **`executeAction` endpoint** (`POST /plugin/vmFolders/executeAction?vmId=X&action=Y`) — executes Restart and future actions via Morpheus API

### Changed
- **Start/Stop buttons** — now use CSS classes `.vmf-start-btn` / `.vmf-stop-btn` with `color:#fff !important` overrides; text visible without hover
- **Cloud filter** — replaced dropdown `<select>` with cloud tab buttons above the folder tree
- **Description** — updated to "Folder organization for VMs in HPE Morpheus VM Essentials"
- **Version** — bumped to 1.1.0
- **`allPaths()`** — now respects `cloudFilter` and `window.vmfContextCloud`; only includes stored folders that have VMs in the current filter
- **`countIn()`** — now respects cloud and host filters for accurate per-folder counts
- **`getFiltered()`** — unified cloud filtering using `cloudFilter` consistently

### Removed
- **Bootstrap tab injection** (`tryInject`) on Compute/Inventory list page — was fighting Morpheus's React tab management; replaced by proper tab providers on detail pages
- **3-dot Actions menu** — removed; Console/Start/Stop covers current needs; XML Editor integration planned for v1.2.0
- **`AbstractCloudTabProvider`** — does not exist in morpheus-plugin-api 1.3.0; cloud detail pages use JS injection instead

### Fixed
- `MissingPropertyException: No such property: vmHypervisor` in `serverActions` endpoint
- Duplicate "Restart" and "Open in Morpheus" items in actions dropdown
- Dropdown overflow clipping — switched to `position:fixed` with viewport-relative coordinates
- Cloud tabs not responding to click — switched from event delegation to direct `addEventListener` per button
- Folder tree showing all clouds when cloud filter active — `allPaths()` now filters stored folders to only those with VMs matching current cloud/host filter
- Button text invisible until hover — Morpheus CSS was overriding `color`; switched to `!important` CSS class rules
- Dark mode CSS lost after React re-render — switched from `body.dark` class to `<style id="vmf-dark-vars">` element injection
- `vmf-cloud-bar` not showing in embedded tabs — added `<div id="vmf-cloud-bar">` to cluster and server tab HBS templates
- CSS layout broken in cluster tab — removed unnecessary ID rename from HBS script blocks
- Duplicate `injectInfrastructureLinks` function causing minification failure

---

## [1.0.0] - 2026-03-01

### Added
- Initial release
- Folder tree: create, rename, delete, collapse/expand
- VM table with NAME, STATUS, OS, MEMORY, vCPU, IP, CLOUD, FOLDER, ACTIONS columns
- Power control (Start/Stop/Restart) via Morpheus API proxy
- Console access via `/terminal/server/{id}?consoleMode=hypervisor`
- Search, sort, multi-select folder assignment
- Backup/Restore/Export JSON database
- Log viewer with export
- Dark/light theme toggle (manual)
- HPE branding (#01A982 green, #425563 header)
- Auto-Organize (basic cloud/host hierarchy)
- PluginController SPA at `/plugin/vmFolders`
- GlobalUIComponentProvider script injection (`vmFolders.js`)
