package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.model.TaskConfig
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
            // Primary server id: buildInstanceConfig first, then the first workload's server
            // (on 9.0.2 buildInstanceConfig yields no server id; the containers path does).
            def serverId = ''
            try {
                TaskConfig config = morpheusContext.buildInstanceConfig(instance, [:], null, [], [:]).blockingGet()
                serverId = config?.server?.id?.toString() ?: config?.serverId?.toString() ?: ''
            } catch(ex) {
                log.warn("VmFoldersInstanceTabProvider: buildInstanceConfig failed: ${ex.message}")
            }
            if (!serverId) {
                // Fallback: first workload's server
                try {
                    def containers = instance?.containers
                    if (containers) {
                        def first = containers.find()
                        serverId = first?.server?.id?.toString() ?: ''
                    }
                } catch(ex2) {
                    log.warn("VmFoldersInstanceTabProvider: could not get serverId from containers: ${ex2.message}")
                }
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
