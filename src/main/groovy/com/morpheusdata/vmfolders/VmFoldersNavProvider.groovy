package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * VM Folders 1.5.10
 * Injects vmFolders.js into every Morpheus page via a nonced script tag.
 * The JS uses a MutationObserver to detect infrastructure pages and injects
 * a "Folder View" tab into Bootstrap's #nav-tabs-wrapper tab bar.
 *
 * Asset path: /assets/plugin/vm-folders/vmFolders.js
 * HBS template: src/main/resources/renderer/hbs/vmFoldersNav.hbs
 *
 * Confirmed working on Morpheus 8.1.1 HPE VME:
 * - getRenderer().renderTemplate() substitutes {{nonce}} correctly
 * - Asset is served at /assets/plugin/vm-folders/vmFolders.js
 * - Bootstrap data-toggle="tab" injection works on #nav-tabs-wrapper pages
 */
@Slf4j
class VmFoldersNavProvider extends AbstractGlobalUIComponentProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    VmFoldersNavProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    String getCode() { return 'vm-folders-nav' }

    @Override
    String getName() { return 'VM Folders Nav Injector' }

    @Override
    Plugin getPlugin() { return plugin }

    @Override
    MorpheusContext getMorpheus() { return morpheusContext }

    @Override
    Boolean show(User user, Account account) { return true }

    @Override
    HTMLResponse renderTemplate(User user, Account account) {
        try {
            ViewModel<Map> model = new ViewModel<>()
            // 1.5.9: Morpheus serves plugin assets with Cache-Control max-age=31536000 and a
            // constant ETag, so a browser keeps the previous build's vmFolders.js for a year.
            // The version in the query string makes every build a new URL.
            model.object = [assetVersion: VmFoldersController.VERSION]
            return getRenderer().renderTemplate('hbs/vmFoldersNav', model)
        } catch(e) {
            log.error("VmFoldersNavProvider.renderTemplate error: ${e.message}", e)
            return HTMLResponse.success('')
        }
    }
}
