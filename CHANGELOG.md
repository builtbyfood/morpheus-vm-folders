# Changelog

## [1.0.0] - 2026-04-24

### Initial Release

#### Features
- Folder tree with create, rename, delete, collapse/expand
- Move single or multiple VMs between folders
- VM table with OS, Memory, vCPU, IP, Cloud columns
- Power control (Start/Stop) per VM
- Console button per VM
- Search and sort across all columns
- Backup and restore database
- Export database as JSON
- Built-in log viewer with export
- HPE brand color scheme

#### Deployment Options
- Morpheus plugin (all editions)
- Standalone HTML + Python companion API
- Docker container
- Bookmarklet

#### Known Issues
- HPE VM Essentials: metadata API returns Strings — mitigated by JSON file storage
- Asset pipeline caches JS for 300 seconds after plugin update
