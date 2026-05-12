# VM Folders Plugin for HPE Morpheus VM Essentials

<<<<<<< HEAD
Folder organization for VMs in HPE Morpheus VM Essentials — a folder tree that lets you organize, browse, and control virtual machines across clouds without leaving Morpheus.
=======
A VM folder organization plugin for HPE Morpheus.  
Organize, move, and manage VMs in a persistent folder tree.

![VM Folders Screenshot](docs/screenshot.png)
>>>>>>> 72fc79a1064e1c864159e4dd483dc491a273e1d1

---

## Features

### Core (v1.0.0)
- **Folder tree** — create, rename, delete, collapse/expand folders
- **VM table** — NAME, STATUS, OS, MEMORY, vCPU, IP, CLOUD, FOLDER, ACTIONS
- **Power control** — Start / Stop per VM via Morpheus API
- **Console access** — opens Morpheus hypervisor console
- **Search & sort** — filter VMs by name, IP, OS, cloud
- **Multi-select move** — drag/assign multiple VMs to a folder at once
- **Backup / Restore / Export** — JSON database snapshots
- **Log viewer** — plugin event log with export
- **Dark / light theme** — auto-syncs to Morpheus UI dark mode setting
- **HPE branding** — HPE green (#01A982), standard header (#425563)

### v1.1.0
- **Cloud tabs** — All | cloud1 | cloud2 selector above the folder tree; auto-populated from loaded VMs
- **Cloud context auto-detection** — reads the Morpheus page DOM to auto-select the correct cloud when viewing from a host or cluster tab
- **Embedded tab providers:**
  - **Infrastructure → Clusters → (cluster) → VM Folders** — read-only folder view scoped to that cluster
  - **Infrastructure → Compute → Hosts → (host) → VM Folders** — read-only folder view scoped to that host; Re-sync button for VME auto-migration
  - **Provisioning → Instances → (instance) → VM Folders** — shows which folder the instance is in with quick-assign
- **Navigation injection:**
  - **Infrastructure sub-nav** — VM Folders link added after Boot
  - **Provisioning sub-nav** — VM Folders link added after Code
  - **Provisioning hover dropdown** — VM Folders link added to the flyout menu
  - **Infrastructure → Clouds detail** — VM Folders tab injected via GlobalUIComponentProvider
- **Auto-Organize** — one-click creation of Cloud → Host folder hierarchy with automatic VM assignment
- **Re-sync Hosts** — updates folder assignments after VME live-migrates VMs between hosts
- **Read-only embedded tabs** — cluster/server/instance tabs hide edit, delete, move controls
- **Dark mode auto-sync** — injects `:root` CSS variable overrides that survive React re-renders
- **Start/Stop button styling** — white text on solid green/red; visible without hover

---

## Requirements

<<<<<<< HEAD
| Component | Version |
=======
| Morpheus Edition | Tested | Notes |
|---|---|---|
| HPE VM Essentials | ✅ | Fully tested |
| Morpheus Enterprise | ✅ | Fully tested |
| Morpheus Community | ✅ | Fully tested |

**Minimum Morpheus version:** 8.x  
**Plugin API version:** 1.3.3

---

## Quick Install (no build required)

1. Download the latest jar from [Releases](https://github.com/builtbyfood/morpheus-vm-folders/releases/latest)
2. In Morpheus: **Admin → Integrations → Plugins → Upload**
3. Select the jar file and click Upload
4. Navigate to: `https://your-morpheus-url/plugin/vmFolders`

That's it. No restart required.

---

## Usage

### Accessing the Plugin

After install, the VM Folders page is available at:
```
https://your-morpheus-url/plugin/vmFolders
```

Bookmark it or add it to your browser favorites.

### Creating Folders

1. Click **+ Folder** in the top right
2. Enter a path using `/` for nesting — e.g. `/Production/Web`
3. Click **Create**
4. The Move dialog opens automatically so you can assign VMs immediately

Folders are persistent — they survive page reloads even with no VMs assigned.

### Moving VMs

**Single VM:** Click the **Move** button on any VM row  
**Multiple VMs:** Check the checkboxes, then click **Move Selected**  
**Remove from folder:** Click the **✕** button or Move to Unorganized

### Folder Tree

- Click any folder to filter the VM list to that folder
- Sub-folders are shown with indentation
- Click **▼/▶** arrows to collapse/expand folders with children
- Hover over a folder to reveal **✎ rename** and **✕ delete** buttons

### Power Control

- **▶ Start** / **■ Stop** buttons appear per VM based on current state
- Power actions use the Morpheus API server-side — no token required

### Console

Click the **▶** console button on any VM to open the hypervisor console in a new tab.

### Backup & Restore

| Button | Action |
>>>>>>> 72fc79a1064e1c864159e4dd483dc491a273e1d1
|---|---|
| HPE Morpheus VM Essentials | 8.1.0+ |
| Morpheus Appliance | 8.1.x – 8.8.x tested |
| morpheus-plugin-api | 1.3.0 |
| Java | 11 |
| Gradle | 7.6.4 (via sdkman) |

---

## Installation

1. Download the latest `.jar` from [Releases](../../releases)
2. In Morpheus: **Administration → Integrations → Plugins → Upload Plugin**
3. Upload `morpheus-vm-folders-plugin-{version}-all.jar`
4. Plugin activates automatically — no restart required

The database file is created automatically at:
```
/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json
```

---

## Building from Source

```bash
<<<<<<< HEAD
# Prerequisites
sdk install gradle 7.6.4
=======
git clone https://github.com/builtbyfood/morpheus-vm-folders
cd morpheus-vm-folders-plugin

>>>>>>> 72fc79a1064e1c864159e4dd483dc491a273e1d1
sdk use gradle 7.6.4
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# Clone and build
git clone https://github.com/your-org/morpheus-vm-folders-plugin
cd morpheus-vm-folders-plugin
gradle shadowJar --no-daemon

# Output
build/libs/morpheus-vm-folders-plugin-{version}-all.jar
```

---

## Usage

### Standalone Page
Navigate to: `https://your-morpheus/plugin/vmFolders`

Available from:
- Infrastructure sub-nav → VM Folders
- Provisioning sub-nav → VM Folders
- Provisioning hover dropdown → VM Folders

### Embedded Tabs
VM Folders appears as a tab on:
- **Infrastructure → Clusters → (cluster name)** — shows cluster VMs filtered to cluster cloud
- **Infrastructure → Compute → Hosts → (host name)** — shows VMs on that host; Re-sync button
- **Infrastructure → Clouds → (cloud name)** — VM Folders tab injected via JS
- **Provisioning → Instances → (instance name)** — shows folder membership

### Cloud Tabs
The folder tree header shows cloud selector tabs: **All | cloud1 | cloud2**

- In embedded tabs, the correct cloud is auto-selected based on the Morpheus page context
- In the standalone page, defaults to All; click a cloud tab to filter

### Auto-Organize
Click the ⚙ **Auto-Organize** button to create a Cloud → Host folder hierarchy:
```
/liber-tea/
/liber-tea/vme1/
/liber-tea/vme2/
/vmware/
/vmware/esxi1/
```
All VMs are assigned to their corresponding host folder.

### Re-sync Hosts
After VME live-migrates a VM between hosts, click **↺ Re-sync** (available in host tabs and via the `/resync` endpoint) to update folder assignments to match the VM's current host.

---

## Architecture

```
src/
├── main/
│   ├── groovy/com/morpheusdata/vmfolders/
│   │   ├── VmFoldersPlugin.groovy           # Plugin entry point, provider registration
│   │   ├── VmFoldersController.groovy       # PluginController: full SPA + API proxy endpoints
│   │   ├── VmFoldersNavProvider.groovy      # GlobalUIComponentProvider: injects vmFolders.js
│   │   ├── VmFoldersClusterTabProvider.groovy  # AbstractClusterTabProvider
│   │   ├── VmFoldersServerTabProvider.groovy   # AbstractServerTabProvider
│   │   └── VmFoldersInstanceTabProvider.groovy # AbstractInstanceTabProvider
│   ├── resources/renderer/hbs/
│   │   ├── vmFoldersNav.hbs               # Script + CSS variables injected on every page
│   │   ├── vmFoldersClusterTab.hbs        # Cluster tab inline HTML
│   │   ├── vmFoldersServerTab.hbs         # Server/host tab inline HTML
│   │   └── vmFoldersInstanceTab.hbs       # Instance tab inline HTML
│   └── assets/javascript/
│       └── vmFolders.js                   # Full SPA: folder tree, VM table, cloud tabs,
│                                          # dark mode, navigation injection
```

### API Endpoints (`/plugin/vmFolders/...`)

| Endpoint | Description |
|---|---|
| `GET /` | Standalone SPA page |
| `GET /vms` | Returns all compute servers (proxied, no session auth required) |
| `GET /db` | Returns current folder/assignment database |
| `POST /saveFolder` | Create or update a folder |
| `POST /delFolder` | Delete a folder |
| `POST /renFolder` | Rename a folder |
| `POST /assign` | Assign a VM to a folder |
| `POST /unassign` | Remove a VM from its folder |
| `GET /serverActions` | Returns available actions for a server |
| `POST /executeAction` | Execute a server action |
| `GET /resync` | Re-sync host assignments for auto-migrated VMs |
| `POST /backup` | Download database backup |
| `POST /restore` | Restore database from backup |
| `GET /export` | Export database as JSON |
| `GET /power` | Start/Stop/Restart a VM |
| `GET /logs` | Return plugin log entries |

### Database Format (`vm-folders.json`)

```json
{
  "version": 1,
  "lastModified": "2026-05-12T10:00:00Z",
  "folders": [
    { "path": "/Production", "desc": "", "created": "..." },
    { "path": "/Production/Web", "desc": "", "created": "..." }
  ],
  "assignments": {
    "42": "/Production/Web",
    "17": "/Staging"
  },
  "history": []
}
```

---

## Known Limitations

- **No `AbstractCloudTabProvider`** in morpheus-plugin-api 1.3.0 — cloud detail tab is injected via JavaScript instead. Works reliably but depends on DOM selectors confirmed for Morpheus 8.8.
- **VMware console** — opens Morpheus hypervisor console proxy. Native VMware VMRC/HTML Console SDK integration is planned for v1.2.0.
- **Per-cloud folder namespacing** — all folders are shared across clouds. Cloud tabs are a UI filter only. True per-cloud folder isolation planned for v1.2.0.
- **VME Migrator plugin conflict** — if VME Migrator plugin's `MigrationController` throws `MissingPropertyException: Permission`, it poisons all plugin routes. Fix: rebuild VME Migrator with the correct `Permission` import or switch to `p()` helper in `getRoutes()`.

---

## Roadmap

### v1.2.0
- Per-cloud folder namespacing (JSON schema v2)
- VMware HTML Console SDK integration (Broadcom techdocs.broadcom.com)
- XML Editor plugin integration (auto-detect when installed)
- Column resizing with localStorage persistence

---

## Build Matrix (confirmed working)

| | |
|---|---|
| Morpheus | 8.1.1 (HPE VME), 8.8 |
| Gradle | 7.6.4 |
| Java | 11 (`java-11-openjdk-amd64`) |
| Shadow | 6.0.0 (johnrengelman) |
| asset-pipeline | 4.4.0 |
| morpheus-plugin-api | 1.3.0 |

---

## Author

<<<<<<< HEAD
Travis DeLuca — HPE CloudOps Maestro Expert
=======
Travis DeLuca — [@builtbyfood](https://github.com/builtbyfood)  
Built for the HPE Morpheus community.
>>>>>>> 72fc79a1064e1c864159e4dd483dc491a273e1d1
