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
        // MUST be first — prevents DynamicTemplateLoader crash on HPE VME 8.1.1+
        this.renderer = new HandlebarsRenderer()

        this.name        = 'VM Folders'
        this.description = 'Folder organization for VMs in HPE Morpheus VM Essentials, Advanced, Enterprise'
        this.author      = 'Travis DeLuca'
        this.version     = '1.3.6'

        // PluginController — standalone folder page at /plugin/vmFolders
        this.controllers.add(new VmFoldersController(this, morpheus))

        // GlobalUIComponentProvider — injects vmFolders.js on every page
        registerProvider(new VmFoldersNavProvider(this, morpheus))

        // ClusterTabProvider — Infrastructure → Clusters → (cluster) → VM Folders tab
        registerProvider(new VmFoldersClusterTabProvider(this, morpheus))

        // ServerTabProvider — Infrastructure → Compute → Hosts → (host) → VM Folders tab
        registerProvider(new VmFoldersServerTabProvider(this, morpheus))

        // InstanceTabProvider — Provisioning → Instances → (instance) → VM Folders tab
        registerProvider(new VmFoldersInstanceTabProvider(this, morpheus))

        log.info("VM Folders Plugin 1.3.6 initialized")
    }

    Boolean hasCustomRenderer() { return true }

    @Override
    void onDestroy() {}
}
