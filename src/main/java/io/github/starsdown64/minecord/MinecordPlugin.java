package io.github.starsdown64.minecord;

import com.google.gson.*;
import de.myzelyam.api.vanish.VanishAPI;
import io.github.starsdown64.minecord.api.ExternalMessageEvent;
import io.github.starsdown64.minecord.command.CommandMinecordOff;
import io.github.starsdown64.minecord.command.CommandMinecordOn;
import io.github.starsdown64.minecord.listeners.SuperVanishListener;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.minecraft.util.Tuple;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_20_R1.advancement.CraftAdvancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

import javax.security.auth.login.LoginException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedList;
import java.util.Locale;

public class MinecordPlugin extends JavaPlugin implements Listener
{
    private FileConfiguration config = getConfig();
    private final Object syncSleep = new Object();
    private final Object syncListM2D = new Object();
    private final Object syncListD2M = new Object();
    private final LinkedList<String> listM2D = new LinkedList<>();
    private final LinkedList<Tuple<String, Message>> listD2M = new LinkedList<>();
    private final boolean noDeathMessages = config.getBoolean("noDeathMessages");
    private final boolean noJoinQuitMessages = config.getBoolean("noJoinQuitMessages");
    private final boolean noAdvancementMessages = config.getBoolean("noAdvancementMessages");
    private final boolean allowExternalMessages = config.getBoolean("allowExternalMessages");
    private final boolean allowBroadcasts = config.getBoolean("allowBroadcasts");

    final boolean enableMultiChannelSync = config.getBoolean("enableMultiChannelSync");
    private final long historyAmount = config.getLong("historyAmount");
    private DiscordSlave slave;
    private boolean running = true;
    private boolean update = false;
    private volatile boolean integrate = true;
    private volatile boolean connected = false;
    private volatile long lastConnected = 0;
    private boolean hasVanish;

