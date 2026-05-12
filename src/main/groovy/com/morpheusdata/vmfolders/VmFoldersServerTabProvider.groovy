package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Adds a "VM Folders" tab on individual host/server detail pages.
 * Infrastructure → Compute → Hosts → (host name) → VM Folders tab
 *
 * NOTE: renderTemplate takes single ComputeServer param in morpheus-plugin-api:1.3.0
 */
@Slf4j
class VmFoldersServerTabProvider extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    VmFoldersServerTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    String getCode() { return 'vm-folders-server-tab' }

    @Override
    String getName() { return 'VM Folders' }

    @Override
    Plugin getPlugin() { return plugin }

    @Override
    MorpheusContext getMorpheus() { return morpheusContext }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        try {
            return server?.vmHypervisor == true || server?.serverType?.vmHypervisor == true
        } catch(e) {
            return true
        }
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            model.object = [
                serverId  : server?.id ?: 0,
                serverName: server?.name ?: 'Host',
                pluginUrl : '/plugin/vmFolders'
            ]
            return getRenderer().renderTemplate('hbs/vmFoldersServerTab', model)
        } catch(e) {
            log.error("VmFoldersServerTabProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('<div style="padding:20px;color:#c00">VM Folders failed to load: ' + e.message + '</div>')
        }
    }
}
