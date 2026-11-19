package com.morpheusdata.vmfolders

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
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
        this.version     = '1.5.10'

        // Datastore scanning (1.4.0) is OFF until an administrator turns it on: it runs
        // read-only commands on hypervisor hosts through the Morpheus agent.
        this.settings = [
            new OptionType(
                name         : 'Enable datastore scanning',
                code         : 'vm-folders-scan-enabled',
                fieldName    : 'scanEnabled',
                fieldLabel   : 'Enable datastore scanning',
                fieldContext : 'config',
                inputType    : OptionType.InputType.CHECKBOX,
                defaultValue : 'off',
                displayOrder : 0,
                required     : false,
                helpText     : 'Allows the VM Folders dashboard to list what is on a datastore by running read-only find/head commands on a hypervisor host via the Morpheus agent. Off by default.'
            ),
            // vmfolder tag reconciliation (1.5.0) is OFF until an administrator turns it
            // on. `consume` is the only mode that writes to a Morpheus record: it deletes
            // the vmfolder tag from the VM once the folder has been assigned.
            // A SELECT here would need an option source, so this is a text field and the
            // value is validated in the controller (anything unrecognised means off).
            // This form is the only place any VM Folders setting is changed (1.5.5); the
            // dashboard's ⚙ Settings dialog only displays the values.
            new OptionType(
                name         : 'vmfolder tag mode',
                code         : 'vm-folders-tag-mode',
                fieldName    : 'tagMode',
                fieldLabel   : 'vmfolder tag mode',
                fieldContext : 'config',
                inputType    : OptionType.InputType.TEXT,
                defaultValue : 'off',
                displayOrder : 1,
                required     : false,
                helpText     : 'off, keep or consume. off (default) ignores vmfolder tags entirely. keep assigns the VM to the tagged folder and leaves the tag in place, so the tag stays the source of truth — read-only, nothing in Morpheus is modified; use it if Terraform manages your tags. consume assigns the VM and then REMOVES the vmfolder tag from the instance (and its copy on the server); no other tag is touched. The tag name vmfolders is also accepted.'
            ),
            new OptionType(
                name         : 'Label for all VMs',
                code         : 'vm-folders-label-all',
                fieldName    : 'labelAll',
                fieldLabel   : 'Label for all VMs',
                fieldContext : 'config',
                inputType    : OptionType.InputType.TEXT,
                defaultValue : 'All VMs',
                displayOrder : 2,
                required     : false,
                helpText     : 'Display name for the root entry in the folder tree. Leave blank for "All VMs".'
            ),
            new OptionType(
                name         : 'Label for unorganized VMs',
                code         : 'vm-folders-label-unorganized',
                fieldName    : 'labelUnorganized',
                fieldLabel   : 'Label for unorganized VMs',
                fieldContext : 'config',
                inputType    : OptionType.InputType.TEXT,
                defaultValue : 'Unorganized',
                displayOrder : 3,
                required     : false,
                helpText     : 'Display name for VMs that are in no folder. Leave blank for "Unorganized".'
            ),
            // Lazy tabs (1.5.8). Read by the tab providers through VmFoldersSettings, which
            // caches this form for 60 seconds — a change here takes up to a minute to show.
            new OptionType(
                name         : 'Show the VM Folders tab',
                code         : 'vm-folders-tab-enabled',
                fieldName    : 'tabEnabled',
                fieldLabel   : 'Show the VM Folders tab',
                fieldContext : 'config',
                inputType    : OptionType.InputType.CHECKBOX,
                defaultValue : 'on',
                displayOrder : 4,
                required     : false,
                helpText     : 'Untick to hide the VM Folders tab on host, cluster and instance pages (takes up to 60 seconds). The standalone VM Folders dashboard, the folder data and vmfolder tag handling are not affected. The plugin runs no background jobs, so there is nothing else to stop.'
            ),
            // TEXT, not a select: OptionType has no option-source-free SELECT (see tagMode).
            new OptionType(
                name         : 'Tab load mode',
                code         : 'vm-folders-tab-load-mode',
                fieldName    : 'tabLoadMode',
                fieldLabel   : 'Tab load mode',
                fieldContext : 'config',
                inputType    : OptionType.InputType.TEXT,
                defaultValue : 'onOpen',
                displayOrder : 5,
                required     : false,
                helpText     : 'onOpen or manual. onOpen (default) loads the tab the first time it is opened. manual loads nothing until the Load button in the tab is clicked. Either way nothing is loaded while the tab is closed. Anything else is treated as onOpen.'
            )
        ]

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

        log.info("VM Folders Plugin 1.5.10 initialized")
    }

    Boolean hasCustomRenderer() { return true }

    @Override
    void onDestroy() {}
}
