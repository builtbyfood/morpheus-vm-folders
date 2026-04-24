package com.morpheusdata.vmfolders

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import com.morpheusdata.model.Permission
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat

@Slf4j
class VmFoldersController implements PluginController {

    MorpheusContext morpheusContext
    Plugin plugin

    static final String DATA_DIR  = '/var/opt/morpheus/morpheus-ui/plugins'
    static final String DB_FILE   = "${DATA_DIR}/vm-folders.json"
    static final String BAK_FILE  = "${DATA_DIR}/vm-folders.json.bak"

    VmFoldersController(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override String getCode()    { 'vm-folders-controller' }
    @Override String getName()    { 'VM Folders Controller' }
    @Override Plugin getPlugin()  { plugin }
    @Override MorpheusContext getMorpheus() { morpheusContext }

    @Override
    List<Route> getRoutes() {
        def p = { String path, String method -> Route.build(path, method, Permission.build("admin-cm", "full")) }
        [
            p("/vmFolders",           "index"),
            p("/vmFolders/index",     "index"),
            p("/vmFolders/vms",       "vms"),
            p("/vmFolders/db",        "getDb"),
            p("/vmFolders/saveFolder","saveFolder"),
            p("/vmFolders/delFolder", "delFolder"),
            p("/vmFolders/renFolder", "renFolder"),
            p("/vmFolders/assign",    "assign"),
            p("/vmFolders/unassign",  "unassign"),
            p("/vmFolders/backup",    "backup"),
            p("/vmFolders/restore",   "restore"),
            p("/vmFolders/export",    "export"),
            p("/vmFolders/power",     "power")
        ]
    }

    // ── DB helpers ────────────────────────────────────────────────────
    private synchronized Map readDb() {
        try {
            def f = new File(DB_FILE)
            if (f.exists()) {
                def d = new JsonSlurper().parse(f) as Map
                if (!d.folders)     d.folders     = []
                if (!d.assignments) d.assignments = [:]
                if (!d.history)     d.history     = []
                return d
            }
        } catch(e) { log.warn("readDb: ${e.message}") }
        return [folders: [], assignments: [:], history: [], version: 1]
    }

    private synchronized void writeDb(Map data) {
        try {
            def f    = new File(DB_FILE)
            def tmp  = new File(DB_FILE + '.tmp')
            def bak  = new File(BAK_FILE)
            // Rotate backup before write
            if (f.exists()) Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            data.lastModified = new Date().toString()
            data.version = (data.version ?: 0) + 1
            tmp.text = JsonOutput.prettyPrint(JsonOutput.toJson(data))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch(e) { log.error("writeDb: ${e.message}") }
    }

    private void addHistory(Map db, String action, Map details) {
        def h = db.history as List ?: []
        h << [ts: new Date().toString(), action: action] + details
        if (h.size() > 100) h = h.drop(h.size() - 100)
        db.history = h
    }

    // ── Page ──────────────────────────────────────────────────────────
    def index(ViewModel<Map> model) {
        def nonce = ''
        try { nonce = model?.request?.getAttribute('js-nonce') ?: '' } catch(e) {}
        def html = getPageHtml(nonce)
        return HTMLResponse.success(html)
    }

    // ── API: VM list ──────────────────────────────────────────────────
    def vms(ViewModel<Map> model) {
        try {
            def db = readDb()
            def assignments = db.assignments as Map ?: [:]

            def servers = morpheusContext.services.computeServer.list(
                new DataQuery().withFilter(new DataFilter('vmHypervisor', false))
            ).toList()

            def result = servers.collect { s ->
                def ps = s.powerState
                def powerState = ps ? ps.toString() : (s.status ? s.status.toString() : 'unknown')
                def osType = ''
                try { osType = (s.osType instanceof String ? s.osType : s.osType?.name) ?: (s.serverOs instanceof String ? s.serverOs : s.serverOs?.name) ?: '' } catch(ex) {}
                def cloudName = ''
                try { cloudName = (s.cloud instanceof String ? s.cloud : s.cloud?.name) ?: '' } catch(ex) {}

                [
                    id         : s.id,
                    name       : s.name ?: "VM-${s.id}",
                    powerState : powerState,
                    externalIp : s.externalIp ?: '',
                    internalIp : s.internalIp ?: '',
                    hostname   : s.hostname ?: '',
                    osType     : osType,
                    maxMemory  : s.maxMemory ?: 0,
                    maxCores   : s.maxCores ?: 0,
                    cloudName  : cloudName,
                    folderPath : assignments[s.id?.toString()] ?: '/'
                ]
            }
            return JsonResponse.of([servers: result, meta: [total: result.size()]])
        } catch(e) {
            log.error("vms error: ${e.message}", e)
            return JsonResponse.of([error: e.message, servers: [], meta: [total: 0]])
        }
    }

    // ── API: get full DB ──────────────────────────────────────────────
    def getDb(ViewModel<Map> model) {
        return JsonResponse.of(readDb())
    }

    // ── API: save folder ──────────────────────────────────────────────
    def saveFolder(ViewModel<Map> model) {
        try {
            def path = model?.request?.getParameter('path') as String
            def desc = model?.request?.getParameter('desc') as String ?: ''
            if (!path) return JsonResponse.of([success: false, error: 'path required'])
            if (!path.startsWith('/')) path = '/' + path
            def db = readDb()
            def folders = db.folders as List ?: []
            if (!folders.find { it.path == path }) {
                folders << [path: path, desc: desc, created: new Date().toString()]
                db.folders = folders
                addHistory(db, 'create_folder', [path: path])
                writeDb(db)
            }
            return JsonResponse.of([success: true, path: path])
        } catch(e) {
            log.error("saveFolder: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: delete folder ────────────────────────────────────────────
    def delFolder(ViewModel<Map> model) {
        try {
            def path = model?.request?.getParameter('path') as String
            if (!path) return JsonResponse.of([success: false, error: 'path required'])
            def db = readDb()
            // Remove folder and all sub-folder assignments
            db.folders = (db.folders as List ?: []).findAll { it.path != path && !it.path.startsWith(path + '/') }
            def asgn = db.assignments as Map ?: [:]
            asgn.entrySet().removeIf { e -> e.value == path || e.value.startsWith(path + '/') }
            db.assignments = asgn
            addHistory(db, 'delete_folder', [path: path])
            writeDb(db)
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("delFolder: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: rename folder ────────────────────────────────────────────
    def renFolder(ViewModel<Map> model) {
        try {
            def oldPath = model?.request?.getParameter('oldPath') as String
            def newPath = model?.request?.getParameter('newPath') as String
            if (!oldPath || !newPath) return JsonResponse.of([success: false, error: 'oldPath and newPath required'])
            if (!newPath.startsWith('/')) newPath = '/' + newPath
            def db = readDb()

            // Update folder definitions
            def folders = db.folders as List ?: []
            folders.each { f ->
                if (f.path == oldPath) f.path = newPath
                else if (f.path.startsWith(oldPath + '/')) f.path = newPath + f.path.substring(oldPath.length())
            }
            db.folders = folders

            // Update assignments
            def asgn = db.assignments as Map ?: [:]
            asgn.each { k, v ->
                if (v == oldPath) asgn[k] = newPath
                else if (v.startsWith(oldPath + '/')) asgn[k] = newPath + v.substring(oldPath.length())
            }
            db.assignments = asgn
            addHistory(db, 'rename_folder', [from: oldPath, to: newPath])
            writeDb(db)
            return JsonResponse.of([success: true, newPath: newPath])
        } catch(e) {
            log.error("renFolder: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: assign VM to folder ──────────────────────────────────────
    def assign(ViewModel<Map> model) {
        try {
            def vmId = model?.request?.getParameter('vmId') as String
            def path = model?.request?.getParameter('path') as String
            if (!vmId || !path) return JsonResponse.of([success: false, error: 'vmId and path required'])
            if (!path.startsWith('/')) path = '/' + path
            def db = readDb()
            def asgn = db.assignments as Map ?: [:]
            asgn[vmId] = path
            db.assignments = asgn
            // Auto-create folder if it doesn't exist
            def folders = db.folders as List ?: []
            if (!folders.find { it.path == path }) {
                folders << [path: path, desc: '', created: new Date().toString()]
                db.folders = folders
            }
            addHistory(db, 'assign', [vmId: vmId, path: path])
            writeDb(db)
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("assign: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: remove VM from folder ────────────────────────────────────
    def unassign(ViewModel<Map> model) {
        try {
            def vmId = model?.request?.getParameter('vmId') as String
            if (!vmId) return JsonResponse.of([success: false, error: 'vmId required'])
            def db = readDb()
            def asgn = db.assignments as Map ?: [:]
            asgn.remove(vmId)
            db.assignments = asgn
            addHistory(db, 'unassign', [vmId: vmId])
            writeDb(db)
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("unassign: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: backup ───────────────────────────────────────────────────
    def backup(ViewModel<Map> model) {
        try {
            def f = new File(DB_FILE)
            def bak = new File(BAK_FILE)
            if (!f.exists()) return JsonResponse.of([success: false, error: 'No database to backup'])
            Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return JsonResponse.of([success: true, message: "Backed up to ${BAK_FILE}", size: bak.length()])
        } catch(e) {
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: restore from backup ──────────────────────────────────────
    def restore(ViewModel<Map> model) {
        try {
            def bak = new File(BAK_FILE)
            if (!bak.exists()) return JsonResponse.of([success: false, error: 'No backup file found'])
            Files.copy(bak.toPath(), Paths.get(DB_FILE), StandardCopyOption.REPLACE_EXISTING)
            return JsonResponse.of([success: true, message: 'Restored from backup'])
        } catch(e) {
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: export DB as JSON download ───────────────────────────────
    def export(ViewModel<Map> model) {
        try {
            def db = readDb()
            def json = JsonOutput.prettyPrint(JsonOutput.toJson(db))
            def stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
            def response = model?.response
            if (response) {
                response.setContentType('application/json')
                response.setHeader('Content-Disposition', "attachment; filename=\"vm-folders-${stamp}.json\"")
                response.getWriter().write(json)
                response.getWriter().flush()
                return null
            }
            return JsonResponse.of(db)
        } catch(e) {
            return JsonResponse.of([error: e.message])
        }
    }

    // ── API: power control ────────────────────────────────────────────
    def power(ViewModel<Map> model) {
        try {
            def vmId = model?.request?.getParameter('vmId') as Long
            def action = model?.request?.getParameter('action') as String
            if (!vmId || !action) return JsonResponse.of([success: false, error: 'vmId and action required'])
            def result = false
            switch(action) {
                case 'start':   result = morpheusContext.services.computeServer.startServer(vmId); break
                case 'stop':    result = morpheusContext.services.computeServer.stopServer(vmId); break
                case 'restart': result = morpheusContext.services.computeServer.restartServer(vmId); break
                default: return JsonResponse.of([success: false, error: "Unknown action: ${action}"])
            }
            return JsonResponse.of([success: result != null ? true : false, action: action, vmId: vmId])
        } catch(e) {
            log.error("power: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── Page HTML ─────────────────────────────────────────────────────
    private String getPageHtml(String nonce) {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>VM Folders | HPE Morpheus</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    :root {
      --hpe-green: #01A982; --hpe-green-dark: #008567; --hpe-header: #425563;
      --hpe-teal: #2AD2C9; --hpe-bg: #F5F5F5; --hpe-border: #CCCCCC;
      --hpe-text: #333333; --hpe-muted: #767676; --hpe-white: #FFFFFF;
      --hpe-selected: #E6F5F1; --hpe-row-hover: #F9FAFA;
    }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 14px; background: var(--hpe-bg); color: var(--hpe-text); height: 100vh; overflow: hidden; display: flex; flex-direction: column; }
    #vmf-header { display: flex; align-items: center; justify-content: space-between; padding: 0 20px; height: 48px; background: var(--hpe-header); color: var(--hpe-white); flex-shrink: 0; box-shadow: 0 2px 4px rgba(0,0,0,.2); }
    #vmf-header h1 { font-size: 15px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    #vmf-header h1::before { content:''; display:inline-block; width:4px; height:18px; background:var(--hpe-green); border-radius:2px; }
    #vmf-back { color:#B0BEC5; font-size:12px; text-decoration:none; margin-left:16px; }
    #vmf-back:hover { color:#fff; }
    #vmf-header-right { display:flex; gap:8px; align-items:center; }
    .vmf-btn { padding:5px 12px; border-radius:4px; font-size:12px; font-weight:500; cursor:pointer; white-space:nowrap; display:inline-flex; align-items:center; gap:5px; text-decoration:none; }
    .vmf-btn-outline { border:1px solid rgba(255,255,255,.4); background:transparent; color:#fff; }
    .vmf-btn-outline:hover { border-color:#fff; background:rgba(255,255,255,.1); }
    .vmf-btn-primary { border:1px solid var(--hpe-green-dark); background:var(--hpe-green); color:#fff; }
    .vmf-btn-primary:hover { background:var(--hpe-green-dark); }
    .vmf-btn-secondary { border:1px solid var(--hpe-border); background:#fff; color:var(--hpe-text); }
    .vmf-btn-secondary:hover { border-color:var(--hpe-green); color:var(--hpe-green-dark); }
    .vmf-btn-danger { border:1px solid #c00; background:#fff; color:#c00; }
    .vmf-btn-danger:hover { background:#fff5f5; }
    .vmf-btn-sm { padding:3px 8px; font-size:11px; }
    #vmf-main { display:flex; flex:1; overflow:hidden; }
    #vmf-tree { width:230px; background:#fff; border-right:1px solid var(--hpe-border); display:flex; flex-direction:column; overflow:hidden; flex-shrink:0; }
    #vmf-tree-head { padding:10px 14px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:.07em; color:var(--hpe-muted); background:var(--hpe-bg); border-bottom:1px solid var(--hpe-border); flex-shrink:0; }
    #vmf-flist { flex:1; overflow-y:auto; padding:4px 0; }
    .vmf-fi { display:flex; align-items:center; gap:7px; padding:7px 14px; cursor:pointer; border-left:3px solid transparent; font-size:13px; user-select:none; position:relative; }
    .vmf-fi:hover { background:var(--hpe-row-hover); }
    .vmf-fi.active { background:var(--hpe-selected); border-left-color:var(--hpe-green); color:var(--hpe-green-dark); font-weight:600; }
    .vmf-fi-icon { font-size:13px; flex-shrink:0; }
    .vmf-fi-name { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; }
    .vmf-fi-count { font-size:10px; background:#eee; color:var(--hpe-muted); border-radius:9px; padding:1px 6px; flex-shrink:0; }
    .vmf-fi.active .vmf-fi-count { background:#c8ede4; color:var(--hpe-green-dark); }
    .vmf-fi-actions { display:none; gap:2px; flex-shrink:0; }
    .vmf-fi:hover .vmf-fi-actions { display:flex; }
    .vmf-fi-btn { background:none; border:none; cursor:pointer; font-size:11px; color:var(--hpe-muted); padding:1px 3px; border-radius:3px; }
    .vmf-fi-btn:hover { color:var(--hpe-text); background:#eee; }
    .vmf-fi-btn.del:hover { color:#c00; background:#fff0f0; }
    .vmf-divider { height:1px; background:var(--hpe-border); margin:3px 0; opacity:.5; }
    #vmf-content { flex:1; display:flex; flex-direction:column; overflow:hidden; }
    #vmf-toolbar { display:flex; align-items:center; gap:8px; padding:8px 14px; background:#fff; border-bottom:1px solid var(--hpe-border); flex-shrink:0; }
    #vmf-search { flex:1; max-width:280px; padding:5px 10px; border:1px solid var(--hpe-border); border-radius:4px; font-size:13px; }
    #vmf-search:focus { outline:none; border-color:var(--hpe-green); }
    #vmf-bc { font-size:12px; color:var(--hpe-muted); }
    #vmf-bc b { color:var(--hpe-text); }
    #vmf-vlist { flex:1; overflow-y:auto; }
    #vmf-status { padding:5px 14px; background:var(--hpe-bg); border-top:1px solid var(--hpe-border); font-size:11px; color:var(--hpe-muted); flex-shrink:0; }
    table.vmft { width:100%; border-collapse:collapse; background:#fff; }
    table.vmft thead th { position:sticky; top:0; background:var(--hpe-bg); border-bottom:2px solid var(--hpe-border); padding:8px 12px; text-align:left; font-weight:600; font-size:11px; text-transform:uppercase; letter-spacing:.05em; color:var(--hpe-muted); cursor:pointer; user-select:none; white-space:nowrap; }
    table.vmft thead th:hover { color:var(--hpe-text); }
    table.vmft thead th.sorted { color:var(--hpe-green-dark); }
    table.vmft tbody tr { border-bottom:1px solid #f0f0f0; }
    table.vmft tbody tr:hover { background:var(--hpe-row-hover); }
    table.vmft tbody tr.sel { background:var(--hpe-selected); }
    table.vmft td { padding:8px 12px; font-size:12px; vertical-align:middle; }
    .vmft-name a { color:var(--hpe-green-dark); text-decoration:none; font-weight:500; }
    .vmft-name a:hover { text-decoration:underline; }
    .vmf-dot { display:inline-block; width:7px; height:7px; border-radius:50%; margin-right:5px; vertical-align:middle; }
    .vmf-tag { display:inline-block; background:#e6f5f1; color:var(--hpe-green-dark); border-radius:3px; padding:1px 7px; font-size:11px; font-weight:500; border:1px solid #b3e0d5; }
    .vmf-act { padding:3px 8px; font-size:11px; border:1px solid var(--hpe-border); border-radius:3px; background:#fff; cursor:pointer; color:var(--hpe-text); text-decoration:none; display:inline-flex; align-items:center; }
    .vmf-act:hover { border-color:var(--hpe-green); color:var(--hpe-green-dark); }
    .vmf-act-x { border-color:transparent; color:var(--hpe-muted); }
    .vmf-act-x:hover { border-color:#c00; color:#c00; background:#fff5f5; }
    .vmf-act-console { background:var(--hpe-green); border-color:var(--hpe-green-dark); color:#fff; }
    .vmf-act-console:hover { background:var(--hpe-green-dark); color:#fff; }
    .vmf-pw-on { color:var(--hpe-green); }
    .vmf-pw-off { color:#c00; }
    #vmf-modal { display:none; position:fixed; inset:0; background:rgba(0,0,0,.5); align-items:center; justify-content:center; z-index:9999; }
    .vmf-mbox { background:#fff; border-radius:6px; box-shadow:0 8px 32px rgba(0,0,0,.2); min-width:360px; max-width:500px; width:90vw; overflow:hidden; }
    .vmf-mhead { padding:13px 18px; background:var(--hpe-header); color:#fff; font-weight:600; font-size:14px; display:flex; align-items:center; justify-content:space-between; }
    .vmf-mx { cursor:pointer; font-size:18px; opacity:.7; line-height:1; }
    .vmf-mx:hover { opacity:1; }
    .vmf-mbody { padding:18px; }
    .vmf-mfoot { padding:10px 18px; border-top:1px solid var(--hpe-border); display:flex; gap:8px; justify-content:flex-end; background:var(--hpe-bg); }
    .vmf-fg { margin-bottom:12px; }
    .vmf-fg label { display:block; font-size:11px; font-weight:600; margin-bottom:4px; color:var(--hpe-muted); text-transform:uppercase; letter-spacing:.05em; }
    .vmf-fg input, .vmf-fg textarea { width:100%; padding:7px 10px; border:1px solid var(--hpe-border); border-radius:4px; font-size:13px; color:var(--hpe-text); font-family:inherit; }
    .vmf-fg input:focus, .vmf-fg textarea:focus { outline:none; border-color:var(--hpe-green); }
    .vmf-hint { font-size:11px; color:var(--hpe-muted); margin-top:4px; }
    #vmf-toast { position:fixed; bottom:20px; right:20px; background:var(--hpe-header); color:#fff; padding:9px 16px; border-radius:4px; font-size:12px; box-shadow:0 4px 16px rgba(0,0,0,.2); opacity:0; transition:opacity .25s; pointer-events:none; z-index:10000; border-left:4px solid var(--hpe-green); }
    #vmf-toast.err { border-left-color:#c00; }
    .vmf-spin { display:flex; align-items:center; justify-content:center; padding:50px; color:var(--hpe-muted); gap:10px; font-size:13px; }
    .vmf-spinner { width:18px; height:18px; border:2px solid var(--hpe-border); border-top-color:var(--hpe-green); border-radius:50%; animation:spin .7s linear infinite; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .vmf-empty { display:flex; flex-direction:column; align-items:center; justify-content:center; padding:50px; color:var(--hpe-muted); gap:6px; }
    .vmf-empty-icon { font-size:36px; opacity:.35; }
    .vmf-section-title { font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:.06em; color:var(--hpe-muted); padding:8px 14px 4px; }
  </style>
</head>
<body>
  <div id="vmf-header">
    <div style="display:flex;align-items:center">
      <h1>VM Folders</h1>
      <a href="/infrastructure/inventory" id="vmf-back">&#8592; Morpheus</a>
    </div>
    <div id="vmf-header-right">
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-backup-btn" title="Backup database">&#128190; Backup</button>
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-restore-btn" title="Restore from backup">&#9100; Restore</button>
      <a class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-export-btn" href="/plugin/vmFolders/export" target="_blank" title="Export database">&#8595; Export</a>
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-refresh-btn">&#8635; Refresh</button>
      <button class="vmf-btn vmf-btn-primary vmf-btn-sm" id="vmf-new-folder-btn">+ Folder</button>
    </div>
  </div>
  <div id="vmf-main">
    <div id="vmf-tree">
      <div id="vmf-tree-head">Folders</div>
      <div id="vmf-flist"><div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div></div>
    </div>
    <div id="vmf-content">
      <div id="vmf-toolbar">
        <input id="vmf-search" type="text" placeholder="Search VMs...">
        <span id="vmf-bc">&#128193; <b>All VMs</b></span>
        <button id="vmf-mv-btn" class="vmf-btn vmf-btn-primary vmf-btn-sm" style="display:none;">Move Selected</button>
      </div>
      <div id="vmf-vlist"><div class="vmf-spin"><div class="vmf-spinner"></div>Loading VMs...</div></div>
      <div id="vmf-status">Ready</div>
    </div>
  </div>
  <div id="vmf-modal">
    <div class="vmf-mbox">
      <div class="vmf-mhead"><span id="vmf-mtitle"></span><span class="vmf-mx" id="vmf-close-modal">&#215;</span></div>
      <div id="vmf-mbody" class="vmf-mbody"></div>
      <div id="vmf-mfoot" class="vmf-mfoot"></div>
    </div>
  </div>
  <div id="vmf-toast"></div>
  <script nonce="${nonce}">window.vmfApiBase='/plugin/vmFolders';</script>
  <script src="/assets/plugin/vm-folders/vmFolders.js" nonce="${nonce}"></script>
  <script nonce="${nonce}">
    document.addEventListener('DOMContentLoaded', function() {
      document.getElementById('vmf-search').addEventListener('input', function() { vmfFilter(this.value); });
      document.getElementById('vmf-refresh-btn').addEventListener('click', function() { vmfReload(); });
      document.getElementById('vmf-new-folder-btn').addEventListener('click', function() { vmfCreateFolder(); });
      document.getElementById('vmf-mv-btn').addEventListener('click', function() { vmfMoveSelected(); });
      document.getElementById('vmf-close-modal').addEventListener('click', function() { vmfCloseModal(); });
      document.getElementById('vmf-modal').addEventListener('click', function(e) { if(e.target===this) vmfCloseModal(); });
      document.addEventListener('keydown', function(e) { if(e.key==='Escape') vmfCloseModal(); });
      document.getElementById('vmf-backup-btn').addEventListener('click', async function() {
        var d = await fetch(window.vmfApiBase+'/backup',{credentials:'same-origin'}).then(r=>r.json());
        vmfToast(d.success ? 'Backup created' : 'Backup failed: '+d.error, !d.success);
      });
      document.getElementById('vmf-restore-btn').addEventListener('click', function() {
        openConfirm('Restore from backup?', 'This will overwrite current data with the last backup.', async function() {
          var d = await fetch(window.vmfApiBase+'/restore',{credentials:'same-origin'}).then(r=>r.json());
          vmfToast(d.success ? 'Restored from backup' : 'Restore failed: '+d.error, !d.success);
          if(d.success) vmfReload();
        });
      });
      vmfReload();
    });
  </script>
</body>
</html>"""
    }
}
