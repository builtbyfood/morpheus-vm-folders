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
 * VM Folders 1.5.10
 * Adds a "VM Folders" tab on instance detail pages.
 * Provisioning → Instances → (instance name) → VM Folders tab
 * Shows which folder this instance is in, with quick-move and link to full view.
 *
 * Lazy tab (1.5.8): show() and renderTemplate() make no service call — see
 * VmFoldersServerTabProvider. The template is a static shell.
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
    Boolean show(Instance instance, User user, Account account) {
        try {
            return VmFoldersSettings.tabEnabled(VmFoldersSettings.cached(morpheusContext, plugin))
        } catch(e) {
            return true
        }
    }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            // 1.5.8: no platform call here. Up to 1.5.7 this ran
            // morpheusContext.buildInstanceConfig(...).blockingGet() on every instance
            // detail page — on the request thread, tab opened or not — and on 9.0.2 it
            // yielded no server id anyway. The id now comes only from the workloads already
            // on the Instance it is handed; when there are none the tab asks
            // /plugin/vmFolders/instanceServer after it has been opened.
            def serverId = ''
            try {
                def first = instance?.containers?.find { it?.server?.id != null }
                serverId = first?.server?.id?.toString() ?: ''
            } catch(ex) {
                log.debug("VmFoldersInstanceTabProvider: no server id on the instance model: ${ex.message}")
            }
            model.object = [
                instanceId  : instance?.id ?: 0,
                instanceName: instance?.name ?: 'Instance',
                serverId    : serverId,
                loadMode    : VmFoldersSettings.loadMode(VmFoldersSettings.cached(morpheusContext, plugin)),
                assetVersion: VmFoldersController.VERSION,   // cache-buster for vmFolders.js (1.5.9)
                pluginUrl   : '/plugin/vmFolders'
            ]
            return getRenderer().renderTemplate('hbs/vmFoldersInstanceTab', model)
        } catch(e) {
            log.error("VmFoldersInstanceTabProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('<div style="padding:20px;color:#c00">VM Folders failed to load: ' + e.message + '</div>')
        }
    }
}
