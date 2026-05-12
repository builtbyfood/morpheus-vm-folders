# Contributing

## Build Environment

```bash
sdk install gradle 7.6.4
sdk use gradle 7.6.4
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
gradle shadowJar --no-daemon
```

## Key Files

| File | Purpose |
|---|---|
| `VmFoldersController.groovy` | All API endpoints + standalone page HTML/CSS |
| `vmFolders.js` | Complete SPA: rendering, cloud tabs, dark mode, nav injection |
| `vmFoldersNav.hbs` | CSS variables injected on every Morpheus page |
| `vmFoldersServerTab.hbs` | Host detail tab — uses CSS vars for dark mode |
| `vmFoldersClusterTab.hbs` | Cluster detail tab |
| `vmFoldersInstanceTab.hbs` | Instance detail tab |

## Morpheus API Notes (morpheus-plugin-api 1.3.0)

- `AbstractClusterTabProvider.show(ComputeServerGroup, User, Account)` — uses `ComputeServerGroup`, not `ComputeServer`
- `AbstractClusterTabProvider.renderTemplate(ComputeServerGroup)` — single param
- `AbstractServerTabProvider.renderTemplate(ComputeServer)` — single param, no Map
- `AbstractCloudTabProvider` — **does not exist** in 1.3.0; use JS injection
- `HandlebarsRenderer` must be initialized **first** in `Plugin.initialize()` to prevent crash on HPE VME
- Routes use `p("/vmFolders/path", "method")` helper — permission `"admin-cm"` required on VME
- API calls use `127.0.0.1` with `ssl.CERT_NONE` to bypass self-signed cert

## DOM Selectors (Morpheus 8.8 confirmed)

| Element | Selector |
|---|---|
| Cloud value in server detail | `.info-column a[href*="/infrastructure/clouds/"]` |
| Infrastructure sub-nav | `ul.infrastructure.admin-filters` |
| Provisioning sub-nav | `ul.provisioning.subnav` |
| Provisioning hover popover | `div.popover.main-nav-popover` |
| Cluster detail tabs | `#nav-tabs-wrapper` |

## Future: XML Editor Integration

When the XML Editor plugin is available, add to `serverActions()` in `VmFoldersController.groovy`:
```groovy
actions << [code:'xml-editor', name:'Edit VM XML', url:'/plugin/xmlEditor?vmId='+server.id]
```
