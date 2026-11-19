package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * VM Folders 1.5.10
 * Adds a "VM Folders" tab on cluster detail pages.
 * Lazy tab (1.5.8): show() and renderTemplate() make no service call — see
 * VmFoldersServerTabProvider. The template is a static shell.
 *
 * Infrastructure → Clouds → (cluster name) → VM Folders tab
 *
 * NOTE: AbstractClusterTabProvider uses ComputeServerGroup, not ComputeServer.
 * Confirmed correct signatures from morpheus-plugin-api:1.3.0 compile errors.
 */
@Slf4j
class VmFoldersClusterTabProvider extends AbstractClusterTabProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    VmFoldersClusterTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    String getCode() { return 'vm-folders-cluster-tab' }

    @Override
    String getName() { return 'VM Folders' }

    @Override
    Plugin getPlugin() { return plugin }

    @Override
    MorpheusContext getMorpheus() { return morpheusContext }

    @Override
    Boolean show(ComputeServerGroup server, User user, Account account) {
        try {
            return VmFoldersSettings.tabEnabled(VmFoldersSettings.cached(morpheusContext, plugin))
        } catch(e) {
            return true
        }
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup server) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            def cloudName = ''
            try {
                // ComputeServerGroup cloud properties vary — try all paths
                ['zone','cloud','computeZone','masterZone'].each { prop ->
                    if (!cloudName) {
                        try {
                            def val = server?.hasProperty(prop) ? server[prop] : null
                            if (val) cloudName = (val instanceof String ? val : val?.name?.toString()) ?: ''
                        } catch(ignore) {}
                    }
                }
            } catch(ex) {}
            model.object = [
                serverId  : server?.id ?: 0,
                serverName: server?.name ?: 'Cluster',
                cloudName : cloudName,
                loadMode  : VmFoldersSettings.loadMode(VmFoldersSettings.cached(morpheusContext, plugin)),
                assetVersion: VmFoldersController.VERSION,   // cache-buster for vmFolders.js (1.5.9)
                pluginUrl : '/plugin/vmFolders'
            ]
            return getRenderer().renderTemplate('hbs/vmFoldersClusterTab', model)
        } catch(e) {
            log.error("VmFoldersClusterTabProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('<div style="padding:20px;color:#c00">VM Folders failed to load: ' + e.message + '</div>')
        }
    }
}
