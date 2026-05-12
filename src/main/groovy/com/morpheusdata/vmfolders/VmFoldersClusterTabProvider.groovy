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
 * Adds a "VM Folders" tab on cluster detail pages.
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
        return true
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup server) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            model.object = [
                serverId  : server?.id ?: 0,
                serverName: server?.name ?: 'Cluster',
                pluginUrl : '/plugin/vmFolders'
            ]
            return getRenderer().renderTemplate('hbs/vmFoldersClusterTab', model)
        } catch(e) {
            log.error("VmFoldersClusterTabProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('<div style="padding:20px;color:#c00">VM Folders failed to load: ' + e.message + '</div>')
        }
    }
}
