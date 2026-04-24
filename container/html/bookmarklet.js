/**
 * VM Folders Bookmarklet
 * Injected into any Morpheus page via bookmark.
 * Slides in as a panel on the right side.
 * Token stored in localStorage. Folder data via companion API or localStorage.
 */
(function() {
  'use strict';

  // Toggle if already loaded
  var existing = document.getElementById('vmfp-root');
  if (existing) {
    existing.style.display = existing.style.display === 'none' ? 'flex' : 'none';
    return;
  }

  var ROOT = '/';
  var allVms = [], storedFolders = [], activeFolder = '__all__', searchQ = '';
  var selectedIds = new Set(), sortCol = 'name', sortAsc = true;
  var cfg = {};
  var useLocalStorage = false;

  // ── Config ────────────────────────────────────────────────────────
  function loadCfg() {
    try { cfg = JSON.parse(localStorage.getItem('vmf-cfg') || '{}'); } catch(e) { cfg = {}; }
    // Auto-detect Morpheus URL from current page
    if (!cfg.morpheusUrl) cfg.morpheusUrl = window.location.origin;
  }
  function saveCfg() { localStorage.setItem('vmf-cfg', JSON.stringify(cfg)); }
  loadCfg();

  // ── Inject styles ─────────────────────────────────────────────────
  var style = document.createElement('style');
  style.textContent = `
    #vmfp-root * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
    #vmfp-root { position: fixed; top: 0; right: 0; width: 780px; max-width: 95vw; height: 100vh; background: #F5F5F5; z-index: 999999; display: flex; flex-direction: column; box-shadow: -4px 0 24px rgba(0,0,0,.25); border-left: 1px solid #CCCCCC; }
    #vmfp-root :root { --hpe-green: #01A982; --hpe-green-dark: #008567; --hpe-header: #425563; }
    #vmfp-hdr { display: flex; align-items: center; justify-content: space-between; padding: 0 14px; height: 44px; background: #425563; color: #fff; flex-shrink: 0; }
    #vmfp-hdr h2 { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    #vmfp-hdr h2::before { content: ''; display: inline-block; width: 3px; height: 16px; background: #01A982; border-radius: 2px; }
    #vmfp-hdr-right { display: flex; gap: 5px; align-items: center; }
    .vmfp-btn { padding: 4px 10px; border-radius: 4px; font-size: 11px; font-weight: 500; cursor: pointer; white-space: nowrap; display: inline-flex; align-items: center; gap: 4px; text-decoration: none; }
    .vmfp-btn-outline { border: 1px solid rgba(255,255,255,.4); background: transparent; color: #fff; }
    .vmfp-btn-outline:hover { border-color: #fff; background: rgba(255,255,255,.1); }
    .vmfp-btn-primary { border: 1px solid #008567; background: #01A982; color: #fff; }
    .vmfp-btn-primary:hover { background: #008567; }
    .vmfp-btn-secondary { border: 1px solid #CCCCCC; background: #fff; color: #333; }
    .vmfp-btn-secondary:hover { border-color: #01A982; color: #008567; }
    .vmfp-btn-close { background: none; border: none; color: #fff; font-size: 18px; cursor: pointer; opacity: .7; line-height: 1; padding: 0 4px; }
    .vmfp-btn-close:hover { opacity: 1; }
    #vmfp-main { display: flex; flex: 1; overflow: hidden; }
    #vmfp-tree { width: 180px; background: #fff; border-right: 1px solid #CCCCCC; display: flex; flex-direction: column; overflow: hidden; flex-shrink: 0; }
    #vmfp-tree-head { padding: 8px 12px; font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: .07em; color: #767676; background: #F5F5F5; border-bottom: 1px solid #CCCCCC; flex-shrink: 0; }
    #vmfp-flist { flex: 1; overflow-y: auto; padding: 3px 0; }
    .vmfp-fi { display: flex; align-items: center; gap: 6px; padding: 6px 12px; cursor: pointer; border-left: 3px solid transparent; font-size: 11px; user-select: none; }
    .vmfp-fi:hover { background: #F9FAFA; }
    .vmfp-fi.active { background: #E6F5F1; border-left-color: #01A982; color: #008567; font-weight: 600; }
    .vmfp-fi-icon { font-size: 12px; flex-shrink: 0; }
    .vmfp-fi-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .vmfp-fi-count { font-size: 10px; background: #eee; color: #767676; border-radius: 8px; padding: 0 5px; flex-shrink: 0; }
    .vmfp-fi.active .vmfp-fi-count { background: #c8ede4; color: #008567; }
    .vmfp-fi-acts { display: none; gap: 2px; flex-shrink: 0; }
    .vmfp-fi:hover .vmfp-fi-acts { display: flex; }
    .vmfp-fi-btn { background: none; border: none; cursor: pointer; font-size: 10px; color: #767676; padding: 1px 2px; border-radius: 2px; }
    .vmfp-fi-btn:hover { background: #eee; color: #333; }
    .vmfp-fi-btn.del:hover { color: #c00; background: #fff0f0; }
    .vmfp-div { height: 1px; background: #CCCCCC; margin: 2px 0; opacity: .5; }
    #vmfp-content { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
    #vmfp-toolbar { display: flex; align-items: center; gap: 6px; padding: 6px 10px; background: #fff; border-bottom: 1px solid #CCCCCC; flex-shrink: 0; }
    #vmfp-search { flex: 1; padding: 4px 8px; border: 1px solid #CCCCCC; border-radius: 4px; font-size: 11px; }
    #vmfp-search:focus { outline: none; border-color: #01A982; }
    #vmfp-bc { font-size: 11px; color: #767676; }
    #vmfp-bc b { color: #333; }
    #vmfp-vlist { flex: 1; overflow-y: auto; }
    #vmfp-status { padding: 4px 12px; background: #F5F5F5; border-top: 1px solid #CCCCCC; font-size: 10px; color: #767676; flex-shrink: 0; }
    table.vmfpt { width: 100%; border-collapse: collapse; background: #fff; }
    table.vmfpt thead th { position: sticky; top: 0; background: #F5F5F5; border-bottom: 2px solid #CCCCCC; padding: 6px 10px; text-align: left; font-weight: 600; font-size: 10px; text-transform: uppercase; letter-spacing: .05em; color: #767676; cursor: pointer; user-select: none; white-space: nowrap; }
    table.vmfpt thead th:hover { color: #333; }
    table.vmfpt thead th.sorted { color: #008567; }
    table.vmfpt tbody tr { border-bottom: 1px solid #f0f0f0; }
    table.vmfpt tbody tr:hover { background: #F9FAFA; }
    table.vmfpt tbody tr.sel { background: #E6F5F1; }
    table.vmfpt td { padding: 6px 10px; font-size: 11px; vertical-align: middle; }
    .vmfpt-name a { color: #008567; text-decoration: none; font-weight: 500; }
    .vmfpt-name a:hover { text-decoration: underline; }
    .vmfp-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
    .vmfp-tag { display: inline-block; background: #e6f5f1; color: #008567; border-radius: 3px; padding: 1px 6px; font-size: 10px; font-weight: 500; border: 1px solid #b3e0d5; }
    .vmfp-act { padding: 2px 7px; font-size: 10px; border: 1px solid #CCCCCC; border-radius: 3px; background: #fff; cursor: pointer; color: #333; text-decoration: none; display: inline-flex; align-items: center; }
    .vmfp-act:hover { border-color: #01A982; color: #008567; }
    .vmfp-act-x { border-color: transparent; color: #767676; }
    .vmfp-act-x:hover { border-color: #c00; color: #c00; background: #fff5f5; }
    .vmfp-act-con { background: #01A982; border-color: #008567; color: #fff; }
    .vmfp-act-con:hover { background: #008567; color: #fff; }
    #vmfp-modal { display: none; position: absolute; inset: 0; background: rgba(0,0,0,.5); align-items: center; justify-content: center; z-index: 10; }
    .vmfp-mbox { background: #fff; border-radius: 6px; box-shadow: 0 8px 32px rgba(0,0,0,.2); width: 340px; overflow: hidden; }
    .vmfp-mhead { padding: 12px 16px; background: #425563; color: #fff; font-weight: 600; font-size: 13px; display: flex; align-items: center; justify-content: space-between; }
    .vmfp-mbody { padding: 16px; }
    .vmfp-mfoot { padding: 10px 16px; border-top: 1px solid #CCCCCC; display: flex; gap: 6px; justify-content: flex-end; background: #F5F5F5; }
    .vmfp-fg { margin-bottom: 10px; }
    .vmfp-fg label { display: block; font-size: 10px; font-weight: 600; margin-bottom: 4px; color: #767676; text-transform: uppercase; letter-spacing: .05em; }
    .vmfp-fg input { width: 100%; padding: 6px 9px; border: 1px solid #CCCCCC; border-radius: 4px; font-size: 12px; }
    .vmfp-fg input:focus { outline: none; border-color: #01A982; }
    .vmfp-hint { font-size: 10px; color: #767676; margin-top: 3px; }
    #vmfp-toast { position: absolute; bottom: 16px; right: 16px; background: #425563; color: #fff; padding: 8px 14px; border-radius: 4px; font-size: 11px; opacity: 0; transition: opacity .25s; pointer-events: none; z-index: 20; border-left: 3px solid #01A982; }
    #vmfp-toast.err { border-left-color: #c00; }
    .vmfp-spin { display: flex; align-items: center; justify-content: center; padding: 40px; color: #767676; gap: 8px; font-size: 12px; }
    .vmfp-spinner { width: 16px; height: 16px; border: 2px solid #CCCCCC; border-top-color: #01A982; border-radius: 50%; animation: vmfpspin .7s linear infinite; }
    @keyframes vmfpspin { to { transform: rotate(360deg); } }
    .vmfp-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px; color: #767676; gap: 5px; }
    .vmfp-empty-icon { font-size: 30px; opacity: .35; }
    #vmfp-token-screen { display: none; position: absolute; inset: 0; background: #425563; align-items: center; justify-content: center; z-index: 15; }
    #vmfp-token-screen.show { display: flex; }
    .vmfp-token-box { background: #fff; border-radius: 6px; width: 320px; overflow: hidden; box-shadow: 0 8px 32px rgba(0,0,0,.3); }
    .vmfp-token-head { background: #425563; color: #fff; padding: 16px; }
    .vmfp-token-head h3 { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    .vmfp-token-head h3::before { content: ''; display: inline-block; width: 3px; height: 16px; background: #01A982; border-radius: 2px; }
    .vmfp-token-head p { font-size: 11px; color: #B0BEC5; margin-top: 4px; }
    .vmfp-token-body { padding: 16px; }
    .vmfp-token-body input { width: 100%; padding: 8px 10px; border: 1px solid #CCCCCC; border-radius: 4px; font-size: 12px; margin-top: 4px; }
    .vmfp-token-body input:focus { outline: none; border-color: #01A982; }
    .vmfp-token-hint { font-size: 10px; color: #767676; margin-top: 6px; line-height: 1.5; }
    .vmfp-token-foot { padding: 10px 16px; border-top: 1px solid #CCCCCC; display: flex; gap: 6px; justify-content: flex-end; background: #F5F5F5; }
    #vmfp-token-err { color: #c00; font-size: 11px; margin-top: 6px; display: none; }
  `;
  document.head.appendChild(style);

  // ── Build DOM ─────────────────────────────────────────────────────
  var root = document.createElement('div');
  root.id = 'vmfp-root';
  root.innerHTML = `
    <!-- Token screen -->
    <div id="vmfp-token-screen" class="${!cfg.token ? 'show' : ''}">
      <div class="vmfp-token-box">
        <div class="vmfp-token-head">
          <h3>VM Folders</h3>
          <p>API token required to access VM data</p>
        </div>
        <div class="vmfp-token-body">
          <label style="font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.05em;color:#767676">API Token</label>
          <input id="vmfp-t-input" type="password" placeholder="Paste your API token">
          <div class="vmfp-token-hint">
            Get your token:<br>
            <b>Profile → User Settings → API Access → Regenerate</b><br>
            Stored in localStorage, only sent to this Morpheus instance.
          </div>
          <div id="vmfp-token-err"></div>
        </div>
        <div class="vmfp-token-foot">
          <button class="vmfp-btn vmfp-btn-secondary" id="vmfp-t-cancel">Cancel</button>
          <button class="vmfp-btn vmfp-btn-primary" id="vmfp-t-save">Connect</button>
        </div>
      </div>
    </div>

    <!-- Header -->
    <div id="vmfp-hdr">
      <h2>VM Folders</h2>
      <div id="vmfp-hdr-right">
        <button class="vmfp-btn vmfp-btn-outline" id="vmfp-settings-btn" title="Settings">&#9881;</button>
        <button class="vmfp-btn vmfp-btn-outline" id="vmfp-backup-btn">&#128190;</button>
        <button class="vmfp-btn vmfp-btn-outline" id="vmfp-export-btn">&#8595; Export</button>
        <button class="vmfp-btn vmfp-btn-outline" id="vmfp-refresh-btn">&#8635;</button>
        <button class="vmfp-btn vmfp-btn-primary" id="vmfp-new-folder-btn">+ Folder</button>
        <button class="vmfp-btn-close" id="vmfp-close-btn">&#215;</button>
      </div>
    </div>

    <!-- Main -->
    <div id="vmfp-main">
      <div id="vmfp-tree">
        <div id="vmfp-tree-head">Folders</div>
        <div id="vmfp-flist"><div class="vmfp-spin"><div class="vmfp-spinner"></div>Loading...</div></div>
      </div>
      <div id="vmfp-content">
        <div id="vmfp-toolbar">
          <input id="vmfp-search" type="text" placeholder="Search VMs...">
          <span id="vmfp-bc">&#128193; <b>All VMs</b></span>
          <button id="vmfp-mv-btn" class="vmfp-btn vmfp-btn-primary" style="display:none;font-size:11px;padding:3px 8px;">Move</button>
        </div>
        <div id="vmfp-vlist"><div class="vmfp-spin"><div class="vmfp-spinner"></div>Loading VMs...</div></div>
        <div id="vmfp-status">Ready</div>
      </div>
    </div>

    <!-- Modal -->
    <div id="vmfp-modal">
      <div class="vmfp-mbox">
        <div class="vmfp-mhead"><span id="vmfp-mtitle"></span><span style="cursor:pointer;font-size:16px;opacity:.7" id="vmfp-close-modal">&#215;</span></div>
        <div id="vmfp-mbody" class="vmfp-mbody"></div>
        <div id="vmfp-mfoot" class="vmfp-mfoot"></div>
      </div>
    </div>
    <div id="vmfp-toast"></div>
  `;
  document.body.appendChild(root);

  // ── Token screen ──────────────────────────────────────────────────
  function showTokenScreen(cancellable) {
    document.getElementById('vmfp-token-screen').classList.add('show');
    document.getElementById('vmfp-t-cancel').style.display = cancellable ? '' : 'none';
    document.getElementById('vmfp-token-err').style.display = 'none';
    document.getElementById('vmfp-t-input').value = '';
    setTimeout(function() { document.getElementById('vmfp-t-input').focus(); }, 50);
  }
  function hideTokenScreen() { document.getElementById('vmfp-token-screen').classList.remove('show'); }

  document.getElementById('vmfp-t-save').addEventListener('click', async function() {
    var token = document.getElementById('vmfp-t-input').value.trim();
    var err = document.getElementById('vmfp-token-err');
    if (!token) { err.textContent = 'Token required.'; err.style.display = ''; return; }
    this.textContent = 'Connecting...'; this.disabled = true;
    try {
      var r = await fetch(cfg.morpheusUrl + '/api/servers?max=1', {
        headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' }
      });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      cfg.token = token; saveCfg();
      hideTokenScreen(); vmfReload();
    } catch(e) {
      err.textContent = 'Failed: ' + e.message; err.style.display = '';
    }
    this.textContent = 'Connect'; this.disabled = false;
  });
  document.getElementById('vmfp-t-cancel').addEventListener('click', hideTokenScreen);

  // ── API ───────────────────────────────────────────────────────────
  function morpheusGet(path) {
    return fetch(cfg.morpheusUrl + path, {
      headers: { Authorization: 'Bearer ' + cfg.token, Accept: 'application/json' }
    }).then(function(r) { if(!r.ok) throw new Error('HTTP '+r.status); return r.json(); });
  }

  async function dbGet(path) {
    if (useLocalStorage) return lsDbOp(path);
    try {
      var r = await fetch(cfg.morpheusUrl + '/vmfolders-api' + path, { credentials: 'same-origin' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    } catch(e) {
      useLocalStorage = true;
      return lsDbOp(path);
    }
  }

  function lsRead() {
    try { return JSON.parse(localStorage.getItem('vmf-db') || '{"folders":[],"assignments":{},"history":[],"version":0}'); } catch(e) { return {folders:[],assignments:{},history:[],version:0}; }
  }
  function lsWrite(db) { localStorage.setItem('vmf-db', JSON.stringify(db)); }

  function lsDbOp(path) {
    var u = new URL('http://x' + path), p = {};
    u.searchParams.forEach(function(v,k){p[k]=v;});
    var ep = u.pathname, db = lsRead();
    if (ep==='/db') return Promise.resolve(db);
    if (ep==='/saveFolder') { var fp=(p.path||'').trim();if(!fp.startsWith('/'))fp='/'+fp;if(!db.folders.find(function(f){return f.path===fp;}))db.folders.push({path:fp,desc:p.desc||'',created:new Date().toISOString()});lsWrite(db);return Promise.resolve({success:true,path:fp}); }
    if (ep==='/delFolder') { var dp=p.path||'';db.folders=db.folders.filter(function(f){return f.path!==dp&&!f.path.startsWith(dp+'/');});Object.keys(db.assignments).forEach(function(k){if(db.assignments[k]===dp||db.assignments[k].startsWith(dp+'/'))delete db.assignments[k];});lsWrite(db);return Promise.resolve({success:true}); }
    if (ep==='/renFolder') { var op=p.oldPath,np=p.newPath;if(!np.startsWith('/'))np='/'+np;db.folders.forEach(function(f){if(f.path===op)f.path=np;else if(f.path.startsWith(op+'/'))f.path=np+f.path.substring(op.length);});Object.keys(db.assignments).forEach(function(k){var v=db.assignments[k];if(v===op)db.assignments[k]=np;else if(v.startsWith(op+'/'))db.assignments[k]=np+v.substring(op.length);});lsWrite(db);return Promise.resolve({success:true,newPath:np}); }
    if (ep==='/assign') { var vid=p.vmId,fp2=(p.path||'');if(!fp2.startsWith('/'))fp2='/'+fp2;db.assignments[vid]=fp2;if(!db.folders.find(function(f){return f.path===fp2;}))db.folders.push({path:fp2,desc:'',created:new Date().toISOString()});lsWrite(db);return Promise.resolve({success:true}); }
    if (ep==='/unassign') { delete db.assignments[p.vmId];lsWrite(db);return Promise.resolve({success:true}); }
    return Promise.resolve({success:false,error:'Unknown'});
  }

  async function fetchAll() {
    var results = await Promise.all([
      (async function(){var all=[],o=0;while(true){var d=await morpheusGet('/api/servers?max=250&offset='+o+'&vmHypervisor=false&serverType=vm');var b=d.servers||d.computeServers||[];all=all.concat(b);if(all.length>=(d.meta&&d.meta.total?d.meta.total:all.length)||b.length<250)break;o+=250;}return all;})(),
      dbGet('/db')
    ]);
    var rawVms = results[0], db = results[1];
    storedFolders = db.folders || [];
    var asgn = db.assignments || {};
    allVms = rawVms.map(function(s) {
      var ps=(s.powerState||s.status||'unknown').toString();
      var os=(s.osType&&typeof s.osType==='object')?(s.osType.name||''):(s.osType||'');
      var cl=(s.cloud&&typeof s.cloud==='object')?(s.cloud.name||''):(s.cloud||'');
      return {id:s.id,name:s.name||'VM-'+s.id,powerState:ps,externalIp:s.externalIp||'',internalIp:s.internalIp||'',osType:os,maxMemory:s.maxMemory||0,maxCores:s.maxCores||0,cloudName:cl,folderPath:asgn[String(s.id)]||ROOT};
    });
  }

  // ── Folder helpers ────────────────────────────────────────────────
  function getVmFolder(vm) { return vm.folderPath || ROOT; }
  function allPaths() {
    var s=new Set(storedFolders.map(function(f){return f.path;}));
    allVms.forEach(function(vm){var p=getVmFolder(vm);if(p!==ROOT)s.add(p);});
    Array.from(s).forEach(function(p){var parts=p.split('/').filter(Boolean);for(var i=1;i<parts.length;i++)s.add('/'+parts.slice(0,i).join('/'));});
    return Array.from(s).sort();
  }
  function countIn(path) { return allVms.filter(function(vm){var p=getVmFolder(vm);return p===path||p.startsWith(path+'/');}).length; }
  function isStored(path) { return storedFolders.some(function(f){return f.path===path;}); }

  // ── Tree ──────────────────────────────────────────────────────────
  function renderTree() {
    var paths = allPaths();
    var html = ti('__all__','&#128196;','All VMs',allVms.length,0,null,false);
    html += '<div class="vmfp-div"></div>';
    if (!paths.length) html += '<div style="padding:8px 12px;color:#767676;font-size:11px;line-height:1.5">No folders.<br>Click <b>+ Folder</b>.</div>';
    else paths.forEach(function(p){var d=p.split('/').filter(Boolean).length;var n=p.split('/').filter(Boolean).pop()||'/';html+=ti(p,'&#128193;',n,countIn(p),d>1?(d-1)*14:0,p,isStored(p));});
    var unorg=allVms.filter(function(vm){return getVmFolder(vm)===ROOT;}).length;
    if(unorg>0){html+='<div class="vmfp-div"></div>';html+=ti(ROOT,'&#128220;','Unorganized',unorg,0,null,false);}
    var el=document.getElementById('vmfp-flist');if(el)el.innerHTML=html;
  }

  function ti(key,icon,name,count,indent,fp,stored) {
    var active=activeFolder===key;
    var cls='vmfp-fi'+(active?' active':'');
    var style=indent?' style="padding-left:'+(12+indent)+'px"':'';
    var title=fp?' title="'+esc(fp)+'"':'';
    var acts=stored?'<div class="vmfp-fi-acts"><button class="vmfp-fi-btn" data-pren="'+esc(key)+'" title="Rename">&#9998;</button><button class="vmfp-fi-btn del" data-pdel="'+esc(key)+'" title="Delete">&#10006;</button></div>':'';
    return '<div class="'+cls+'"'+style+' data-pfk="'+esc(key)+'"'+title+'>'+
      '<span class="vmfp-fi-icon">'+icon+'</span><span class="vmfp-fi-name">'+esc(name)+'</span>'+
      '<span class="vmfp-fi-count">'+count+'</span>'+acts+'</div>';
  }

  root.addEventListener('click', function(e) {
    var modal=document.getElementById('vmfp-modal');
    if(modal&&modal.contains(e.target))return;
    var ren=e.target.closest('[data-pren]'),del=e.target.closest('[data-pdel]'),fi=e.target.closest('[data-pfk]');
    if(ren){e.stopPropagation();vmfpRenameFolder(ren.getAttribute('data-pren'));return;}
    if(del){e.stopPropagation();vmfpDeleteFolder(del.getAttribute('data-pdel'));return;}
    if(fi)vmfpSelectFolder(fi.getAttribute('data-pfk'));
  });

  // ── VM table ──────────────────────────────────────────────────────
  function getFiltered() {
    var vms=activeFolder==='__all__'?allVms.slice():allVms.filter(function(vm){return getVmFolder(vm)===activeFolder;});
    if(searchQ){var q=searchQ.toLowerCase();vms=vms.filter(function(vm){return(vm.name||'').toLowerCase().includes(q)||(vm.externalIp||'').includes(q)||(vm.internalIp||'').includes(q)||(vm.osType||'').toLowerCase().includes(q);});}
    return vms.sort(function(a,b){var av=String(a[sortCol]||'').toLowerCase(),bv=String(b[sortCol]||'').toLowerCase();return sortAsc?(av<bv?-1:av>bv?1:0):(av>bv?-1:av<bv?1:0);});
  }

  function dot(s){var c=String(s||'').toLowerCase().match(/on|running/)?'#01A982':String(s||'').toLowerCase().match(/off|stopped/)?'#CC0000':'#CCCCCC';return'<span class="vmfp-dot" style="background:'+c+'"></span>';}
  function fmtMem(b){if(!b)return'—';var g=b/1073741824;return g>=1?g.toFixed(1)+' GB':Math.round(b/1048576)+' MB';}

  function renderVms() {
    var vms=getFiltered(),el=document.getElementById('vmfp-vlist');
    if(!el)return;
    if(!vms.length){el.innerHTML='<div class="vmfp-empty"><div class="vmfp-empty-icon">&#128193;</div><div>'+(searchQ?'No matches.':'Folder is empty.')+'</div></div>';setStatus('0 VMs');return;}
    var html='<table class="vmfpt"><thead><tr><th style="width:22px"><input type="checkbox" id="vmfp-ca"></th>';
    [['name','Name'],['powerState','Status'],['osType','OS'],['maxMemory','Mem'],['externalIp','IP']].forEach(function(c){var s=sortCol===c[0];html+='<th class="'+(s?'sorted':'')+'" data-psort="'+c[0]+'">'+c[1]+(s?(sortAsc?' &#9650;':' &#9660;'):'')+' </th>';});
    html+='<th>Folder</th><th>Act.</th></tr></thead><tbody>';
    vms.forEach(function(vm){
      var id=vm.id,fp=getVmFolder(vm),sel=selectedIds.has(id),ip=vm.externalIp||vm.internalIp||'—';
      var flabel=fp===ROOT?'<span style="color:#767676;font-style:italic;font-size:10px">None</span>':'<span class="vmfp-tag">'+esc(fp.split('/').filter(Boolean).pop()||'/')+'</span>';
      var ss=String(vm.powerState||'unknown'),isOn=ss.toLowerCase().match(/on|running/);
      html+='<tr class="'+(sel?'sel':'')+'">'+'<td><input type="checkbox" class="vmfp-cb" data-id="'+id+'"'+(sel?' checked':'')+' ></td>'+'<td class="vmfpt-name"><a href="'+cfg.morpheusUrl+'/infrastructure/servers/'+id+'" target="_blank">'+esc(vm.name||'VM-'+id)+'</a></td>'+'<td>'+dot(vm.powerState)+'<span style="vertical-align:middle">'+esc(ss)+'</span></td>'+'<td>'+esc(vm.osType||'—')+'</td>'+'<td>'+fmtMem(vm.maxMemory)+'</td>'+'<td>'+esc(ip)+'</td>'+'<td>'+flabel+'</td>'+'<td><div style="display:flex;gap:2px">'+'<button class="vmfp-act vmfp-mov" data-id="'+id+'">Move</button>'+'<a class="vmfp-act vmfp-act-con" href="'+cfg.morpheusUrl+'/terminal/server/'+id+'?consoleMode=hypervisor" target="_blank" title="Console">&#9654;</a>'+(isOn?'<button class="vmfp-act vmfp-pw" data-id="'+id+'" data-action="stop" title="Stop" style="color:#c00;font-size:10px">&#9632;</button>':'<button class="vmfp-act vmfp-pw" data-id="'+id+'" data-action="start" title="Start" style="color:#01A982;font-size:10px">&#9654;</button>')+(fp!==ROOT?'<button class="vmfp-act vmfp-act-x vmfp-rm" data-id="'+id+'" title="Remove">&#10006;</button>':'')+'</div></td></tr>';
    });
    html+='</tbody></table>';
    el.innerHTML=html;
    var ca=document.getElementById('vmfp-ca');if(ca)ca.addEventListener('change',function(){vmfpToggleAll(this.checked);});
    el.addEventListener('change',function(e){if(e.target.classList.contains('vmfp-cb'))vmfpToggleSel(parseInt(e.target.dataset.id),e.target.checked);});
    el.addEventListener('click',function(e){
      var mb=e.target.closest('.vmfp-mov'),rb=e.target.closest('.vmfp-rm'),sh=e.target.closest('[data-psort]'),pb=e.target.closest('.vmfp-pw');
      if(mb)vmfpMoveSingle(parseInt(mb.dataset.id));
      if(rb)vmfpRemoveSingle(parseInt(rb.dataset.id));
      if(sh)vmfpSort(sh.getAttribute('data-psort'));
      if(pb)vmfpPower(parseInt(pb.dataset.id),pb.dataset.action);
    });
    setStatus(vms.length+' VM'+(vms.length!==1?'s':'')+(searchQ?' (filtered)':'')+(useLocalStorage?' [local]':''));
  }

  // ── Public ────────────────────────────────────────────────────────
  window.vmfReload = async function() {
    if(!cfg.token){showTokenScreen(false);return;}
    setStatus('Loading...');
    var fl=document.getElementById('vmfp-flist'),vl=document.getElementById('vmfp-vlist');
    if(fl)fl.innerHTML='<div class="vmfp-spin"><div class="vmfp-spinner"></div>Loading...</div>';
    if(vl)vl.innerHTML='<div class="vmfp-spin"><div class="vmfp-spinner"></div>Loading VMs...</div>';
    selectedIds.clear();updateMvBtn();
    try{await fetchAll();renderTree();renderVms();setStatus(allVms.length+' VMs loaded');}
    catch(e){if(vl)vl.innerHTML='<div class="vmfp-empty"><div class="vmfp-empty-icon">&#9888;</div><div style="color:#c00">Error: '+esc(e.message)+'</div></div>';setStatus('Error');}
  };

  function vmfpSelectFolder(key){activeFolder=key;selectedIds.clear();updateMvBtn();renderTree();renderVms();var bc=document.getElementById('vmfp-bc');if(bc)bc.innerHTML='&#128193; <b>'+esc(key==='__all__'?'All VMs':key===ROOT?'Unorganized':key)+'</b>';}
  function vmfpSort(col){if(sortCol===col)sortAsc=!sortAsc;else{sortCol=col;sortAsc=true;}renderVms();}
  function vmfpToggleSel(id,v){if(v)selectedIds.add(id);else selectedIds.delete(id);updateMvBtn();renderVms();}
  function vmfpToggleAll(v){getFiltered().forEach(function(vm){if(v)selectedIds.add(vm.id);else selectedIds.delete(vm.id);});updateMvBtn();renderVms();}
  function vmfpMoveSingle(id){selectedIds.clear();selectedIds.add(id);vmfpMoveSelected();}
  function vmfpRemoveSingle(id){openConfirm('Remove from folder?','Move to Unorganized?',async function(){await doMove([id],ROOT);});}

  function vmfpMoveSelected(){
    if(!selectedIds.size)return;
    var opts=allPaths().map(function(p){return'<option>'+esc(p)+'</option>';}).join('');
    openModal('Move',[fg('Folder Path','<input id="vmfp-fi" list="vmfp-fl" placeholder="/Production/Web" value="'+(activeFolder!=='__all__'&&activeFolder!==ROOT?esc(activeFolder):'')+'">'+'<datalist id="vmfp-fl">'+opts+'</datalist>','Moving '+selectedIds.size+' VM(s).')],[
      {l:'Cancel',fn:vmfpCloseModal},
      {l:'Move',primary:true,fn:async function(){var el=document.getElementById('vmfp-fi');if(!el)return;var p=el.value.trim();if(!p)return;if(!p.startsWith('/'))p='/'+p;vmfpCloseModal();await doMove(Array.from(selectedIds),p);}}
    ]);
    setTimeout(function(){var el=document.getElementById('vmfp-fi');if(el)el.focus();},50);
  }

  function vmfpCreateFolder(){
    openModal('New Folder',[fg('Path','<input id="vmfp-nfp" placeholder="/Production/Web">','Use / for nesting'),fg('Description (optional)','<input id="vmfp-nfd">')],[
      {l:'Cancel',fn:vmfpCloseModal},
      {l:'Create',primary:true,fn:async function(){
        var elp=document.getElementById('vmfp-nfp');if(!elp)return;
        var p=elp.value.trim();if(!p)return;if(!p.startsWith('/'))p='/'+p;
        var desc=(document.getElementById('vmfp-nfd')||{}).value||'';
        vmfpCloseModal();
        var d=await dbGet('/saveFolder?path='+encodeURIComponent(p)+'&desc='+encodeURIComponent(desc));
        if(d.success){vmfpToast('Folder created');var db=await dbGet('/db');storedFolders=db.folders||[];renderTree();vmfpSelectFolder(p);setTimeout(vmfpMoveSelected,300);}
        else vmfpToast('Failed: '+d.error,true);
      }}
    ]);
    setTimeout(function(){var el=document.getElementById('vmfp-nfp');if(el)el.focus();},50);
  }

  function vmfpRenameFolder(path){
    openModal('Rename',[fg('Current','<input disabled value="'+esc(path)+'">'),fg('New Path','<input id="vmfp-rnp" value="'+esc(path)+'">')],[
      {l:'Cancel',fn:vmfpCloseModal},
      {l:'Rename',primary:true,fn:async function(){
        var el=document.getElementById('vmfp-rnp');if(!el)return;
        var np=el.value.trim();if(!np||np===path)return vmfpCloseModal();
        if(!np.startsWith('/'))np='/'+np;vmfpCloseModal();
        var d=await dbGet('/renFolder?oldPath='+encodeURIComponent(path)+'&newPath='+encodeURIComponent(np));
        if(d.success){vmfpToast('Renamed');var db=await dbGet('/db');storedFolders=db.folders||[];allVms.forEach(function(vm){if(vm.folderPath===path)vm.folderPath=np;else if(vm.folderPath.startsWith(path+'/'))vm.folderPath=np+vm.folderPath.substring(path.length);});renderTree();vmfpSelectFolder(np);}
        else vmfpToast('Failed: '+d.error,true);
      }}
    ]);
    setTimeout(function(){var el=document.getElementById('vmfp-rnp');if(el){el.focus();el.select();}},50);
  }

  async function vmfpDeleteFolder(path){
    var c=countIn(path);
    openConfirm('Delete "'+path+'"?',(c>0?c+' VM(s) will become Unorganized. ':'')+'This cannot be undone.',async function(){
      var d=await dbGet('/delFolder?path='+encodeURIComponent(path));
      if(d.success){vmfpToast('Deleted');var db=await dbGet('/db');storedFolders=db.folders||[];allVms.forEach(function(vm){if(vm.folderPath===path||vm.folderPath.startsWith(path+'/'))vm.folderPath=ROOT;});renderTree();vmfpSelectFolder('__all__');}
      else vmfpToast('Failed: '+d.error,true);
    });
  }

  async function vmfpPower(id,action){
    try{
      var r=await fetch(cfg.morpheusUrl+'/api/servers/'+id+'/'+(action==='start'?'start':'stop'),{method:'PUT',headers:{Authorization:'Bearer '+cfg.token,Accept:'application/json','Content-Type':'application/json'}});
      vmfpToast(r.ok?(action==='start'?'Starting':'Stopping')+' VM '+id:'Failed: HTTP '+r.status,!r.ok);
      if(r.ok)setTimeout(vmfReload,3000);
    }catch(e){vmfpToast('Error: '+e.message,true);}
  }

  function vmfpCloseModal(){var m=document.getElementById('vmfp-modal');if(m){m.style.display='none';m.style.visibility='hidden';m.style.pointerEvents='none';}}

  async function doMove(ids,path){
    setStatus('Moving...');var ok=0,fail=0;
    for(var i=0;i<ids.length;i++){try{var d=path===ROOT?await dbGet('/unassign?vmId='+ids[i]):await dbGet('/assign?vmId='+ids[i]+'&path='+encodeURIComponent(path));if(d.success)ok++;else fail++;}catch(e){fail++;}}
    selectedIds.clear();updateMvBtn();
    vmfpToast(fail>0?ok+' moved, '+fail+' failed':'Moved '+ok+' VM'+(ok!==1?'s':'')+' to '+(path===ROOT?'Unorganized':path),fail>0);
    await fetchAll();renderTree();renderVms();
  }

  function fg(label,input,hint){return'<div class="vmfp-fg"><label>'+label+'</label>'+input+(hint?'<div class="vmfp-hint">'+hint+'</div>':'')+'</div>';}

  function openModal(title,bodyParts,btns){
    var m=document.getElementById('vmfp-modal');if(!m)return;
    document.getElementById('vmfp-mtitle').textContent=title;
    document.getElementById('vmfp-mbody').innerHTML=bodyParts.join('');
    var foot=document.getElementById('vmfp-mfoot');foot.innerHTML='';
    btns.forEach(function(b){var btn=document.createElement('button');btn.className='vmfp-btn '+(b.primary?'vmfp-btn-primary':'vmfp-btn-secondary');btn.textContent=b.l;btn.addEventListener('click',b.fn);foot.appendChild(btn);});
    m.style.display='flex';m.style.visibility='visible';m.style.pointerEvents='';
  }

  function openConfirm(title,msg,onConfirm){
    openModal(title,['<p style="font-size:12px;line-height:1.5">'+esc(msg)+'</p>'],[
      {l:'Cancel',fn:vmfpCloseModal},
      {l:'Confirm',primary:true,fn:function(){vmfpCloseModal();onConfirm();}}
    ]);
  }

  function updateMvBtn(){var b=document.getElementById('vmfp-mv-btn');if(b)b.style.display=selectedIds.size>0?'':'none';}
  function setStatus(msg){var el=document.getElementById('vmfp-status');if(el)el.textContent=msg;}
  function vmfpToast(msg,err){var t=document.getElementById('vmfp-toast');if(!t)return;t.textContent=msg;t.className=err?'err':'';t.style.opacity='1';clearTimeout(t._t);t._t=setTimeout(function(){t.style.opacity='0';},3500);}
  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}

  // ── Wire up header buttons ────────────────────────────────────────
  document.getElementById('vmfp-close-btn').addEventListener('click', function(){ root.style.display='none'; });
  document.getElementById('vmfp-refresh-btn').addEventListener('click', function(){ vmfReload(); });
  document.getElementById('vmfp-new-folder-btn').addEventListener('click', function(){ vmfpCreateFolder(); });
  document.getElementById('vmfp-mv-btn').addEventListener('click', function(){ vmfpMoveSelected(); });
  document.getElementById('vmfp-close-modal').addEventListener('click', function(){ vmfpCloseModal(); });
  document.getElementById('vmfp-settings-btn').addEventListener('click', function(){ showTokenScreen(true); });
  document.getElementById('vmfp-search').addEventListener('input', function(){ searchQ=this.value; renderVms(); });
  document.getElementById('vmfp-backup-btn').addEventListener('click', async function(){
    var d=await dbGet('/backup'); vmfpToast(d.success?'Backup created':'Failed: '+d.error, !d.success);
  });
  document.getElementById('vmfp-export-btn').addEventListener('click', async function(){
    var d=await dbGet('/db');
    var blob=new Blob([JSON.stringify(d,null,2)],{type:'application/json'});
    var url=URL.createObjectURL(blob);var a=document.createElement('a');
    a.href=url;a.download='vm-folders-'+new Date().toISOString().slice(0,10)+'.json';
    document.body.appendChild(a);a.click();document.body.removeChild(a);URL.revokeObjectURL(url);
    vmfpToast('Exported');
  });
  document.addEventListener('keydown', function(e){ if(e.key==='Escape'&&root.style.display!=='none'){ vmfpCloseModal(); } });

  // ── Init ──────────────────────────────────────────────────────────
  if (cfg.token) vmfReload();
  else showTokenScreen(false);

})();