    @Override
    public final void onEnable()
    {
        saveDefaultConfig();
        getCommand("minecord_on").setExecutor(new CommandMinecordOn(this));
        getCommand("minecord_off").setExecutor(new CommandMinecordOff(this));
        hasVanish = getServer().getPluginManager().isPluginEnabled("SuperVanish") || getServer().getPluginManager().isPluginEnabled("PremiumVanish");
        slave = new DiscordSlave(this);
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                boolean loggedIn = false;
                try
                {
                    slave.start();
                    loggedIn = true;
                    connected = true;
                }
                catch (LoginException | NumberFormatException exception)
                {
                    exception.printStackTrace();
                    return;
                }

                String message;
                Tuple<String, Message> message2;
                OUTER: while (true)
                {
                    synchronized (syncSleep)
                    {
                        while (!update)
                        {
                            if (!running)
                                break OUTER;
                            try
                            {
                                syncSleep.wait();
                            }
                            catch (InterruptedException exception)
                            {
                                exception.printStackTrace();
                            }
                        }
                        update = false;
                    }
                    while (true)
                    {
                        synchronized (syncListM2D)
                        {
                            if (!connected || listM2D.isEmpty())
                                break;
                            message = listM2D.removeFirst();
                        }
                        if (loggedIn && integrate)
                            slave.send(message);
                    }
                    while (true)
                    {
                        synchronized (syncListD2M)
                        {
                            if (listD2M.isEmpty())
                                break;
                            message2 = listD2M.removeFirst();
                        }
                        if (integrate) {
                            if (slave.channelIDs.size() > 1 && enableMultiChannelSync) {
                                String originalChannelID = message2.b().getChannel().getId();
                                String messageToSend = message2.a();
                                for (TextChannel channel : slave.channels) {
                                    if (!channel.getId().equals(originalChannelID)) {
                                        MessageCreateBuilder builder = new MessageCreateBuilder();
                                        LinkedList<FileUpload> files = new LinkedList<>();
                                        for (Message.Attachment attachment : message2.b().getAttachments()) {
                                            try {
                                                InputStream in = new URL(attachment.getUrl()).openStream();
                                                files.add(FileUpload.fromData(in, attachment.getFileName()));
                                            } catch (Exception exception) {
                                                exception.printStackTrace();
                                            }
                                        }
                                        builder.setContent(messageToSend);
                                        builder.setFiles(files);
                                        channel.sendMessage(builder.build()).queue();
                                    }
                                }
                                getServer().broadcastMessage(messageToSend);
                            } else {
                                getServer().broadcastMessage(message2.a());
                            }
                        }
                    }
                }
                synchronized (syncListM2D)
                {
                    listM2D.clear();
                }
                synchronized (syncListD2M)
                {
                    listD2M.clear();
                }

                slave.stop();
            }
        }).start();
        getServer().getPluginManager().registerEvents(this, this);
        if (hasVanish)
            getServer().getPluginManager().registerEvents(new SuperVanishListener(this), this);
    }

    @Override
    public final void onDisable()
    {
        slave.send("Minecord has shut down.");
        synchronized (syncSleep)
        {
            running = false;
            syncSleep.notify();
        }
    }

    public final void setIntegration(boolean integrate)
    {
        this.integrate = integrate;
    }

    public final boolean getIntegration()
    {
        return integrate;
    }

    public final void setConnected(boolean connected)
    {
        this.connected = connected;
    }

    public final boolean getConnected()
    {
        return connected;
    }

    public final void setLastConnected(long lastConnected)
    {
        this.lastConnected = lastConnected;
    }

    public final long getLastConnected()
    {
        if (connected)
            lastConnected = System.currentTimeMillis();
        return lastConnected;
    }

    public final DiscordSlave getSlave()
    {
        return slave;
    }

    public final FileConfiguration getConfigFile()
    {
        return config;
    }

    public final void printToMinecraft(String message, Message messageObj)
    {
        synchronized (syncSleep)
        {
            synchronized (syncListD2M)
            {
                listD2M.addLast(new Tuple<>(message, messageObj));
            }
            update = true;
            syncSleep.notify();
        }
    }

    public final void printToDiscord(String message)
    {
        String strippedMessage = ChatColor.stripColor(message);
        if (strippedMessage == null || strippedMessage.isEmpty())
            return;
        if (!connected && listM2D.size() >= historyAmount)
            return;
        synchronized (syncSleep)
        {
            synchronized (syncListM2D)
            {
                listM2D.addLast(strippedMessage);
            }
            update = true;
            syncSleep.notify();
        }
    }

    /**
     * Send a message to discord bypassing restrictions.
     * This method is meant to provide debug or system info only.
     * Do not use this for normal messages.
     *
     * @param message The debug or system message to send
     */
    public final void printToDiscordBypass(String message)
    {
        if (message == null || message.isEmpty())
            return;
        synchronized (syncSleep)
        {
            synchronized (syncListM2D)
            {
                listM2D.addLast(message);
            }
            update = true;
            syncSleep.notify();
        }
    }
    public final String getFormattedTabMenu()
    {
        StringBuilder output = new StringBuilder("**Players Online:**\n```\n");
        for (Player p : getServer().getOnlinePlayers())
        {
            if (isVanished(p))
                continue;
            output.append(ChatColor.stripColor(teamedName(p))).append("\n");
        }
        output.append("```");
        return output.toString().equals("**Players Online:**\n```\n```") ? "**No players online**" : output.toString();
    }

    private final boolean isVanished(Player p)
    {
        if (hasVanish)
            return VanishAPI.isInvisible(p);
        else
        {
            for (MetadataValue meta : p.getMetadata("vanished"))
                if (meta.asBoolean())
                    return true;
            return false;
        }
    }

    private final String teamedName(Player p)
    {
        Team t = p.getScoreboard().getEntryTeam(p.getName());
        String prefix = (t == null) ? "" : t.getPrefix();
        String suffix = (t == null) ? "" : t.getSuffix();
        return prefix + p.getName() + suffix;
    }

    private final String parseJSONMessage(String message)
    {
        if (message == null)
            return null;

        try
        {
            JsonElement document = JsonParser.parseString(message);
            return document == null ? null : extractJSONMessage(document);
        }
        catch (JsonSyntaxException e)
        {
            return null;
        }
    }

    private final String extractJSONMessage(JsonElement element)
    {
        if (element.isJsonNull())
            return "";

        try
        {
            if (element.isJsonPrimitive())
                return extractJSONMessage(element.getAsJsonPrimitive());
            if (element.isJsonObject())
                return extractJSONMessage(element.getAsJsonObject());
            if (element.isJsonArray())
                return extractJSONMessage(element.getAsJsonArray());
        }
        catch (Error e)
        {
            return null;
        }

        return "";
    }

    private final String extractJSONMessage(JsonPrimitive primitive)
    {
        if (primitive.isString())
            return primitive.getAsString();
        if (primitive.isNumber())
            return primitive.getAsNumber().toString();
        if (primitive.isBoolean())
            return Boolean.toString(primitive.getAsBoolean());

        throw new Error();
    }

    private final String extractJSONMessage(JsonObject object)
    {
        String output = "";

        if (object.has("text"))
        {
            JsonElement nested = object.get("text");
            if (!nested.isJsonPrimitive())
                throw new Error();
            output += extractJSONMessage(nested.getAsJsonPrimitive());
        }

        if (object.has("extra"))
        {
            JsonElement nested = object.get("extra");
            if (!nested.isJsonArray())
                throw new Error();
            output += extractJSONMessage(nested.getAsJsonArray());
        }

        if (object.has("bold"))
        {
            JsonElement nested = object.get("bold");
            if (!nested.isJsonPrimitive())
                throw new Error();
            if (Boolean.TRUE.equals(nested.getAsBoolean()))
                output = "**" + output + "**";
        }

        if (object.has("italic"))
        {
            JsonElement nested = object.get("italic");
            if (!nested.isJsonPrimitive())
                throw new Error();
            if (Boolean.TRUE.equals(nested.getAsBoolean()))
                output = "*" + output + "*";
        }

        if (object.has("underlined"))
        {
            JsonElement nested = object.get("underlined");
            if (!nested.isJsonPrimitive())
                throw new Error();
            if (Boolean.TRUE.equals(nested.getAsBoolean()))
                output = "__" + output + "__";
        }

        if (object.has("strikethrough"))
        {
            JsonElement nested = object.get("strikethrough");
            if (!nested.isJsonPrimitive())
                throw new Error();
            if (Boolean.TRUE.equals(nested.getAsBoolean()))
                output = "~~" + output + "~~";
        }

        if (object.has("obfuscated"))
        {
            JsonElement nested = object.get("obfuscated");
            if (!nested.isJsonPrimitive())
                throw new Error();
            if (Boolean.TRUE.equals(nested.getAsBoolean()))
                output = "||" + output + "||";
        }

        return output;
    }

    private final String extractJSONMessage(JsonArray array)
    {
        StringBuilder output = new StringBuilder();

        for (JsonElement child : array)
            output.append(extractJSONMessage(child));

        return output.toString();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onChat(AsyncPlayerChatEvent event)
    {
        printToDiscord("<" + MarkdownSanitizer.escape(event.getPlayer().getName()) + "> " + event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onCommand(PlayerCommandPreprocessEvent event)
    {
        String command = event.getMessage();
        String commandLowerCase = command.toLowerCase(Locale.ROOT);
        if (commandLowerCase.startsWith("/say "))
        {
            if (!event.getPlayer().hasPermission("minecraft.command.say"))
                return;
            String message = command.substring(5);
            printToDiscord("[" + MarkdownSanitizer.escape(teamedName(event.getPlayer())) + "] " + message);
        }
        else if (commandLowerCase.startsWith("/me "))
        {
            if (!event.getPlayer().hasPermission("minecraft.command.me"))
                return;
            String message = command.substring(4);
            printToDiscord("* " + MarkdownSanitizer.escape(teamedName(event.getPlayer())) + " " + message);
        }
        else if (commandLowerCase.startsWith("/tellraw @a "))
        {
            if (!event.getPlayer().hasPermission("minecraft.command.tellraw"))
                return;
            String message = command.substring(12);
            message = parseJSONMessage(message);
            printToDiscord(message);
        }
        else if (commandLowerCase.startsWith("/sv login ") || commandLowerCase.equals("/sv login"))
        {
            if (!event.getPlayer().hasPermission("sv.login") || !hasVanish)
                return;
            onJoin(new PlayerJoinEvent(event.getPlayer(), event.getPlayer().getName() + " joined the game"));
        }
        else if (commandLowerCase.startsWith("/sv logout ") || commandLowerCase.equals("/sv logout"))
        {
            if (!event.getPlayer().hasPermission("sv.logout") || !hasVanish)
                return;
            onQuit(new PlayerQuitEvent(event.getPlayer(), event.getPlayer().getName() + " left the game"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onCommand(ServerCommandEvent event)
    {
        String command = event.getCommand();
        String commandLowerCase = command.toLowerCase(Locale.ROOT);
        if (commandLowerCase.startsWith("say "))
        {
            String message = command.substring(4);
            printToDiscord("[" + event.getSender().getName() + "] " + message);
        }
        else if (commandLowerCase.startsWith("me "))
        {
            String message = command.substring(3);
            printToDiscord("* " + event.getSender().getName() + " " + message);
        }
        else if (commandLowerCase.startsWith("tellraw @a "))
        {
            String message = command.substring(11);
            message = parseJSONMessage(message);
            printToDiscord(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onCommand(RemoteServerCommandEvent event)
    {
        String command = event.getCommand();
        String commandLowerCase = command.toLowerCase(Locale.ROOT);
        if (commandLowerCase.startsWith("say "))
        {
            String message = command.substring(4);
            printToDiscord("[" + event.getSender().getName() + "] " + message);
        }
        else if (commandLowerCase.startsWith("me "))
        {
            String message = command.substring(3);
            printToDiscord("* " + event.getSender().getName() + " " + message);
        }
        else if (commandLowerCase.startsWith("tellraw @a "))
        {
            String message = command.substring(11);
            message = parseJSONMessage(message);
            printToDiscord(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onPlayerDeath(PlayerDeathEvent event)
    {
        if (noDeathMessages || event.getDeathMessage() == null)
            return;
        printToDiscord(MarkdownSanitizer.escape(event.getDeathMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onJoin(PlayerJoinEvent event)
    {
        if (noJoinQuitMessages || event.getJoinMessage() == null)
            return;
        printToDiscord(MarkdownSanitizer.escape(event.getJoinMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onQuit(PlayerQuitEvent event)
    {
        if (noJoinQuitMessages || event.getQuitMessage() == null)
            return;
        printToDiscord(MarkdownSanitizer.escape(event.getQuitMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onAdvancement(PlayerAdvancementDoneEvent event)
    {
        if (noAdvancementMessages || event.getAdvancement().getKey().getKey().contains("recipe/") || ((CraftAdvancement) event.getAdvancement()).getHandle().d() == null || event.getAdvancement().getKey().toString().contains("root"))
            return;
        String advancement = ((CraftAdvancement) event.getAdvancement()).getHandle().d().a().getString();
        String type = ((CraftAdvancement) event.getAdvancement()).getHandle().d().e().a();
        if (type.equals("challenge"))
            printToDiscord(MarkdownSanitizer.escape(event.getPlayer().getName()) + " has completed the challenge [" + advancement + "]");
        else if (type.equals("goal"))
            printToDiscord(MarkdownSanitizer.escape(event.getPlayer().getName()) + " has reached the goal [" + advancement + "]");
        else if (type.equals("task"))
            printToDiscord(MarkdownSanitizer.escape(event.getPlayer().getName()) + " has made the advancement [" + advancement + "]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onExternalMessage(ExternalMessageEvent event)
    {
        if (!allowExternalMessages)
            return;
        printToDiscord(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onBroadcast(BroadcastMessageEvent event)
    {
        if (!allowBroadcasts || event.getMessage().startsWith("<@"))
            return;
        printToDiscord(event.getMessage());
    }

    public void tryWhitelist(long channelId, long userId, String discordName, String content) {
        WhitelistThread t = new WhitelistThread(channelId, userId, discordName, content);
        Bukkit.getScheduler().runTaskAsynchronously(this, t);
    }

    class WhitelistThread implements Runnable {
        long channelId;

        long userId;

        String discordName;

        String ign;

        public WhitelistThread(long channelId, long userId, String discordName, String ign) {
            this.channelId = channelId;
            this.userId = userId;
            this.discordName = discordName;
            this.ign = ign;
        }

        public void run() {
            try {
                URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + this.ign);
                InputStream in = url.openStream();
                if (in.available() > 0) {
                    OfflinePlayer player = Bukkit.getOfflinePlayer(this.ign);
                    if (player.isWhitelisted()) {
                        MinecordPlugin.this.slave.temporaryMessageTo(this.channelId, this.userId, "You are already whitelisted!");
                    } else {
                        player.setWhitelisted(true);
                        WhitelistLog.log("User " + this.discordName + " (" + this.userId + ") has been whitelisted as " + this.ign + ".");
                        MinecordPlugin.this.slave.temporaryMessageTo(this.channelId, this.userId, "You have been whitelisted!");
                    }
                } else {
                    MinecordPlugin.this.slave.temporaryMessageTo(this.channelId, this.userId, "The username you provided does not exist!");
                }
                in.close();
            } catch (Exception e) {
                MinecordPlugin.this.slave.temporaryMessageTo(this.channelId, this.userId, "The username you provided does not exist!");
            }
        }
    }

}