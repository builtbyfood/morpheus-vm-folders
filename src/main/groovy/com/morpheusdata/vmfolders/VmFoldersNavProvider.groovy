package com.morpheusdata.vmfolders

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

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
        // Outputs a non-executable script tag (type=text/plain) containing
        // a loader that runs via DOM injection with the page nonce.
        // The actual tab injection JS lives in the external asset file
        // which is served from /assets/plugin/vm-folders/vmFolders.js
        def html = '<script src="/assets/plugin/vm-folders/vmFolders.js"></script>'
        return HTMLResponse.success(html)
    }

    @Override
    ContentSecurityPolicy getContentSecurityPolicy() {
        def csp = new ContentSecurityPolicy()
        csp.scriptSrc = "'self'"
        return csp
    }
}
