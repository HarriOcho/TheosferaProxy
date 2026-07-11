package com.theosfera.template;

import org.bukkit.plugin.java.JavaPlugin;

public final class TheosferaPluginTemplate extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TheosferaPluginTemplate enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TheosferaPluginTemplate disabled.");
    }
}