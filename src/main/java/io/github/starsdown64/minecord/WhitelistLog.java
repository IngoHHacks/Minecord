package io.github.starsdown64.minecord;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

public class WhitelistLog {

    private final Object lock = new Object();

    private final MinecordPlugin master;

    public static WhitelistLog instance;

    private String path;

    public WhitelistLog(MinecordPlugin master) {
        instance = this;
        this.master = master;
        path = master.getDataFolder().getAbsolutePath() + "\\whitelist.log";
    }

    public static void log(String message) {
        synchronized (instance.lock) {
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(instance.path, true));
                String time = LocalDateTime.now().toString();
                writer.append("[Minecord] [").append(time).append("] ").append(message).append("\n");
                writer.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
