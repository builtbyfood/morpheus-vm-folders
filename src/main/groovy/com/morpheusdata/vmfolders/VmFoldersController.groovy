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
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
    static final String VERSION   = '1.4.1'
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
            p("/vmFolders/power",          "power"),
            p("/vmFolders/serverActions",   "serverActions"),
            p("/vmFolders/executeAction",   "executeAction"),
            p("/vmFolders/resync",          "resync"),
            p("/vmFolders/logs",      "logs"),
            // 1.4.0 datastore scan. `scan` runs a command on a host and is a mutation;
            // `scans` and `scanConfig` are read-only.
            p("/vmFolders/scan",       "scan"),
            p("/vmFolders/scans",      "scans"),
            p("/vmFolders/scanConfig", "scanConfig"),
            p("/vmFolders/scanEnable", "scanEnable")
        ]
    }

    // ── Request guards ────────────────────────────────────────────────
    // Route.method in plugin-api 1.3.0 is the controller method name, not an
    // HTTP verb, so verb enforcement has to happen here.
    //
    // CSRF: the plugin API exposes no session token accessor, so mutations are
    // protected by (a) POST only, (b) a required custom header that browsers
    // will not attach cross-origin without a CORS preflight, and (c) an
    // Origin/Referer host check when either header is present.
    static final String REQ_HEADER = 'X-VMF-Request'

    /** Returns null if the request may mutate state, otherwise a JsonResponse rejecting it. */
    private JsonResponse requireMutation(ViewModel<Map> model) {
        // On Morpheus 9.0.2 model.request is not an instance of javax.servlet.http.HttpServletRequest
        // under the plugin classloader (1.1.3 returned 400 here on every POST), so access it
        // dynamically: Groovy dispatches getMethod()/getHeader() on whatever wrapper core hands us.
        def req = model?.request
        if (req == null) return reject(model, 400, 'invalid request')
        String verb = ''
        try { verb = (req.respondsTo('getMethod') ? req.getMethod() : req.method)?.toString()?.toUpperCase() ?: '' }
        catch(ex) { log.warn("VmFolders: cannot read request method (${req.getClass().name}): ${ex.message}"); return reject(model, 400, 'invalid request') }
        if (verb != 'POST') return reject(model, 405, 'POST required')
        if (!header(req, REQ_HEADER)) return reject(model, 403, 'missing request header')
        def origin = header(req, 'Origin') ?: header(req, 'Referer')
        if (origin) {
            def originHost = ''
            try { originHost = new URI(origin).host ?: '' } catch(ex) { originHost = '' }
            def serverName = ''
            try { serverName = req.respondsTo('getServerName') ? (req.getServerName() ?: '') : '' } catch(ex) {}
            def reqHost = (header(req, 'X-Forwarded-Host') ?: header(req, 'Host') ?: serverName ?: '')
                .split(',')[0].trim().split(':')[0]
            if (!originHost || !originHost.equalsIgnoreCase(reqHost)) return reject(model, 403, 'cross-origin request rejected')
        }
        if (!model?.user?.account) return reject(model, 401, 'no authenticated user')
        return null
    }

    /** Header lookup that works on any servlet-request-like object; null if unavailable. */
    private static String header(def req, String name) {
        try { return req.respondsTo('getHeader') ? req.getHeader(name)?.toString() : null }
        catch(ex) { return null }
    }

    private JsonResponse reject(ViewModel<Map> model, int status, String msg) {
        if (status == 401) log.error("VmFolders: ViewModel.user/account is null on this request — tenant scoping cannot be enforced; refusing")
        def r = JsonResponse.of([success: false, error: msg])
        r.status = status
        return r
    }

    // ── Tenant scoping ────────────────────────────────────────────────
    private DataQuery userQuery(ViewModel<Map> model) {
        // DataQuery(UserIdentity) sets account = user.account. Scoping is not
        // guaranteed by every service impl, so callers also filter explicitly.
        return new DataQuery(model.user)
    }

    private boolean canAccess(ViewModel<Map> model, def server) {
        def acct = model?.user?.account
        if (!acct || !server) return false
        if (acct.masterAccount) return true
        return server.account?.id == acct.id
    }

    /** Loads a server and verifies the caller's tenant may act on it; null if not. */
    private def scopedServer(ViewModel<Map> model, Long id) {
        if (!id) return null
        def s = morpheusContext.services.computeServer.get(id)
        return canAccess(model, s) ? s : null
    }

    // ── DB helpers ────────────────────────────────────────────────────
    private final Object dbLock = new Object()

    private Map readDb() {
        synchronized (dbLock) { return readDbUnlocked() }
    }

    private Map readDbUnlocked() {
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

    /**
     * Atomic read-modify-write. The closure receives the current DB map and
     * returns true if it changed anything (triggers a write) or false to skip.
     */
    private Map mutateDb(Closure<Boolean> mutation) {
        synchronized (dbLock) {
            def db = readDbUnlocked()
            def changed = mutation.call(db)
            if (changed) writeDbUnlocked(db)
            return db
        }
    }

    private void writeDbUnlocked(Map data) {
        def f    = new File(DB_FILE)
        def tmp  = new File(DB_FILE + '.tmp')
        def bak  = new File(BAK_FILE)
        if (f.exists()) Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
        data.lastModified = new Date().toString()
        data.version = (data.version ?: 0) + 1
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(data))
        def fos = new FileOutputStream(tmp)
        try {
            def w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)
            w.write(json)
            w.flush()
            fos.getFD().sync()      // durable before the rename
        } finally { fos.close() }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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

    /**
     * True for a VM Morpheus inventoried from the hypervisor rather than provisioned:
     * `serverType == 'unmanaged'` or `discovered == true` (verified on 9.0.2 against a
     * host's Discovered VMs tab, 2026-09-21).
     *
     * NOT `ComputeServer.managed` — that is the agent flag. Keying off it in 1.3.0-1.3.2
     * mislabelled VMs and removed power controls from running instances.
     *
     * This drives a label only. Power and actions stay available for every VM: the
     * platform decides what it will accept, and its own error beats a hidden button.
     */
    private static boolean isUnmanaged(def s) {
        try {
            if (s.serverType?.toString()?.equalsIgnoreCase('unmanaged')) return true
            return s.discovered == true
        } catch(ex) { return false }
    }

    /** True for a hypervisor host (as opposed to a guest VM): drives a stronger power confirm. */
    private static boolean isHypervisorHost(def s) {
        try { return s.computeServerType?.vmHypervisor == true } catch(ex) { return false }
    }

    // ── Disks ─────────────────────────────────────────────────────────
    // computeServer.list(DataQuery) hydrates volumes on 9.0.2 (verified 2026-09-20),
    // so no per-server fetch is needed. Datastore may be null on VME volumes —
    // that is a real "no datastore" bucket, not an error. Sizes are bytes.
    private List<Map> diskList(def s) {
        def vols = []
        try { vols = (s.volumes ?: []).toList() } catch(ex) { return [] }
        vols = vols.sort { a, b ->
            def ra = a.rootVolume ? 0 : 1, rb = b.rootVolume ? 0 : 1
            ra <=> rb ?: ((a.displayOrder ?: 0) <=> (b.displayOrder ?: 0)) ?: ((a.id ?: 0L) <=> (b.id ?: 0L))
        }
        vols.collect { v ->
            def dsName = null
            try { def d = v.datastore; dsName = (d instanceof String ? d : d?.name)?.toString() ?: null } catch(ex) {}
            // Volume type explains why a disk has no datastore: types with
            // hasDatastore=false (CD/DVD, mounted ISO) never carry one.
            def typeName = null
            try {
                def t = v.type
                typeName = (t instanceof String ? t : (t?.displayName ?: t?.code ?: t?.volumeType))?.toString() ?: null
            } catch(ex) {}
            if (!typeName) { try { typeName = (v.volumeType ?: v.diskType)?.toString() ?: null } catch(ex) {} }
            [
                name     : (v.deviceDisplayName ?: v.name ?: '')?.toString(),
                datastore: dsName,
                type     : typeName,
                removable: v.removable ? true : false,
                total    : (v.maxStorage ?: 0L) as Long,
                used     : (v.usedStorage ?: 0L) as Long,
                root     : v.rootVolume ? true : false
            ]
        }
    }

    /** ds:<datastoreId>:<dirname> — an entry found on disk by a datastore scan. */
    private static boolean isScanKey(String key) { return key?.startsWith('ds:') }

    /**
     * A scan key is usable only when the caller can see that datastore and the
     * directory appeared in its cached scan. Keeps scan keys tenant-scoped and stops
     * arbitrary strings being written into the assignments map.
     */
    private boolean scanKeyVisible(ViewModel<Map> model, String key) {
        try {
            def parts = key.split(':', 3)
            if (parts.size() != 3 || !parts[1].isLong() || !parts[2]) return false
            if (!scannableDatastores(model).any { it.id?.toString() == parts[1] }) return false
            def cached = readDb()?.scans?.get(parts[1])
            return cached?.vmDirs?.any { it.name?.toString() == parts[2] } ? true : false
        } catch(ex) { return false }
    }

    // ══ Datastore scan (1.4.0) ═════════════════════════════════════════
    // Reads what is actually on a datastore and reports directories and volume
    // files that Morpheus does not know about. Read-only: it runs `find`, `test`
    // and `head` on a host and never writes to the datastore.
    //
    // Verified on Morpheus 9.0.2 / VME (2026-09-22):
    //  - executeCommandOnServer(server, cmd) 2-arg runs through the Morpheus agent
    //    in ~50-80 ms. Do not use the long overloads.
    //  - stdout arrives in TaskResult.data (String); TaskResult.output is always null
    //    on the agent path.
    //  - the agent runs as morpheus-node (uid 996, groups libvirt/kvm/libvirt-qemu).
    //    Whether it can read a mount-based store depends on that store's ownership and
    //    any NFS squash: a store it cannot read is reported per-store as not readable,
    //    never as a failed scan.
    //  - the path comes from Datastore.externalPath and is allow-listed below.

    static final String SCAN_SETTING = 'scanEnabled'
    static final int    SCAN_TIMEOUT_SEC = 30
    /** /mnt/<uuid> (NFS, GFS2) or /var/morpheus/kvm/<name> (host-local directory). */
    static final java.util.regex.Pattern SCAN_PATH_OK = ~/^(\/mnt\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\/var\/morpheus\/kvm\/[a-z0-9-]+)$/
    static final List<String> SHARED_TYPES = ['libvirt-netfs-nfs', 'libvirt-dir-gfs2']

    /** Scanning is off unless an admin turned it on. Fail closed on any error. */
    private boolean scanEnabled() {
        try {
            def raw = morpheusContext.getSettings(plugin)?.blockingGet()
            if (raw) {
                def parsed = new JsonSlurper().parseText(raw as String)
                def v = (parsed instanceof Map) ? parsed[SCAN_SETTING] : null
                if (v?.toString()?.equalsIgnoreCase('true') || v == true) return true
            }
        } catch(ex) { log.debug("VmFolders: plugin settings unreadable (${ex.message}); falling back to the local flag") }
        // Fallback for appliances where the plugin settings form is unavailable: a flag
        // in the plugin's own database, writable only through the permission-gated
        // scanEnable route. Both default to off.
        try { return readDb()?.settings?.get(SCAN_SETTING) == true } catch(ex) { return false }
    }

    /** Datastores the caller may see, minus Morpheus-internal and offline stores. */
    private List scannableDatastores(ViewModel<Map> model) {
        def stores = morpheusContext.services.cloud.datastore.list(userQuery(model))?.toList() ?: []
        return stores.findAll { d ->
            try {
                if (d.online == false) return false
                if (d.externalId?.toString()?.startsWith('morpheus-')) return false
                return d.externalPath && SCAN_PATH_OK.matcher(d.externalPath.toString()).matches()
            } catch(ex) { return false }
        }
    }

    private static boolean isSharedStore(def d) {
        try { return SHARED_TYPES.contains(d.datastoreType?.code?.toString()) } catch(ex) { return false }
    }

    /**
     * Which host to run on: a shared store can be scanned from any online hypervisor
     * host; a host-local store must be scanned on the host that owns it.
     */
    private def scanHostFor(ViewModel<Map> model, def store) {
        def hosts = morpheusContext.services.computeServer.list(userQuery(model)).toList().findAll {
            canAccess(model, it) && isHypervisorHost(it)
        }
        if (!isSharedStore(store) && store.hostId) {
            return hosts.find { it.id == store.hostId }
        }
        return hosts.find { it.powerState?.toString()?.toLowerCase()?.contains('on') } ?: hosts.find()
    }

    /** One agent command. Returns stdout (TaskResult.data) or null when it failed. */
    private String runOnHost(def host, String cmd) {
        try {
            def r = morpheusContext.executeCommandOnServer(host, cmd)
                        .timeout(SCAN_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                        .blockingGet()
            if (r?.success != true) {
                log.warn("VmFolders scan: command failed on ${host?.name}: exitCode=${r?.exitCode} error=${r?.error}")
                return null
            }
            // stdout is in `data` on the agent path; fall back to `output` for any
            // platform where that is not true.
            def out = r?.data ?: r?.output
            return out != null ? out.toString() : ''
        } catch(java.util.concurrent.TimeoutException tx) {
            throw tx
        } catch(ex) {
            log.warn("VmFolders scan: command threw on ${host?.name}: ${ex.class.name}: ${ex.message}")
            return null
        }
    }

    /** GET /scanConfig — is scanning enabled, and which datastores can be scanned. */
    def scanConfig(ViewModel<Map> model) {
        try {
            if (!model?.user?.account) return reject(model, 401, 'no authenticated user')
            def enabled = scanEnabled()
            def stores = enabled ? scannableDatastores(model).collect { d ->
                [ id: d.id, name: d.name, path: d.externalPath?.toString(),
                  type: d.datastoreType?.code?.toString(), shared: isSharedStore(d) ]
            } : []
            return JsonResponse.of([success: true, enabled: enabled, datastores: stores])
        } catch(e) {
            log.error("scanConfig error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message, enabled: false, datastores: []])
        }
    }

    /** POST /scanEnable — admin switch for appliances without the plugin settings form. */
    def scanEnable(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def on = model?.request?.getParameter('enabled')?.toString() == 'true'
            mutateDb { Map db ->
                def settings = db.settings instanceof Map ? db.settings : [:]
                settings[SCAN_SETTING] = on
                db.settings = settings
                return true
            }
            log.info("VmFolders scan: scanning ${on ? 'ENABLED' : 'disabled'} by user=${model?.user?.username}")
            return JsonResponse.of([success: true, enabled: on])
        } catch(e) {
            log.error("scanEnable error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    /** GET /scans — cached scan results (all, or one datastore via ?datastoreId=). */
    def scans(ViewModel<Map> model) {
        try {
            if (!model?.user?.account) return reject(model, 401, 'no authenticated user')
            def all = readDb()?.scans instanceof Map ? readDb().scans : [:]
            def visible = scannableDatastores(model)*.id*.toString() as Set
            def dsId = model?.request?.getParameter('datastoreId')?.toString()
            def out = [:]
            all.each { k, v -> if (visible.contains(k.toString()) && (!dsId || dsId == k.toString())) out[k] = v }
            return JsonResponse.of([success: true, scans: out])
        } catch(e) {
            log.error("scans error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message, scans: [:]])
        }
    }

    /** POST /scan?datastoreId=N — run the scan and cache the result. */
    def scan(ViewModel<Map> model) {
        def started = System.currentTimeMillis()
        try {
            def denied = requireMutation(model); if (denied) return denied
            if (!scanEnabled()) return reject(model, 403, 'datastore scanning is disabled')

            def dsId = model?.request?.getParameter('datastoreId')?.toString()
            if (!dsId?.isLong()) return JsonResponse.of([success: false, error: 'datastoreId required'])
            def store = scannableDatastores(model).find { it.id?.toString() == dsId }
            if (!store) return reject(model, 404, 'datastore not found')

            def path = store.externalPath.toString()
            if (!SCAN_PATH_OK.matcher(path).matches()) return reject(model, 400, 'datastore path is not scannable')

            def host = scanHostFor(model, store)
            if (!host) return reject(model, 409, 'no host available to scan this datastore')

            def result = scanStore(model, store, host, path)
            result.at = new Date().toString()
            result.scannedOn = host.name?.toString()
            result.scannedOnId = host.id
            result.datastoreId = store.id
            result.datastoreName = store.name?.toString()
            result.path = path

            mutateDb { Map db ->
                def scansMap = db.scans instanceof Map ? db.scans : [:]
                scansMap[dsId] = result
                db.scans = scansMap
                return true
            }
            def ms = System.currentTimeMillis() - started
            log.info("VmFolders scan: user=${model?.user?.username} datastore=${store.name}(${dsId}) host=${host.name} " +
                     "ms=${ms} readable=${result.readable} vmDirs=${result.vmDirs?.size()} diskDirs=${result.diskDirs?.size()} " +
                     "volumes=${result.volumes?.size()} other=${result.other?.size()}")
            return JsonResponse.of([success: true, scan: result])
        } catch(java.util.concurrent.TimeoutException tx) {
            log.warn("VmFolders scan: timed out after ${SCAN_TIMEOUT_SEC}s")
            return JsonResponse.of([success: false, error: "scan timed out after ${SCAN_TIMEOUT_SEC}s", timedOut: true])
        } catch(e) {
            log.error("scan error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    /** The scan itself: readability check, one find pass, one bounded XML pass. */
    private Map scanStore(ViewModel<Map> model, def store, def host, String path) {
        def out = [readable: true, vmDirs: [], diskDirs: [], volumes: [], other: []]

        // 1. Can the agent user read this store at all? A store it cannot read is a
        //    per-store result, not an error — ownership and NFS squash vary per site.
        def probe = runOnHost(host, "test -r '${path}' && test -x '${path}' && echo VMF_OK || echo VMF_DENIED")
        if (probe == null) { out.readable = false; out.error = 'command failed on host'; return out }
        if (!probe.contains('VMF_OK')) {
            out.readable = false
            out.error = "not readable by the agent user (morpheus-node) on ${host.name}"
            return out
        }

        // 2. One bounded find: depth <= 2, regular files only, either >1MB or *.xml.
        def findCmd = "find '${path}' -mindepth 1 -maxdepth 2 \\( -type f -size +1M -o -type f -name '*.xml' \\) " +
                      "-printf '%d\\t%y\\t%s\\t%TY-%Tm-%Td\\t%p\\n' 2>/dev/null"
        def findOut = runOnHost(host, findCmd)
        if (findOut == null) { out.readable = false; out.error = 'find failed on host'; return out }

        // 3. Which XMLs are libvirt domains: first 512 bytes, tested on the host.
        def xmlCmd = "find '${path}' -mindepth 1 -maxdepth 2 -type f -name '*.xml' 2>/dev/null | while read f; do " +
                     "if head -c 512 \"\$f\" | grep -q '<domain'; then echo \"\$f\"; fi; done"
        def domainOut = runOnHost(host, xmlCmd) ?: ''
        def domainXmls = domainOut.readLines()*.trim().findAll { it } as Set

        parseScan(out, path, findOut, domainXmls)
        annotateScan(model, out)
        return out
    }

    /**
     * A directory holding several domain XMLs, or XMLs that do not share the directory's
     * name, is a backup or collection dump rather than one VM (e.g. `converted/` with 14
     * `ova-test_backup_*.xml`). Label it; the scan cannot know more than that.
     */
    private static String dirKind(Map e) {
        def xmls = e.domainXmls ?: []
        if (xmls.size() > 3) return 'collection'
        def dirName = e.name?.toString()?.toLowerCase()
        def matches = xmls.any { x ->
            def base = x.toString().toLowerCase().replaceAll(/\.xml$/, '')
            base == dirName || base.startsWith(dirName + '-') || base.startsWith(dirName + '_')
        }
        return matches ? 'vm' : 'collection'
    }

    /** Turns find output into vmDirs / diskDirs / volumes / other. */
    private void parseScan(Map out, String path, String findOut, Set domainXmls) {
        def dirs = [:]      // directory name -> entry
        def flat = []       // depth-1 files
        findOut.readLines().each { line ->
            def f = line.split('\t')
            if (f.size() < 5) return
            def depth = f[0]?.trim()?.isInteger() ? f[0].trim() as Integer : 0
            def size  = f[2]?.trim()?.isLong() ? f[2].trim() as Long : 0L
            def mtime = f[3]?.trim()
            def full  = f[4]
            def rel   = full.startsWith(path + '/') ? full.substring(path.length() + 1) : full
            if (depth == 1) {
                flat << [name: rel, size: size, mtime: mtime, full: full]
            } else {
                def dirName = rel.contains('/') ? rel.substring(0, rel.indexOf('/')) : rel
                def fileName = rel.contains('/') ? rel.substring(rel.indexOf('/') + 1) : rel
                def e = dirs[dirName]
                if (!e) { e = [name: dirName, xmlFiles: [], domainXmls: [], diskFiles: [], totalBytes: 0L, newest: null, isVm: false]; dirs[dirName] = e }
                if (fileName.toLowerCase().endsWith('.xml')) {
                    e.xmlFiles << fileName
                    if (domainXmls.contains(full)) { e.isVm = true; e.domainXmls << fileName }
                } else {
                    // Disk images on VME are extension-less regular files over 1MB,
                    // which is what the find filter already selected for.
                    e.diskFiles << [name: fileName, size: size, mtime: mtime]
                    e.totalBytes += size
                }
                if (!e.newest || (mtime && mtime > e.newest)) e.newest = mtime
            }
        }

        // A directory holding a libvirt domain XML is a VM. One holding disk-sized files
        // but no domain XML is reported separately rather than buried in Other: a
        // directory with a 30 GB disk and no XML is exactly what an operator wants to see.
        // Everything else at depth 2 is clutter (extracted OVAs, zips, backups).
        dirs.values().each { e ->
            if (e.isVm) {
                out.vmDirs << [name: e.name, xmlFiles: e.xmlFiles, diskFiles: e.diskFiles,
                               totalBytes: e.totalBytes, newest: e.newest, inMorpheus: null,
                               kind: dirKind(e)]
            } else if (e.diskFiles) {
                out.diskDirs << [name: e.name, diskFiles: e.diskFiles, xmlFiles: e.xmlFiles,
                                 totalBytes: e.totalBytes, newest: e.newest, inMorpheus: null]
            } else {
                out.other << [name: e.name, type: 'dir', size: e.totalBytes,
                              files: e.xmlFiles.size() + e.diskFiles.size()]
            }
        }

        // Flat files named like a uuid are Morpheus-provisioned volumes; the rest is clutter.
        def uuidRe = ~/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
        flat.each { f ->
            if (uuidRe.matcher(f.name).matches()) {
                out.volumes << [uuid: f.name, size: f.size, mtime: f.mtime, full: f.full, volumeId: null, serverId: null]
            } else {
                out.other << [name: f.name, type: 'file', size: f.size]
            }
        }
        out.vmDirs = out.vmDirs.sort { it.name }
        out.diskDirs = out.diskDirs.sort { it.name }
        out.volumes = out.volumes.sort { it.uuid }
        out.other = out.other.sort { it.name }
    }

    /**
     * Marks which findings Morpheus already knows about: a VM directory whose name
     * matches a scoped VM, and a uuid file matching a StorageVolume on one of them.
     * Informational — a managed VM's own directory is not an orphan.
     */
    private void annotateScan(ViewModel<Map> model, Map out) {
        try {
            def servers = morpheusContext.services.computeServer.list(
                userQuery(model).withFilter(new DataFilter('vmHypervisor', false))
            ).toList().findAll { canAccess(model, it) }

            def byName = [:]
            servers.each { s -> if (s.name) byName[s.name.toString().toLowerCase()] = s.id }
            out.vmDirs.each { d -> d.inMorpheus = byName[d.name?.toString()?.toLowerCase()] }
            out.diskDirs.each { d -> d.inMorpheus = byName[d.name?.toString()?.toLowerCase()] }

            // Index every identifier form a volume file's name could take. 1.4.0 indexed
            // only the raw values of four fields and looked up by full path or basename,
            // which missed every volume on cargo-bay: the stored form and the on-disk
            // name differ (path vs basename, and an id may carry a suffix). So index each
            // value AND its basename, lowercased, and look up the same way.
            def volIndex = [:]
            def indexKey = { String k, Map hit ->
                if (!k) return
                def lower = k.toLowerCase()
                if (!volIndex.containsKey(lower)) volIndex[lower] = hit
                def base = lower.contains('/') ? lower.substring(lower.lastIndexOf('/') + 1) : lower
                if (base && !volIndex.containsKey(base)) volIndex[base] = hit
            }
            servers.each { s ->
                try {
                    (s.volumes ?: []).each { v ->
                        def hit = [volumeId: v.id, serverId: s.id]
                        [v.volumePath, v.uuid, v.externalId, v.internalId, v.uniqueId, v.name].each { val ->
                            indexKey(val?.toString(), hit)
                        }
                    }
                } catch(ex) {}
            }
            out.volumes.each { vol ->
                def full = vol.full?.toString()?.toLowerCase()
                def base = vol.uuid?.toString()?.toLowerCase()
                def hit = (full ? volIndex[full] : null) ?: (base ? volIndex[base] : null)
                if (hit) { vol.volumeId = hit.volumeId; vol.serverId = hit.serverId }
            }

            // TEMPORARY 1.4.1 diagnostic — remove once orphan matching is confirmed.
            // Logs what is still unmatched next to the identifier forms actually stored
            // for volumes on this datastore, so a remaining mismatch names itself.
            def unmatched = out.volumes.findAll { !it.volumeId }
            if (unmatched) {
                log.info("VmFolders scan/match: ${unmatched.size()} unmatched volume file(s) on ${out.datastoreName ?: out.path}: " +
                         unmatched.take(20).collect { it.uuid }.join(', '))
                int logged = 0
                servers.each { s ->
                    if (logged >= 40) return
                    try {
                        (s.volumes ?: []).each { v ->
                            if (logged >= 40) return
                            def dsName = null
                            try { def d = v.datastore; dsName = (d instanceof String ? d : d?.name)?.toString() } catch(ig) {}
                            if (out.datastoreName && dsName && dsName != out.datastoreName) return
                            logged++
                            log.info("VmFolders scan/match: server=${s.name} volumeId=${v.id} datastore=${dsName} " +
                                     "volumePath=[${v.volumePath}] uuid=[${v.uuid}] externalId=[${v.externalId}] " +
                                     "internalId=[${v.internalId}] uniqueId=[${v.uniqueId}] name=[${v.name}] " +
                                     "deviceDisplayName=[${v.deviceDisplayName}]")
                        }
                    } catch(ex) {}
                }
            }
        } catch(ex) { log.warn("VmFolders scan: annotate failed: ${ex.message}") }
    }

    // ── API: VM list ──────────────────────────────────────────────────
    def vms(ViewModel<Map> model) {
        try {
            if (!model?.user?.account) return reject(model, 401, 'no authenticated user')
            def db = readDb()
            def assignments = db.assignments as Map ?: [:]

            def servers = morpheusContext.services.computeServer.list(
                userQuery(model).withFilter(new DataFilter('vmHypervisor', false))
            ).toList().findAll { canAccess(model, it) }

            def result = servers.collect { s ->
                def ps = s.powerState
                def powerState = ps ? ps.toString() : (s.status ? s.status.toString() : 'unknown')
                def osType = ''
                try {
                    def osRaw = (s.osType instanceof String ? s.osType : s.osType?.name) ?: (s.serverOs instanceof String ? s.serverOs : s.serverOs?.name) ?: ''
                    osType = (osRaw.contains('@') || osRaw.contains('com.morpheus')) ? '' : osRaw
                } catch(ex) {}
                def cloudName = ''
                try { cloudName = (s.cloud instanceof String ? s.cloud : s.cloud?.name) ?: '' } catch(ex) {}
                def disks = diskList(s)

                [
                    id         : s.id,
                    name       : s.name ?: "VM-${s.id}",
                    powerState : powerState,
                    externalIp : s.externalIp ?: '',
                    internalIp : s.internalIp ?: '',
                    hostname   : s.hostname ?: '',
                    osType     : osType,
                    hostId     : s.parentServer?.id?.toString() ?: '',
                    hostName   : (s.parentServer?.name instanceof String ? s.parentServer?.name : s.parentServer?.name?.toString()) ?: '',
                    hostIp     : s.parentServer?.sshHost ?: s.parentServer?.externalIp ?: '',
                    maxMemory  : s.maxMemory ?: 0,
                    maxCores   : s.maxCores ?: 0,
                    cloudName  : cloudName,
                    folderPath : assignments[s.id?.toString()] ?: '/',
                    disks      : disks,
                    datastores : disks*.datastore.findAll { it }.unique().sort(),
                    unmanaged  : isUnmanaged(s),
                    isHost     : isHypervisorHost(s)
                ]
            }
            return JsonResponse.of([servers: result, meta: [total: result.size(), unmanaged: result.count { it.unmanaged }]])
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
            def denied = requireMutation(model); if (denied) return denied
            def path = model?.request?.getParameter('path') as String
            def desc = model?.request?.getParameter('desc') as String ?: ''
            if (!path) return JsonResponse.of([success: false, error: 'path required'])
            if (!path.startsWith('/')) path = '/' + path
            def fpath = path
            mutateDb { Map db ->
                def folders = db.folders as List ?: []
                if (folders.find { it.path == fpath }) return false
                folders << [path: fpath, desc: desc, created: new Date().toString()]
                db.folders = folders
                addHistory(db, 'create_folder', [path: fpath])
                return true
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
            def denied = requireMutation(model); if (denied) return denied
            def path = model?.request?.getParameter('path') as String
            if (!path) return JsonResponse.of([success: false, error: 'path required'])
            mutateDb { Map db ->
                // Remove folder and all sub-folder assignments
                db.folders = (db.folders as List ?: []).findAll { it.path != path && !it.path.startsWith(path + '/') }
                def asgn = db.assignments as Map ?: [:]
                asgn.entrySet().removeIf { e -> e.value == path || e.value.startsWith(path + '/') }
                db.assignments = asgn
                addHistory(db, 'delete_folder', [path: path])
                return true
            }
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("delFolder: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: rename folder ────────────────────────────────────────────
    def renFolder(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def oldPath = model?.request?.getParameter('oldPath') as String
            def newPath = model?.request?.getParameter('newPath') as String
            if (!oldPath || !newPath) return JsonResponse.of([success: false, error: 'oldPath and newPath required'])
            if (!newPath.startsWith('/')) newPath = '/' + newPath
            def np = newPath
            mutateDb { Map db ->
                // Update folder definitions
                def folders = db.folders as List ?: []
                folders.each { f ->
                    if (f.path == oldPath) f.path = np
                    else if (f.path.startsWith(oldPath + '/')) f.path = np + f.path.substring(oldPath.length())
                }
                db.folders = folders

                // Update assignments
                def asgn = db.assignments as Map ?: [:]
                asgn.each { k, v ->
                    if (v == oldPath) asgn[k] = np
                    else if (v.startsWith(oldPath + '/')) asgn[k] = np + v.substring(oldPath.length())
                }
                db.assignments = asgn
                addHistory(db, 'rename_folder', [from: oldPath, to: np])
                return true
            }
            return JsonResponse.of([success: true, newPath: newPath])
        } catch(e) {
            log.error("renFolder: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: assign VM to folder ──────────────────────────────────────
    def assign(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def vmId = model?.request?.getParameter('vmId') as String
            def path = model?.request?.getParameter('path') as String
            if (!vmId || !path) return JsonResponse.of([success: false, error: 'vmId and path required'])
            // Two key shapes: a numeric compute server id, or an on-disk entry found by a
            // datastore scan, keyed ds:<datastoreId>:<dirname>. Server ids keep the strict
            // isLong() check; scan keys are validated separately and never reach a
            // platform call, so isLong() is NOT relaxed.
            if (isScanKey(vmId)) {
                if (!scanKeyVisible(model, vmId)) return reject(model, 404, 'datastore entry not found')
            } else {
                if (!vmId.isLong()) return JsonResponse.of([success: false, error: 'invalid vmId'])
                if (!scopedServer(model, vmId as Long)) return reject(model, 404, 'server not found')
            }
            if (!path.startsWith('/')) path = '/' + path
            def fpath = path
            mutateDb { Map db ->
                def asgn = db.assignments as Map ?: [:]
                asgn[vmId] = fpath
                db.assignments = asgn
                // Auto-create folder if it doesn't exist
                def folders = db.folders as List ?: []
                if (!folders.find { it.path == fpath }) {
                    folders << [path: fpath, desc: '', created: new Date().toString()]
                    db.folders = folders
                }
                addHistory(db, 'assign', [vmId: vmId, path: fpath])
                return true
            }
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("assign: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: remove VM from folder ────────────────────────────────────
    def unassign(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def vmId = model?.request?.getParameter('vmId') as String
            if (!vmId) return JsonResponse.of([success: false, error: 'vmId required'])
            if (isScanKey(vmId)) {
                if (!scanKeyVisible(model, vmId)) return reject(model, 404, 'datastore entry not found')
                def removedKey = false
                mutateDb { Map db ->
                    def asgn = db.assignments as Map ?: [:]
                    if (!asgn.containsKey(vmId)) return false
                    asgn.remove(vmId); db.assignments = asgn; removedKey = true
                    addHistory(db, 'unassign', [vmId: vmId])
                    return true
                }
                return JsonResponse.of([success: true, removed: removedKey])
            }
            if (!vmId.isLong()) return JsonResponse.of([success: false, error: 'invalid vmId'])
            // Allow clearing stale assignments for VMs no longer in Morpheus,
            // but never touch a VM that exists and belongs to another tenant.
            def existing = morpheusContext.services.computeServer.get(vmId as Long)
            if (existing && !canAccess(model, existing)) return reject(model, 404, 'server not found')
            mutateDb { Map db ->
                def asgn = db.assignments as Map ?: [:]
                if (!asgn.containsKey(vmId)) return false
                asgn.remove(vmId)
                db.assignments = asgn
                addHistory(db, 'unassign', [vmId: vmId])
                return true
            }
            return JsonResponse.of([success: true])
        } catch(e) {
            log.error("unassign: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: backup ───────────────────────────────────────────────────
    def backup(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def f = new File(DB_FILE)
            def bak = new File(BAK_FILE)
            if (!f.exists()) return JsonResponse.of([success: false, error: 'No database to backup'])
            synchronized (dbLock) { Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return JsonResponse.of([success: true, message: "Backed up to ${BAK_FILE}", size: bak.length()])
        } catch(e) {
            return JsonResponse.of([success: false, error: e.message])
        }
    }

    // ── API: restore from backup ──────────────────────────────────────
    def restore(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def bak = new File(BAK_FILE)
            if (!bak.exists()) return JsonResponse.of([success: false, error: 'No backup file found'])
            synchronized (dbLock) {
                // Parse first so a corrupt backup cannot replace a good DB.
                def parsed = new JsonSlurper().parse(bak)
                if (!(parsed instanceof Map)) return JsonResponse.of([success: false, error: 'Backup is not a valid database'])
                Files.copy(bak.toPath(), Paths.get(DB_FILE), StandardCopyOption.REPLACE_EXISTING)
            }
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


    // ── API: log collection ───────────────────────────────────────────
    def logs(ViewModel<Map> model) {
        try {
            def lines  = (model?.request?.getParameter('lines') ?: '200') as Integer
            def filter = model?.request?.getParameter('filter') ?: 'VmFolder'
            def logFile = new File('/var/log/morpheus/morpheus-ui/current')
            def result = []
            if (logFile.exists()) {
                logFile.withReader { reader ->
                    def all = reader.readLines()
                    result = filter == 'ALL' ? all : all.findAll { it.contains(filter) }
                    if (result.size() > lines) result = result.takeRight(lines)
                }
            }
            def db = readDb()
            return JsonResponse.of([
                success    : true,
                lines      : result,
                lineCount  : result.size(),
                filter     : filter,
                dbVersion  : db.version,
                dbModified : db.lastModified,
                dbFolders  : (db.folders as List)?.size() ?: 0,
                dbAssigned : (db.assignments as Map)?.size() ?: 0
            ])
        } catch(e) {
            return JsonResponse.of([success: false, error: e.message, lines: []])
        }
    }

    // ── API: power control ────────────────────────────────────────────
    def power(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def vmId = model?.request?.getParameter('vmId') as Long
            def action = model?.request?.getParameter('action') as String
            if (!vmId || !action) return JsonResponse.of([success: false, error: 'vmId and action required'])
            if (!scopedServer(model, vmId)) return reject(model, 404, 'server not found')
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
    body.dark {
      --hpe-bg:         #1A1F2B;
      --hpe-white:      #242B38;
      --hpe-border:     #3A4458;
      --hpe-text:       #E0E6F0;
      --hpe-muted:      #8899B0;
      --hpe-selected:   #1A3A30;
      --hpe-row-hover:  #1E2535;
      --hpe-header:     #2C3547;
    }
    /* Dark mode overrides for hardcoded backgrounds */
    body.dark #vmf-tree,
    body.dark #vmf-content,
    body.dark #vmf-toolbar,
    body.dark #vmf-tree-head,
    body.dark table.vmft,
    body.dark table.vmft thead th,
    body.dark table.vmft tbody tr,
    body.dark .vmf-mbox,
    body.dark .vmf-mbody,
    body.dark .vmf-mfoot,
    body.dark .vmf-fg input,
    body.dark .vmf-fg input:disabled { background: var(--hpe-white); color: var(--hpe-text); }
    body.dark table.vmft tbody tr:hover { background: var(--hpe-row-hover); }
    body.dark table.vmft tbody tr.sel  { background: var(--hpe-selected); }
    body.dark #vmf-ds-head, body.dark tr.vmf-disk-row, body.dark tr.vmf-disk-row:hover { background: var(--hpe-bg); }
    body.dark .vmf-badge-un { background:#3A2E1A; color:#E0B870; border-color:#5A4828; }
    body.dark .vmf-scan-panel { background: var(--hpe-white); }
    body.dark .vmf-scan-head { background: var(--hpe-bg); }
    body.dark .vmf-scan-warn { background:#3A2E1A; color:#E0B870; border-color:#5A4828; }
    body.dark #vmf-search { background: var(--hpe-white); color: var(--hpe-text); border-color: var(--hpe-border); }
    body.dark .vmf-act   { background: var(--hpe-white); color: var(--hpe-text); border-color: var(--hpe-border); }
    body.dark .vmf-act:hover { border-color: var(--hpe-green); color: var(--hpe-green); }
    body.dark .vmf-fi    { color: var(--hpe-text); }
    body.dark .vmf-fi:hover { background: var(--hpe-row-hover); }
    body.dark .vmf-fi.active { background: var(--hpe-selected); color: var(--hpe-green); }
    body.dark .vmf-fi-count  { background: #2E3A4E; color: var(--hpe-muted); }
    body.dark .vmf-fi.active .vmf-fi-count { background: #1A3A30; color: var(--hpe-green); }
    body.dark .vmft-name a  { color: var(--hpe-green); }
    body.dark .vmf-tag  { background: #1A3A30; color: var(--hpe-green); border-color: #2A5A48; }
    body.dark .vmf-btn-secondary { background: var(--hpe-white); color: var(--hpe-text); border-color: var(--hpe-border); }
    body.dark .vmf-btn-secondary:hover { border-color: var(--hpe-green); color: var(--hpe-green); }
    body.dark .vmf-mhead { background: #1A2030; }
    body.dark #vmf-modal { background: rgba(0,0,0,.7); }
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
    #vmf-ds-head { padding:8px 14px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:.07em; color:var(--hpe-muted); background:var(--hpe-bg); border-top:1px solid var(--hpe-border); border-bottom:1px solid var(--hpe-border); flex-shrink:0; }
    #vmf-dslist { max-height:38%; overflow-y:auto; padding:4px 0; flex-shrink:0; }
    .vmf-chev { display:inline-block; width:14px; cursor:pointer; color:var(--hpe-muted); font-size:10px; user-select:none; margin-right:2px; transition:transform .15s; }
    .vmf-chev.open { transform:rotate(90deg); }
    .vmf-chev-none { display:inline-block; width:14px; margin-right:2px; }
    tr.vmf-disk-row { background:var(--hpe-bg); }
    tr.vmf-disk-row:hover { background:var(--hpe-bg); }
    tr.vmf-disk-row > td { padding:4px 12px 10px 48px; }
    table.vmft-sub { border-collapse:collapse; font-size:11px; min-width:420px; }
    table.vmft-sub th { text-align:left; font-weight:600; color:var(--hpe-muted); text-transform:uppercase; letter-spacing:.04em; font-size:10px; padding:3px 10px 3px 0; border-bottom:1px solid var(--hpe-border); }
    table.vmft-sub td { padding:3px 10px 3px 0; color:var(--hpe-text); }
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
    .vmf-scan-panel { margin:14px; border:1px solid var(--hpe-border); border-radius:4px; background:#fff; }
    .vmf-scan-head { display:flex; align-items:center; gap:10px; padding:8px 12px; border-bottom:1px solid var(--hpe-border); background:var(--hpe-bg); font-size:12px; }
    .vmf-scan-head b { font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--hpe-muted); }
    .vmf-scan-meta { color:var(--hpe-muted); font-size:11px; margin-right:auto; }
    .vmf-scan-sect { padding:9px 12px 4px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:.05em; color:var(--hpe-muted); display:flex; align-items:center; gap:7px; }
    .vmf-scan-empty { padding:0 12px 10px; font-size:12px; color:var(--hpe-muted); }
    .vmf-scan-warn { padding:10px 12px; font-size:12px; color:#8a5300; background:#fff3e0; border-top:1px solid #f0c890; }
    table.vmf-scan-tbl { width:calc(100% - 24px); margin:0 12px 10px; }
    table.vmf-scan-tbl th { border-bottom:1px solid var(--hpe-border); }
    .vmf-scan-other { margin:0 12px 12px; font-size:12px; }
    .vmf-scan-other summary { cursor:pointer; color:var(--hpe-muted); font-size:11px; text-transform:uppercase; letter-spacing:.05em; font-weight:600; padding:4px 0; }
    .vmf-badge-un { display:inline-block; background:#fff3e0; color:#8a5300; border:1px solid #f0c890; border-radius:3px; padding:0 5px; font-size:10px; font-weight:600; text-transform:uppercase; letter-spacing:.03em; vertical-align:middle; }
    .vmf-tag { display:inline-block; background:#e6f5f1; color:var(--hpe-green-dark); border-radius:3px; padding:1px 7px; font-size:11px; font-weight:500; border:1px solid #b3e0d5; }
    .vmf-act { padding:3px 8px; font-size:11px; border:1px solid var(--hpe-border); border-radius:3px; background:#fff; cursor:pointer; color:var(--hpe-text); text-decoration:none; display:inline-flex; align-items:center; }
    .vmf-act:hover { border-color:var(--hpe-green); color:var(--hpe-green-dark); }
    .vmf-act-x { border-color:transparent; color:var(--hpe-muted); }
    .vmf-act-x:hover { border-color:#c00; color:#c00; background:#fff5f5; }
    .vmf-act-console { background:var(--hpe-green); border-color:var(--hpe-green-dark); color:#fff; }
    .vmf-start-btn { background:#01A982 !important; border-color:#008567 !important; color:#fff !important; }
    .vmf-stop-btn  { background:#c00    !important; border-color:#a00    !important; color:#fff !important; }
    .vmf-start-btn:hover { background:#008567 !important; }
    .vmf-stop-btn:hover  { background:#a00    !important; }
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
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-theme-btn" title="Toggle dark/light mode">&#9681;</button>
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-logs-btn" title="View plugin logs">&#128196; Logs</button>
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-refresh-btn">&#8635; Refresh</button>
      <button class="vmf-btn vmf-btn-primary vmf-btn-sm" id="vmf-new-folder-btn">+ Folder</button>
    </div>
  </div>
  <div id="vmf-main">
    <div id="vmf-tree">
      <div id="vmf-cloud-bar" style="display:flex;flex-wrap:nowrap;gap:4px;padding:5px 10px;background:var(--hpe-bg);border-bottom:1px solid var(--hpe-border);flex-shrink:0;overflow-x:auto;align-items:center;"></div>
      <div id="vmf-tree-head">Folders</div>
      <div id="vmf-flist"><div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div></div>
      <div id="vmf-ds-head" style="display:none">Datastores</div>
      <div id="vmf-dslist" style="display:none"></div>
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
      // Theme toggle
      (function() {
        var theme = localStorage.getItem('vmf-theme') || 'light';
        if (theme === 'dark') document.body.classList.add('dark');
        document.getElementById('vmf-theme-btn').textContent = theme === 'dark' ? '\u2600' : '\u263D';
      })();
      document.getElementById('vmf-theme-btn').addEventListener('click', function() {
        var isDark = document.body.classList.toggle('dark');
        localStorage.setItem('vmf-theme', isDark ? 'dark' : 'light');
        this.textContent = isDark ? '\u2600' : '\u263D';
      });
      document.getElementById('vmf-logs-btn').addEventListener('click', function() { vmfShowLogs(); });
      document.getElementById('vmf-refresh-btn').addEventListener('click', function() { vmfReload(); });
      document.getElementById('vmf-new-folder-btn').addEventListener('click', function() { vmfCreateFolder(); });
      document.getElementById('vmf-mv-btn').addEventListener('click', function() { vmfMoveSelected(); });
      document.getElementById('vmf-close-modal').addEventListener('click', function() { vmfCloseModal(); });
      document.getElementById('vmf-modal').addEventListener('click', function(e) { if(e.target===this) vmfCloseModal(); });
      document.addEventListener('keydown', function(e) { if(e.key==='Escape') vmfCloseModal(); });
      // backup and restore are mutations: they must go through vmfPost (POST +
      // X-VMF-Request + core's CSRF token). A plain GET fetch here returned
      // 405 "POST required" from requireMutation() — both buttons were dead.
      document.getElementById('vmf-backup-btn').addEventListener('click', async function() {
        var d = await window.vmfPost('/backup', {});
        vmfToast(d.success ? 'Backup created' : 'Backup failed: '+(d.error||'unknown'), !d.success);
      });
      document.getElementById('vmf-restore-btn').addEventListener('click', function() {
        openConfirm('Restore from backup?', 'This will overwrite current data with the last backup.', async function() {
          var d = await window.vmfPost('/restore', {});
          vmfToast(d.success ? 'Restored from backup' : 'Restore failed: '+(d.error||'unknown'), !d.success);
          if(d.success) vmfReload();
        });
      });
      vmfReload();
    });
  </script>
</body>
</html>"""
    }

    // ── API: server actions (proxy to Morpheus) ──────────────────────
    def serverActions(ViewModel<Map> model) {
        try {
            def vmId = model?.request?.getParameter('vmId') as Long
            if (!vmId) return JsonResponse.of([success:false, error:'vmId required'])
            def server = scopedServer(model, vmId)
            if (!server) return reject(model, 404, 'Server not found')
            // Build standard action list based on server type and state
            def ps = server.powerState?.toString()?.toLowerCase() ?: 'unknown'
            def isOn = ps.matches('.*on.*|.*running.*')
            def actions = []
            if (isOn)  actions << [code:'restart', name:'Restart']
            // Simplified — Console/Start/Stop/Restart handled client-side
            // Add extra actions here in future if needed
            return JsonResponse.of([success:true, actions:actions])
        } catch(e) {
            log.error("serverActions error: ${e.message}", e)
            return JsonResponse.of([success:false, error:e.message, actions:[]])
        }
    }

    // ── API: execute server action ────────────────────────────────────
    def executeAction(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def vmId   = model?.request?.getParameter('vmId') as Long
            def action = model?.request?.getParameter('action') ?: ''
            if (!vmId || !action) return JsonResponse.of([success:false, error:'vmId and action required'])
            if (!scopedServer(model, vmId)) return reject(model, 404, 'server not found')
            switch(action) {
                case 'restart':
                    def result = morpheusContext.services.computeServer.restartServer(vmId)
                    return JsonResponse.of([success:result, action:action])
                default:
                    return JsonResponse.of([success:false, error:"Action '${action}' not implemented via plugin. Use Morpheus UI for workflows/tasks."])
            }
        } catch(e) {
            log.error("executeAction error: ${e.message}", e)
            return JsonResponse.of([success:false, error:e.message])
        }
    }

    // ── API: resync host assignments for auto-migrated VMs ────────────
    def resync(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            // Compute expected paths outside the lock (platform calls), apply inside it.
            def servers = morpheusContext.services.computeServer.list(
                userQuery(model).withFilter(new DataFilter('vmHypervisor', false))
            ).toList().findAll { canAccess(model, it) }
            def expected = [:]
            servers.each { s ->
                def hostName = (s.parentServer?.name instanceof String ? s.parentServer?.name : s.parentServer?.name?.toString()) ?: ''
                def cloudName = (s.cloud instanceof String ? s.cloud : s.cloud?.name?.toString()) ?: ''
                if (hostName && cloudName) expected[s.id?.toString()] = '/' + cloudName.replace('/', '-') + '/' + hostName.replace('/', '-')
            }
            def moved = 0
            mutateDb { Map db ->
                def assignments = db.assignments as Map ?: [:]
                expected.each { vid, expectedPath ->
                    def currentPath = assignments[vid]
                    if (!currentPath || currentPath == '/') return
                    // Only update if this looks like an auto-organized path (cloud/host)
                    if (currentPath.split('/').findAll { it }.size() < 2) return
                    if (currentPath != expectedPath) { assignments[vid] = expectedPath; moved++ }
                }
                if (moved > 0) { db.assignments = assignments; return true }
                return false
            }
            return JsonResponse.of([success:true, moved:moved, message:"Re-synced ${moved} VM(s)"])
        } catch(e) {
            log.error("resync error: ${e.message}", e)
            return JsonResponse.of([success:false, error:e.message])
        }
    }

}
