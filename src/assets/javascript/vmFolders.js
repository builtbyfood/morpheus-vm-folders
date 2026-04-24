(function() {
  var ROOT = '/';
  var allVms = [], storedFolders = [], activeFolder = '__all__', searchQ = '';
  var selectedIds = new Set(), sortCol = 'name', sortAsc = true;
  var API = window.vmfApiBase || '/plugin/vmFolders';

  // ── Tab injection ──────────────────────────────────────────────────
  var injected = false;
  function tryInject() {
    if (injected || window.location.href.indexOf('/infrastructure/') === -1) return;
    var tabBar = document.getElementById('nav-tabs-wrapper');
    var tabContent = document.querySelector('.tab-content');
    if (!tabBar || !tabContent || document.getElementById('vmf-tab-li')) return;
    var li = document.createElement('li');
    li.id = 'vmf-tab-li'; li.setAttribute('role','presentation');
    li.innerHTML = '<a href="#vmf-folders" aria-controls="vmf-folders" role="tab" data-toggle="tab">&#128193; Folder View</a>';
    tabBar.appendChild(li);
    var pane = document.createElement('div');
    pane.id = 'vmf-folders'; pane.setAttribute('role','tabpanel'); pane.className = 'tab-pane';
    pane.innerHTML = '<div style="padding:20px;text-align:center;color:#767676">Click to load Folder View</div>';
    tabContent.appendChild(pane);
    li.querySelector('a').addEventListener('click', function() { setTimeout(vmfReload, 150); });
    injected = true;
  }
  var lastHref = window.location.href;
  new MutationObserver(function() {
    if (window.location.href !== lastHref) { lastHref = window.location.href; injected = false; }
    tryInject();
  }).observe(document.body, { childList: true, subtree: true });
  tryInject();

  // ── API ────────────────────────────────────────────────────────────
  async function get(path) {
    var r = await fetch(API + path, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    return r.json();
  }

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
    var s = new Set(storedFolders.map(function(f) { return f.path; }));
    allVms.forEach(function(vm) { var p = getVmFolder(vm); if (p !== ROOT) s.add(p); });
    Array.from(s).forEach(function(p) {
      var parts = p.split('/').filter(Boolean);
      for (var i = 1; i < parts.length; i++) s.add('/' + parts.slice(0, i).join('/'));
    });
    return Array.from(s).sort();
  }

  function countIn(path) {
    return allVms.filter(function(vm) { var p = getVmFolder(vm); return p === path || p.startsWith(path + '/'); }).length;
  }

  function isStored(path) { return storedFolders.some(function(f) { return f.path === path; }); }

  // ── Tree ───────────────────────────────────────────────────────────
  function renderTree() {
    var paths = allPaths();
    var html = treeItem('__all__', '&#128196;', 'All VMs', allVms.length, 0, null, false);
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
    var unorg = allVms.filter(function(vm) { return getVmFolder(vm) === ROOT; }).length;
    if (unorg > 0) {
      html += '<div class="vmf-divider"></div>';
      html += treeItem(ROOT, '&#128220;', 'Unorganized', unorg, 0, null, false);
    }
    var el = document.getElementById('vmf-flist');
    if (el) el.innerHTML = html;
  }

  function treeItem(key, icon, name, count, indent, fullPath, stored) {
    var active = activeFolder === key;
    var cls = 'vmf-fi' + (active ? ' active' : '');
    var style = indent ? ' style="padding-left:' + (14 + indent) + 'px"' : '';
    var title = fullPath ? ' title="' + esc(fullPath) + '"' : '';
    var actions = stored ? '<div class="vmf-fi-actions">' +
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
    var ren = e.target.closest('[data-rename]');
    var del = e.target.closest('[data-delfolder]');
    var fi  = e.target.closest('[data-folder-key]');
    if (ren) { e.stopPropagation(); vmfRenameFolder(ren.getAttribute('data-rename')); return; }
    if (del) { e.stopPropagation(); vmfDeleteFolder(del.getAttribute('data-delfolder')); return; }
    if (fi)  vmfSelectFolder(fi.getAttribute('data-folder-key'));
  });

  // ── VM table ───────────────────────────────────────────────────────
  function getFiltered() {
    var vms = activeFolder === '__all__' ? allVms.slice() :
              activeFolder === ROOT ? allVms.filter(function(vm) { return getVmFolder(vm) === ROOT; }) :
              allVms.filter(function(vm) { var p = getVmFolder(vm); return p === activeFolder || p.startsWith(activeFolder + '/'); });
    if (searchQ) {
      var q = searchQ.toLowerCase();
      vms = vms.filter(function(vm) {
        return (vm.name||'').toLowerCase().includes(q) || (vm.externalIp||'').includes(q) ||
               (vm.internalIp||'').includes(q) || (vm.osType||'').toLowerCase().includes(q) ||
               (vm.cloudName||'').toLowerCase().includes(q);
      });
    }
    return vms.sort(function(a,b) {
      var av=String(a[sortCol]||'').toLowerCase(), bv=String(b[sortCol]||'').toLowerCase();
      return sortAsc ? (av<bv?-1:av>bv?1:0) : (av>bv?-1:av<bv?1:0);
    });
  }

  function dot(status) {
    var s = String(status||'').toLowerCase();
    var c = s.match(/on|running/) ? '#01A982' : s.match(/off|stopped/) ? '#CC0000' : '#CCCCCC';
    return '<span class="vmf-dot" style="background:' + c + '"></span>';
  }
  function fmtMem(b) { if(!b) return '—'; var g=b/1073741824; return g>=1?g.toFixed(1)+' GB':Math.round(b/1048576)+' MB'; }

  function renderVms() {
    var vms = getFiltered(), el = document.getElementById('vmf-vlist');
    if (!el) return;
    if (!vms.length) {
      el.innerHTML = '<div class="vmf-empty"><div class="vmf-empty-icon">&#128193;</div><div>' + (searchQ?'No matches.':'Folder is empty.') + '</div></div>';
      setStatus('0 VMs'); return;
    }
    var cols = [['name','Name'],['powerState','Status'],['osType','OS'],['maxMemory','Memory'],['maxCores','vCPU'],['externalIp','IP'],['cloudName','Cloud']];
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
      html += '<tr class="'+(sel?'sel':'')+'">' +
        '<td><input type="checkbox" class="vmf-cb" data-id="'+id+'"'+(sel?' checked':'')+' ></td>' +
        '<td class="vmft-name"><a href="/infrastructure/servers/'+id+'" target="_blank">'+esc(vm.name||'VM-'+id)+'</a></td>' +
        '<td>'+dot(vm.powerState)+'<span style="vertical-align:middle">'+esc(statusStr)+'</span></td>' +
        '<td>'+esc(vm.osType||'—')+'</td>' +
        '<td>'+fmtMem(vm.maxMemory)+'</td>' +
        '<td>'+(vm.maxCores||'—')+'</td>' +
        '<td>'+esc(ip)+'</td>' +
        '<td>'+esc(vm.cloudName||'—')+'</td>' +
        '<td>'+folderLabel+'</td>' +
        '<td><div style="display:flex;gap:3px;flex-wrap:wrap">' +
        '<button class="vmf-act vmf-move-btn" data-id="'+id+'">Move</button>' +
        '<a class="vmf-act vmf-act-console" href="/terminal/server/'+id+'?consoleMode=hypervisor" target="_blank" title="Open console">&#9654;</a>' +
        (isOn ? '<button class="vmf-act vmf-pw-btn" data-id="'+id+'" data-action="stop" title="Stop VM" style="color:#c00">&#9632; Stop</button>' : '<button class="vmf-act vmf-pw-btn" data-id="'+id+'" data-action="start" title="Start VM" style="color:#01A982">&#9654; Start</button>') +
        (fp!==ROOT ? '<button class="vmf-act vmf-act-x vmf-rm-btn" data-id="'+id+'" title="Remove from folder">&#10006;</button>' : '') +
        '</div></td></tr>';
    });
    html += '</tbody></table>';
    el.innerHTML = html;

    var ca = document.getElementById('vmf-ca');
    if (ca) ca.addEventListener('change', function() { vmfToggleAll(this.checked); });
    el.addEventListener('change', function(e) {
      if (e.target.classList.contains('vmf-cb')) vmfToggleSel(parseInt(e.target.dataset.id), e.target.checked);
    });
    el.addEventListener('click', function(e) {
      var mb=e.target.closest('.vmf-move-btn'), rb=e.target.closest('.vmf-rm-btn'), sh=e.target.closest('[data-sort]'), pb=e.target.closest('.vmf-pw-btn');
      if (mb) vmfMoveSingle(parseInt(mb.dataset.id));
      if (rb) vmfRemoveSingle(parseInt(rb.dataset.id));
      if (sh) vmfSort(sh.getAttribute('data-sort'));
      if (pb) vmfPower(parseInt(pb.dataset.id), pb.dataset.action);
    });
    setStatus(vms.length+' VM'+(vms.length!==1?'s':'')+(searchQ?' (filtered)':''));
  }

  // ── Public ─────────────────────────────────────────────────────────
  window.vmfReload = async function() {
    setStatus('Loading...');
    var fl=document.getElementById('vmf-flist'), vl=document.getElementById('vmf-vlist');
    if(fl) fl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading...</div>';
    if(vl) vl.innerHTML='<div class="vmf-spin"><div class="vmf-spinner"></div>Loading VMs...</div>';
    selectedIds.clear(); updateMvBtn();
    try {
      await fetchAll();
      renderTree(); renderVms();
      setStatus(allVms.length+' VMs loaded');
    } catch(e) {
      if(vl) vl.innerHTML='<div class="vmf-empty"><div class="vmf-empty-icon">&#9888;</div><div style="color:#c00">Error: '+esc(e.message)+'</div></div>';
      setStatus('Error');
    }
  };

  window.vmfSelectFolder = function(key) {
    activeFolder=key; selectedIds.clear(); updateMvBtn(); renderTree(); renderVms();
    var bc=document.getElementById('vmf-bc');
    if(bc) bc.innerHTML='&#128193; <b>'+esc(key==='__all__'?'All VMs':key===ROOT?'Unorganized':key)+'</b>';
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
        var d=await get('/saveFolder?path='+encodeURIComponent(path)+'&desc='+encodeURIComponent(desc));
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
        var d=await get('/renFolder?oldPath='+encodeURIComponent(path)+'&newPath='+encodeURIComponent(np));
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
      var d=await get('/delFolder?path='+encodeURIComponent(path));
      if(d.success){ vmfToast('Folder deleted'); await fetchAll(); renderTree(); vmfSelectFolder('__all__'); }
      else vmfToast('Failed: '+d.error,true);
    });
  };

  window.vmfPower = async function(id, action) {
    var labels={start:'Starting',stop:'Stopping',restart:'Restarting'};
    setStatus(labels[action]||'Working'+'...');
    try {
      var d=await get('/power?vmId='+id+'&action='+action);
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
          ? await get('/unassign?vmId='+ids[i])
          : await get('/assign?vmId='+ids[i]+'&path='+encodeURIComponent(path));
        if(d.success) ok++; else fail++;
      } catch(e){fail++;}
    }
    selectedIds.clear(); updateMvBtn();
    vmfToast(fail>0?ok+' moved, '+fail+' failed':'Moved '+ok+' VM'+(ok!==1?'s':'')+' to '+(path===ROOT?'Unorganized':path),fail>0);
    await fetchAll(); renderTree(); renderVms();
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

  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');}

})();
