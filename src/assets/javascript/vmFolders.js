(function() {
  // Single-instance guard. VmFoldersNavProvider injects this script on every core
  // page AND each tab template re-injects it when vmfReload is not yet defined, so
  // two IIFEs could run with separate allVms/storedFolders while sharing the DOM and
  // both listening on document — one would render its empty state over the other's.
  window.__vmfInstances = (window.__vmfInstances || 0) + 1;
  if (window.__vmfLoaded) {
    try { console.warn('vmFolders: already loaded ('+window.__vmfInstances+' instances requested), skipping duplicate'); } catch(e) {}
    return;
  }
  window.__vmfLoaded = true;

  var ROOT = '/';
  var allVms = [], storedFolders = [], assignments = {}, activeFolder = '__all__', searchQ = '';
  var selectedIds = new Set(), sortCol = 'name', sortAsc = true;
  var cloudFilter = '__all__';
  // Datastore browsing (read-only): a datastore name, NO_DS for "no datastore", or null.
  // Folder selection and datastore selection are mutually exclusive — see resetSelection().
  var NO_DS = '__none__';
  var activeDatastore = null;
  // Datastore scan (1.4.0): off unless an admin enabled it. dsIndex maps datastore
  // name -> id, because the sidebar is built from VM data (names) while scanning
  // addresses stores by id.
  var scanEnabled = false, scanStores = [], scanCache = {}, scanBusy = false;
  // Root labels are settings (display-only, always esc()'d). Defaults match 1.4.x so a
  // failed config fetch changes nothing on screen.
  var labels = { all: 'All VMs', unorganized: 'Unorganized' };
  // vmfolder tag reconciliation (1.5.0): off | keep | consume, from the plugin settings form.
  var tagMode = 'off', tagName = 'vmfolder';

  // Single reset path for the sidebar selection state so folder and datastore
  // views can never both be active (and so a future SPA-navigation reset has one hook).
  function resetSelection(folderKey, dsKey) {
    activeFolder = folderKey || '__all__';
    activeDatastore = dsKey || null;
    selectedIds.clear();
  }

  // Expose syncDarkMode globally so it can be called from HBS templates
  window.vmfSyncDark = function() {
    var isDark = detectMorpheusDark();
    var styleId = 'vmf-dark-vars';
    var existing = document.getElementById(styleId);
    if (isDark) {
      if (!existing) {
        var s = document.createElement('style');
        s.id = styleId;
        s.textContent = ':root{--hpe-bg:#1A1F2B!important;--hpe-white:#242B38!important;--hpe-border:#3A4458!important;--hpe-text:#E0E6F0!important;--hpe-muted:#8899B0!important;--hpe-selected:#1A3A30!important;--hpe-row-hover:#1E2535!important;--hpe-header:#2C3547!important;}';
        document.head.appendChild(s);
      }
    } else {
      if (existing) existing.remove();
    }
  };
  // Run immediately when vmFolders.js loads on any page
  window.vmfSyncDark();

  function detectMorpheusDark() {
    var selectors = ['.main-header','header.navbar','.navbar-header','.site-header','nav.navbar','body'];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        var bg = window.getComputedStyle(el).backgroundColor;
        var rgb = bg.match(/\d+/g);
        if (rgb && !(parseInt(rgb[0])===0&&parseInt(rgb[1])===0&&parseInt(rgb[2])===0)) {
          return (parseInt(rgb[0])*299+parseInt(rgb[1])*587+parseInt(rgb[2])*114)/1000 < 80;
        }
      }
    }
    return false;
  }
  function syncDarkMode() {
    if (!localStorage.getItem('vmf-theme')) {
      if (detectMorpheusDark()) document.body.classList.add('dark');
      else document.body.classList.remove('dark');
    }
  }
  var API = window.vmfApiBase || '/plugin/vmFolders';

  // Cloud name for an embedded tab, read from the Morpheus detail page.
  // Link-only matching ('a[href*="/infrastructure/clouds/"]') also matches the GROUP
  // row on host/cluster pages, which is how a group name ended up as the cloud filter.
  // Prefer the row whose own label says "Cloud"; fall back to a link, and let
  // activeCloudName() discard anything that matches no loaded VM.
  window.vmfDetectContextCloud = function() {
    try {
      var rows = document.querySelectorAll('.info-column li, .info-column tr, .info-column .detail-row, .info-column dl > div');
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var labelEl = row.querySelector('label, .label, dt, th, .detail-label');
        var label = (labelEl && labelEl.textContent || '').trim();
        if (!/^cloud\b/i.test(label)) continue;
        var a = row.querySelector('a[href*="/infrastructure/clouds/"]');
        if (a && a.textContent.trim()) return a.textContent.trim();
        var valEl = row.querySelector('.value, dd, td:last-child, span:last-child');
        var val = (valEl && valEl.textContent || '').replace(/^\s*cloud\s*:?\s*/i, '').trim();
        if (val) return val;
      }
    } catch (e) {}
    return '';
  };

  // ── Tab injection ──────────────────────────────────────────────────
  // v1.1.0: Tab injection removed for compute pages



  // v1.1.2: Cloud detail page injection
  // v1.1.3: Provisioning page injection
  function injectProvisioningLinks() {
    // Inject into ALL ul.provisioning.subnav — covers both page tab bar AND hover popover
    document.querySelectorAll('ul.provisioning.subnav').forEach(function(ul) {
      if (ul.querySelector('.vmf-prov-li')) return;
      var li = document.createElement('li');
      li.className = 'vmf-prov-li';
      li.innerHTML = '<a href="/plugin/vmFolders">&#128193; VM Folders</a>';
      ul.appendChild(li);
    });
  }


  function injectInfrastructureLinks() {
    document.querySelectorAll('ul.infrastructure.admin-filters').forEach(function(ul) {
      if (ul.querySelector('.vmf-infra-li')) return;
      var li = document.createElement('li');
      li.className = 'vmf-infra-li';
      li.innerHTML = '<a href="/plugin/vmFolders">&#128193; VM Folders</a>';
      ul.appendChild(li);
    });
  }

  // v1.1.2: Cloud detail page injection
  var cloudTabInjected = false;
  function injectCloudTab() {
    if (cloudTabInjected) return;
    var path = window.location.pathname;
    // Match cloud detail pages: /infrastructure/clouds/{id}
    if (!path.match(/\/infrastructure\/clouds\/\d+/)) return;
    var tabBar = document.getElementById('nav-tabs-wrapper');
    var tabContent = document.querySelector('.tab-content');
    if (!tabBar || !tabContent) return;
    if (document.getElementById('vmf-cloud-tab-li')) return;

    // Get cloud name from breadcrumb or page heading
    var cloudName = '';
    try {
      var crumb = document.querySelector('.breadcrumb li:last-child a, .breadcrumb li:last-child span');
      if (crumb) cloudName = crumb.textContent.trim();
      if (!cloudName) {
        var h1 = document.querySelector('h1, .detail-title, .page-title');
        if (h1) cloudName = h1.textContent.trim();
      }
    } catch(e) {}

    // Inject tab
    var li = document.createElement('li');
    li.id = 'vmf-cloud-tab-li';
    li.setAttribute('role','presentation');
    li.innerHTML = '<a href="#" style="cursor:pointer">&#128193; VM Folders</a>';
    tabBar.querySelector('ul') ? tabBar.querySelector('ul').appendChild(li) : tabBar.appendChild(li);
    li.querySelector('a').addEventListener('click', function(e) {
      e.preventDefault(); e.stopPropagation();
      if (cloudName) { cloudFilter = cloudName; window.vmfContextCloud = cloudName; }
      vmfShowOverlay();
    });
    cloudTabInjected = true;
  }

  var _lastNavHref = window.location.href;
  new MutationObserver(function() {
    if (window.location.href !== _lastNavHref) {
      _lastNavHref = window.location.href;
      cloudTabInjected = false;
      document.querySelectorAll('.vmf-prov-li').forEach(function(el){el.remove();});
    }
    if (window.location.href.includes('/infrastructure/clouds/')) injectCloudTab();
    injectProvisioningLinks();
  injectInfrastructureLinks();
    injectInfrastructureLinks();
    
  
  }).observe(document.body, {childList:true, subtree:true});
  if (window.location.href.includes('/infrastructure/clouds/')) injectCloudTab();
  injectProvisioningLinks();
  injectInfrastructureLinks();

  // ── API ────────────────────────────────────────────────────────────
  // X-VMF-Request marks this as the dashboard's own XHR. /vms reconciles vmfolder tags,
  // which can write, and a cross-origin GET cannot set a custom header without a CORS
  // preflight — so a plain browser GET of /vms stays strictly read-only (the read-only-GET rule).
  // opts.timeoutMs (1.5.8, lazy tabs only) aborts a request that hangs, so the tab ends
  // in an error with a Retry button instead of a spinner that never stops.
  async function get(path, opts) {
    var init = {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-VMF-Request': '1' }
    };
    var ms = opts && opts.timeoutMs, timer = null;
    if (ms && typeof AbortController === 'function') {
      var ac = new AbortController();
      init.signal = ac.signal;
      timer = setTimeout(function() { ac.abort(); }, ms);
    }
    try {
      var r = await fetch(API + path, init);
      return await r.json();
    } catch (e) {
      if (e && e.name === 'AbortError') throw new Error('no answer from ' + path.split('?')[0] + ' after ' + Math.round(ms / 1000) + ' s');
      throw e;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  // /config for the embedded tabs is kept for the browser session (1.5.8). The standalone
  // dashboard and every Refresh still fetch it, and rewrite this copy when they do.
  var CFG_KEY = 'vmFolders.config';
  function cfgRead() {
    try { var s = sessionStorage.getItem(CFG_KEY); return s ? JSON.parse(s) : null; } catch (e) { return null; }
  }
  function cfgWrite(cfg) {
    try { sessionStorage.setItem(CFG_KEY, JSON.stringify(cfg)); } catch (e) {}
  }

  // ── CSRF token ─────────────────────────────────────────────────────
  // Morpheus core runs Spring Security CSRF in front of /plugin/*: every POST
  // must carry the session token as an X-XSRF-TOKEN header (or a _csrf form
  // field) or core answers 302 → /error/invalid-csrf before the plugin sees it.
  // Core pages expose the token as <meta name="_csrf">. The standalone
  // dashboard is not a core page, so it falls back to a readable cookie, then
  // to fetching one core page and scraping the meta tag. Cached per page load.
  var CSRF_HEADER_DEFAULT = 'X-XSRF-TOKEN';
  var csrfCache = null;          // { header: 'X-XSRF-TOKEN', token: '...' }
  var csrfFetchPromise = null;   // in-flight scrape, so concurrent posts share it

  function csrfFromMeta(doc) {
    var m = (doc || document).querySelector('meta[name="_csrf"]');
    if (!m || !m.content) return null;
    var h = (doc || document).querySelector('meta[name="_csrf_header"]');
    return { header: (h && h.content) || CSRF_HEADER_DEFAULT, token: m.content };
  }

  // Standalone page only: pull a lightweight core page and read its meta tag.
  // Core's cookies are all HttpOnly, so document.cookie is empty here; this is
  // the only way the standalone dashboard can obtain the token.
  async function csrfFromCorePage() {
    if (csrfFetchPromise) return csrfFetchPromise;
    csrfFetchPromise = (async function() {
      var pages = ['/operations/dashboard', '/'];
      for (var i = 0; i < pages.length; i++) {
        try {
          var r = await fetch(pages[i], { credentials: 'same-origin', redirect: 'follow', headers: { Accept: 'text/html' } });
          if (!r.ok) continue;
          var doc = new DOMParser().parseFromString(await r.text(), 'text/html');
          var t = csrfFromMeta(doc);
          if (t) return t;
        } catch (e) { /* try the next page */ }
      }
      return null;
    })();
    try { return await csrfFetchPromise; }
    finally { csrfFetchPromise = null; }
  }

  async function getCsrf(force) {
    if (csrfCache && !force) return csrfCache;
    csrfCache = csrfFromMeta() || await csrfFromCorePage();
    if (!csrfCache) console.warn('vmFolders: no CSRF token found; core will reject POSTs');
    return csrfCache;
  }

  function csrfRejected(r) {
    // fetch follows the 302; the landing URL is what tells us core refused it
    return !!(r.redirected && /invalid-csrf/.test(r.url));
  }

  // All state-changing calls go through here: POST + custom header + core's
  // CSRF token. The plugin rejects GET and any request without X-VMF-Request;
  // core rejects anything without a valid token. One retry with a fresh token.
  async function post(path, params) {
    var body = new URLSearchParams();
    Object.keys(params || {}).forEach(function(k) {
      if (params[k] !== undefined && params[k] !== null) body.append(k, String(params[k]));
    });
    var bodyStr = body.toString();

    async function send(csrf) {
      var headers = {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'X-VMF-Request': '1'
      };
      if (csrf) headers[csrf.header] = csrf.token;
      return fetch(API + path, { method: 'POST', credentials: 'same-origin', headers: headers, body: bodyStr });
    }

    var r = await send(await getCsrf(false));
    if (csrfRejected(r)) r = await send(await getCsrf(true));
    if (csrfRejected(r)) return { success: false, error: 'rejected by server CSRF check (reload the page and retry)' };
    try { return await r.json(); }
    catch (e) { return { success: false, error: 'HTTP ' + r.status }; }
  }
  // Shared with the HBS tab templates so they use the same token logic.
  window.vmfPost = post;

  // Tag reconciliation stats from the last /vms call, reported by the Re-sync toast.
  var lastTagStats = null;
  // Server-side phase timings from the last /vms call (1.5.8), shown as the tooltip on a
  // lazy tab's "Loaded in N.N s" line and in vmfState().
  var lastVmsTiming = null;

  // opts (1.5.8, all optional): cached = accept the server's 30 s /vms cache and the
  // session copy of /config (first open of an embedded tab only — every other caller,
  // including Refresh and the reload after an action, asks for fresh data);
  // strict = throw when /vms reports an error; embedded = skip /scans, which only the
  // standalone dashboard's datastore section uses; timeoutMs = per-request timeout.
  async function fetchAll(opts) {
    opts = opts || {};
    var t = opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : null;
    var results = await Promise.all([get('/vms' + (opts.cached ? '' : '?fresh=1'), t), get('/db', t)]);
    if (opts.strict && results[0] && results[0].error) throw new Error(results[0].error);
    allVms = results[0].servers || [];
    var meta = results[0].meta || {};
    lastTagStats = meta.tags || null;
    lastVmsTiming = meta.timing || null;
    // /vms carries the labels too, so the tree is never rendered with a stale one.
    if (meta.labels) {
      if (meta.labels.all) labels.all = meta.labels.all;
      if (meta.labels.unorganized) labels.unorganized = meta.labels.unorganized;
    }
    var db = results[1];
    storedFolders = db.folders || [];
    assignments = db.assignments || {};
    await loadConfig(opts);
    return db;
  }

  // Labels, tag mode, scan state and cached scan results. Failures here never break the
  // folder view: every value falls back to its 1.4.x default.
  async function loadConfig(opts) {
    opts = opts || {};
    try {
      var t = opts.timeoutMs ? { timeoutMs: opts.timeoutMs } : null;
      var cfg = opts.cached ? cfgRead() : null;
      if (!cfg) {
        cfg = await get('/config', t);
        if (cfg && cfg.success === true) cfgWrite(cfg);
      }
      scanEnabled = cfg.enabled === true;
      scanStores = cfg.datastores || [];
      tagMode = cfg.tagMode || 'off';
      tagName = cfg.tagName || 'vmfolder';
      if (cfg.labelAll) labels.all = cfg.labelAll;
      if (cfg.labelUnorganized) labels.unorganized = cfg.labelUnorganized;
      if (scanEnabled && !opts.embedded) {
        var r = await get('/scans');
        scanCache = r.scans || {};
      } else { scanCache = {}; }
    } catch (e) { scanEnabled = false; scanStores = []; scanCache = {}; }
  }

  // VMs that carry a vmfolder tag right now. Only ever non-empty in keep mode — in
  // consume mode the tag is gone before the row is rendered.
  function taggedVms() { return allVms.filter(function(vm){ return vm.tagged === true; }); }
  function taggedUnder(path) {
    if (!path) return [];
    return taggedVms().filter(function(vm) {
      var tp = vm.tagPath || '';
      return tp === path || tp.indexOf(path + '/') === 0;
    });
  }

  function scanStoreByName(name) {
    for (var i = 0; i < scanStores.length; i++) {
      if (scanStores[i].name === name) return scanStores[i];
    }
    return null;
  }
  function scanFor(name) {
    var st = scanStoreByName(name);
    return st ? scanCache[String(st.id)] : null;
  }
  function ageText(iso) {
    if (!iso) return '';
    var t = Date.parse(iso);
    if (isNaN(t)) return '';
    var mins = Math.round((Date.now() - t) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + ' minute' + (mins !== 1 ? 's' : '') + ' ago';
    var hrs = Math.round(mins / 60);
    if (hrs < 48) return hrs + ' hour' + (hrs !== 1 ? 's' : '') + ' ago';
    return Math.round(hrs / 24) + ' days ago';
  }

  // ── Folder helpers ─────────────────────────────────────────────────
  function getVmFolder(vm) { return vm.folderPath || ROOT; }

  function allPaths() {
    // Apply all active filters to determine visible VMs
    var srcVms = allVms;
    if (window.vmfHostId) srcVms = srcVms.filter(function(vm){ return String(vm.hostId||'') === String(window.vmfHostId); });
    var activeCloud = activeCloudName();
    if (activeCloud) srcVms = srcVms.filter(function(vm){ return (vm.cloudName||'') === activeCloud; });

    // Build set of paths from visible VMs
    var assignedPaths = new Set();
    srcVms.forEach(function(vm){ var p=getVmFolder(vm); if(p!==ROOT) assignedPaths.add(p); });

    // Only include stored folders that have VMs in current filter
    var s = new Set();
    storedFolders.forEach(function(f){
      var hasVms = Array.from(assignedPaths).some(function(p){ return p===f.path || p.startsWith(f.path+'/'); });
      if (hasVms) s.add(f.path);
    });
    srcVms.forEach(function(vm){ var p=getVmFolder(vm); if(p!==ROOT) s.add(p); });
    Array.from(s).forEach(function(p){var parts=p.split('/').filter(Boolean);for(var i=1;i<parts.length;i++)s.add('/'+parts.slice(0,i).join('/'));});
    return Array.from(s).sort();
  }

  function countIn(path) {
    return scopedVms().filter(function(vm){var p=getVmFolder(vm); return p===path||p.startsWith(path+'/');}).length;
  }

  // The active cloud filter, but ONLY if it names a cloud present in the loaded data.
  // window.vmfContextCloud is scraped from the Morpheus DOM by the tab templates; when
  // that scrape returns a label that is not a cloud name (or a name that no longer
  // matches after a reload) an unvalidated filter silently empties every view — the
  // folder tree included, which is why "No folders yet" appeared on the host tab.
  function activeCloudName() {
    var ac = (cloudFilter !== '__all__') ? cloudFilter : (window.vmfContextCloud || null);
    if (!ac) return null;
    var known = allVms.some(function(vm){ return (vm.cloudName||'') === ac; });
    if (!known) {
      try { console.warn('vmFolders: ignoring cloud filter "'+ac+'" — no VM reports that cloud'); } catch(e) {}
      return null;
    }
    return ac;
  }

  // VMs narrowed by the cloud chips and host context only (no folder/datastore/search).
  function scopedVms() {
    var src = allVms;
    var ac = activeCloudName();
    if (ac) src = src.filter(function(vm){ return (vm.cloudName||'') === ac; });
    if (window.vmfHostId) src = src.filter(function(vm){ return String(vm.hostId||'') === String(window.vmfHostId); });
    return src;
  }

  // ── Datastores (derived client-side from /vms; tenant scoping already applied server-side) ──
  function vmDisks(vm) { return Array.isArray(vm.disks) ? vm.disks : []; }
  function vmDatastoreNames(vm) { return Array.isArray(vm.datastores) ? vm.datastores : []; }

  function vmOnDatastore(vm, key) {
    // "No datastore" is VMs with no datastore AT ALL (including VMs with no disks) —
    // not VMs that merely have one disk without one. A mixed VM (a datastore-backed
    // disk plus, say, a mounted ISO) belongs to its real datastores; the null-datastore
    // disk shows as — in the expanded detail row, which is the right place for it.
    if (key === NO_DS) return vmDatastoreNames(vm).length === 0;
    return vmDatastoreNames(vm).indexOf(key) !== -1;
  }

  // { name: {vms, disks} } plus a NO_DS bucket, using the same membership rule as
  // vmOnDatastore() so a count can never disagree with what clicking it lists.
  // A VM with disks on two datastores counts in both.
  function datastoreStats() {
    var stats = {};
    function bucket(k) { if (!stats[k]) stats[k] = { vms: 0, disks: 0 }; return stats[k]; }
    scopedVms().forEach(function(vm) {
      var names = vmDatastoreNames(vm);
      if (!names.length) {
        var b = bucket(NO_DS);
        b.vms++;
        b.disks += vmDisks(vm).length;   // zero for a VM with no disks at all
        return;
      }
      names.forEach(function(n) { bucket(n).vms++; });
      vmDisks(vm).forEach(function(d) {
        if (d.datastore) bucket(d.datastore).disks++;
      });
    });
    return stats;
  }

  function renderDatastores() {
    var el = document.getElementById('vmf-dslist'), head = document.getElementById('vmf-ds-head');
    if (!el) return;                       // embedded tabs have no datastore section
    var stats = datastoreStats();
    var names = Object.keys(stats).filter(function(k){ return k !== NO_DS; }).sort();
    if (!names.length && !stats[NO_DS]) { el.style.display = 'none'; if (head) head.style.display = 'none'; return; }
    el.style.display = ''; if (head) head.style.display = '';
    var html = '';
    names.forEach(function(n) { html += dsItem(n, n, stats[n]); });
    if (stats[NO_DS]) {
      if (names.length) html += '<div class="vmf-divider"></div>';
      html += dsItem(NO_DS, 'No datastore', stats[NO_DS]);
    }
    el.innerHTML = html;
  }

  function dsItem(key, label, st) {
    var active = activeDatastore === key;
    var tip = st.vms + ' VM' + (st.vms!==1?'s':'') + ', ' + st.disks + ' disk' + (st.disks!==1?'s':'') + ' — a VM with disks on several datastores is counted in each';
    // Scan button appears only for a real datastore the server says is scannable,
    // and only when an administrator has enabled scanning.
    var store = (key !== NO_DS && scanEnabled) ? scanStoreByName(key) : null;
    var scanBtn = store
      ? '<button class="vmf-fi-btn vmf-scan-btn" data-scan-id="' + esc(store.id) + '" title="Scan this datastore for files Morpheus does not know about">&#128269;</button>'
      : '';
    return '<div class="vmf-fi' + (active ? ' active' : '') + '" data-ds-key="' + esc(key) + '" title="' + esc(tip) + '">' +
      '<span class="vmf-fi-icon">&#128451;</span>' +
      '<span class="vmf-fi-name">' + esc(label) + '</span>' +
      '<span class="vmf-fi-count">' + st.vms + '</span>' +
      (scanBtn ? '<div class="vmf-fi-actions">' + scanBtn + '</div>' : '') + '</div>';
  }

  function isStored(path) { return storedFolders.some(function(f) { return f.path === path; }); }

  // ── Tree ───────────────────────────────────────────────────────────
  function renderCloudBar() {
    var bar = document.getElementById('vmf-cloud-bar');
    if (!bar) return;
    var clouds = {};
    allVms.forEach(function(vm){ if(vm.cloudName) clouds[vm.cloudName]=true; });
    var names = Object.keys(clouds).sort();
    if (names.length <= 1) { bar.style.display='none'; return; }
    bar.style.display='flex';
    bar.innerHTML='';
    function makeCtab(cloud, label) {
      var btn = document.createElement('button');
      btn.style.cssText = ctabStyle(cloudFilter===cloud);
      btn.textContent = label;
      btn.addEventListener('click', function(e) {
        e.stopPropagation();
        cloudFilter = cloud;
        window.vmfContextCloud = cloud==='__all__' ? null : cloud;
        renderCloudBar(); renderTree(); renderDatastores(); renderVms();
      });
      bar.appendChild(btn);
    }
    makeCtab('__all__', 'All');
    names.forEach(function(c){ makeCtab(c, c); });
  }

  function ctabStyle(active) {
    return 'padding:2px 8px;border-radius:10px;font-size:11px;font-weight:'+(active?'600':'400')+';cursor:pointer;border:1px solid '+(active?'var(--hpe-green-dark,#008567)':'var(--hpe-border,#CCCCCC)')+';background:'+(active?'var(--hpe-green,#01A982)':'transparent')+';color:'+(active?'#fff':'var(--hpe-muted,#767676)')+';font-family:-apple-system,sans-serif;white-space:nowrap;line-height:1.4;';
  }

  // Read-only state dump for support: type vmfState() in the console. Kept out of the
  // render path so nothing is logged during normal use.
  window.vmfState = function() {
    return {
      instances: window.__vmfInstances || 1,
      allVms: allVms.length, scoped: scopedVms().length, filtered: getFiltered().length,
      storedFolders: storedFolders.length, paths: allPaths().length,
      activeFolder: activeFolder, activeDatastore: activeDatastore,
      cloudFilter: cloudFilter, contextCloud: window.vmfContextCloud || null, cloudUsed: activeCloudName(),
      hostId: window.vmfHostId || null, readOnly: !!window.vmfReadOnly, vmsTiming: lastVmsTiming,
      tagMode: tagMode, tagged: taggedVms().length,
      labels: { all: labels.all, unorganized: labels.unorganized },
      lastTagStats: lastTagStats,
      clouds: Array.from(new Set(allVms.map(function(vm){ return vm.cloudName||''; }))).sort()
    };
  };

  function renderTree() {
    var paths = allPaths();
    var html = treeItem('__all__', '&#128196;', labels.all, scopedVms().length, 0, null, false);
    html += '<div class="vmf-divider"></div>';
    if (!paths.length) {
      html += '<div style="padding:10px 14px;color:#767676;font-size:12px;line-height:1.6">No folders yet.<br>Click <b>+ Folder</b> to create one.</div>';
    } else {
      paths.forEach(function(p) {
        var depth = p.split('/').filter(Boolean).length;
        var name = p.split('/').filter(Boolean).pop() || '/';
        html += treeItem(p, '&#128193;', name, countIn(p), depth > 1 ? (depth-1)*16 : 0, p, isStored(p));
      });
    }
    var unorg = scopedVms().filter(function(vm) { return getVmFolder(vm) === ROOT; }).length;
    if (unorg > 0) {
      html += '<div class="vmf-divider"></div>';
      html += treeItem(ROOT, '&#128220;', labels.unorganized, unorg, 0, null, false);
    }
    var el = document.getElementById('vmf-flist');
    if (el) el.innerHTML = html;
  }

  function treeItem(key, icon, name, count, indent, fullPath, stored) {
    var active = !activeDatastore && activeFolder === key;
    var cls = 'vmf-fi' + (active ? ' active' : '');
    var style = indent ? ' style="padding-left:' + (14 + indent) + 'px"' : '';
    var title = fullPath ? ' title="' + esc(fullPath) + '"' : '';
    // Rename is offered on every real folder path, including an auto-created top level
    // with no stored record — renFolder rewrites paths and assignments, not records, and
    // a renamed auto folder keeps its origin so Re-sync keeps feeding it.
    var actions = '';
    if (!window.vmfReadOnly && fullPath) {
      actions = '<div class="vmf-fi-actions">' +
        '<button class="vmf-fi-btn" data-rename="' + esc(key) + '" title="Rename">&#9998;</button>' +
        (stored ? '<button class="vmf-fi-btn del" data-delfolder="' + esc(key) + '" title="Delete">&#10006;</button>' : '') +
        '</div>';
    }
    return '<div class="' + cls + '"' + style + ' data-folder-key="' + esc(key) + '"' + title + '>' +
      '<span class="vmf-fi-icon">' + icon + '</span>' +
      '<span class="vmf-fi-name">' + esc(name) + '</span>' +
      '<span class="vmf-fi-count">' + count + '</span>' +
      actions + '</div>';
  }

  document.addEventListener('click', function(e) {
    var act = e.target.closest('[data-actid]');
    if (act) { e.stopPropagation(); vmfShowActions(parseInt(act.getAttribute('data-actid')), act); return; }

    var ren = e.target.closest('[data-rename]');
    var del = e.target.closest('[data-delfolder]');
    var fi  = e.target.closest('[data-folder-key]');
    var di  = e.target.closest('[data-ds-key]');
    var sc  = e.target.closest('[data-scan-id]');
    if (sc) { e.stopPropagation(); vmfScanDatastore(sc.getAttribute('data-scan-id')); return; }
    if (ren) { e.stopPropagation(); vmfRenameFolder(ren.getAttribute('data-rename')); return; }
    if (del) { e.stopPropagation(); vmfDeleteFolder(del.getAttribute('data-delfolder')); return; }
    if (fi)  { vmfSelectFolder(fi.getAttribute('data-folder-key')); return; }
    if (di)  vmfSelectDatastore(di.getAttribute('data-ds-key'));
  });
  document.addEventListener('change', function(e) {
    if (e.target && e.target.id === 'vmf-cloud-filter') { cloudFilter = e.target.value; renderVms(); }
  });

  // ── VM table ───────────────────────────────────────────────────────
  function getFiltered() {
    var vms = activeDatastore ? allVms.filter(function(vm) { return vmOnDatastore(vm, activeDatastore); }) :
              activeFolder === '__all__' ? allVms.slice() :
              activeFolder === ROOT ? allVms.filter(function(vm) { return getVmFolder(vm) === ROOT; }) :
              allVms.filter(function(vm) { var p = getVmFolder(vm); return p === activeFolder || p.startsWith(activeFolder + '/'); });
    var ac = activeCloudName();
    if (ac) vms = vms.filter(function(vm){ return (vm.cloudName||'') === ac; });
    if (window.vmfHostId) vms = vms.filter(function(vm){ return String(vm.hostId||'') === String(window.vmfHostId); });
    if (searchQ) {
      var q = searchQ.toLowerCase();
      vms = vms.filter(function(vm) {
        return (vm.name||'').toLowerCase().includes(q) || (vm.externalIp||'').includes(q) ||
               (vm.internalIp||'').includes(q) || (vm.osType||'').toLowerCase().includes(q) ||
               (vm.cloudName||'').toLowerCase().includes(q) || dsLabel(vm).toLowerCase().includes(q);
      });
    }
    return vms.sort(function(a,b) {
      var av=sortVal(a), bv=sortVal(b);
      return sortAsc ? (av<bv?-1:av>bv?1:0) : (av>bv?-1:av<bv?1:0);
    });
  }
  // Sort key for the active column; arrays (datastores) sort on their joined label.
  function sortVal(vm) {
    var v = vm[sortCol];
    if (Array.isArray(v)) return v.join(', ').toLowerCase();
    return String(v||'').toLowerCase();
  }
  function dsLabel(vm) { return Array.isArray(vm.datastores) && vm.datastores.length ? vm.datastores.join(', ') : ''; }

  function dot(status) {
    var s = String(status||'').toLowerCase();
    var c = s.match(/on|running/) ? '#01A982' : s.match(/off|stopped/) ? '#CC0000' : '#CCCCCC';
    return '<span class="vmf-dot" style="background:' + c + '"></span>';
  }
  function fmtMem(b) { if(!b) return '—'; var g=b/1073741824; return g>=1?g.toFixed(1)+' GB':Math.round(b/1048576)+' MB'; }
  // Disk sizes: TB / GB / MB. 0 or null renders as '—' (VME/KVM report usedStorage=0).
  function fmtMemSmall(b) {
    b = Number(b||0); if(!b) return '—';
    var t=b/1099511627776; if(t>=1) return (t>=10?t.toFixed(1):t.toFixed(2))+' TB';
    var g=b/1073741824;    if(g>=1) return (g>=100?Math.round(g):g.toFixed(1))+' GB';
    return Math.round(b/1048576)+' MB';
  }
  function diskDetailRow(vm) {
    var disks = vmDisks(vm);
    if (!disks.length) return '';
    var rows = disks.map(function(d) {
      var type = d.type || '';
      if (d.removable && !/cd|dvd|iso|removable/i.test(type)) type = type ? type + ' (removable)' : 'removable';
      return '<tr><td>'+esc(d.name||'—')+'</td><td>'+esc(type||'—')+'</td><td>'+esc(d.datastore||'—')+'</td><td>'+fmtMemSmall(d.used)+'</td><td>'+fmtMemSmall(d.total)+'</td><td>'+(d.root?'&#10003;':'')+'</td></tr>';
    }).join('');
    return '<tr class="vmf-disk-row" data-disk-for="'+vm.id+'" hidden><td colspan="11">' +
      '<table class="vmft-sub"><thead><tr><th>Disk</th><th>Type</th><th>Datastore</th><th>Used</th><th>Total</th><th>Root</th></tr></thead><tbody>'+rows+'</tbody></table>' +
      '</td></tr>';
  }

  // Listeners are bound ONCE per container. #vmf-vlist persists across renders
  // (only its innerHTML is replaced), so binding inside renderVms stacked a new
  // handler on every render: after one folder click the chevron toggled twice
  // and appeared dead, and sort flipped direction twice. Delegation + a guard flag.
  function bindVlist(el) {
    if (el._vmfBound) return;
    el._vmfBound = true;
    el.addEventListener('change', function(e) {
      if (e.target.id === 'vmf-ca') { vmfToggleAll(e.target.checked); return; }
      if (e.target.classList.contains('vmf-cb')) vmfToggleSel(parseInt(e.target.dataset.id), e.target.checked);
    });
    el.addEventListener('click', function(e) {
      var mb=e.target.closest('.vmf-move-btn'), rb=e.target.closest('.vmf-rm-btn'), sh=e.target.closest('[data-sort]'), pb=e.target.closest('.vmf-pw-btn');
      var sc2=e.target.closest('[data-scan-id]');
      if (sc2) { vmfScanDatastore(sc2.getAttribute('data-scan-id')); return; }
      var dmv=e.target.closest('.vmf-dsmove-btn'), drm=e.target.closest('.vmf-dsrm-btn');
      if (dmv) { vmfMoveScanEntry(dmv.getAttribute('data-dskey')); return; }
      if (drm) { vmfRemoveScanEntry(drm.getAttribute('data-dskey')); return; }
      var ch=e.target.closest('[data-chev]');
      if (ch) {
        var dr = el.querySelector('tr.vmf-disk-row[data-disk-for="'+ch.getAttribute('data-chev')+'"]');
        if (dr) { dr.hidden = !dr.hidden; ch.classList.toggle('open', !dr.hidden); }
        return;
      }
      if (mb) vmfMoveSingle(parseInt(mb.dataset.id));
      if (rb) vmfRemoveSingle(parseInt(rb.dataset.id));
      if (sh) vmfSort(sh.getAttribute('data-sort'));
      if (pb && !pb.disabled) {
        var pid = parseInt(pb.dataset.id), paction = pb.dataset.action;
        var pvm = allVms.find(function(v){return v.id===pid;}) || {};
        var pname = pvm.name || 'VM '+pid;
        var plabel = paction==='start' ? 'Start' : paction==='stop' ? 'Stop' : 'Restart';
        // A hypervisor host carries every VM running on it — stopping or restarting one
        // is not the same action as power-cycling a guest, so it gets its own warning.
        var isHost = pvm.isHost === true;
        var title = isHost ? plabel+' HYPERVISOR HOST' : plabel+' VM';
        var body  = isHost && paction !== 'start'
          ? plabel+' the host "'+esc(pname)+'"? This is a hypervisor host — every VM running on it will go down.'
          : plabel+' "'+esc(pname)+'"?';
        openConfirm(title, body, function() {
          pb.disabled = true; pb.style.opacity = '0.5';
          setTimeout(function() { pb.disabled = false; pb.style.opacity = ''; }, 8000);
          vmfPower(pid, paction);
        });
      }
    });
  }

  function renderVms() {
    var vms = getFiltered(), el = document.getElementById('vmf-vlist');
    if (!el) return;
    if (!vms.length) {
      el.innerHTML = '<div class="vmf-empty"><div class="vmf-empty-icon">'+(activeDatastore?'&#128451;':'&#128193;')+'</div><div>' + (searchQ?'No matches.':activeDatastore?'No VMs on this datastore.':'Folder is empty.') + '</div></div>' + scanPanel();
      bindVlist(el);
      setStatus('0 VMs'); return;
    }
    var cols = [['name','Name'],['powerState','Status'],['osType','OS'],['maxMemory','Memory'],['maxCores','vCPU'],['externalIp','IP'],['cloudName','Cloud'],['datastores','Datastore']];
    var html = '<table class="vmft"><thead><tr><th style="width:26px"><input type="checkbox" id="vmf-ca"></th>';
    cols.forEach(function(c) {
      var s = sortCol===c[0];
      html += '<th class="'+(s?'sorted':'')+'" data-sort="'+c[0]+'">'+c[1]+(s?(sortAsc?' &#9650;':' &#9660;'):'')+' </th>';
    });
    html += '<th>Folder</th><th>Actions</th></tr></thead><tbody>';
    vms.forEach(function(vm) {
      var id=vm.id, fp=getVmFolder(vm), sel=selectedIds.has(id);
      var ip = vm.externalIp||vm.internalIp||'—';
      var folderLabel = fp===ROOT
        ? '<span style="color:#767676;font-style:italic;font-size:11px">'+esc(labels.unorganized)+'</span>'
        : '<span class="vmf-tag">'+esc(fp.split('/').filter(Boolean).pop()||'/')+' </span>';
      var statusStr = String(vm.powerState||'unknown');
      var isOn = statusStr.toLowerCase().match(/on|running/);
      var hasDisks = vmDisks(vm).length > 0;
      // Label only — power buttons and actions stay available for unmanaged VMs.
      var unmanaged = vm.unmanaged === true;
      var chev = hasDisks ? '<span class="vmf-chev" data-chev="'+id+'" title="Show disks">&#9654;</span>' : '<span class="vmf-chev-none"></span>';
      html += '<tr class="'+(sel?'sel':'')+'">' +
        '<td><input type="checkbox" class="vmf-cb" data-id="'+id+'"'+(sel?' checked':'')+' ></td>' +
        '<td class="vmft-name">'+chev+'<a href="/infrastructure/servers/'+id+'" target="_blank">'+esc(vm.name||'VM-'+id)+'</a>' +
        (unmanaged ? ' <span class="vmf-badge-un" title="Discovered on the hypervisor, not provisioned by Morpheus">unmanaged</span>' : '') +
        (vm.tagged ? ' <span class="vmf-badge-tag" title="Carries a '+esc(tagName)+' tag for '+esc(vm.tagPath||'')+'. The tag is the source of truth in keep mode: a Move here is reverted on the next reconcile.">tagged</span>' : '') + '</td>' +
        '<td>'+dot(vm.powerState)+'<span style="vertical-align:middle">'+esc(statusStr)+'</span></td>' +
        '<td>'+esc((function(o){return(!o||o.includes('@')||o.includes('morpheus'))?'—':o;})(vm.osType))+'</td>' +
        '<td>'+fmtMem(vm.maxMemory)+'</td>' +
        '<td>'+(vm.maxCores||'—')+'</td>' +
        '<td>'+esc(ip)+'</td>' +
        '<td>'+esc(vm.cloudName||'—')+'</td>' +
        '<td>'+esc(dsLabel(vm)||'—')+'</td>' +
        '<td>'+folderLabel+'</td>' +
        '<td><div style="display:flex;gap:3px;flex-wrap:wrap">' +
        (!window.vmfReadOnly ? '<button class="vmf-act vmf-move-btn" data-id="'+id+'">Move</button>' : '') +
        '<a class="vmf-act vmf-act-console" href="/terminal/server/'+id+'?consoleMode=hypervisor" target="_blank" title="Open console">&#9654;</a>' +
        (isOn ? '<button class="vmf-act vmf-pw-btn vmf-stop-btn" data-id="'+id+'" data-action="stop" title="Stop VM">&#9632; Stop</button>' : '<button class="vmf-act vmf-pw-btn vmf-start-btn" data-id="'+id+'" data-action="start" title="Start VM">&#9654; Start</button>') +
        (!window.vmfReadOnly && fp!==ROOT ? '<button class="vmf-act vmf-act-x vmf-rm-btn" data-id="'+id+'" title="Remove from folder">&#10006;</button>' : '') +
        '</div></td></tr>' + diskDetailRow(vm);
    });
    html += '</tbody></table>';
    el.innerHTML = html + scanPanel();

    bindVlist(el);
    // Status reflects the rows actually listed; when host/cloud scoping or a folder
    // narrows the set, name the total too so the footer can't contradict the table.
    var total = allVms.length;
    setStatus(vms.length+' VM'+(vms.length!==1?'s':'')+(vms.length!==total?' of '+total:'')+(searchQ?' (filtered)':''));
  }

  // ── Public ─────────────────────────────────────────────────────────
  // Fetch + render. Throws on failure so a lazy tab can show the error and a Retry
  // button; vmfReload() below keeps its 1.5.7 behaviour of reporting inside the table.
  async function reloadCore(opts) {
    await fetchAll(opts);
    // Auto-detect cloud from context read off the Morpheus page (DOM-read synchronously).
    // Adopt it only when it names a cloud the loaded VMs actually report, so a bad
    // DOM scrape cannot latch a filter that empties every view.
    if (cloudFilter === '__all__' && window.vmfContextCloud) {
      if (allVms.some(function(vm){ return (vm.cloudName||'') === window.vmfContextCloud; })) {
        cloudFilter = window.vmfContextCloud;
      } else {
        try { console.warn('vmFolders: context cloud "'+window.vmfContextCloud+'" matches no VM; staying on All'); } catch(e) {}
        window.vmfContextCloud = null;
      }
    }
    renderCloudBar(); renderTree(); renderDatastores(); renderVms();
    // The page and both tabs start with a static "All VMs" breadcrumb; replace it
    // with the configured label once config has loaded (also covers host/cluster tabs).
    if (!activeDatastore) setBreadcrumb(activeFolder);
    // renderVms() has already set the status to the rows it listed — do not overwrite
    // it with the unscoped total (that is what showed "21 VMs loaded" against 9 rows).
  }

  window.vmfReload = async function() {
    syncDarkMode();
    setStatus('Loading...');
    var fl=document.getElementById('vmf-flist'), vl=document.getElementById('vmf-vlist');
    if(fl) fl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div>';
    if(vl) vl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading VMs...</div>';
    selectedIds.clear(); updateMvBtn();
    try {
      await reloadCore();
    } catch(e) {
      if(vl) vl.innerHTML='<div class="vmf-empty"><div class="vmf-empty-icon">&#9888;</div><div style="color:#c00">Error: '+esc(e.message)+'</div></div>';
      setStatus('Error');
    }
  };

  // Breadcrumb text for a folder key, honouring the configured root labels.
  function bcLabel(key) { return key==='__all__' ? labels.all : key===ROOT ? labels.unorganized : key; }
  function setBreadcrumb(key) {
    var bc=document.getElementById('vmf-bc');
    if(bc) bc.innerHTML='&#128193; <b>'+esc(bcLabel(key))+'</b>';
  }

  window.vmfSelectFolder = function(key) {
    resetSelection(key, null); updateMvBtn(); renderTree(); renderDatastores(); renderVms();
    setBreadcrumb(key);
  };

  // On-disk entries are assigned with the key ds:<datastoreId>:<dirname>; the server
  // validates the key against its cached scan before writing it.
  window.vmfMoveScanEntry = function(key) {
    var paths = allPaths();
    var opts = paths.map(function(p){ return '<option value="'+esc(p)+'">'+esc(p)+'</option>'; }).join('');
    openModal('Move to Folder', [
      fg('Folder', '<select id="vmf-dsmove-sel">'+opts+'</select>', 'Or type a new path below'),
      fg('New path', '<input id="vmf-dsmove-new" type="text" placeholder="/Production/Imported">', '')
    ], [
      {l:'Cancel', fn:vmfCloseModal},
      {l:'Move', primary:true, fn: async function() {
        var sel = document.getElementById('vmf-dsmove-sel');
        var nw  = document.getElementById('vmf-dsmove-new');
        var path = (nw && nw.value.trim()) || (sel && sel.value) || '';
        if (!path) return;
        if (path.charAt(0) !== '/') path = '/' + path;
        vmfCloseModal();
        var d = await post('/assign', {vmId: key, path: path});
        if (d.success) { await fetchAll(); renderTree(); renderDatastores(); renderVms(); vmfToast('Moved'); }
        else vmfToast('Move failed: ' + (d.error || 'unknown'), true);
      }}
    ]);
  };

  window.vmfRemoveScanEntry = async function(key) {
    var d = await post('/unassign', {vmId: key});
    if (d.success) { await fetchAll(); renderTree(); renderDatastores(); renderVms(); vmfToast('Removed from folder'); }
    else vmfToast('Remove failed: ' + (d.error || 'unknown'), true);
  };

  // Runs a scan on one datastore, then re-renders. Never runs on page load.
  window.vmfScanDatastore = async function(dsId) {
    if (scanBusy) { vmfToast('A scan is already running', true); return; }
    var store = null;
    for (var i = 0; i < scanStores.length; i++) { if (String(scanStores[i].id) === String(dsId)) store = scanStores[i]; }
    if (!store) { vmfToast('Datastore not scannable', true); return; }
    scanBusy = true;
    setStatus('Scanning ' + store.name + '…');
    try {
      var d = await post('/scan', { datastoreId: dsId });
      if (d.success) {
        scanCache[String(dsId)] = d.scan;
        if (d.scan && d.scan.readable === false) {
          vmfToast('Scanned, but not readable: ' + (d.scan.error || 'permission denied'), true);
        } else {
          var n = (d.scan && d.scan.vmDirs ? d.scan.vmDirs.filter(function(v){ return !v.inMorpheus; }).length : 0);
          vmfToast('Scanned ' + store.name + ' — ' + n + ' on-disk VM' + (n !== 1 ? 's' : '') + ' not in Morpheus');
        }
        if (activeDatastore !== store.name) vmfSelectDatastore(store.name);
        else { renderDatastores(); renderVms(); }
      } else {
        vmfToast(d.timedOut ? 'Scan timed out' : 'Scan failed: ' + (d.error || 'unknown'), true);
      }
    } catch (e) {
      vmfToast('Scan error: ' + e.message, true);
    } finally { scanBusy = false; }
  };

  // Scan results for the datastore currently being browsed, rendered under the table.
  function scanPanel() {
    if (!scanEnabled || !activeDatastore || activeDatastore === NO_DS) return '';
    var store = scanStoreByName(activeDatastore);
    if (!store) return '';
    var sc = scanCache[String(store.id)];
    if (!sc) {
      return '<div class="vmf-scan-panel"><div class="vmf-scan-head">' +
        '<b>On disk</b><span class="vmf-scan-meta">not scanned yet</span>' +
        '<button class="vmf-btn vmf-btn-outline vmf-btn-sm" data-scan-id="' + esc(store.id) + '">&#128269; Scan datastore</button>' +
        '</div></div>';
    }
    var head = '<div class="vmf-scan-head"><b>On disk</b>' +
      '<span class="vmf-scan-meta">scanned on ' + esc(sc.scannedOn || '?') + ' ' + esc(ageText(sc.at)) + '</span>' +
      '<button class="vmf-btn vmf-btn-outline vmf-btn-sm" data-scan-id="' + esc(store.id) + '">&#8635; Rescan</button></div>';

    if (sc.readable === false) {
      return '<div class="vmf-scan-panel">' + head +
        '<div class="vmf-scan-warn">' + esc(sc.error || 'This datastore could not be read on the scanning host.') + '</div></div>';
    }

    var orphans = (sc.vmDirs || []).filter(function(v){ return !v.inMorpheus; });
    var body = '';

    body += '<div class="vmf-scan-sect">On disk, not in Morpheus <span class="vmf-fi-count">' + orphans.length + '</span></div>';
    if (!orphans.length) {
      body += '<div class="vmf-scan-empty">Every VM directory on this datastore matches a VM in Morpheus.</div>';
    } else {
      body += '<table class="vmft-sub vmf-scan-tbl"><thead><tr><th>Directory</th><th>Disks</th><th>Size</th><th>XML</th><th>Newest</th><th>Folder</th>' +
              (window.vmfReadOnly ? '' : '<th></th>') + '</tr></thead><tbody>';
      orphans.forEach(function(v) {
        var key = 'ds:' + store.id + ':' + v.name;
        var fp = assignments[key] || '';
        var kindBadge = v.kind === 'collection'
          ? ' <span class="vmf-badge-un" title="Several domain XMLs, or XMLs that do not match the directory name — a backup or collection dump rather than one VM">backup/collection</span>'
          : '';
        body += '<tr><td>' + esc(v.name) + kindBadge + '</td>' +
          '<td>' + (v.diskFiles ? v.diskFiles.length : 0) + '</td>' +
          '<td>' + fmtMemSmall(v.totalBytes) + '</td>' +
          '<td>' + esc((v.xmlFiles || []).join(', ') || '—') + '</td>' +
          '<td>' + esc(v.newest || '—') + '</td>' +
          '<td>' + (fp ? '<span class="vmf-tag">' + esc(fp.split('/').filter(Boolean).pop() || '/') + '</span>'
                       : '<span style="color:#767676;font-style:italic">' + esc(labels.unorganized) + '</span>') + '</td>' +
          (window.vmfReadOnly ? '' :
            '<td><button class="vmf-act vmf-dsmove-btn" data-dskey="' + esc(key) + '">Move</button>' +
            (fp ? '<button class="vmf-act vmf-act-x vmf-dsrm-btn" data-dskey="' + esc(key) + '" title="Remove from folder">&#10006;</button>' : '') + '</td>') +
          '</tr>';
      });
      body += '</tbody></table>';
    }

    // Directories with disk-sized files but no domain XML. Not "VMs" by the detection
    // rule, but a 30 GB disk with no XML is worth surfacing, not burying in Other.
    var diskDirs = (sc.diskDirs || []).filter(function(d){ return !d.inMorpheus; });
    body += '<div class="vmf-scan-sect">Disk-only directories <span class="vmf-fi-count">' + diskDirs.length + '</span></div>';
    if (!diskDirs.length) {
      body += '<div class="vmf-scan-empty">No directories with disks but no domain XML.</div>';
    } else {
      body += '<table class="vmft-sub vmf-scan-tbl"><thead><tr><th>Directory</th><th>Disks</th><th>Size</th><th>XML</th><th>Newest</th></tr></thead><tbody>';
      diskDirs.forEach(function(d) {
        body += '<tr><td>' + esc(d.name) + '</td>' +
          '<td>' + (d.diskFiles ? d.diskFiles.length : 0) + '</td>' +
          '<td>' + fmtMemSmall(d.totalBytes) + '</td>' +
          '<td>' + esc((d.xmlFiles || []).join(', ') || '—') + '</td>' +
          '<td>' + esc(d.newest || '—') + '</td></tr>';
      });
      body += '</tbody></table>';
    }

    var orphanVols = (sc.volumes || []).filter(function(v){ return !v.volumeId; });
    body += '<div class="vmf-scan-sect">Orphaned volumes <span class="vmf-fi-count">' + orphanVols.length + '</span></div>';
    if (!orphanVols.length) {
      body += '<div class="vmf-scan-empty">No volume files without a matching Morpheus record.</div>';
    } else {
      body += '<table class="vmft-sub vmf-scan-tbl"><thead><tr><th>Volume</th><th>Size</th><th>Modified</th></tr></thead><tbody>';
      orphanVols.forEach(function(v) {
        body += '<tr><td>' + esc(v.uuid) + '</td><td>' + fmtMemSmall(v.size) + '</td><td>' + esc(v.mtime || '—') + '</td></tr>';
      });
      body += '</tbody></table>';
      body += '<div class="vmf-scan-empty">Morpheus virtual images (ISOs such as virtio-win) are not StorageVolumes, so they always appear here.</div>';
    }

    var other = sc.other || [];
    body += '<details class="vmf-scan-other"><summary>Other files (' + other.length + ')</summary>';
    if (other.length) {
      body += '<table class="vmft-sub vmf-scan-tbl"><thead><tr><th>Name</th><th>Type</th><th>Size</th></tr></thead><tbody>';
      other.forEach(function(o) {
        body += '<tr><td>' + esc(o.name) + '</td><td>' + esc(o.type || '') + '</td><td>' + fmtMemSmall(o.size) + '</td></tr>';
      });
      body += '</tbody></table>';
    }
    body += '</details>';

    return '<div class="vmf-scan-panel">' + head + body + '</div>';
  }

  // Read-only datastore view: filters the table, never writes assignments.
  window.vmfSelectDatastore = function(key) {
    resetSelection('__all__', key); updateMvBtn(); renderTree(); renderDatastores(); renderVms();
    var bc=document.getElementById('vmf-bc');
    if(bc) bc.innerHTML='&#128451; Datastore: <b>'+esc(key===NO_DS?'No datastore':key)+'</b>';
  };

  window.vmfSort = function(col) { if(sortCol===col) sortAsc=!sortAsc; else{sortCol=col;sortAsc=true;} renderVms(); };
  window.vmfFilter = function(q) { searchQ=q; renderVms(); };
  window.vmfToggleSel = function(id,v) { if(v) selectedIds.add(id); else selectedIds.delete(id); updateMvBtn(); renderVms(); };
  window.vmfToggleAll = function(v) { getFiltered().forEach(function(vm){if(v)selectedIds.add(vm.id);else selectedIds.delete(vm.id);}); updateMvBtn(); renderVms(); };
  window.vmfMoveSingle = function(id) { selectedIds.clear(); selectedIds.add(id); vmfMoveSelected(); };

  window.vmfRemoveSingle = async function(id) {
    if(!confirm('Remove from folder?')) return;
    await doMove([id], ROOT);
  };

  window.vmfMoveSelected = function() {
    if(!selectedIds.size) return;
    var opts = allPaths().map(function(p){return '<option>'+esc(p)+'</option>';}).join('');
    // In keep mode the tag is the source of truth, so a Move of a tagged VM is undone on
    // the next reconcile. Say so rather than letting the move silently revert.
    var taggedSel = taggedVms().filter(function(vm){ return selectedIds.has(vm.id); });
    var warn = taggedSel.length
      ? '<div class="vmf-note">'+taggedSel.length+' of the selected VM'+(taggedSel.length!==1?'s':'')+
        ' carr'+(taggedSel.length!==1?'y':'ies')+' a <b>'+esc(tagName)+'</b> tag. The tag is the source of truth in '+
        esc(tagMode)+' mode, so this move will be reverted on the next reconcile. Change or remove the tag instead.</div>'
      : '';
    openModal('Move to Folder', [
      warn,
      fg('Folder Path','<input id="vmf-fi" list="vmf-fl" placeholder="/Production/Web" value="'+(activeFolder!=='__all__'&&activeFolder!==ROOT?esc(activeFolder):'')+'">'+'<datalist id="vmf-fl">'+opts+'</datalist>',
        'Moving '+selectedIds.size+' VM'+(selectedIds.size!==1?'s':'')+'. Use / for nesting.')
    ],[
      {l:'Cancel',fn:vmfCloseModal},
      {l:'Move',primary:true,fn:async function(){
        var el=document.getElementById('vmf-fi'); if(!el) return;
        var path=el.value.trim(); if(!path) return;
        if(!path.startsWith('/')) path='/'+path;
        vmfCloseModal(); await doMove(Array.from(selectedIds), path);
      }}
    ]);
    setTimeout(function(){var el=document.getElementById('vmf-fi');if(el)el.focus();},50);
  };

  window.vmfCreateFolder = function() {
    openModal('New Folder',[
      fg('Folder Path','<input id="vmf-nfp" placeholder="/Production/Web">','Use / separators, e.g. /Production/Web'),
      fg('Description (optional)','<input id="vmf-nfd" placeholder="What lives here?">')
    ],[
      {l:'Cancel',fn:vmfCloseModal},
      {l:'Create',primary:true,fn:async function(){
        var elp=document.getElementById('vmf-nfp'); if(!elp) return;
        var path=elp.value.trim(); if(!path) return;
        if(!path.startsWith('/')) path='/'+path;
        var desc=(document.getElementById('vmf-nfd')||{}).value||'';
        vmfCloseModal();
        var d=await post('/saveFolder', {path: path, desc: desc});
        if(d.success){
          vmfToast('Folder '+path+' created');
          await fetchAll(); renderTree(); vmfSelectFolder(path);
          setTimeout(vmfMoveSelected,300);
        } else { vmfToast('Failed: '+d.error,true); }
      }}
    ]);
    setTimeout(function(){var el=document.getElementById('vmf-nfp');if(el)el.focus();},50);
  };

  window.vmfRenameFolder = function(path) {
    var f = storedFolders.find(function(x){return x.path===path;});
    // An auto-created folder keeps its origin across a rename, so Re-sync keeps using it.
    var autoNote = (f && f.origin)
      ? '<div class="vmf-hint" style="margin-bottom:12px">This folder was created automatically from '+
        esc(f.origin.cloud||'')+(f.origin.host?' / '+esc(f.origin.host):'')+
        '. It keeps that origin after the rename, so Re-sync will keep placing those VMs here.</div>'
      : '';
    // keep mode strands tags that still name the old path: they win on the next reconcile.
    var stranded = (tagMode === 'keep') ? taggedUnder(path) : [];
    var tagNote = stranded.length
      ? '<div class="vmf-note">'+stranded.length+' VM'+(stranded.length!==1?'s':'')+' carr'+(stranded.length!==1?'y':'ies')+
        ' a <b>'+esc(tagName)+'</b> tag with the old path; in keep mode they will move back on the next reconcile. '+
        'Update the tag values too.</div>'
      : '';
    openModal('Rename Folder',[
      tagNote,
      autoNote,
      fg('Current Path','<input disabled value="'+esc(path)+'">'),
      fg('New Path','<input id="vmf-rnp" value="'+esc(path)+'">','Sub-folders will be updated automatically.')
    ],[
      {l:'Cancel',fn:vmfCloseModal},
      {l:'Rename',primary:true,fn:async function(){
        var el=document.getElementById('vmf-rnp'); if(!el) return;
        var np=el.value.trim(); if(!np||np===path) return vmfCloseModal();
        if(!np.startsWith('/')) np='/'+np;
        vmfCloseModal();
        var d=await post('/renFolder', {oldPath: path, newPath: np});
        if(d.success){ vmfToast('Renamed to '+np); await fetchAll(); renderTree(); vmfSelectFolder(np); }
        else vmfToast('Failed: '+d.error,true);
      }}
    ]);
    setTimeout(function(){var el=document.getElementById('vmf-rnp');if(el){el.focus();el.select();}},50);
  };

  window.vmfDeleteFolder = async function(path) {
    var c=countIn(path);
    var msg='Delete "'+path+'"?'+(c>0?' '+c+' VM(s) will become '+labels.unorganized+'.':'');
    openConfirm('Delete Folder', msg, async function() {
      var d=await post('/delFolder', {path: path});
      if(d.success){ vmfToast('Folder deleted'); await fetchAll(); renderTree(); vmfSelectFolder('__all__'); }
      else vmfToast('Failed: '+d.error,true);
    });
  };

  window.vmfPower = async function(id, action) {
    var labels={start:'Starting',stop:'Stopping',restart:'Restarting'};
    setStatus(labels[action]||'Working'+'...');
    try {
      var d=await post('/power', {vmId: id, action: action});
      vmfToast(d.success ? (action.charAt(0).toUpperCase()+action.slice(1)+' VM '+id) : 'Power failed: '+d.error, !d.success);
      if(d.success) setTimeout(vmfReload, 3000);
    } catch(e){ vmfToast('Power error: '+e.message,true); }
  };

  window.vmfCloseModal = function() { var m=document.getElementById('vmf-modal'); if(m) m.style.display='none'; };

  async function doMove(ids, path) {
    setStatus('Moving '+ids.length+' VM(s)...');
    var ok=0,fail=0;
    for(var i=0;i<ids.length;i++){
      try {
        var d = path===ROOT
          ? await post('/unassign', {vmId: ids[i]})
          : await post('/assign', {vmId: ids[i], path: path});
        if(d.success) ok++; else fail++;
      } catch(e){fail++;}
    }
    selectedIds.clear(); updateMvBtn();
    vmfToast(fail>0?ok+' moved, '+fail+' failed':'Moved '+ok+' VM'+(ok!==1?'s':'')+' to '+(path===ROOT?labels.unorganized:path),fail>0);
    await fetchAll(); renderTree(); renderDatastores(); renderVms();
  }

  function fg(label,input,hint) {
    return '<div class="vmf-fg"><label>'+label+'</label>'+input+(hint?'<div class="vmf-hint">'+hint+'</div>':'')+'</div>';
  }

  function openModal(title,bodyParts,btns) {
    var m=document.getElementById('vmf-modal'); if(!m) return;
    document.getElementById('vmf-mtitle').textContent=title;
    document.getElementById('vmf-mbody').innerHTML=bodyParts.join('');
    var foot=document.getElementById('vmf-mfoot'); foot.innerHTML='';
    btns.forEach(function(b){
      var btn=document.createElement('button');
      btn.className='vmf-btn '+(b.primary?'vmf-btn-primary':'vmf-btn-secondary');
      btn.textContent=b.l; btn.addEventListener('click',b.fn); foot.appendChild(btn);
    });
    m.style.display='flex';
  }

  window.openConfirm = function(title, msg, onConfirm) {
    openModal(title,['<p style="font-size:13px;line-height:1.5">'+esc(msg)+'</p>'],[
      {l:'Cancel',fn:vmfCloseModal},
      {l:'Confirm',primary:true,fn:function(){vmfCloseModal();onConfirm();}}
    ]);
  };

  function updateMvBtn(){var b=document.getElementById('vmf-mv-btn');if(b)b.style.display=selectedIds.size>0?'':'none';}
  function setStatus(msg){var el=document.getElementById('vmf-status');if(el)el.textContent=msg;}

  window.vmfToast=function(msg,err){
    var t=document.getElementById('vmf-toast'); if(!t) return;
    t.textContent=msg; t.className=err?'err':''; t.style.opacity='1';
    clearTimeout(t._t); t._t=setTimeout(function(){t.style.opacity='0';},3500);
  };


  // ── Settings ─────────────────────────────────────────────────────────
  // Read-only. Since 1.5.5 every setting lives in the plugin settings form
  // (Administration → Plugins → VM Folders) and nothing here writes one; this dialog
  // shows the values /config reports and says where to change them.
  window.vmfSettings = function() {
    var modeText = {
      off:     'off — ' + tagName + ' tags are ignored (default)',
      keep:    'keep — assign from the tag and leave it in place (read-only)',
      consume: 'consume — assign, then remove the ' + tagName + ' tag from the instance'
    };
    function row(label, value) {
      return '<div class="vmf-fg"><label>'+esc(label)+'</label><div style="font-size:13px">'+esc(value)+'</div></div>';
    }
    openModal('VM Folders Settings', [
      '<div class="vmf-note">Change these under <b>Administration &#8594; Plugins &#8594; VM Folders</b> (the edit form). '+
        'Changes apply on the next dashboard load.</div>',
      row('vmfolder tag mode', modeText[tagMode] || tagMode),
      row('Label for all VMs', labels.all),
      row('Label for unorganized VMs', labels.unorganized),
      row('Datastore scanning', scanEnabled ? 'enabled' : 'disabled (default)')
    ], [
      {l:'Close', primary:true, fn:vmfCloseModal}
    ]);
  };

  window.vmfShowLogs = async function() {
    openModal('Plugin Logs', [
      '<div class="vmf-fg"><label>Filter</label><input id="vmf-lf" value="VmFolder"><div class="vmf-hint">Use ALL for everything, or any string to filter</div></div>' +
      '<div id="vmf-log-out" style="background:#1e2d3d;color:#a8c7a8;font-family:monospace;font-size:11px;padding:10px;border-radius:4px;max-height:300px;overflow-y:auto;white-space:pre-wrap">Loading...</div>'
    ], [
      {l:'Close', fn:vmfCloseModal},
      {l:'Refresh', primary:true, fn:async function(){
        var f=document.getElementById('vmf-lf');
        await loadLogs(f?f.value:'VmFolder');
      }},
      {l:'Export .txt', fn:function(){ vmfCloseModal(); vmfExportLogs(); }}
    ]);
    await loadLogs('VmFolder');
  };

  async function loadLogs(filter) {
    var out = document.getElementById('vmf-log-out');
    if (!out) return;
    out.textContent = 'Loading...';
    try {
      var d = await get('/logs?lines=200&filter='+encodeURIComponent(filter||'VmFolder'));
      if (d.success) {
        out.textContent = d.lines.join('\n') || '(no matching lines)';
        out.scrollTop = out.scrollHeight;
        vmfToast('DB v'+d.dbVersion+' | '+d.dbFolders+' folders | '+d.dbAssigned+' assigned');
      } else { out.textContent = 'Error: '+d.error; }
    } catch(e){ out.textContent = 'Error: '+e.message; }
  }

  window.vmfExportLogs = async function() {
    vmfToast('Collecting logs...');
    try {
      var d = await get('/logs?lines=2000&filter=ALL');
      if (!d.success) { vmfToast('Log export failed: '+d.error, true); return; }
      var text = [
        '# VM Folders Plugin Log Export',
        '# Exported: ' + new Date().toISOString(),
        '# DB Version: ' + d.dbVersion,
        '# Folders: ' + d.dbFolders + ' | Assigned VMs: ' + d.dbAssigned,
        '# ============================================================',
        ''
      ].join('\n') + d.lines.join('\n');
      var blob = new Blob([text], {type:'text/plain'});
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url; a.download = 'vm-folders-logs-'+new Date().toISOString().slice(0,19).replace(/:/g,'-')+'.txt';
      document.body.appendChild(a); a.click();
      document.body.removeChild(a); URL.revokeObjectURL(url);
      vmfToast('Logs exported ('+d.lineCount+' lines)');
    } catch(e) { vmfToast('Export error: '+e.message, true); }
  };


  // ── Server Actions dropdown ──────────────────────────────────────────
  window.vmfShowActions = async function(id, btn) {
    document.querySelectorAll('.vmf-act-dd').forEach(function(d){ d.remove(); });
    var vm = allVms.find(function(v){return v.id===id;});
    var isOn = vm && String(vm.powerState||'').toLowerCase().match(/on|running/);
    var dd = document.createElement('div');
    dd.className = 'vmf-act-dd';
    // Use fixed positioning so overflow:hidden on scroll containers doesn't clip
    document.body.appendChild(dd);
    dd.style.cssText = 'position:fixed;background:#fff;border:1px solid #CCCCCC;border-radius:4px;box-shadow:0 4px 16px rgba(0,0,0,.2);z-index:99999;min-width:190px;overflow:hidden;';
    var br = btn.getBoundingClientRect();
    var left = br.right - 190;
    if (left < 4) left = 4;
    var top = br.bottom + 2;
    dd.style.left = left + 'px';
    dd.style.top = top + 'px';
    // Flip upward if near bottom
    setTimeout(function(){
      var dr = dd.getBoundingClientRect();
      if (dr.bottom > window.innerHeight - 8) {
        dd.style.top = (br.top - dr.height - 2) + 'px';
      }
    }, 0);
    var actions = [
      {label:'&#9654; Open Console',fn:function(){window.open('/terminal/server/'+id+'?consoleMode=hypervisor','_blank');dd.remove();}},
    ];
    if (!isOn) actions.push({label:'&#9654; Start',   fn:function(){openConfirm('Start VM','Start "'+(vm&&vm.name||'VM')+'"?',function(){vmfPower(id,'start');});dd.remove();}});
    if (isOn)  actions.push({label:'&#9632; Stop',    fn:function(){openConfirm('Stop VM', 'Stop "' +(vm&&vm.name||'VM')+'"?',function(){vmfPower(id,'stop');});dd.remove();}});

    actions.push({divider:true});
    actions.push({label:'&#128279; Open in Morpheus',fn:function(){window.open('/infrastructure/servers/'+id,'_blank');dd.remove();}});
    try {
      var r=await fetch('/plugin/vmFolders/serverActions?vmId='+id);
      if(r.ok){var d=await r.json();if(d.actions&&d.actions.length){actions.push({divider:true});d.actions.forEach(function(a){if(['start','stop'].includes((a.code||'').toLowerCase()))return;actions.push({label:'&#9881; '+a.name,fn:(function(ac,an,au){return function(){
              if(au){window.open(au,'_blank');dd.remove();return;}
              post('/executeAction', {vmId: id, action: ac})
                .then(function(res){vmfToast(res.success?an+' sent':'Failed: '+(res.error||''),!res.success);})
                .catch(function(e){vmfToast('Error: '+e.message,true);});
              dd.remove();};})(a.code,a.name,a.url||null)});});}}
    } catch(e){}
    dd.innerHTML='';
    actions.forEach(function(a){
      if(a.divider){var s=document.createElement('div');s.style.cssText='height:1px;background:#f0f0f0;margin:2px 0;';dd.appendChild(s);return;}
      var item=document.createElement('button');
      item.style.cssText='display:block;width:100%;text-align:left;padding:7px 14px;font-size:12px;font-family:-apple-system,sans-serif;background:none;border:none;cursor:pointer;color:#333;white-space:nowrap;';
      item.innerHTML=a.label;
      item.addEventListener('mouseenter',function(){this.classList.add('vmf-dd-on');});
      item.addEventListener('mouseleave',function(){this.classList.remove('vmf-dd-on');});
      item.addEventListener('click',a.fn);
      dd.appendChild(item);
    });
    setTimeout(function(){document.addEventListener('click',function cls(e){if(!dd.contains(e.target)&&e.target!==btn){dd.remove();document.removeEventListener('click',cls);}});},0);
  };

  // ── Compute tab overlay (React-safe full-screen panel) ───────────────
  window.vmfShowOverlay = function() {
    var ov=document.getElementById('vmf-overlay');
    if(!ov){
      ov=document.createElement('div');ov.id='vmf-overlay';
      ov.style.cssText='position:fixed;inset:0;background:#F5F5F5;z-index:9000;display:flex;flex-direction:column;overflow:hidden;';
      var hdr=document.createElement('div');
      hdr.style.cssText='display:flex;align-items:center;gap:8px;padding:0 16px;height:44px;background:#425563;color:#fff;flex-shrink:0;';
      hdr.innerHTML='<span style="display:flex;align-items:center;gap:6px;font-size:14px;font-weight:600;"><span style="display:inline-block;width:3px;height:16px;background:#01A982;border-radius:2px;"></span>VM Folders</span>'+
        '<button id="vmf-ov-organize" style="margin-left:auto;padding:4px 10px;border-radius:4px;font-size:11px;border:1px solid rgba(255,255,255,.4);background:transparent;color:#fff;cursor:pointer;">&#9881; Auto-Organize</button>'+
        '<button id="vmf-ov-close" style="background:none;border:none;color:#fff;font-size:20px;cursor:pointer;opacity:.7;padding:0 4px;">&#215;</button>';
      ov.appendChild(hdr);
      var panel=document.createElement('div');panel.style.cssText='flex:1;overflow:hidden;display:flex;flex-direction:column;';
      panel.innerHTML=buildPanelHTML();ov.appendChild(panel);
      document.body.appendChild(ov);buildModal();buildToast();
      document.getElementById('vmf-ov-close').addEventListener('click',function(){ov.style.display='none';});
      document.getElementById('vmf-ov-organize').addEventListener('click',function(){if(typeof vmfAutoOrganize==='function')vmfAutoOrganize();});
    } else { ov.style.display='flex'; }
    syncDarkMode();
    setTimeout(vmfReload,150);
  };

  // ── Auto-organize: Cloud/Host hierarchy ──────────────────────────────
  // One server-side call. 1.4.x looped saveFolder + assign per folder and per VM with
  // paths recomputed from names, which recreated a renamed folder; the server now
  // resolves each target by origin so a renamed folder is reused.
  window.vmfAutoOrganize = function() {
    openConfirm('Auto-Organize VMs',
      'Create Cloud/Host folders and assign all VMs. Folders you renamed are reused, not recreated. Existing assignments in the Cloud/Host tree will be updated.',
      async function(){
        setStatus('Auto-organizing...');
        var d = await post('/autoOrganize', {});
        if (!d.success) { vmfToast('Auto-organize failed: '+(d.error||'unknown'), true); setStatus('Error'); return; }
        await fetchAll(); renderTree(); renderDatastores(); vmfSelectFolder('__all__');
        vmfToast('Organized '+d.assigned+' VM'+(d.assigned!==1?'s':'')+(d.folders?', '+d.folders+' new folder'+(d.folders!==1?'s':''):''));
      });
  };

  // ── Re-sync host assignments (VME auto-migration) ────────────────────
  window.vmfResyncHosts = async function() {
    var body = 'Update VMs in Cloud/Host folders to reflect current host locations. ' +
      'Folders you renamed keep receiving their VMs. A VM in no folder is placed into its ' +
      'Cloud/Host folder only when that folder already exists.' +
      (tagMode !== 'off' ? ' vmfolder tags are reconciled in ' + tagMode + ' mode.' : '');
    openConfirm('Re-sync Host Assignments', body, async function(){
      setStatus('Re-syncing...');
      var d=await post('/resync', {});
      if (!d.success) { vmfToast('Re-sync failed: '+(d.error||'unknown'), true); setStatus('Error'); return; }
      var parts = [];
      if (d.moved)  parts.push('moved '+d.moved);
      if (d.placed) parts.push('placed '+d.placed);
      var t = d.tags || {};
      if (t.mode && t.mode !== 'off') {
        parts.push('assigned from tags: '+(t.assigned||0));
        parts.push('consumed: '+(t.consumed||0));
        parts.push('invalid: '+(t.invalid||0));
      }
      vmfToast(parts.length ? 'Re-sync — '+parts.join(', ') : 'All VMs already current');
      await fetchAll(); renderTree(); renderDatastores(); renderVms();
    });
  };

  // ── Lazy embedded tabs (1.5.8) ─────────────────────────────────────
  // The host, cluster and instance tab templates are static shells: a status line, a
  // Load button and a Refresh button. Nothing under /plugin/vmFolders/ is requested and
  // no tab CSS or markup exists until the tab pane is actually visible (onOpen) or Load
  // is clicked (manual, or the per-user "Don't auto-load" box). Visibility comes from an
  // IntersectionObserver on the shell, so nothing here depends on Morpheus tab markup.
  var LAZY_KEY = 'vmFolders.tab.autoload';       // localStorage; '0' = don't auto-load
  var LAZY_TIMEOUT_MS = 20000;                   // per request, so a hung call ends in Retry
  var LAZY_CSS = {
    bar: '.vmf-lazy-spin{display:inline-block;width:12px;height:12px;border:2px solid #CCCCCC;border-top-color:#01A982;border-radius:50%;animation:vmfLazySpin .7s linear infinite;vertical-align:middle;margin-right:6px}@keyframes vmfLazySpin{to{transform:rotate(360deg)}}',
    server: "#vmf-st *{box-sizing:border-box;}#vmf-st{padding:0;background:var(--hpe-bg,#F5F5F5);min-height:500px;display:flex;flex-direction:column;}#vmf-st-hdr{display:flex;align-items:center;gap:8px;padding:10px 16px;background:var(--hpe-header,#425563);color:#fff;flex-shrink:0;}#vmf-st-hdr h3{font-size:14px;font-weight:600;display:flex;align-items:center;gap:6px;font-family:-apple-system,sans-serif;margin:0;}#vmf-st-hdr h3::before{content:'';display:inline-block;width:3px;height:16px;background:#01A982;border-radius:2px;}.vmf-hbadge{background:rgba(0,0,0,.3);color:#ECF0F1;border-radius:3px;padding:2px 8px;font-size:10px;font-weight:600;}.vmf-hbtn{padding:4px 10px;border-radius:4px !important;font-size:11px;cursor:pointer;white-space:nowrap;border:1px solid rgba(255,255,255,.4) !important;background:transparent !important;color:#fff !important;font-family:-apple-system,sans-serif;}.vmf-hbtn:hover{border-color:#fff !important;background:rgba(255,255,255,.1) !important;}#vmf-st .vmf-main{display:flex;flex:1;overflow:hidden;min-height:420px;}#vmf-st #vmf-cloud-bar{display:flex;flex-wrap:wrap;gap:3px;padding:6px 10px;background:var(--hpe-bg,#F5F5F5);border-bottom:1px solid var(--hpe-border,#CCCCCC);flex-shrink:0;}#vmf-st #vmf-tree{width:190px;background:var(--hpe-white,#fff);border-right:1px solid var(--hpe-border,#CCCCCC);display:flex;flex-direction:column;overflow:hidden;flex-shrink:0;}#vmf-st #vmf-tree-head{padding:8px 12px;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.07em;color:var(--hpe-muted,#767676);background:var(--hpe-bg,#F5F5F5);border-bottom:1px solid var(--hpe-border,#CCCCCC);}#vmf-st #vmf-flist{flex:1;overflow-y:auto;padding:3px 0;}#vmf-st .vmf-fi{display:flex;align-items:center;gap:6px;padding:5px 12px;cursor:pointer;border-left:3px solid transparent;font-size:12px;font-family:-apple-system,sans-serif;color:var(--hpe-text,#333);}#vmf-st .vmf-fi:hover{background:var(--hpe-row-hover,#F9FAFA);}#vmf-st .vmf-fi.active{background:var(--hpe-selected,#E6F5F1);border-left-color:#01A982;color:#008567;font-weight:600;}#vmf-st .vmf-fi-icon{font-size:12px;flex-shrink:0;}#vmf-st .vmf-fi-name{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}#vmf-st .vmf-fi-count{font-size:10px;background:var(--hpe-border,#eee);color:var(--hpe-muted,#767676);border-radius:8px;padding:0 5px;flex-shrink:0;}#vmf-st .vmf-fi.active .vmf-fi-count{background:#c8ede4;color:#008567;}#vmf-st .vmf-divider{height:1px;background:var(--hpe-border,#CCCCCC);margin:2px 0;opacity:.5;}#vmf-st #vmf-content{flex:1;display:flex;flex-direction:column;overflow:hidden;}#vmf-st #vmf-toolbar{display:flex;align-items:center;gap:6px;padding:7px 12px;background:var(--hpe-white,#fff);border-bottom:1px solid var(--hpe-border,#CCCCCC);flex-shrink:0;flex-wrap:wrap;}#vmf-st #vmf-search{padding:4px 8px;border:1px solid var(--hpe-border,#CCCCCC);border-radius:4px !important;font-size:12px;max-width:200px;background:var(--hpe-white,#fff);color:var(--hpe-text,#333);}#vmf-st #vmf-search:focus{outline:none;border-color:#01A982;}#vmf-st #vmf-bc{font-size:11px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;}#vmf-st #vmf-vlist{flex:1;overflow-y:auto;background:var(--hpe-white,#fff);}#vmf-st #vmf-status{padding:4px 12px;background:var(--hpe-bg,#F5F5F5);border-top:1px solid var(--hpe-border,#CCCCCC);font-size:10px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;}#vmf-st table.vmft{width:100%;border-collapse:collapse;background:var(--hpe-white,#fff);}#vmf-st table.vmft thead th{position:sticky;top:0;background:var(--hpe-bg,#F5F5F5);border-bottom:2px solid var(--hpe-border,#CCCCCC);padding:6px 8px;text-align:left;font-weight:600;font-size:10px;text-transform:uppercase;color:var(--hpe-muted,#767676);white-space:nowrap;cursor:pointer;font-family:-apple-system,sans-serif;}#vmf-st table.vmft tbody tr{border-bottom:1px solid var(--hpe-border,#f0f0f0);}#vmf-st table.vmft tbody tr:hover{background:var(--hpe-row-hover,#F9FAFA);}#vmf-st table.vmft tbody tr.sel{background:var(--hpe-selected,#E6F5F1);}#vmf-st table.vmft td{padding:5px 8px;font-size:11px;font-family:-apple-system,sans-serif;vertical-align:middle;color:var(--hpe-text,#333);}#vmf-st .vmft-name a{color:#008567;text-decoration:none;font-weight:500;}#vmf-st .vmft-name a:hover{text-decoration:underline;}#vmf-st .vmf-dot{display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:3px;vertical-align:middle;}#vmf-st .vmf-tag{background:var(--hpe-selected,#e6f5f1);color:#008567;border-radius:3px;padding:1px 6px;font-size:10px;font-weight:500;border:1px solid #b3e0d5;}#vmf-st .vmf-act{padding:2px 7px !important;font-size:11px !important;border:1px solid var(--hpe-border,#CCCCCC) !important;border-radius:3px !important;background:var(--hpe-white,#fff) !important;cursor:pointer !important;color:var(--hpe-text,#333) !important;font-family:-apple-system,sans-serif !important;line-height:1.4 !important;min-height:0 !important;height:auto !important;}#vmf-st .vmf-act:hover{border-color:#01A982 !important;color:#008567 !important;}#vmf-st .vmf-act-more{font-weight:bold;letter-spacing:1px;}#vmf-st .vmf-pw-btn{color:#fff !important;}#vmf-st .vmf-start-btn{background:#01A982 !important;border-color:#008567 !important;color:#fff !important;padding:2px 7px !important;font-size:11px !important;border-radius:3px !important;line-height:1.4 !important;}#vmf-st .vmf-stop-btn{background:#c00 !important;border-color:#a00 !important;color:#fff !important;padding:2px 7px !important;font-size:11px !important;border-radius:3px !important;line-height:1.4 !important;}#vmf-st .vmf-start-btn:hover{background:#008567 !important;}#vmf-st .vmf-stop-btn:hover{background:#a00 !important;}#vmf-st .vmf-empty, #vmf-st .vmf-spin{display:flex;align-items:center;justify-content:center;padding:30px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;gap:8px;font-size:12px;}#vmf-st .vmf-spinner{width:14px;height:14px;border:2px solid var(--hpe-border,#CCCCCC);border-top-color:#01A982;border-radius:50%;animation:vmfSpin .7s linear infinite;}@keyframes vmfSpin{to{transform:rotate(360deg);}}.vmf-badge-un{display:inline-block;background:#fff3e0;color:#8a5300;border:1px solid #f0c890;border-radius:3px;padding:0 5px;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.03em;vertical-align:middle;}body.dark .vmf-badge-un{background:#3A2E1A;color:#E0B870;border-color:#5A4828;}",
    cluster: "#vmf-ct *{box-sizing:border-box;}#vmf-ct{padding:0;background:var(--hpe-bg,#F5F5F5);min-height:500px;display:flex;flex-direction:column;}#vmf-ct-hdr{display:flex;align-items:center;gap:8px;padding:10px 16px;background:var(--hpe-header,#425563);color:#fff;flex-shrink:0;}#vmf-ct-hdr span.title{font-size:14px;font-weight:600;display:flex;align-items:center;gap:6px;font-family:-apple-system,sans-serif;}#vmf-ct-hdr span.title::before{content:'';display:inline-block;width:3px;height:16px;background:#01A982;border-radius:2px;}.vmf-cl-btn{padding:4px 10px;border-radius:4px !important;font-size:11px;cursor:pointer;border:1px solid rgba(255,255,255,.4) !important;background:transparent !important;color:#fff !important;text-decoration:none;display:inline-block;font-family:-apple-system,sans-serif;}.vmf-cl-btn:hover{border-color:#fff !important;background:rgba(255,255,255,.1) !important;}#vmf-ct .vmf-main{display:flex;flex:1;overflow:hidden;min-height:420px;}#vmf-ct #vmf-cloud-bar{display:flex;flex-wrap:wrap;gap:3px;padding:6px 10px;background:var(--hpe-bg,#F5F5F5);border-bottom:1px solid var(--hpe-border,#CCCCCC);flex-shrink:0;}#vmf-ct #vmf-tree{width:190px;background:var(--hpe-white,#fff);border-right:1px solid var(--hpe-border,#CCCCCC);display:flex;flex-direction:column;overflow:hidden;flex-shrink:0;}#vmf-ct #vmf-tree-head{padding:8px 12px;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.07em;color:var(--hpe-muted,#767676);background:var(--hpe-bg,#F5F5F5);border-bottom:1px solid var(--hpe-border,#CCCCCC);}#vmf-ct #vmf-flist{flex:1;overflow-y:auto;padding:3px 0;}#vmf-ct .vmf-fi{display:flex;align-items:center;gap:6px;padding:5px 12px;cursor:pointer;border-left:3px solid transparent;font-size:12px;font-family:-apple-system,sans-serif;color:var(--hpe-text,#333);}#vmf-ct .vmf-fi:hover{background:var(--hpe-row-hover,#F9FAFA);}#vmf-ct .vmf-fi.active{background:var(--hpe-selected,#E6F5F1);border-left-color:#01A982;color:#008567;font-weight:600;}#vmf-ct .vmf-fi-icon{font-size:12px;flex-shrink:0;}#vmf-ct .vmf-fi-name{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}#vmf-ct .vmf-fi-count{font-size:10px;background:var(--hpe-border,#eee);color:var(--hpe-muted,#767676);border-radius:8px;padding:0 5px;flex-shrink:0;}#vmf-ct .vmf-fi.active .vmf-fi-count{background:#c8ede4;color:#008567;}#vmf-ct .vmf-divider{height:1px;background:var(--hpe-border,#CCCCCC);margin:2px 0;opacity:.5;}#vmf-ct #vmf-content{flex:1;display:flex;flex-direction:column;overflow:hidden;}#vmf-ct #vmf-toolbar{display:flex;align-items:center;gap:6px;padding:7px 12px;background:var(--hpe-white,#fff);border-bottom:1px solid var(--hpe-border,#CCCCCC);flex-shrink:0;flex-wrap:wrap;}#vmf-ct #vmf-search{padding:4px 8px;border:1px solid var(--hpe-border,#CCCCCC);border-radius:4px !important;font-size:12px;max-width:200px;background:var(--hpe-white,#fff);color:var(--hpe-text,#333);}#vmf-ct #vmf-search:focus{outline:none;border-color:#01A982;}#vmf-ct #vmf-bc{font-size:11px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;}#vmf-ct #vmf-vlist{flex:1;overflow-y:auto;background:var(--hpe-white,#fff);}#vmf-ct #vmf-status{padding:4px 12px;background:var(--hpe-bg,#F5F5F5);border-top:1px solid var(--hpe-border,#CCCCCC);font-size:10px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;}#vmf-ct table.vmft{width:100%;border-collapse:collapse;background:var(--hpe-white,#fff);}#vmf-ct table.vmft thead th{position:sticky;top:0;background:var(--hpe-bg,#F5F5F5);border-bottom:2px solid var(--hpe-border,#CCCCCC);padding:6px 8px;text-align:left;font-weight:600;font-size:10px;text-transform:uppercase;color:var(--hpe-muted,#767676);white-space:nowrap;cursor:pointer;font-family:-apple-system,sans-serif;}#vmf-ct table.vmft tbody tr{border-bottom:1px solid var(--hpe-border,#f0f0f0);}#vmf-ct table.vmft tbody tr:hover{background:var(--hpe-row-hover,#F9FAFA);}#vmf-ct table.vmft td{padding:5px 8px;font-size:11px;font-family:-apple-system,sans-serif;vertical-align:middle;color:var(--hpe-text,#333);}#vmf-ct .vmft-name a{color:#008567;text-decoration:none;font-weight:500;}#vmf-ct .vmft-name a:hover{text-decoration:underline;}#vmf-ct .vmf-dot{display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:3px;vertical-align:middle;}#vmf-ct .vmf-tag{background:var(--hpe-selected,#e6f5f1);color:#008567;border-radius:3px;padding:1px 6px;font-size:10px;font-weight:500;border:1px solid #b3e0d5;}#vmf-ct .vmf-act{padding:2px 7px !important;font-size:11px !important;border:1px solid var(--hpe-border,#CCCCCC) !important;border-radius:3px !important;background:var(--hpe-white,#fff) !important;cursor:pointer !important;color:var(--hpe-text,#333) !important;font-family:-apple-system,sans-serif !important;line-height:1.4 !important;min-height:0 !important;height:auto !important;}#vmf-ct .vmf-act:hover{border-color:#01A982 !important;color:#008567 !important;}#vmf-ct .vmf-pw-btn{color:#fff !important;}#vmf-ct .vmf-start-btn{background:#01A982 !important;border-color:#008567 !important;color:#fff !important;padding:2px 7px !important;font-size:11px !important;border-radius:3px !important;line-height:1.4 !important;}#vmf-ct .vmf-stop-btn{background:#c00 !important;border-color:#a00 !important;color:#fff !important;padding:2px 7px !important;font-size:11px !important;border-radius:3px !important;line-height:1.4 !important;}#vmf-ct .vmf-start-btn:hover{background:#008567 !important;}#vmf-ct .vmf-stop-btn:hover{background:#a00 !important;}#vmf-ct .vmf-empty, #vmf-ct .vmf-spin{display:flex;align-items:center;justify-content:center;padding:30px;color:var(--hpe-muted,#767676);font-family:-apple-system,sans-serif;gap:8px;font-size:12px;}#vmf-ct .vmf-spinner{width:14px;height:14px;border:2px solid var(--hpe-border,#CCCCCC);border-top-color:#01A982;border-radius:50%;animation:vmfCtSpin .7s linear infinite;}@keyframes vmfCtSpin{to{transform:rotate(360deg);}}.vmf-badge-un{display:inline-block;background:#fff3e0;color:#8a5300;border:1px solid #f0c890;border-radius:3px;padding:0 5px;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.03em;vertical-align:middle;}body.dark .vmf-badge-un{background:#3A2E1A;color:#E0B870;border-color:#5A4828;}",
    instance: "#vmf-inst-wrap{padding:20px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}#vmf-inst-wrap *{box-sizing:border-box;}.vmf-inst-card{background:#fff;border:1px solid #CCCCCC;border-radius:6px;overflow:hidden;max-width:600px;}.vmf-inst-head{background:#425563;color:#fff;padding:12px 16px;display:flex;align-items:center;gap:8px;}.vmf-inst-head h3{font-size:14px;font-weight:600;display:flex;align-items:center;gap:6px;margin:0;}.vmf-inst-head h3::before{content:'';display:inline-block;width:3px;height:16px;background:#01A982;border-radius:2px;}.vmf-inst-body{padding:20px;}.vmf-inst-row{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #f0f0f0;}.vmf-inst-row:last-child{border-bottom:none;}.vmf-inst-label{font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.05em;color:#767676;width:100px;flex-shrink:0;}.vmf-inst-val{font-size:13px;color:#333;flex:1;}.vmf-inst-val.folder{color:#008567;font-weight:500;}.vmf-inst-val.unorg{color:#767676;font-style:italic;}.vmf-inst-actions{display:flex;gap:8px;padding-top:16px;}.vmf-inst-btn{padding:7px 14px;border-radius:4px;font-size:12px;font-weight:500;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:5px;}.vmf-inst-btn-primary{background:#01A982;border:1px solid #008567;color:#fff;}.vmf-inst-btn-primary:hover{background:#008567;}.vmf-inst-btn-outline{background:#fff;border:1px solid #CCCCCC;color:#333;}.vmf-inst-btn-outline:hover{border-color:#01A982;color:#008567;}.vmf-inst-loading{color:#767676;font-size:13px;display:flex;align-items:center;gap:8px;}.vmf-inst-spinner{width:14px;height:14px;border:2px solid #CCCCCC;border-top-color:#01A982;border-radius:50%;animation:vmfInstSpin .7s linear infinite;}@keyframes vmfInstSpin{to{transform:rotate(360deg);}}.vmf-inst-move-form{display:none;margin-top:12px;padding:12px;background:#F5F5F5;border-radius:4px;border:1px solid #CCCCCC;}.vmf-inst-move-form label{display:block;font-size:11px;font-weight:600;margin-bottom:4px;color:#767676;text-transform:uppercase;}.vmf-inst-move-form input{width:100%;padding:7px 10px;border:1px solid #CCCCCC;border-radius:4px;font-size:13px;margin-bottom:8px;}.vmf-inst-move-form input:focus{outline:none;border-color:#01A982;}"
  };

  function lazyAutoPref() {
    try { return localStorage.getItem(LAZY_KEY) !== '0'; } catch (e) { return true; }
  }
  function lazySetAutoPref(on) {
    try { if (on) localStorage.removeItem(LAZY_KEY); else localStorage.setItem(LAZY_KEY, '0'); } catch (e) {}
  }
  function lazyCss(kind) {
    var id = 'vmf-css-' + kind;
    if (document.getElementById(id)) return;
    var s = document.createElement('style');
    s.id = id;
    s.textContent = LAZY_CSS[kind];
    document.head.appendChild(s);
  }

  // Cloud name for the host/cluster tab, read from the Morpheus detail page (moved here
  // from the tab templates). vmfReload/reloadCore discards a value that matches no VM.
  function lazyContextCloud(fromModel) {
    if (fromModel && fromModel !== 'null' && fromModel !== 'undefined') return fromModel;
    try {
      var labelled = window.vmfDetectContextCloud();
      if (labelled) return labelled;
      var ic = document.querySelector('.info-column a[href*="/infrastructure/clouds/"]');
      if (ic) return ic.textContent.trim();
      var all = document.querySelectorAll('a[href*="/infrastructure/clouds/"]');
      for (var i = 0; i < all.length; i++) {
        if (!all[i].closest('nav, header, #main-nav, .main-nav, .navbar')) return all[i].textContent.trim();
      }
    } catch (e) {}
    return '';
  }

  // The tree + table both tabs share (same ids the standalone dashboard uses).
  function lazyPanelHtml() {
    return '<div class="vmf-main">' +
      '<div id="vmf-tree"><div id="vmf-cloud-bar"></div><div id="vmf-tree-head">Folders</div>' +
        '<div id="vmf-flist"><div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div></div></div>' +
      '<div id="vmf-content"><div id="vmf-toolbar">' +
        '<input id="vmf-search" type="text" placeholder="Search VMs...">' +
        '<span id="vmf-bc">&#128193; <b>All VMs</b></span></div>' +
        '<div id="vmf-vlist"><div class="vmf-spin"><div class="vmf-spinner"></div>Loading VMs...</div></div>' +
        '<div id="vmf-status">Loading...</div></div></div>';
  }
  function lazyServerHtml(name) {
    return '<div id="vmf-st"><div id="vmf-st-hdr"><h3>VM Folders</h3>' +
      '<span class="vmf-hbadge">' + esc(name) + '</span>' +
      '<button class="vmf-hbtn" id="vmf-resync-btn" title="Re-sync VMs to current host folders">&#8635; Re-sync</button>' +
      '<a href="/plugin/vmFolders" target="_blank" class="vmf-hbtn" style="text-decoration:none;margin-left:auto;">Open Full View &#8599;</a>' +
      '</div>' + lazyPanelHtml() + '</div>';
  }
  function lazyClusterHtml(name) {
    return '<div id="vmf-ct"><div id="vmf-ct-hdr"><span class="title">VM Folders</span>' +
      '<span style="font-size:11px;color:#B0BEC5;">' + esc(name) + '</span>' +
      '<a href="/plugin/vmFolders" target="_blank" class="vmf-cl-btn" style="margin-left:auto;">Open Full View &#8599;</a>' +
      '</div>' + lazyPanelHtml() + '</div>';
  }

  // Host and cluster tabs. The first load builds the panel and resets the shared view
  // state (a previous detail page's host or cloud filter must not carry over); a Refresh
  // keeps what is on screen and only replaces the data.
  async function lazyLoadFolders(kind, wrap, body, cached) {
    var ds = wrap.dataset || {};
    if (!body.firstChild) {
      lazyCss(kind);
      body.innerHTML = kind === 'server' ? lazyServerHtml(ds.serverName || 'Host') : lazyClusterHtml(ds.serverName || 'Cluster');
      window.vmfReadOnly = true;
      window.vmfHostId = kind === 'server' ? (ds.serverId || '') : '';
      window.vmfContextCloud = lazyContextCloud(kind === 'cluster' ? (ds.cloudName || '') : '');
      cloudFilter = '__all__'; searchQ = '';
      resetSelection('__all__', null);
      var rs = body.querySelector('#vmf-resync-btn');
      if (rs) rs.addEventListener('click', function() { window.vmfResyncHosts(); });
      var se = body.querySelector('#vmf-search');
      if (se) se.addEventListener('input', function() { window.vmfFilter(se.value); });
    }
    syncDarkMode();
    selectedIds.clear(); updateMvBtn();
    await reloadCore({ cached: cached, strict: true, embedded: true, timeoutMs: LAZY_TIMEOUT_MS });
  }

  // Instance tab: which folder this instance's VM is in, with assign / move / remove.
  // (Moved here from the template; the buttons are wired by delegation, not onclick.)
  function lazyInstanceRender(wrap, state) {
    var folder = state.folder, isOrg = folder && folder !== '/';
    var folderDisplay = isOrg
      ? '<span class="vmf-inst-val folder">&#128193; ' + esc(folder) + '</span>'
      : '<span class="vmf-inst-val unorg">' + esc(state.labelUnorganized) + '</span>';
    var html = '<div class="vmf-inst-row"><span class="vmf-inst-label">Instance</span>' +
      '<span class="vmf-inst-val">' + esc(state.instanceName) + '</span></div>' +
      '<div class="vmf-inst-row"><span class="vmf-inst-label">Server ID</span>' +
      '<span class="vmf-inst-val">' + esc(state.serverId || '—') + '</span></div>' +
      '<div class="vmf-inst-row"><span class="vmf-inst-label">Folder</span>' + folderDisplay + '</div>';
    html += '<div class="vmf-inst-actions">' +
      '<a href="/plugin/vmFolders" target="_blank" class="vmf-inst-btn vmf-inst-btn-outline">&#128196; Open VM Folders</a>' +
      '<button type="button" class="vmf-inst-btn vmf-inst-btn-outline" data-inst-act="toggle">&#128193; ' + (isOrg ? 'Move Folder' : 'Assign Folder') + '</button>' +
      '</div>';
    html += '<div class="vmf-inst-move-form" id="vmf-inst-mf"><label>Folder Path</label>' +
      '<input id="vmf-inst-fp" type="text" placeholder="/Production/Web" value="' + (isOrg ? esc(folder) : '') + '">' +
      '<div style="display:flex;gap:6px;">' +
      '<button type="button" class="vmf-inst-btn vmf-inst-btn-primary" data-inst-act="save">Save</button>' +
      '<button type="button" class="vmf-inst-btn vmf-inst-btn-outline" data-inst-act="toggle">Cancel</button>' +
      (isOrg ? '<button type="button" class="vmf-inst-btn vmf-inst-btn-outline" style="color:#c00;border-color:#c00" data-inst-act="remove">Remove</button>' : '') +
      '</div></div>';
    var c = wrap.querySelector('#vmf-inst-content');
    if (c) c.innerHTML = html;
  }

  async function lazyInstanceAct(wrap, state, act) {
    if (act === 'toggle') {
      var f = wrap.querySelector('#vmf-inst-mf');
      if (f) f.style.display = (f.style.display === 'none' || !f.style.display) ? 'block' : 'none';
      return;
    }
    if (!state.serverId) return;
    try {
      if (act === 'save') {
        var el = wrap.querySelector('#vmf-inst-fp');
        var path = el ? el.value.trim() : '';
        if (!path) return;
        if (path.charAt(0) !== '/') path = '/' + path;
        var d = await post('/assign', { vmId: state.serverId, path: path });
        if (d.success) { state.folder = path; lazyInstanceRender(wrap, state); } else { alert('Failed: ' + d.error); }
      } else if (act === 'remove') {
        if (!confirm('Remove from folder?')) return;
        var r = await post('/unassign', { vmId: state.serverId });
        if (r.success) { state.folder = ''; lazyInstanceRender(wrap, state); } else { alert('Failed: ' + r.error); }
      }
    } catch (e) { alert('Error: ' + e.message); }
  }

  async function lazyLoadInstance(wrap, body, cached) {
    var ds = wrap.dataset || {};
    var t = { timeoutMs: LAZY_TIMEOUT_MS };
    var state = wrap.__vmfInst;
    if (!body.firstChild || !state) {
      lazyCss('instance');
      state = wrap.__vmfInst = { serverId: ds.serverId || '', instanceName: ds.instanceName || '', folder: '', labelUnorganized: 'Unorganized' };
      body.innerHTML = '<div id="vmf-inst-wrap"><div class="vmf-inst-card"><div class="vmf-inst-head"><h3>VM Folders</h3>' +
        '<span style="font-size:11px;color:#B0BEC5;">' + esc(state.instanceName) + '</span>' +
        '<a href="/plugin/vmFolders" target="_blank" style="margin-left:auto;font-size:11px;color:#B0BEC5;text-decoration:none;border:1px solid rgba(255,255,255,.3);padding:3px 8px;border-radius:3px;">Full View &#8599;</a>' +
        '</div><div class="vmf-inst-body"><div id="vmf-inst-content">' +
        '<div class="vmf-inst-loading"><div class="vmf-inst-spinner"></div>Loading folder info...</div></div></div></div></div>';
      if (!wrap.__vmfInstWired) {
        wrap.__vmfInstWired = true;
        body.addEventListener('click', function(e) {
          var b = e.target.closest('[data-inst-act]');
          if (b && wrap.__vmfInst) lazyInstanceAct(wrap, wrap.__vmfInst, b.getAttribute('data-inst-act'));
        });
      }
    }
    // The provider no longer makes a platform call to find the VM behind the instance.
    // When the instance model it was handed carried no workload, ask once, now.
    if (!state.serverId && ds.instanceId) {
      var r = await get('/instanceServer?instanceId=' + encodeURIComponent(ds.instanceId), t);
      if (r && r.serverId) state.serverId = String(r.serverId);
    }
    var cfg = cached ? cfgRead() : null;
    var res = await Promise.all([
      get('/db', t),
      cfg ? Promise.resolve(cfg) : get('/config', t).catch(function() { return {}; })
    ]);
    var db = res[0] || {};
    cfg = res[1] || {};
    if (cfg.success === true) cfgWrite(cfg);
    if (cfg.labelUnorganized) state.labelUnorganized = cfg.labelUnorganized;
    var asg = db.assignments || {};
    state.folder = state.serverId ? (asg[state.serverId] || '') : '';
    lazyInstanceRender(wrap, state);
  }

  // Called by each tab shell: kind is 'server', 'cluster' or 'instance'.
  window.vmfLazyInit = function(kind) {
    var wrap = document.getElementById('vmf-lazy-' + kind);
    if (!wrap || wrap.__vmfLazy) return;
    wrap.__vmfLazy = true;
    var statusEl = wrap.querySelector('.vmf-lazy-status');
    var loadBtn = wrap.querySelector('.vmf-lazy-load');
    var refreshBtn = wrap.querySelector('.vmf-lazy-refresh');
    var noAuto = wrap.querySelector('.vmf-lazy-noauto');
    var body = wrap.querySelector('.vmf-lazy-body');
    if (!body) return;
    var manual = ((wrap.dataset && wrap.dataset.loadMode) || 'onOpen') === 'manual';
    var loaded = false, busy = false, failed = false;

    function show(el, on) { if (el) el.style.display = on ? '' : 'none'; }
    function status(text, spin) {
      if (!statusEl) return;
      statusEl.innerHTML = (spin ? '<span class="vmf-lazy-spin"></span>' : '') + esc(text);
    }

    async function run(cached) {
      if (busy) return;
      busy = true;
      var t0 = Date.now();
      lazyCss('bar');
      show(loadBtn, false); show(refreshBtn, false);
      status('Loading… 0 s', true);
      var tick = setInterval(function() {
        status('Loading… ' + Math.round((Date.now() - t0) / 1000) + ' s', true);
      }, 1000);
      try {
        if (kind === 'instance') await lazyLoadInstance(wrap, body, cached);
        else await lazyLoadFolders(kind, wrap, body, cached);
        loaded = true; failed = false;
        status('Loaded in ' + ((Date.now() - t0) / 1000).toFixed(1) + ' s', false);
        if (statusEl) statusEl.title = (kind !== 'instance' && lastVmsTiming) ? ('Server /vms: ' + JSON.stringify(lastVmsTiming)) : '';
        show(refreshBtn, true);
      } catch (e) {
        failed = true;
        // A failed first load leaves no half-built panel behind; a failed Refresh
        // keeps the data that was already on screen.
        if (!loaded) { body.innerHTML = ''; wrap.__vmfInst = null; }
        status('Failed: ' + ((e && e.message) || 'unknown error'), false);
        if (loadBtn) { loadBtn.textContent = 'Retry'; show(loadBtn, true); }
      } finally {
        clearInterval(tick);
        busy = false;
      }
    }

    if (noAuto) {
      noAuto.checked = !lazyAutoPref();
      noAuto.addEventListener('change', function() { lazySetAutoPref(!noAuto.checked); });
    }
    // Load uses the short server-side cache; Retry and Refresh always bypass it.
    if (loadBtn) loadBtn.addEventListener('click', function() { run(!failed && !loaded); });
    if (refreshBtn) refreshBtn.addEventListener('click', function() { run(false); });

    if (!manual && lazyAutoPref() && typeof IntersectionObserver === 'function') {
      var io = new IntersectionObserver(function(entries) {
        for (var i = 0; i < entries.length; i++) {
          if (entries[i].isIntersecting) {
            io.disconnect();
            if (!loaded && !busy && lazyAutoPref()) run(true);
            return;
          }
        }
      });
      io.observe(wrap);
    }
  };

  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}

})();
