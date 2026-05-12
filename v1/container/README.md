# VM Folders Container

Standalone Docker container for the VM Folders app.
Shares data with the Morpheus plugin via NFS or bind mount.

## Quick Start

```bash
docker compose up -d
```

Access at: http://your-host:8090

## Sharing Data with Morpheus Plugin

The plugin stores its database at:
`/var/opt/morpheus/morpheus-ui/plugins/vm-folders.json`

### Option A: NFS Mount (recommended for homelab)

Mount the Morpheus appliance's plugin directory via NFS on your Docker host,
then update docker-compose.yml to use the bind mount.

On the Morpheus appliance, export the plugins directory:
```bash
echo "/var/opt/morpheus/morpheus-ui/plugins 192.168.0.0/24(rw,sync,no_subtree_check)" | sudo tee -a /etc/exports
sudo exportfs -ra
```

On the Docker host:
```bash
sudo mkdir -p /mnt/morpheus-plugins
sudo mount -t nfs 192.168.200.17:/var/opt/morpheus/morpheus-ui/plugins /mnt/morpheus-plugins
```

Then in docker-compose.yml, replace the volume with:
```yaml
volumes:
  - /mnt/morpheus-plugins:/data
```

### Option B: NAS (your existing setup)

Since you already have NAS at 192.168.0.30, copy the JSON file there and
point both the plugin and container at that path.

Update the plugin's DB_PATH in VmFoldersController.groovy:
```groovy
static final String DB_FILE = '/mnt/nas/vm-folders.json'
```

And in docker-compose.yml:
```yaml
volumes:
  - /mnt/nas:/data
environment:
  - DB_PATH=/data/vm-folders.json
```

### Option C: Manual Sync

Use the Export button in either interface to download the JSON,
then import it into the other using the Restore feature.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| DB_PATH | /data/vm-folders.json | Path to the database file |
| BAK_PATH | /data/vm-folders.json.bak | Path to backup file |
| API_PORT | 8181 | Internal API port |

## Home Assistant Integration (planned)

The container exposes a simple REST API at `/vmfolders-api/` that
could be consumed by a HA custom integration to:
- Show VM folder structure in HA dashboard
- Trigger VM power actions
- Display VM status as HA entities
