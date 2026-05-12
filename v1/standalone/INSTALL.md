# VM Folders Standalone - Install Guide

## Requirements
- SSH access to the Morpheus appliance
- A Morpheus API token (User Settings → API Access → Regenerate)

## Install Steps

### 1. Copy files to appliance
```bash
scp vmfolders-api.py admin@morpheus.thedelucahome.com:/tmp/
scp vmfolders-api.service admin@morpheus.thedelucahome.com:/tmp/
scp vmfolders/index.html admin@morpheus.thedelucahome.com:/tmp/
```

### 2. On the appliance
```bash
# Install API service
sudo cp /tmp/vmfolders-api.py /opt/morpheus/vmfolders-api.py
sudo chmod +x /opt/morpheus/vmfolders-api.py
sudo cp /tmp/vmfolders-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now vmfolders-api
sudo systemctl status vmfolders-api

# Install HTML file
sudo mkdir -p /opt/morpheus/embedded/nginx/html/vmfolders
sudo cp /tmp/index.html /opt/morpheus/embedded/nginx/html/vmfolders/
```

### 3. Add nginx proxy block
```bash
sudo nano /opt/morpheus/embedded/nginx/conf/nginx.conf
```
Add the contents of `nginx-vmfolders.conf` inside the `server {}` block, then:
```bash
sudo /opt/morpheus/embedded/nginx/sbin/nginx -t
sudo /opt/morpheus/embedded/nginx/sbin/nginx -s reload
```

### 4. Access
Open: https://your-morpheus-url/vmfolders/

### 5. First-time setup
- Enter your Morpheus URL
- Enter your API token
- Click Save — you're done

## Shared JSON Database
The standalone version shares the same `vm-folders.json` database as the plugin.
If both are installed, they read/write the same file.

## API Token
Get your token: Profile → User Settings → API Access → Regenerate
The token is stored in your browser's localStorage. Each user has their own token.

## Data location
Same as plugin: `/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json`
