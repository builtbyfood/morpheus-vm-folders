package com.morpheusdata.vmfolders

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * VM Folders 1.5.10 — the two tab settings, read for the tab providers.
 *
 * show() and renderTemplate() run on the HTTP request thread for every detail page, so
 * they must not wait on anything. The only call they make is cached(): the plugin
 * settings form, re-read from the platform at most once every 60 seconds and served
 * from memory in between. A failed read keeps the previous values (or the defaults) and
 * is retried after the same interval, so a slow or broken settings read cannot be
 * repeated on every page.
 *
 * The parse helpers take a plain map so the controller can apply the same rules to its
 * own (5 second) copy when it reports the values in /config.
 */
@Slf4j
class VmFoldersSettings {

    static final long TTL_MS = 60000L
    static final String TAB_ENABLED_KEY = 'tabEnabled'
    static final String TAB_LOAD_MODE_KEY = 'tabLoadMode'
    static final List<String> LOAD_MODES = ['onOpen', 'manual']

    private static volatile Map cache = [:]
    private static volatile long cacheAt = 0L

    /** The settings form as a map, at most 60 s old. Never throws. */
    static Map cached(MorpheusContext morpheusContext, Plugin plugin) {
        long now = System.currentTimeMillis()
        if (cacheAt != 0L && (now - cacheAt) < TTL_MS) return cache
        Map out = cache
        try {
            def raw = morpheusContext.getSettings(plugin)?.blockingGet()
            def parsed = raw ? new JsonSlurper().parseText(raw as String) : [:]
            out = (parsed instanceof Map) ? parsed : [:]
        } catch (ex) {
            log.warn("VmFolders: tab settings unreadable (${ex.message}); keeping the previous values for 60 s")
        }
        cache = out
        cacheAt = now
        return out
    }

    /**
     * The tab is shown unless the checkbox is explicitly off. A value that was never
     * saved (an upgrade from 1.5.7, where the setting did not exist) means shown.
     */
    static boolean tabEnabled(Map settings) {
        def v = settings?.get(TAB_ENABLED_KEY)
        if (v == false) return false
        def s = v?.toString()?.trim()?.toLowerCase()
        return !(s == 'off' || s == 'false')
    }

    /** onOpen | manual. Anything unrecognised is onOpen. */
    static String loadMode(Map settings) {
        def s = settings?.get(TAB_LOAD_MODE_KEY)?.toString()?.trim()?.toLowerCase()
        return s == 'manual' ? 'manual' : 'onOpen'
    }
}
