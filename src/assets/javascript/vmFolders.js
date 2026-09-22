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
  var allVms = [], storedFolders = [], activeFolder = '__all__', searchQ = '';
  var selectedIds = new Set(), sortCol = 'name', sortAsc = true;
  var cloudFilter = '__all__';
  // Datastore browsing (read-only): a datastore name, NO_DS for "no datastore", or null.
  // Folder selection and datastore selection are mutually exclusive — see resetSelection().
  var NO_DS = '__none__';
  var activeDatastore = null;

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
  async function get(path) {
    var r = await fetch(API + path, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    return r.json();
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

  async function fetchAll() {
    var results = await Promise.all([get('/vms'), get('/db')]);
    allVms = results[0].servers || [];
    var db = results[1];
    storedFolders = db.folders || [];
    return db;
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
    return '<div class="vmf-fi' + (active ? ' active' : '') + '" data-ds-key="' + esc(key) + '" title="' + esc(tip) + '">' +
      '<span class="vmf-fi-icon">&#128451;</span>' +
      '<span class="vmf-fi-name">' + esc(label) + '</span>' +
      '<span class="vmf-fi-count">' + st.vms + '</span></div>';
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
      hostId: window.vmfHostId || null, readOnly: !!window.vmfReadOnly,
      clouds: Array.from(new Set(allVms.map(function(vm){ return vm.cloudName||''; }))).sort()
    };
  };

  function renderTree() {
    var paths = allPaths();
    var html = treeItem('__all__', '&#128196;', 'All VMs', scopedVms().length, 0, null, false);
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
      html += treeItem(ROOT, '&#128220;', 'Unorganized', unorg, 0, null, false);
    }
    var el = document.getElementById('vmf-flist');
    if (el) el.innerHTML = html;
  }

  function treeItem(key, icon, name, count, indent, fullPath, stored) {
    var active = !activeDatastore && activeFolder === key;
    var cls = 'vmf-fi' + (active ? ' active' : '');
    var style = indent ? ' style="padding-left:' + (14 + indent) + 'px"' : '';
    var title = fullPath ? ' title="' + esc(fullPath) + '"' : '';
    var actions = (!window.vmfReadOnly && stored) ? '<div class="vmf-fi-actions">' +
      '<button class="vmf-fi-btn" data-rename="' + esc(key) + '" title="Rename">&#9998;</button>' +
      '<button class="vmf-fi-btn del" data-delfolder="' + esc(key) + '" title="Delete">&#10006;</button>' +
      '</div>' : '';
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
        var pname = (allVms.find(function(v){return v.id===pid;})||{}).name || 'VM '+pid;
        var plabel = paction==='start' ? 'Start' : paction==='stop' ? 'Stop' : 'Restart';
        openConfirm(plabel+' VM', plabel+' "'+esc(pname)+'"?', function() {
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
      el.innerHTML = '<div class="vmf-empty"><div class="vmf-empty-icon">'+(activeDatastore?'&#128451;':'&#128193;')+'</div><div>' + (searchQ?'No matches.':activeDatastore?'No VMs on this datastore.':'Folder is empty.') + '</div></div>';
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
        ? '<span style="color:#767676;font-style:italic;font-size:11px">Unorganized</span>'
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
        (unmanaged ? ' <span class="vmf-badge-un" title="Discovered on the hypervisor, not provisioned by Morpheus">unmanaged</span>' : '') + '</td>' +
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
    el.innerHTML = html;

    bindVlist(el);
    // Status reflects the rows actually listed; when host/cloud scoping or a folder
    // narrows the set, name the total too so the footer can't contradict the table.
    var total = allVms.length;
    setStatus(vms.length+' VM'+(vms.length!==1?'s':'')+(vms.length!==total?' of '+total:'')+(searchQ?' (filtered)':''));
  }

  // ── Public ─────────────────────────────────────────────────────────
  window.vmfReload = async function() {
    syncDarkMode();
    setStatus('Loading...');
    var fl=document.getElementById('vmf-flist'), vl=document.getElementById('vmf-vlist');
    if(fl) fl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div>';
    if(vl) vl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading VMs...</div>';
    selectedIds.clear(); updateMvBtn();
    try {
      await fetchAll();
      // Auto-detect cloud from context set by HBS template (DOM-read synchronously).
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
      // renderVms() has already set the status to the rows it listed — do not overwrite
      // it with the unscoped total (that is what showed "21 VMs loaded" against 9 rows).
    } catch(e) {
      if(vl) vl.innerHTML='<div class="vmf-empty"><div class="vmf-empty-icon">&#9888;</div><div style="color:#c00">Error: '+esc(e.message)+'</div></div>';
      setStatus('Error');
    }
  };

  window.vmfSelectFolder = function(key) {
    resetSelection(key, null); updateMvBtn(); renderTree(); renderDatastores(); renderVms();
    var bc=document.getElementById('vmf-bc');
    if(bc) bc.innerHTML='&#128193; <b>'+esc(key==='__all__'?'All VMs':key===ROOT?'Unorganized':key)+'</b>';
  };

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
    openModal('Move to Folder', [
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
    openModal('Rename Folder',[
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
    var msg='Delete "'+path+'"?'+(c>0?' '+c+' VM(s) will become Unorganized.':'');
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
    vmfToast(fail>0?ok+' moved, '+fail+' failed':'Moved '+ok+' VM'+(ok!==1?'s':'')+' to '+(path===ROOT?'Unorganized':path),fail>0);
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
  window.vmfAutoOrganize = function() {
    openConfirm('Auto-Organize VMs','Create Cloud/Host folders and assign all VMs. Existing assignments will be updated.',async function(){
      setStatus('Auto-organizing...');
      var folders={},assignments={};
      allVms.forEach(function(vm){
        var cloud=(vm.cloudName||'Unknown').replace(/[/]/g,'-');
        var host=(vm.hostName||'Unknown Host').replace(/[/]/g,'-');
        folders['/'+cloud]=true; folders['/'+cloud+'/'+host]=true;
        assignments[vm.id]='/'+cloud+'/'+host;
      });
      for(var fp of Object.keys(folders).sort()) await post('/saveFolder', {path: fp});
      for(var vid of Object.keys(assignments)) await post('/assign', {vmId: vid, path: assignments[vid]});
      await fetchAll();renderTree();vmfSelectFolder('__all__');
      vmfToast('Auto-organized '+allVms.length+' VMs');
    });
  };

  // ── Re-sync host assignments (VME auto-migration) ────────────────────
  window.vmfResyncHosts = async function() {
    openConfirm('Re-sync Host Assignments','Update VMs in Cloud/Host folders to reflect current host locations.',async function(){
      setStatus('Re-syncing...');
      var d=await post('/resync', {});
      vmfToast(d.success?(d.moved>0?'Re-synced '+d.moved+' VM(s)':'All VMs already current'):'Re-sync failed: '+d.error,!d.success);
      if(d.success&&d.moved>0){await fetchAll();renderTree();renderVms();}
    });
  };

  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}

})();
