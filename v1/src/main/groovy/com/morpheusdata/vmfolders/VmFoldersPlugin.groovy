package com.morpheusdata.vmfolders

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

@Slf4j
class VmFoldersPlugin extends Plugin {

    // Init block runs before registerPlugin — sets renderer to prevent
    // DynamicTemplateLoader immutable list crash in HPE VME Morpheus 8.1.x
    {
        this.renderer = new HandlebarsRenderer()
    }

    @Override
    String getCode() { return 'vm-folders-plugin' }

    @Override
    void initialize() {
        this.name        = 'VM Folders'
        this.description = 'VM Folder organization for HPE VM Essentials'
        this.author      = 'Travis DeLuca'
        this.version     = '1.0.0'

        VmFoldersNavProvider navProvider = new VmFoldersNavProvider(this, morpheus)
        registerProvider(navProvider)

        VmFoldersController controller = new VmFoldersController(this, morpheus)
        setControllers([controller])

        log.info("VM Folders Plugin initialized")
    }

    Boolean hasCustomRenderer() { return true }

    @Override
    void onDestroy() {}
}
