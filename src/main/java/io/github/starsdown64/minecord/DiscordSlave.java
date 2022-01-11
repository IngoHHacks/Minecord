package io.github.starsdown64.minecord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.DisconnectEvent;
import net.dv8tion.jda.api.events.ReconnectedEvent;
import net.dv8tion.jda.api.events.ResumedEvent;
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent;
import net.dv8tion.jda.api.events.guild.GuildUnavailableEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.ChatColor;

import javax.security.auth.login.LoginException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

public class DiscordSlave extends ListenerAdapter {
    private final MinecordPlugin master;
    private final String token;
    private final String channelID;
    private final String prefix;
    private final ArrayList<String> minecordIntegrationToggle;
    private final boolean emptyNewlineTruncation;
    private TextChannel channel;
    private JDA discord;

    public DiscordSlave(MinecordPlugin master)
    {
        this.master = master;
        this.token = master.getConfigFile().getString("token");
        this.channelID = master.getConfigFile().getString("channelID");
        this.prefix = master.getConfigFile().getString("prefix");
        this.minecordIntegrationToggle = (ArrayList<String>) master.getConfigFile().getStringList("minecordIntegrationToggle");
        this.emptyNewlineTruncation = master.getConfigFile().getBoolean("emptyNewlineTruncation");
    }

    public final void start() throws LoginException, NumberFormatException
    {
        this.discord = JDABuilder.createLight(token).addEventListeners(this).build();
        while (true)
        {
            try
            {
                discord.awaitReady();
                break;
            }
            catch (InterruptedException exception)
            {
                exception.printStackTrace();
            }
        }
        this.channel = discord.getTextChannelById(channelID);
    }

    public final void stop()
    {
        discord.setAutoReconnect(false);
        discord.shutdown();
    }

    public final void send(String message)
    {
        if (!discord.getStatus().equals(JDA.Status.CONNECTED))
            return;
        channel.sendMessage(message).queue();
    }

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event)
    {
        Message message = event.getMessage();
        if (!message.getChannel().getId().equals(channelID) || message.getAuthor().isBot())
            return;
        String content = message.getContentDisplay();

        // Command handler
        if (content.startsWith(prefix))
        {
            if (content.substring(prefix.length()).equalsIgnoreCase("on"))
            {
                if (!minecordIntegrationToggle.contains(message.getAuthor().getId()))
                {
                    message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", you do not have permission to toggle integration.").queue();
                    return;
                }
                if (master.getIntegration())
                {
                    message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", integration is already on.").queue();
                    return;
                }
                master.setIntegration(true);
                message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", integration has been turned on.").queue();
                master.getServer().broadcastMessage("[Minecord] Integration has been turned on.");
                return;
            }
            else if (content.substring(prefix.length()).equalsIgnoreCase("off"))
            {
                if (!minecordIntegrationToggle.contains(message.getAuthor().getId()))
                {
                    message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", you do not have permission to toggle integration.").queue();
                    return;
                }
                if (!master.getIntegration())
                {
                    message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", integration is already off.").queue();
                    return;
                }
                master.setIntegration(false);
                message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", integration has been turned off.").queue();
                master.getServer().broadcastMessage("[Minecord] Integration has been turned off.");
                return;
            }
            else if (content.substring(prefix.length()).equalsIgnoreCase("tab"))
            {
                message.getChannel().sendMessage(master.getFormattedTabMenu()).queue();
                return;
            }
            else
            {
                message.getChannel().sendMessage(message.getAuthor().getAsMention() + ", taht is not a valid command.").queue();
                return;
            }
        }

        String author = message.getAuthor().getAsTag();
        if (ChatColor.stripColor(content).replaceAll("§", "").isBlank())
            return;
        if (emptyNewlineTruncation)
        {
            LinkedList<Integer> toRemove = new LinkedList<>();
            LinkedList<String> contentList = new LinkedList<>(Arrays.asList(content.split("\n")));
            for (int i = 0; i < contentList.size(); i++)
                if (!(contentList.size() == i + 1) && contentList.get(i + 1).isBlank() && contentList.get(i).isBlank())
                    toRemove.addLast(i);
            while (toRemove.size() > 0)
            {
                contentList.remove(toRemove.remove(0).intValue());
                for (int i = 0; i < toRemove.size(); i++)
                    toRemove.set(i, toRemove.get(i) - 1);
            }
            content = String.join("\n", contentList);
        }
        content = content.replaceAll("\n", "\n<" + author + "> ");
        master.printToMinecraft("<" + author + "> " + content);
    }

    @Override
    public void onGuildUnavailable(GuildUnavailableEvent event)
    {
        if (event.getGuild().equals(channel.getGuild()))
        {
            if (master.getConnected())
                master.setLastConnected(System.currentTimeMillis());
            master.setConnected(false);
            master.getLogger().warning("Minecord has lost connection to the Guild.");
        }
    }

    @Override
    public void onGuildAvailable(GuildAvailableEvent event)
    {
        if (event.getGuild().equals(channel.getGuild()))
        {
            long timeLost = System.currentTimeMillis() - master.getLastConnected();
            master.setConnected(true);
            if (timeLost < 600000)
                return;
            master.printToDiscordBypass("Minecord has reconnected to the Guild. it was disconnected for " + new DecimalFormat("#.##").format(timeLost / 1000.0 / 60.0) + " minutes.");
        }
    }

    @Override
    public void onDisconnect(DisconnectEvent event)
    {
        if (master.getConnected())
            master.setLastConnected(event.getTimeDisconnected().toEpochSecond() * 1000);
        master.setConnected(false);
        master.getLogger().warning("Minecord has lost connection to Discord.");
    }

    @Override
    public void onReconnected(ReconnectedEvent event)
    {
        long timeLost = System.currentTimeMillis() - master.getLastConnected();
        master.setConnected(true);
        if (timeLost < 600000)
            return;
        master.printToDiscordBypass("Minecord has reconnected to Discord. It was disconnected for " + new DecimalFormat("#.##").format(timeLost / 1000.0 / 60.0) + " minutes.");
    }

    @Override
    public void onResumed(ResumedEvent event)
    {
        long timeLost = System.currentTimeMillis() - master.getLastConnected();
        master.setConnected(true);
        if (timeLost < 600000)
            return;
        master.printToDiscordBypass("Minecord has resumed its connection to Discord. It was disconnected for " + new DecimalFormat("#.##").format(timeLost / 1000.0 / 60.0) + " minutes.");
    }
}
