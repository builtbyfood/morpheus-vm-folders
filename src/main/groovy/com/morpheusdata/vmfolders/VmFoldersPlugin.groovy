package com.morpheusdata.vmfolders

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

@Slf4j
class VmFoldersPlugin extends Plugin {

    @Override
    String getCode() { return 'vm-folders-plugin' }

    @Override
    void initialize() {
        this.renderer = new HandlebarsRenderer()

        this.name        = 'VM Folders'
        this.description = 'vCenter-style folder organization for VMs in HPE Morpheus'
        this.author      = 'Travis DeLuca'
        this.version     = '1.1.0'

        this.controllers.add(new VmFoldersController(this, morpheus))

        registerProvider(new VmFoldersNavProvider(this, morpheus))
        registerProvider(new VmFoldersClusterTabProvider(this, morpheus))
        registerProvider(new VmFoldersServerTabProvider(this, morpheus))
        registerProvider(new VmFoldersInstanceTabProvider(this, morpheus))

        log.info("VM Folders Plugin v1.1.0 initialized")
    }

    Boolean hasCustomRenderer() { return true }

    @Override
    void onDestroy() {}
}
