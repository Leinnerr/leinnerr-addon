package com.leinnerr.addon;

import com.leinnerr.addon.commands.BotsCommand;
import com.leinnerr.addon.commands.SchematicCommand;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.modules.AntiSpamKills;
import com.leinnerr.addon.modules.AutoCrawlTrap;
import com.leinnerr.addon.modules.BotJoiner;
import com.leinnerr.addon.modules.InfiniteReach;
import com.leinnerr.addon.modules.JugadorTP;
import com.leinnerr.addon.modules.LAnchor;
import com.leinnerr.addon.modules.Phase;
import com.leinnerr.addon.modules.PlaceWater;
import com.leinnerr.addon.modules.TopTrap;
import com.leinnerr.addon.modules.BlockerPlus;
import com.leinnerr.addon.modules.SchematicModule;
import com.leinnerr.addon.modules.BedrockESP;
import com.leinnerr.addon.modules.SmartCrystal;
import com.leinnerr.addon.modules.IgnorePlayer;
import com.leinnerr.addon.modules.Surround;
import com.leinnerr.addon.modules.LArrow;
import com.leinnerr.addon.online.Online;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeinnerrAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("Leinnerr Addon");
    public static final Category CATEGORY = new Category("Leinnerr");

    @Override
    public void onInitialize() {
        LOG.info("Leinnerr Addon ({}) cargando...", Compat.TARGET);

        Modules modules = Modules.get();
        modules.add(new IgnorePlayer());
        modules.add(new JugadorTP());
        modules.add(new InfiniteReach());
        modules.add(new AntiSpamKills());
        modules.add(new Surround());
        modules.add(new AutoCrawlTrap());
        modules.add(new Phase());
        modules.add(new LAnchor());
        modules.add(new SmartCrystal());
        modules.add(new LArrow());
        modules.add(new BotJoiner());
        modules.add(new SchematicModule());
        modules.add(new PlaceWater());
        modules.add(new TopTrap());
        modules.add(new BedrockESP());
        modules.add(new BlockerPlus());
        Commands.add(new BotsCommand());
        Commands.add(new SchematicCommand());

        Compat.registerVersionModules(modules);

        Online.init();
        Runtime.getRuntime().addShutdownHook(new Thread(Online::shutdown));
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.leinnerr.addon";
    }
}