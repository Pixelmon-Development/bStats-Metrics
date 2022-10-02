package org.bstats.forge;

import com.google.common.collect.Lists;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.logging.log4j.Logger;
import org.bstats.MetricsBase;
import org.bstats.charts.CustomChart;
import org.bstats.json.JsonObjectBuilder;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class Metrics {

    private final ModContainer plugin;
    private final Logger logger;
    private final Path configDir;
    private final int serviceId;

    private MetricsBase metricsBase;

    private String serverUUID;
    private boolean logErrors = false;
    private boolean logSentData;
    private boolean logResponseStatusText;

    private boolean enabled = false;

    public Metrics(ModContainer plugin, Logger logger, Path configDir, int serviceId) {
        this.plugin = plugin;
        this.logger = logger;
        this.configDir = configDir;
        this.serviceId = serviceId;

        MinecraftForge.EVENT_BUS.register(this);

        try {
            loadConfig();
        } catch (IOException e) {
            // Failed to load configuration
            logger.warn("Failed to load bStats config!", e);
            return;
        }

        Scheduler scheduler = new Scheduler();
        MinecraftForge.EVENT_BUS.register(scheduler);

        metricsBase = new MetricsBase(
                "sponge",
                serverUUID,
                serviceId,
                enabled,
                this::appendPlatformData,
                this::appendServiceData,
                task -> scheduler.runnable.add(task),
                () -> true,
                logger::warn,
                logger::info,
                logErrors,
                logSentData,
                logResponseStatusText
        );

        StringBuilder builder = new StringBuilder().append(System.lineSeparator());
        builder.append("Plugin ").append(plugin.getModId()).append(" is using bStats Metrics ");
        if (enabled) {
            builder.append(" and is allowed to send data.");
        } else {
            builder.append(" but currently has data sending disabled.").append(System.lineSeparator());
            builder.append("To change the enabled/disabled state of any bStats use in a plugin, visit the Sponge config!");
        }
        logger.info(builder.toString());
    }

    /**
     * Loads the bStats configuration.
     */
    private void loadConfig() throws IOException {
        File configPath = configDir.resolve("bStats").toFile();
        configPath.mkdirs();
        File configFile = new File(configPath, "config.conf");
        HoconConfigurationLoader configurationLoader = HoconConfigurationLoader.builder().file(configFile).build();
        CommentedConfigurationNode node;

        String serverUuidComment =
            "bStats (https://bStats.org) collects some basic information for plugin authors, like how\n" +
            "many people use their plugin and their total player count. It's recommended to keep bStats\n" +
            "enabled, but if you're not comfortable with this, you can disable data collection in the\n" +
            "Sponge configuration file. There is no performance penalty associated with having metrics\n" +
            "enabled, and data sent to bStats is fully anonymous.";

        if (!configFile.exists()) {
            configFile.createNewFile();
            node = configurationLoader.load();

            node.node("serverUuid").set(UUID.randomUUID().toString());
            node.node("logFailedRequests").set(false);
            node.node("logSentData").set(false);
            node.node("logResponseStatusText").set(false);
            node.node("serverUuid").comment(serverUuidComment);
            node.node("configVersion").set(2);

            configurationLoader.save(node);
        } else {
            node = configurationLoader.load();

            if (!node.node("configVersion").virtual()) {

                node.node("configVersion").set(2);

                node.node("enabled").comment(
                        "Enabling bStats in this file is deprecated. At least one of your plugins now uses the\n" +
                        "Sponge config to control bStats. Leave this value as you want it to be for outdated plugins,\n" +
                        "but look there for further control");

                node.node("serverUuid").comment(serverUuidComment);
                configurationLoader.save(node);
            }
        }

        // Load configuration
        serverUUID = node.node("serverUuid").getString();
        logErrors = node.node("logFailedRequests").getBoolean(false);
        logSentData = node.node("logSentData").getBoolean(false);
        logResponseStatusText = node.node("logResponseStatusText").getBoolean(false);
        this.enabled = node.node("enabled").getBoolean(false);
    }

    /**
     * Adds a custom chart.
     *
     * @param chart The chart to add.
     */
    public void addCustomChart(CustomChart chart) {
        metricsBase.addCustomChart(chart);
    }

    private void appendPlatformData(JsonObjectBuilder builder) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        builder.appendField("playerAmount", server.getPlayerList().getPlayers().size());
        builder.appendField("onlineMode", server.usesAuthentication() ? 1 : 0);
        builder.appendField("minecraftVersion", server.getServerVersion());

        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private void appendServiceData(JsonObjectBuilder builder) {
        builder.appendField("pluginVersion", plugin.getModInfo().getVersion().toString());
    }

    public static class Scheduler {

        private List<Runnable> runnable = Lists.newCopyOnWriteArrayList();

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            for (Runnable runnable1 : runnable) {
                runnable1.run();
            }

            this.runnable.clear();
        }
    }
}