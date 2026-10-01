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
    static final String VERSION   = '1.5.4'
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
            // `scans` is read-only.
            p("/vmFolders/scan",       "scan"),
            p("/vmFolders/scans",      "scans"),
            p("/vmFolders/scanEnable", "scanEnable"),
            // 1.5.0. `config` is read-only and replaces `scanConfig` (it carries the
            // scan keys plus tagMode and the root labels). `setConfig` and
            // `autoOrganize` are mutations.
            p("/vmFolders/config",       "config"),
            p("/vmFolders/setConfig",    "setConfig"),
            p("/vmFolders/autoOrganize", "autoOrganize")
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

    // ══ Settings (1.5.0) ═══════════════════════════════════════════════
    // Two stores, in this order: the plugin's own settings form (Admin → Plugins), then
    // a key in the plugin database written through the permission-gated setConfig route.
    // The form wins when it holds a value, so an administrator can always override the
    // dashboard. Whether the settings form is usable on this platform is still
    // unverified (see the 1.4.0 notes), which is why the database fallback exists.
    static final String TAG_MODE_SETTING   = 'tagMode'
    static final String LABEL_ALL_SETTING  = 'labelAll'
    static final String LABEL_UNORG_SETTING = 'labelUnorganized'
    static final List<String> TAG_MODES    = ['off', 'keep', 'consume']
    static final String LABEL_ALL_DEFAULT  = 'All VMs'
    static final String LABEL_UNORG_DEFAULT = 'Unorganized'
    static final int MAX_LABEL_LEN = 40

    // One request reads several settings, so the platform call is memoized briefly.
    // Only the settings FORM is cached; the plugin database is re-read every time, so a
    // change made in the dashboard is visible immediately.
    static final long SETTINGS_TTL_MS = 5000L
    private volatile Map settingsCache = null
    private volatile long settingsCacheAt = 0L

    /** The plugin settings form as a map; empty when it is unreadable or unset. */
    private Map pluginSettings() {
        def now = System.currentTimeMillis()
        def cached = settingsCache
        if (cached != null && (now - settingsCacheAt) < SETTINGS_TTL_MS) return cached
        def out = [:]
        try {
            def raw = morpheusContext.getSettings(plugin)?.blockingGet()
            if (raw) {
                def parsed = new JsonSlurper().parseText(raw as String)
                if (parsed instanceof Map) out = parsed
            }
        } catch(ex) { log.debug("VmFolders: plugin settings unreadable (${ex.message}); using the local store") }
        settingsCache = out
        settingsCacheAt = now
        return out
    }

    /** [value, source] where source is 'plugin', 'db' or 'default'. */
    private List settingWithSource(String key, String dflt) {
        def v = pluginSettings()[key]
        if (v != null && !v.toString().trim().isEmpty()) return [v.toString().trim(), 'plugin']
        try {
            def local = readDb()?.settings?.get(key)
            if (local != null && !local.toString().trim().isEmpty()) return [local.toString().trim(), 'db']
        } catch(ex) {}
        return [dflt, 'default']
    }

    /** off | keep | consume. Anything unrecognised is off — the feature fails closed. */
    private String tagMode() {
        def m = settingWithSource(TAG_MODE_SETTING, 'off')[0].toLowerCase()
        return TAG_MODES.contains(m) ? m : 'off'
    }

    private String labelAll()   { sanitizeLabel(settingWithSource(LABEL_ALL_SETTING,  LABEL_ALL_DEFAULT)[0],  LABEL_ALL_DEFAULT) }
    private String labelUnorg() { sanitizeLabel(settingWithSource(LABEL_UNORG_SETTING, LABEL_UNORG_DEFAULT)[0], LABEL_UNORG_DEFAULT) }

    /** Display-only label: control chars out, length capped. esc() still applies on output. */
    private static String sanitizeLabel(String raw, String dflt) {
        if (raw == null) return dflt
        def clean = raw.replaceAll(/\p{Cntrl}/, '').trim()
        if (clean.length() > MAX_LABEL_LEN) clean = clean.substring(0, MAX_LABEL_LEN)
        return clean ?: dflt
    }

    // ══ Folder paths ═══════════════════════════════════════════════════
    static final int MAX_PATH_DEPTH   = 8
    static final int MAX_SEGMENT_LEN  = 64
    static final int MAX_PATH_LEN     = 512

    /**
     * The canonical form of a folder path, or null when the value cannot be one.
     * Trims, forces a leading slash, collapses duplicate slashes, drops a trailing
     * slash, and rejects control characters, '.' / '..' segments, a backslash inside a
     * segment, over-long segments and over-deep paths. Returns '/' for an empty path
     * (the root — callers that need a real folder must check for it).
     *
     * Angle brackets and quotes are deliberately NOT rejected: a hostile folder name is
     * allowed and is made safe by esc() at render time, which is what the verification
     * checklist tests.
     */
    private static String normPath(String raw) {
        if (raw == null) return null
        def s = raw.trim()
        if (!s) return null
        if (s.length() > MAX_PATH_LEN) return null
        if (s =~ /\p{Cntrl}/) return null
        def segs = []
        s.split('/').each { part ->
            def seg = part?.trim()
            if (seg) segs << seg
        }
        if (!segs) return '/'
        if (segs.size() > MAX_PATH_DEPTH) return null
        for (seg in segs) {
            if (seg == '.' || seg == '..') return null
            if (seg.length() > MAX_SEGMENT_LEN) return null
            if (seg.contains('\\')) return null
        }
        return '/' + segs.join('/')
    }

    /** A cloud or host name reduced to one path segment. */
    private static String pathSeg(String name) {
        def seg = (name ?: '').replace('/', '-').replace('\\', '-').replaceAll(/\p{Cntrl}/, '').trim()
        if (seg.length() > MAX_SEGMENT_LEN) seg = seg.substring(0, MAX_SEGMENT_LEN)
        return seg
    }

    // On VME-managed VMs these arrive as a String on some records and an object on
    // others — both shapes have to be handled (see CLAUDE.md).
    private static String cloudNameOf(def s) {
        try { return ((s.cloud instanceof String) ? s.cloud : s.cloud?.name)?.toString() ?: '' } catch(ex) { return '' }
    }
    private static String hostNameOf(def s) {
        try { def n = s.parentServer?.name; return ((n instanceof String) ? n : n?.toString()) ?: '' } catch(ex) { return '' }
    }

    // ══ Folder origin (1.5.0) ══════════════════════════════════════════
    // An auto-created folder records WHERE it came from: {cloud: 'x'} for a cloud
    // folder, {cloud: 'x', host: 'y'} for a host folder. Re-sync resolves targets by
    // origin instead of by name, so renaming /managed-democracy to /Lab no longer makes
    // the next Re-sync recreate the old tree and drag every VM back into it.
    //
    // A folder with no origin was made by hand and Re-sync never touches it.

    /**
     * Stamps `origin` on folder records that still match a cloud or cloud/host name, so
     * folders created before 1.5.0 are migrated. Each record is examined once
     * (`originChecked`), on the read path, inside the caller's mutateDb.
     *
     * Depth 2 under a known cloud counts as a host folder even when the host segment
     * matches no current host: after a migration the last VM can leave a host, and a
     * folder that lost its origin that way would strand the VMs still assigned to it.
     *
     * Returns true when it changed the db.
     */
    private boolean stampOrigins(Map db, List servers) {
        def folders = db.folders as List ?: []
        if (!folders.any { (it instanceof Map) && it.originChecked != true }) return false
        def clouds = [:]     // cloud segment -> real cloud name
        def hosts  = [:]     // "cloudSeg/hostSeg" -> [cloud: , host: ]
        servers.each { s ->
            def c = cloudNameOf(s), h = hostNameOf(s)
            if (c) clouds[pathSeg(c)] = c
            if (c && h) hosts[pathSeg(c) + '/' + pathSeg(h)] = [cloud: c, host: h]
        }
        boolean changed = false
        folders.each { f ->
            if (!(f instanceof Map)) return
            if (f.originChecked == true) return
            f.originChecked = true
            changed = true
            if (f.origin != null) return
            def segs = (f.path ?: '').toString().split('/').findAll { it }
            if (segs.size() == 1) {
                if (clouds[segs[0]]) f.origin = [cloud: clouds[segs[0]]]
            } else if (segs.size() == 2 && clouds[segs[0]]) {
                def exact = hosts[segs[0] + '/' + segs[1]]
                f.origin = exact ?: [cloud: clouds[segs[0]], host: segs[1]]
            }
        }
        if (changed) db.folders = folders
        return changed
    }

    /**
     * The folder a VM on {cloud, host} belongs in, resolved by origin. With create=true
     * the folder (and its cloud parent) is created when no folder carries that origin —
     * at the name-derived path, which is only ever used for a folder that does not exist
     * yet. Mutates db.folders; the caller is inside mutateDb.
     */
    private String originTarget(Map db, String cloud, String host, boolean create) {
        def folders = db.folders as List ?: []
        def hit = folders.find { (it instanceof Map) && (it.origin instanceof Map) && it.origin.cloud == cloud && it.origin.host == host }
        if (hit) return hit.path?.toString()
        if (!create) return null
        def parent = folders.find { (it instanceof Map) && (it.origin instanceof Map) && it.origin.cloud == cloud && !it.origin.host }
        def parentPath = parent?.path?.toString() ?: ('/' + pathSeg(cloud))
        if (!parent && !folders.find { (it instanceof Map) && it.path == parentPath }) {
            folders << [path: parentPath, desc: '', created: new Date().toString(),
                        origin: [cloud: cloud], originChecked: true]
        }
        def path = normPath(parentPath + '/' + pathSeg(host))
        if (!path || path == '/') return null
        def existing = folders.find { (it instanceof Map) && it.path == path }
        if (existing) {
            existing.origin = [cloud: cloud, host: host]
            existing.originChecked = true
        } else {
            folders << [path: path, desc: '', created: new Date().toString(),
                        origin: [cloud: cloud, host: host], originChecked: true]
        }
        db.folders = folders
        return path
    }

    // ══ vmfolder tag reconciliation (1.5.0) ════════════════════════════
    // A `vmfolder` tag whose value is a folder path puts a VM in that folder, so the
    // provisioning wizard's Tags step, the API and Terraform can all set a folder at
    // create time. One way only: the plugin never writes a vmfolder tag, and moving a VM
    // in the dashboard never creates one.
    //
    // Verified against the 1.3.0 jar (2026-09-30):
    //  - ComputeServer.metadata and Instance.metadata are List<MetadataTag>; MetadataTag
    //    carries `name` and `value` (name comes from MetadataTagIdentityProjection,
    //    which also carries refType/refId). ComputeServer.tags is a separate String
    //    field and is NOT the tag list.
    //  - MorpheusMetadataTagService (morpheusContext.async.metadataTag) has
    //    remove(List<MetadataTagIdentityProjection>, ComputeServerIdentityProjection)
    //    and the Instance equivalent; the synchronous service only has remove(List).
    //    ComputeServer IS a ComputeServerIdentityProjection, so the server can be passed
    //    straight in and Groovy picks the overload by runtime type.
    //
    // NOT verifiable without the appliance, hence the probe log line in reconcileTags():
    //  - whether computeServer.list() hydrates `metadata` (as it does `volumes`),
    //  - whether a provisioning-time tag lands on the ComputeServer, the Instance or
    //    both — so both are read, and the tag is removed from whichever carried it.
    static final String TAG_NAME = 'vmfolder'

    /** Reconcile only runs for a request that came from the dashboard's own XHR.
     *  It can write (consume mode removes a tag), and /vms is a GET, so the custom
     *  header a cross-origin GET cannot set is what keeps CLAUDE.md rule 6 honest:
     *  a plain browser GET of /vms stays strictly read-only. */
    private boolean vmfXhr(ViewModel<Map> model) {
        try { return header(model?.request, REQ_HEADER) != null } catch(ex) { return false }
    }

    /** The vmfolder tags on one record. Case-insensitive on the tag name. */
    private static List tagsOn(def owner) {
        def hits = []
        try {
            (owner?.metadata ?: []).each { t ->
                def n = t?.name?.toString()?.trim()
                if (n && n.equalsIgnoreCase(TAG_NAME)) hits << t
            }
        } catch(ex) {}
        return hits
    }

    /** server id -> Instance, so a tag the platform put on the instance is still found. */
    private Map instancesByServerId(ViewModel<Map> model) {
        def map = [:]
        try {
            morpheusContext.services.instance.list(userQuery(model))?.each { inst ->
                // canAccess() is applied here for the same reason it is applied to servers:
                // DataQuery(user) sets the account but service-level scoping is not
                // guaranteed, and consume mode removes a tag from whatever this returns.
                if (!canAccess(model, inst)) return
                boolean mapped = false
                try {
                    (inst.containers ?: []).each { w ->
                        def sid = w?.server?.id
                        if (sid != null && !map.containsKey(sid)) { map[sid] = inst; mapped = true }
                    }
                } catch(ex) {}
                // containers is not hydrated by instance.list() on 9.0.2, so a tagged
                // instance is mapped from the workload side instead. Only tagged instances
                // pay for the query. This is what finds a tag that is on the instance only —
                // e.g. after 1.5.2 removed the server copy and left the instance tag.
                // [V] whether instance.list() hydrates metadata, and the 'instance.id' filter.
                if (!mapped && tagsOn(inst)) {
                    try {
                        def w = morpheusContext.services.workload.find(new DataQuery().withFilter('instance.id', inst.id))
                        def sid = w?.server?.id
                        if (sid != null && w.instance?.id in [null, inst.id] && !map.containsKey(sid)) map[sid] = inst
                        else log.info("VmFolders tag/probe: tagged instance#${inst.id} not mapped (workload=${w?.id} server=${sid} instance=${w?.instance?.id})")
                    } catch(ex) {
                        log.warn("VmFolders tag: workload lookup for instance#${inst.id} failed (${ex.class.name}: ${ex.message})")
                    }
                }
            }
        } catch(ex) { log.warn("VmFolders tag: instance list failed (${ex.class.name}: ${ex.message})") }
        return map
    }

    /** The tag to act on for this VM, server record first, then its instance. */
    private Map tagHit(def s, Map instByServerId) {
        def hits = tagsOn(s)
        def ownerType = 'server'
        def owner = s
        if (!hits) {
            def inst = instByServerId?.get(s.id)
            if (inst) {
                def ih = tagsOn(inst)
                if (ih) { hits = ih; ownerType = 'instance'; owner = inst }
            }
        }
        if (!hits) return null
        if (hits.size() > 1) {
            log.warn("VmFolders tag: vm=${s.id} carries ${hits.size()} ${TAG_NAME} tags; using the first [${hits[0]?.value}]")
        }
        return [tag: hits[0], extra: hits.drop(1), ownerType: ownerType, owner: owner, server: s]
    }

    /**
     * Whether a tag on this VM may be acted on yet. A VM still being built can carry the
     * tag before the record is real, and consuming it then would spend the tag on a VM
     * that may never finish. Only clearly in-flight states are skipped, so an
     * unrecognised status never silently disables the feature.
     *
     * Exact matches only: 1.5.0–1.5.1 used substring checks, and 'provision' matched
     * 'provisioned' — the normal status of every finished Morpheus-built VM — so every
     * such VM was skipped as not ready.
     */
    static final Set<String> TAG_NOT_READY = ['provisioning', 'pending', 'queued', 'deploying', 'creating',
                                              'resizing', 'removing', 'deprovisioning'] as Set
    private static boolean tagReady(def s) {
        try {
            def st = s.status?.toString()?.trim()?.toLowerCase()
            return !st || !TAG_NOT_READY.contains(st)
        } catch(ex) { return true }
    }

    /**
     * The Instance that owns this server, or null. Returns [instance, how-it-was-found].
     *
     * Instance.containers is NOT hydrated by instance.list() on 9.0.2 (1.5.2 probe:
     * instancesMapped=0), so the link is resolved from the workload side: one workload
     * query filtered by server.id, then workload.instance, then instance.get() for a
     * fully loaded record. The server has already passed canAccess(); the instance is
     * checked again before anything is removed from it.
     * [V] Whether the 'server.id' filter path is honoured and workload.instance is
     * populated is unverified — the consumed log line reports how the instance was
     * found, or why it was not.
     */
    private List instanceForServer(ViewModel<Map> model, def s, Map instByServerId) {
        def inst = instByServerId?.get(s.id)
        if (inst) return [inst, 'instance.containers']
        try {
            def w = morpheusContext.services.workload.find(new DataQuery().withFilter('server.id', s.id))
            if (!w) return [null, "no workload with server.id=${s.id}"]
            if (w.server?.id != null && w.server.id != s.id) return [null, "workload#${w.id} belongs to server#${w.server.id} (filter ignored?)"]
            def ref = w.instance
            if (ref?.id == null) return [null, "workload#${w.id} has no instance"]
            inst = morpheusContext.services.instance.get(ref.id as Long) ?: ref
            if (!canAccess(model, inst)) return [null, "instance#${ref.id} not accessible to caller"]
            return [inst, "workload#${w.id}.instance"]
        } catch(ex) {
            return [null, "workload lookup failed (${ex.class.simpleName}: ${ex.message})"]
        }
    }

    /** vmfolder tags on an instance: its metadata, else a metadataTag query by ref.
     *  [V] refType 'Instance' is the Morpheus convention but unverified on 9.0.2. */
    private List instanceTags(def inst) {
        def hits = tagsOn(inst)
        if (hits) return [hits, 'instance.metadata']
        try {
            def q = new DataQuery().withFilter('refType', 'Instance').withFilter('refId', inst.id as Long)
            hits = (morpheusContext.services.metadataTag.list(q) ?: []).findAll { t ->
                t?.name?.toString()?.trim()?.equalsIgnoreCase(TAG_NAME)
            }
            return [hits, 'metadataTag.list(refType=Instance)']
        } catch(ex) {
            return [[], "metadataTag query failed (${ex.class.simpleName}: ${ex.message})"]
        }
    }

    /** Removes the given tags from one owning record. Returns [removed, via]. */
    private List removeFrom(List tags, def ref) {
        def kind = ref?.getClass()?.simpleName
        try {
            def r = morpheusContext.async.metadataTag.remove(tags, ref)?.blockingGet()
            if (r == true) return [true, "async.remove(tags, ${kind})"]
            log.warn("VmFolders tag: async.remove(tags, ${kind}#${ref?.id}) returned ${r}; trying the plain remove")
        } catch(ex) {
            log.warn("VmFolders tag: async.remove(tags, ${kind}#${ref?.id}) failed (${ex.class.name}: ${ex.message}); trying the plain remove")
        }
        try {
            return [morpheusContext.services.metadataTag.remove(tags) == true, 'services.remove(tags)']
        } catch(ex) {
            log.error("VmFolders tag: removal FAILED on ${kind}#${ref?.id}: ${ex.class.name}: ${ex.message}", ex)
            return [false, "failed: ${ex.class.simpleName}"]
        }
    }

    /**
     * Removes the vmfolder tag from the VM: the server's copy and the instance's tag.
     *
     * THIS IS THE ONLY PLACE THE PLUGIN WRITES TO A MORPHEUS RECORD, and it runs only in
     * consume mode. It deletes nothing but the vmfolder tag itself: other tags on either
     * record are untouched. Every removal is audit-logged with the user, the VM, the path
     * and the result on each record.
     *
     * On 9.0.2 a tag set on an instance (wizard, instances#update) is copied to its
     * server's metadata, and the instance is the source the UI shows. Removing only the
     * server copy (1.5.0–1.5.2) returned removed=true and left the tag visible, so the
     * instance is resolved through its workload and its tag removed too. Success means
     * the instance tag is gone, or — for a VM with no instance — the server tag is.
     */
    private boolean removeTag(ViewModel<Map> model, Map hit, Map instByServerId) {
        def s = hit.server
        def srvTags = tagsOn(s)
        def srv = srvTags ? removeFrom(srvTags, s) : [null, 'no tag on server']
        def (inst, found) = instanceForServer(model, s, instByServerId)
        def ins = [null, 'no instance']
        if (inst) {
            def (instTags, src) = instanceTags(inst)
            ins = instTags ? removeFrom(instTags, inst) + [src] : [null, "no ${TAG_NAME} tag on instance (${src})"]
        }
        boolean ok = (ins[0] == true) || (ins[0] == null && srv[0] == true)
        log.info("VmFolders tag consumed: user=${model?.user?.username} vm=${hit.vmId} path=${hit.path} ok=${ok} | " +
                 "server#${s?.id} tags=${srvTags.size()} removed=${srv[0]} via=${srv[1]} | " +
                 "instance#${inst?.id ?: '-'} found=${found} removed=${ins[0]} via=${ins.drop(1).join(' / ')}")
        if (!inst) {
            log.warn("VmFolders tag: vm=${hit.vmId} instance not resolved (${found}) — if the tag was set on the " +
                     "instance it is still there and still shown in the Morpheus UI")
        }
        return ok
    }

    /**
     * One reconcile pass over the caller's scoped VMs. Every assignment is written in a
     * single mutateDb batch; tag removal (consume mode only) happens after it, so a
     * failed removal can never lose the assignment it was consumed for.
     *
     * Returns [mode, assigned, consumed, invalid, skipped, tagged: [vmId: path]].
     * In off mode it reads no tags and logs nothing.
     */
    private Map reconcileTags(ViewModel<Map> model, List servers, boolean allowWrite) {
        def mode = tagMode()
        def stats = [mode: mode, assigned: 0, consumed: 0, invalid: 0, skipped: 0, tagged: [:]]
        if (mode == 'off') return stats
        // TEMPORARY 1.5.2 diagnostic: proves reconcile was entered and where the mode came from.
        log.info("VmFolders tag/probe: enter mode=${mode} source=${settingWithSource(TAG_MODE_SETTING, 'off')[1]} servers=${servers?.size()}")
        if (!allowWrite) {
            // TEMPORARY 1.5.2 diagnostic (info, not debug): 1.5.1 in keep mode wrote no probe
            // line at all, so either the mode resolved to off or this branch was taken.
            def req = model?.request
            log.info("VmFolders tag/probe: SKIPPED mode=${mode} — ${REQ_HEADER} not seen on /vms " +
                     "(request=${req?.getClass()?.name} respondsTo(getHeader)=${req?.respondsTo('getHeader') ? true : false})")
            stats.mode = mode
            return stats
        }
        def instMap = instancesByServerId(model)
        def hits = []
        int srvTagged = 0, instTagged = 0
        servers.each { s ->
            def h = tagHit(s, instMap)
            if (!h) return
            if (h.ownerType == 'instance') instTagged++ else srvTagged++
            if (!tagReady(s)) { stats.skipped++; return }
            def path = normPath(h.tag?.value?.toString())
            if (!path || path == '/') {
                stats.invalid++
                log.warn("VmFolders tag: vm=${s.id} (${s.name}) has an unusable ${TAG_NAME} value " +
                         "[${h.tag?.value}] — VM untouched, tag left in place so it can be corrected")
                return
            }
            h.vmId = s.id?.toString()
            h.path = path
            if (h.vmId) hits << h
        }
        // TEMPORARY 1.5.0 probe — answers the open items in SPEC-1.5.0: whether
        // computeServer.list() hydrates `metadata`, and whether a provisioning tag lands
        // on the server or the instance. Strip once both are confirmed on the appliance.
        log.info("VmFolders tag/probe: mode=${mode} servers=${servers.size()} serverTagged=${srvTagged} " +
                 "instanceTagged=${instTagged} instancesMapped=${instMap.size()} usable=${hits.size()} " +
                 "invalid=${stats.invalid} notReady=${stats.skipped}")
        if (!hits) return stats
        def assignedDetail = []
        mutateDb { Map db ->
            def asgn = db.assignments as Map ?: [:]
            def folders = db.folders as List ?: []
            boolean changed = false
            hits.each { h ->
                if (asgn[h.vmId] == h.path) return
                asgn[h.vmId] = h.path
                if (!folders.find { (it instanceof Map) && it.path == h.path }) {
                    folders << [path: h.path, desc: '', created: new Date().toString(), originChecked: true]
                }
                // The history ring holds 100 entries; a first pass over a Terraform-tagged
                // estate must not flush it, so the detail is capped and the rest summarized.
                if (stats.assigned < 25) addHistory(db, 'assign_from_tag', [vmId: h.vmId, path: h.path, mode: mode])
                assignedDetail << "vm=${h.vmId} -> ${h.path} (from ${h.ownerType})"
                stats.assigned++
                changed = true
            }
            if (stats.assigned > 25) addHistory(db, 'assign_from_tag_bulk', [count: stats.assigned, mode: mode])
            if (changed) { db.assignments = asgn; db.folders = folders }
            return changed
        }
        assignedDetail.take(100).each { log.info("VmFolders tag assigned: ${it}") }
        if (assignedDetail.size() > 100) log.info("VmFolders tag assigned: ... and ${assignedDetail.size() - 100} more")
        if (mode == 'consume') {
            hits.each { h -> if (removeTag(model, h, instMap)) stats.consumed++ }
        } else {
            // keep mode: the tag stays the source of truth, so the dashboard shows the
            // badge and warns that a Move will be reverted on the next reconcile.
            hits.each { h -> stats.tagged[h.vmId] = h.path }
        }
        return stats
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
            def v = pluginSettings()[SCAN_SETTING]
            if (v?.toString()?.equalsIgnoreCase('true') || v == true) return true
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

    /**
     * GET /config — everything the dashboard needs before it renders: the root labels,
     * the tag mode and where each came from, and the scan state with the datastores that
     * can be scanned. Read-only; replaces 1.4.x's /scanConfig.
     */
    def config(ViewModel<Map> model) {
        try {
            if (!model?.user?.account) return reject(model, 401, 'no authenticated user')
            def enabled = scanEnabled()
            def stores = enabled ? scannableDatastores(model).collect { d ->
                [ id: d.id, name: d.name, path: d.externalPath?.toString(),
                  type: d.datastoreType?.code?.toString(), shared: isSharedStore(d) ]
            } : []
            def mode = settingWithSource(TAG_MODE_SETTING, 'off')
            return JsonResponse.of([
                success         : true,
                version         : VERSION,
                enabled         : enabled,
                datastores      : stores,
                tagMode         : tagMode(),
                tagModeSource   : mode[1],
                tagName         : TAG_NAME,
                labelAll        : labelAll(),
                labelUnorganized: labelUnorg(),
                labelSource     : [ all: settingWithSource(LABEL_ALL_SETTING, LABEL_ALL_DEFAULT)[1],
                                    unorganized: settingWithSource(LABEL_UNORG_SETTING, LABEL_UNORG_DEFAULT)[1] ]
            ])
        } catch(e) {
            log.error("config error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message, enabled: false, datastores: [],
                                    tagMode: 'off', labelAll: LABEL_ALL_DEFAULT, labelUnorganized: LABEL_UNORG_DEFAULT])
        }
    }

    /**
     * POST /setConfig — writes one allow-listed setting to the plugin database.
     * tagMode is validated against TAG_MODES; the labels are sanitized display strings.
     * The plugin settings form still wins when it holds a value, and the response says so.
     */
    def setConfig(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def key = model?.request?.getParameter('key')?.toString()
            def val = model?.request?.getParameter('value')?.toString() ?: ''
            if (!([TAG_MODE_SETTING, LABEL_ALL_SETTING, LABEL_UNORG_SETTING].contains(key))) {
                return reject(model, 400, 'unknown setting')
            }
            String stored
            if (key == TAG_MODE_SETTING) {
                stored = val.trim().toLowerCase()
                if (!TAG_MODES.contains(stored)) return reject(model, 400, "tagMode must be one of ${TAG_MODES.join(', ')}")
            } else {
                stored = sanitizeLabel(val, key == LABEL_ALL_SETTING ? LABEL_ALL_DEFAULT : LABEL_UNORG_DEFAULT)
            }
            mutateDb { Map db ->
                def settings = db.settings instanceof Map ? db.settings : [:]
                settings[key] = stored
                db.settings = settings
                return true
            }
            def source = settingWithSource(key, stored)[1]
            log.info("VmFolders config: ${key} set to [${stored}] by user=${model?.user?.username} (effective source=${source})")
            return JsonResponse.of([success: true, key: key, value: stored, effectiveSource: source])
        } catch(e) {
            log.error("setConfig error: ${e.message}", e)
            return JsonResponse.of([success: false, error: e.message])
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
        } catch(ex) { log.warn("VmFolders scan: annotate failed: ${ex.message}") }
    }

    // ── API: VM list ──────────────────────────────────────────────────
    def vms(ViewModel<Map> model) {
        try {
            if (!model?.user?.account) return reject(model, 401, 'no authenticated user')

            def servers = morpheusContext.services.computeServer.list(
                userQuery(model).withFilter(new DataFilter('vmHypervisor', false))
            ).toList().findAll { canAccess(model, it) }

            // 1.5.0: reconcile vmfolder tags against the scoped list, then migrate any
            // folder that still has no origin. Both are no-ops unless there is something
            // to do, and reconcile does nothing at all in the default off mode.
            def tagStats = reconcileTags(model, servers, vmfXhr(model))
            def db = mutateDb { Map d -> stampOrigins(d, servers) }
            def assignments = db.assignments as Map ?: [:]
            def tagged = tagStats.tagged as Map ?: [:]

            def result = servers.collect { s ->
                def ps = s.powerState
                def powerState = ps ? ps.toString() : (s.status ? s.status.toString() : 'unknown')
                def osType = ''
                try {
                    def osRaw = (s.osType instanceof String ? s.osType : s.osType?.name) ?: (s.serverOs instanceof String ? s.serverOs : s.serverOs?.name) ?: ''
                    osType = (osRaw.contains('@') || osRaw.contains('com.morpheus')) ? '' : osRaw
                } catch(ex) {}
                def cloudName = cloudNameOf(s)
                def disks = diskList(s)
                def idStr = s.id?.toString()

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
                    isHost     : isHypervisorHost(s),
                    // keep mode only: in consume mode the tag is gone before this renders.
                    tagged     : tagged.containsKey(idStr),
                    tagPath    : tagged[idStr] ?: ''
                ]
            }
            return JsonResponse.of([
                servers: result,
                meta: [
                    total    : result.size(),
                    unmanaged: result.count { it.unmanaged },
                    tags     : [mode: tagStats.mode, assigned: tagStats.assigned, consumed: tagStats.consumed,
                                invalid: tagStats.invalid, skipped: tagStats.skipped, tagged: tagged.size()],
                    labels   : [all: labelAll(), unorganized: labelUnorg()]
                ]
            ])
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
            def raw = model?.request?.getParameter('path') as String
            def desc = model?.request?.getParameter('desc') as String ?: ''
            if (!raw) return JsonResponse.of([success: false, error: 'path required'])
            def path = normPath(raw)
            if (!path || path == '/') return JsonResponse.of([success: false, error: 'invalid folder path'])
            def fpath = path
            mutateDb { Map db ->
                def folders = db.folders as List ?: []
                if (folders.find { it.path == fpath }) return false
                // Created by hand: no origin, and marked examined so the origin migration
                // never adopts it. Re-sync leaves folders without an origin alone.
                folders << [path: fpath, desc: desc, created: new Date().toString(), originChecked: true]
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
            def oldPath = normPath(model?.request?.getParameter('oldPath') as String)
            def newPath = normPath(model?.request?.getParameter('newPath') as String)
            if (!oldPath || !newPath) return JsonResponse.of([success: false, error: 'oldPath and newPath required'])
            if (oldPath == '/' || newPath == '/') return JsonResponse.of([success: false, error: 'invalid folder path'])
            if (newPath == oldPath) return JsonResponse.of([success: true, newPath: newPath])
            if (newPath.startsWith(oldPath + '/')) return JsonResponse.of([success: false, error: 'cannot move a folder inside itself'])
            def np = newPath
            mutateDb { Map db ->
                // Update folder definitions. Only `path` changes, so `origin` survives the
                // rename — that is what lets a renamed auto-created folder keep receiving
                // the VMs it was created for instead of being recreated by Re-sync.
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
            def fpath = normPath(path)
            if (!fpath || fpath == '/') return JsonResponse.of([success: false, error: 'invalid folder path'])
            mutateDb { Map db ->
                def asgn = db.assignments as Map ?: [:]
                asgn[vmId] = fpath
                db.assignments = asgn
                // Auto-create folder if it doesn't exist
                def folders = db.folders as List ?: []
                if (!folders.find { it.path == fpath }) {
                    folders << [path: fpath, desc: '', created: new Date().toString(), originChecked: true]
                    db.folders = folders
                }
                addHistory(db, 'assign', [vmId: vmId, path: fpath])
                return true
            }
            return JsonResponse.of([success: true, path: fpath])
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
    body.dark .vmf-badge-tag { background:#1A3A30; color:#01A982; border-color:#2A5A48; }
    body.dark .vmf-note { background:#3A2E1A; color:#E0B870; border-color:#5A4828; }
    body.dark .vmf-fg select { background:var(--hpe-white); color:var(--hpe-text); }
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
    .vmf-badge-tag { display:inline-block; background:#e6f5f1; color:#008567; border:1px solid #b3e0d5; border-radius:3px; padding:0 5px; font-size:10px; font-weight:600; text-transform:uppercase; letter-spacing:.03em; vertical-align:middle; }
    .vmf-fg select { width:100%; padding:7px 10px; border:1px solid var(--hpe-border); border-radius:4px; font-size:13px; color:var(--hpe-text); background:var(--hpe-white); font-family:inherit; }
    .vmf-note { font-size:12px; line-height:1.5; padding:8px 10px; border-radius:4px; background:#fff3e0; color:#8a5300; border:1px solid #f0c890; margin-bottom:12px; }
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
      <button class="vmf-btn vmf-btn-outline vmf-btn-sm" id="vmf-settings-btn" title="Plugin settings">&#9881; Settings</button>
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
      document.getElementById('vmf-settings-btn').addEventListener('click', function() { vmfSettings(); });
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

    /** The caller's scoped guest VMs (hypervisor hosts excluded). */
    private List scopedVmList(ViewModel<Map> model) {
        return morpheusContext.services.computeServer.list(
            userQuery(model).withFilter(new DataFilter('vmHypervisor', false))
        ).toList().findAll { canAccess(model, it) }
    }

    // ── API: resync host assignments for auto-migrated VMs ────────────
    /**
     * Follows VME live-migration: a VM in the auto-organized tree moves to the folder for
     * the host it is on now.
     *
     * 1.5.0 resolves the target by ORIGIN, not by name. A folder renamed from
     * /managed-democracy to /Lab keeps its origin, so it stays the target and its VMs stay
     * in it — 1.4.x recomputed /<cloud>/<host> from the current names and dragged every VM
     * back into a recreated tree.
     *
     * Also runs one tag reconcile pass, and places a VM that is in no folder into its
     * cloud/host folder when that folder ALREADY exists — it never invents structure for
     * an unassigned VM. A folder with no origin was made by hand and is never touched.
     */
    def resync(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            // Platform calls outside the lock, db changes inside it.
            def servers = scopedVmList(model)
            def tagStats = reconcileTags(model, servers, true)
            def pairs = [:]
            servers.each { s ->
                def c = cloudNameOf(s), h = hostNameOf(s)
                if (c && h) pairs[s.id?.toString()] = [cloud: c, host: h]
            }
            def taggedIds = ((tagStats.tagged as Map) ?: [:]).keySet()
            int moved = 0, placed = 0
            def detail = []
            mutateDb { Map db ->
                boolean changed = stampOrigins(db, servers)
                def asgn = db.assignments as Map ?: [:]
                pairs.each { vid, p ->
                    if (!vid) return
                    // In keep mode the tag is the source of truth, so a tagged VM is left
                    // where the tag put it — moving it here would only be undone on the
                    // next reconcile.
                    if (taggedIds.contains(vid)) return
                    def folders = db.folders as List ?: []
                    def current = asgn[vid]?.toString()
                    if (!current || current == '/') {
                        def existing = originTarget(db, p.cloud, p.host, false)
                        if (existing) {
                            asgn[vid] = existing; placed++; changed = true
                            detail << "placed vm=${vid} -> ${existing}"
                        }
                        return
                    }
                    def cf = folders.find { (it instanceof Map) && it.path == current }
                    // Hand-made folder (examined, no origin): never touched.
                    if (cf != null && cf.originChecked == true && cf.origin == null) return
                    // Otherwise the VM is in the auto tree if its folder carries an origin
                    // or the path is a cloud/host shape — the 1.x rule, kept.
                    boolean auto = (cf?.origin instanceof Map) || current.split('/').findAll { it }.size() >= 2
                    if (!auto) return
                    def target = originTarget(db, p.cloud, p.host, true)
                    if (target && target != current) {
                        asgn[vid] = target; moved++; changed = true
                        detail << "moved vm=${vid} ${current} -> ${target}"
                    }
                }
                if (moved > 0 || placed > 0) {
                    db.assignments = asgn
                    // One entry per call: the 100-entry history ring must survive a first
                    // run over a large estate. Per-VM detail goes to the appliance log.
                    addHistory(db, 'resync', [moved: moved, placed: placed, user: model?.user?.username?.toString()])
                }
                return changed
            }
            def msg = "Re-synced ${moved} VM(s)" + (placed > 0 ? ", placed ${placed}" : '')
            log.info("VmFolders resync: user=${model?.user?.username} moved=${moved} placed=${placed} " +
                     "tagMode=${tagStats.mode} tagAssigned=${tagStats.assigned} tagConsumed=${tagStats.consumed} tagInvalid=${tagStats.invalid}")
            detail.take(100).each { log.info("VmFolders resync: ${it}") }
            if (detail.size() > 100) log.info("VmFolders resync: ... and ${detail.size() - 100} more")
            return JsonResponse.of([success:true, moved:moved, placed:placed, tags:tagStats.subMap(['mode','assigned','consumed','invalid','skipped']), message:msg])
        } catch(e) {
            log.error("resync error: ${e.message}", e)
            return JsonResponse.of([success:false, error:e.message])
        }
    }

    // ── API: auto-organize into the cloud/host tree ───────────────────
    /**
     * Builds the cloud/host tree and assigns every scoped VM, resolving each target by
     * origin so a renamed folder is reused instead of recreated. Replaces the client-side
     * loop of 1.4.x, which recomputed paths from names (defeating renames) and issued one
     * request per folder and per VM.
     */
    def autoOrganize(ViewModel<Map> model) {
        try {
            def denied = requireMutation(model); if (denied) return denied
            def servers = scopedVmList(model)
            def pairs = [:]
            servers.each { s ->
                def vid = s.id?.toString()
                if (!vid) return
                pairs[vid] = [cloud: cloudNameOf(s) ?: 'Unknown', host: hostNameOf(s) ?: 'Unknown Host']
            }
            int assigned = 0, createdFolders = 0
            mutateDb { Map db ->
                boolean changed = stampOrigins(db, servers)
                def asgn = db.assignments as Map ?: [:]
                int before = ((db.folders as List) ?: []).size()
                pairs.each { vid, p ->
                    def target = originTarget(db, p.cloud, p.host, true)
                    if (!target) return
                    if (asgn[vid] != target) { asgn[vid] = target; assigned++; changed = true }
                }
                createdFolders = (((db.folders as List) ?: []).size()) - before
                if (assigned > 0) db.assignments = asgn
                if (changed || createdFolders > 0) addHistory(db, 'auto_organize', [assigned: assigned, folders: createdFolders])
                return changed || createdFolders > 0
            }
            log.info("VmFolders autoOrganize: user=${model?.user?.username} vms=${pairs.size()} assigned=${assigned} foldersCreated=${createdFolders}")
            return JsonResponse.of([success:true, assigned:assigned, folders:createdFolders,
                                    message:"Organized ${assigned} VM(s) into ${createdFolders} new folder(s)"])
        } catch(e) {
            log.error("autoOrganize error: ${e.message}", e)
            return JsonResponse.of([success:false, error:e.message])
        }
    }

}
