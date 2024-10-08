package com.tencent.supersonic.chat.server.plugin.event;

import com.tencent.supersonic.chat.server.plugin.ChatPlugin;
import org.springframework.context.ApplicationEvent;

public class PluginDelEvent extends ApplicationEvent {

    private ChatPlugin plugin;

    public PluginDelEvent(Object source, ChatPlugin plugin) {
        super(source);
        this.plugin = plugin;
    }

    public ChatPlugin getPlugin() {
        return plugin;
    }
}
