#!/usr/bin/env python3
"""
VM Folders Companion API - runs on localhost:8181
Handles JSON database read/write for the standalone vmfolders.html page.

Install:
  sudo cp vmfolders-api.py /opt/morpheus/vmfolders-api.py
  sudo cp vmfolders-api.service /etc/systemd/system/
  sudo systemctl enable --now vmfolders-api
  # Add nginx proxy block (see README)
"""
import json, os, shutil
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime

DB_PATH  = '/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json'
BAK_PATH = '/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json.bak'
PORT     = 8181

def read_db():
    try:
        if os.path.exists(DB_PATH):
            with open(DB_PATH) as f:
                d = json.load(f)
                for k in ('folders','assignments','history'):
                    d.setdefault(k, [] if k != 'assignments' else {})
                return d
    except Exception as e:
        print(f'readDb: {e}')
    return {'folders':[],'assignments':{},'history':[],'version':0}

def write_db(data):
    if os.path.exists(DB_PATH):
        shutil.copy2(DB_PATH, BAK_PATH)
    data['lastModified'] = datetime.now().isoformat()
    data['version'] = data.get('version', 0) + 1
    tmp = DB_PATH + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, DB_PATH)

def hist(db, action, **kw):
    h = db.get('history', [])
    h.append({'ts': datetime.now().isoformat(), 'action': action, **kw})
    db['history'] = h[-100:]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')

    def json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', len(body))
        self.cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200); self.cors(); self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        p = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        ep = parsed.path.rstrip('/')
        try:
            if ep == '/db':
                self.json(read_db())
            elif ep == '/saveFolder':
                path = p.get('path','').strip()
                if not path: return self.json({'success':False,'error':'path required'})
                if not path.startswith('/'): path = '/'+path
                db = read_db()
                if not any(f['path']==path for f in db['folders']):
                    db['folders'].append({'path':path,'desc':p.get('desc',''),'created':datetime.now().isoformat()})
                    hist(db,'create_folder',path=path); write_db(db)
                self.json({'success':True,'path':path})
            elif ep == '/delFolder':
                path = p.get('path','')
                if not path: return self.json({'success':False,'error':'path required'})
                db = read_db()
                db['folders'] = [f for f in db['folders'] if f['path']!=path and not f['path'].startswith(path+'/')]
                db['assignments'] = {k:v for k,v in db['assignments'].items() if v!=path and not v.startswith(path+'/')}
                hist(db,'delete_folder',path=path); write_db(db)
                self.json({'success':True})
            elif ep == '/renFolder':
                old,new = p.get('oldPath',''),p.get('newPath','').strip()
                if not old or not new: return self.json({'success':False,'error':'oldPath and newPath required'})
                if not new.startswith('/'): new='/'+new
                db = read_db()
                for f in db['folders']:
                    if f['path']==old: f['path']=new
                    elif f['path'].startswith(old+'/'): f['path']=new+f['path'][len(old):]
                db['assignments']={k:(new+v[len(old):] if v.startswith(old+'/') else (new if v==old else v)) for k,v in db['assignments'].items()}
                hist(db,'rename_folder',fromPath=old,toPath=new); write_db(db)
                self.json({'success':True,'newPath':new})
            elif ep == '/assign':
                vid,path = p.get('vmId',''),p.get('path','').strip()
                if not vid or not path: return self.json({'success':False,'error':'vmId and path required'})
                if not path.startswith('/'): path='/'+path
                db = read_db()
                db['assignments'][vid]=path
                if not any(f['path']==path for f in db['folders']):
                    db['folders'].append({'path':path,'desc':'','created':datetime.now().isoformat()})
                hist(db,'assign',vmId=vid,path=path); write_db(db)
                self.json({'success':True})
            elif ep == '/unassign':
                vid = p.get('vmId','')
                if not vid: return self.json({'success':False,'error':'vmId required'})
                db = read_db()
                db['assignments'].pop(vid, None)
                hist(db,'unassign',vmId=vid); write_db(db)
                self.json({'success':True})
            elif ep == '/backup':
                if not os.path.exists(DB_PATH): return self.json({'success':False,'error':'No database'})
                shutil.copy2(DB_PATH, BAK_PATH)
                self.json({'success':True,'message':f'Backed up to {BAK_PATH}'})
            elif ep == '/restore':
                if not os.path.exists(BAK_PATH): return self.json({'success':False,'error':'No backup found'})
                shutil.copy2(BAK_PATH, DB_PATH)
                self.json({'success':True,'message':'Restored from backup'})
            elif ep == '/health':
                db = read_db()
                self.json({'status':'ok','version':db.get('version',0),'folders':len(db.get('folders',[])),'assigned':len(db.get('assignments',{}))})
            else:
                self.json({'error':'Not found'},404)
        except Exception as e:
            self.json({'success':False,'error':str(e)},500)

if __name__=='__main__':
    server = HTTPServer(('127.0.0.1', PORT), Handler)
    print(f'VM Folders API on 127.0.0.1:{PORT} | DB: {DB_PATH}')
    server.serve_forever()
