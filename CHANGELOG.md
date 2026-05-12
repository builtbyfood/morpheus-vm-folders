# Changelog

## [1.1.x] - 2026-05-12

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
