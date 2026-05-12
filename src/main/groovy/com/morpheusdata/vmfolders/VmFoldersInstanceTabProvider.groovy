package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Adds a "VM Folders" tab on instance detail pages.
 * Provisioning → Instances → (instance name) → VM Folders tab
 * Shows which folder this instance is in, with quick-move and link to full view.
 */
@Slf4j
class VmFoldersInstanceTabProvider extends AbstractInstanceTabProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    VmFoldersInstanceTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    String getCode() { return 'vm-folders-instance-tab' }

    @Override
    String getName() { return 'VM Folders' }

    @Override
    Plugin getPlugin() { return plugin }

    @Override
    MorpheusContext getMorpheus() { return morpheusContext }

    @Override
    Boolean show(Instance instance, User user, Account account) { return true }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            // Get the primary server ID from the instance containers
            def serverId = ''
            try {
                def containers = instance.containers
                if (containers) {
                    def first = containers.find()
                    serverId = first?.server?.id?.toString() ?: first?.serverId?.toString() ?: ''
                }
            } catch(ex) {
                log.warn("VmFoldersInstanceTabProvider: could not get serverId from instance ${instance?.id}: ${ex.message}")
            }
            model.object = [
                instanceId  : instance?.id ?: 0,
                instanceName: instance?.name ?: 'Instance',
                serverId    : serverId,
                pluginUrl   : '/plugin/vmFolders'
            ]
            return getRenderer().renderTemplate('hbs/vmFoldersInstanceTab', model)
        } catch(e) {
            log.error("VmFoldersInstanceTabProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('<div style="padding:20px;color:#c00">VM Folders failed to load: ' + e.message + '</div>')
        }
    }
}
