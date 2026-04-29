# HPE Morpheus VM Folders Plugin — Development Log

**Version:** 1.0.0  
**Morpheus Version:** 8.1.1 (HPE VM Essentials)  
**Author:** Travis DeLuca  
**Build Machine:** flightaware (Ubuntu, Gradle 7.6.4, Java 11)  
**Appliance:** morpheus.thedelucahome.com  
**Plugin URL:** `https://morpheus.thedelucahome.com/plugin/vmFolders`  
**Source:** `~/morpheus-vm-folder-plugin/`

---

## What This Plugin Does

Provides vCenter-style folder organization for VMs in HPE Morpheus 8.1.1. VMs can be grouped into a persistent folder tree, viewed, moved, and managed from a standalone page. Folder assignments are stored in a JSON database on the appliance. No external dependencies.

**Features:**
- Folder tree with create, rename, delete
- Move single or multiple VMs into folders
- VM table with OS, Memory, vCPU, IP, Cloud columns
- Status indicators (on/off/unknown)
- Link to VM details page and console
- Start/Stop VM power control
- Search and sort
- Backup, restore, and export of the folder database
- Log collection endpoint
- HPE brand color scheme (#01A982 green, #425563 header)

---

## Architecture

### Files

```
src/main/groovy/com/morpheusdata/vmfolders/
  VmFoldersPlugin.groovy       ← Plugin entrypoint
  VmFoldersController.groovy   ← PluginController: page + all API endpoints
  VmFoldersNavProvider.groovy  ← GlobalUIComponentProvider: injects JS into nav

src/assets/javascript/
  vmFolders.js                 ← Full SPA logic

build.gradle                   ← Gradle 7.6.4 + shadow 6.0.0 + asset-pipeline 4.3.0
```

### Data Storage

```
/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json      ← Live database
/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json.bak  ← Auto-backup (on every write)
```

**Database schema:**
```json
{
  "version": 12,
  "lastModified": "Thu Apr 24 ...",
  "folders": [
    { "path": "/Production/Web", "desc": "Web servers", "created": "..." }
  ],
  "assignments": {
    "29": "/Production/Web",
    "65": "/Home Automation"
  },
  "history": [
    { "ts": "...", "action": "assign", "vmId": "29", "path": "/Production/Web" }
  ]
}
```

### API Endpoints (all at `/plugin/vmFolders/...`)

| Path | Method | Description |
|---|---|---|
| `/vmFolders` | GET | Serve the SPA page |
| `/vmFolders/vms` | GET | List all VMs with folder assignments |
| `/vmFolders/db` | GET | Return full JSON database |
| `/vmFolders/saveFolder` | GET | Create a folder (params: path, desc) |
| `/vmFolders/delFolder` | GET | Delete folder + unassign VMs (param: path) |
| `/vmFolders/renFolder` | GET | Rename folder + update all assignments (params: oldPath, newPath) |
| `/vmFolders/assign` | GET | Assign VM to folder (params: vmId, path) |
| `/vmFolders/unassign` | GET | Remove VM from folder (param: vmId) |
| `/vmFolders/backup` | GET | Copy db to .bak |
| `/vmFolders/restore` | GET | Copy .bak to db |
| `/vmFolders/export` | GET | Download db as JSON file |
| `/vmFolders/power` | GET | Power control (params: vmId, action: start/stop/restart) |
| `/vmFolders/logs` | GET | Collect plugin logs |

---

## Build Instructions

```bash
# On build machine
cd ~/morpheus-vm-folder-plugin
sdk use gradle 7.6.4
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
rm -rf build/ .gradle/
gradle shadowJar --no-daemon

# Output: build/libs/morpheus-vm-folders-plugin-1.0.0-all.jar
```

**Upload:** Admin → Integrations → Plugins → Upload

**Log location:** `/var/log/morpheus/morpheus-ui/current`

---

## Critical Bugs Found and Fixed

### 1. DynamicTemplateLoader crash on plugin load
**Error:** `java.lang.UnsupportedOperationException at DynamicTemplateLoader.addTemplateLoader`  
**Cause:** Morpheus 8.1.1 HPE VME uses an immutable list for template loaders. Any plugin that triggers Handlebars renderer initialization crashes.  
**Fix:** Set `this.renderer = new HandlebarsRenderer()` as the **first line of `initialize()`** — before `registerProvider()`. This pre-populates the renderer field so the PluginManager skips the addTemplateLoader call.

```groovy
@Override
void initialize() {
    this.renderer = new HandlebarsRenderer()  // MUST be first
    this.name = 'VM Folders'
    ...
}
Boolean hasCustomRenderer() { return true }
```

### 2. Route permission string
**Error:** Routes registered but 404 on all requests  
**Fix:** Permission string must be `"admin-cm"` not `"admin"`. Discovered by extracting MicrosoftDns embedded plugin bytecode.

```groovy
Route.build("/vmFolders/index", "index", Permission.build("admin-cm", "full"))
```

### 3. ViewModel.params does not exist
**Error:** `No such property: params for class: com.morpheusdata.views.ViewModel`  
**Fix:** Use `model?.request?.getParameter('key')` instead of `model?.params?.get('key')`

### 4. CSP blocking inline event handlers
**Error:** `Executing inline event handler violates CSP directive 'script-src'`  
**Cause:** Morpheus uses `strict-dynamic` CSP. Inline `onclick=` attributes are blocked even with a nonce.  
**Fix:** All event handlers use `addEventListener`. Modal buttons are created as DOM elements with `.addEventListener('click', fn)`. No `onclick=` anywhere.

### 5. CSP blocking external script tag
**Error:** `Loading the script violates CSP directive 'script-src 'self' 'nonce-...'`  
**Fix:** Read nonce from `model?.request?.getAttribute('js-nonce')` and add to script tags:
```groovy
def nonce = model?.request?.getAttribute('js-nonce') ?: ''
// In HTML:
// <script src="/assets/plugin/vm-folders/vmFolders.js" nonce="${nonce}"></script>
```

### 6. Morpheus REST API returns 401 from browser
**Error:** `GET /api/servers 401 (Unauthorized)`  
**Cause:** Morpheus REST API requires Bearer token even for same-origin browser requests. Session cookie alone is not sufficient.  
**Fix:** All data fetching goes through controller proxy endpoints which run server-side with full Morpheus context. No Bearer token needed.

### 7. computeServer.getMetaData() returns Strings
**Error:** `No such property: name for class: java.lang.String`  
**Cause:** `morpheusContext.services.computeServer.getMetaData(id)` returns plain String values in HPE VME 8.1.1, not MetadataTag objects.  
**Fix:** Abandoned server-side metadata API entirely. All folder assignments stored in JSON file. Zero dependency on Morpheus metadata API for folder operations.

### 8. MetadataTag.create() requires MetadataTagType
**Error:** `No signature of method: addMetaData() is applicable for argument types: (Long, LinkedHashMap)`  
**Cause:** `addMetaData(id, Map)` signature doesn't exist. The correct API requires `MetadataTag` objects with a `MetadataTagType`, which requires additional service calls to look up or create the type.  
**Fix:** Same as #7 — abandoned metadata API, use JSON file instead.

### 9. osType.name crash
**Error:** `No such property: name for class: java.lang.String` (in vms endpoint)  
**Cause:** `s.osType` returns a String directly in VME, not an OsType object.  
**Fix:**
```groovy
def osType = (s.osType instanceof String ? s.osType : s.osType?.name) ?: ''
```
Same pattern needed for `s.cloud`, `s.computeServerType`, `s.serverOs`.

### 10. Asset pipeline caching (300 seconds)
**Symptom:** New jar uploaded but old JS still served  
**Cause:** Morpheus asset pipeline caches compiled assets for 300 seconds.  
**Fix:** Wait 5 minutes after uploading a new jar before testing JS changes. Hard refresh (Ctrl+Shift+R) required to bypass browser cache too.

### 11. Gradle/Java version matrix
**Working combination:** Gradle 7.6.4 + Java 11 + `com.github.johnrengelman.shadow:6.0.0`  
**Does not work:** Gradle 9 + Java 17 (Groovy 3.x can't compile Java 17 bytecode from morpheus-plugin-api)  
**Build command:**
```bash
sdk use gradle 7.6.4
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

### 12. HBS template files cause DynamicTemplateLoader crash
**Symptom:** Plugin crashes on load if `src/main/resources/renderer/hbs/` directory exists in jar  
**Fix:** Delete `src/main/resources/` entirely. The plugin uses raw HTMLResponse, no Handlebars templates needed.

---

## Key API Discoveries

### morpheusContext.services.computeServer methods (confirmed working)
```groovy
.list(new DataQuery().withFilter(new DataFilter('vmHypervisor', false)))  // list VMs
.get(id)                    // returns ComputeServer directly (not Single/Observable)
.startServer(Long id)       // returns Boolean
.stopServer(Long id)        // returns Boolean
.restartServer(Long id)     // returns Boolean
```

### morpheusContext.async.metadataTag methods (confirmed working)
```groovy
.create([MetadataTag], ComputeServer)  // returns Single<Boolean>, use .blockingGet()
// MetadataTag fields: type (MetadataTagType required), value, masked
// listSyncProjections('ComputeServer', id) exists but returned empty in testing
```

### Route registration
```groovy
// In Plugin.initialize():
this.controllers.add(new MyController(this, morpheus))

// In Controller.getRoutes():
Route.build("/myPath/action", "methodName", Permission.build("admin-cm", "full"))
// URL becomes: /plugin/myPath/action
```

### Request parameters in PluginController
```groovy
def myMethod(ViewModel<Map> model) {
    def nonce   = model?.request?.getAttribute('js-nonce')   // CSP nonce
    def param   = model?.request?.getParameter('paramName')  // query string param
    def session = model?.request?.session?.getAttribute('SPRING_SECURITY_CONTEXT')
    // Return JSON:
    return JsonResponse.of([key: 'value'])
    // Return HTML:
    return HTMLResponse.success(htmlString)
}
```

---

## Planned Features

- [ ] Plugin settings page (API token for advanced features)
- [ ] Folder-level power control (start/stop all VMs in folder)
- [ ] VM notes/description field stored in JSON DB
- [ ] Drag-and-drop VM assignment
- [ ] Folder color labels
- [ ] Import JSON from file upload
- [ ] `--network-fix` migration option for pve-to-vme toolkit integration
- [ ] Tab injection on Infrastructure → Inventory page

---

## Infrastructure Reference

| Host | IP | Role |
|---|---|---|
| vme1–vme5 | 192.168.200.x | VME cluster nodes |
| vme-manager | 192.168.200.17 | VME Manager |
| morpheus | morpheus.thedelucahome.com | Morpheus appliance |
| NAS | 192.168.0.30 | Shared storage |

**Plugin jar path:** `/var/opt/morpheus/morpheus-ui/plugins/`  
**Embedded plugins:** `/var/opt/morpheus/morpheus-local/plugins-embedded/`  
**Logs:** `/var/log/morpheus/morpheus-ui/current`  
**DB file:** `/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json`

---

## Deployment Options

### Option 1: Morpheus Plugin (current approach)
**Requirements:** Morpheus admin access, plugin upload capability  
**Pros:** Fully integrated, served from Morpheus domain (no CORS), uses Morpheus session auth, proxies all API calls server-side  
**Cons:** Requires plugin upload permission, Morpheus 8.x plugin API

### Option 2: Standalone Web App (nginx on appliance)
**Requirements:** SSH access to appliance, nginx config edit  
**How:** Drop static HTML/JS into `/opt/morpheus/embedded/nginx/html/vmfolders/`. Serve at `https://morpheus.thedelucahome.com/vmfolders/`. Still needs a Bearer token for API calls — solved by embedding a long-lived service account token in the JS config.

### Option 3: Standalone App on Separate Host
**Requirements:** Any Linux VM with Python/nginx  
**How:** `python3 -m http.server 8080` on any VM. User configures Morpheus URL + API token in the UI. Works cross-version as long as `/api/servers` and `/api/servers/{id}/metadata` exist.

### Option 4: Morpheus Catalog Item (no plugin needed)
**Requirements:** Morpheus catalog access  
**How:** Create a Catalog Item that generates an HTML page via a shell task. No plugin required. Limited by what catalog tasks can return.

### Option 5: Bookmarklet / Browser Extension
**Requirements:** Nothing server-side  
**How:** A bookmarklet injects the folder UI into the Morpheus infrastructure page. Reads/writes metadata via the existing browser session. Works on any Morpheus version with no installation.

---

## Version Compatibility Notes

| Feature | 8.1.1 HPE VME | Standard Morpheus | Notes |
|---|---|---|---|
| Plugin loading | ✓ (with renderer fix) | ✓ | HandlebarsRenderer init block required on VME |
| PluginController routes | ✓ | ✓ | Must use `admin-cm` permission |
| `services.computeServer.list()` | ✓ | ✓ | |
| `services.computeServer.getMetaData()` | Returns Strings | Returns objects | VME-specific bug |
| `async.metadataTag.create()` | ✓ (writes) | ✓ | Read-back broken on VME |
| `services.computeServer.startServer()` | ✓ | ✓ | |
| Asset pipeline cache | 300s | 300s | |
| CSP nonce | `js-nonce` request attr | `js-nonce` request attr | |

---

## Session Notes

This plugin was developed in a single session (April 23, 2026) starting from scratch. Total iterations: ~80+ build/upload cycles. Key session findings are captured above under "Critical Bugs Found and Fixed".

The metadata API approach was attempted and abandoned after discovering that `getMetaData()` returns Strings on HPE VME. The final architecture uses a JSON file for all persistence, which is simpler, faster, and version-independent.

