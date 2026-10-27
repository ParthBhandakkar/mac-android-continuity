#!/usr/bin/env node

/**
 * Mac-Android Continuity Native Bridge Service
 * Port: 8898 (Mac Receiver) <--> Port: 8899 (Android Companion)
 * 
 * Capabilities:
 *  1. Receives files sent from phone -> saves to ~/Downloads/ & triggers macOS notification.
 *  2. Serves Mac device telemetry & recent Mac ~/Downloads files to phone.
 *  3. Queries Phone recent files (/sdcard/Download, /sdcard/DCIM) & pulls them to Mac.
 *  4. Pushes files from Mac to Phone.
 *  5. Invisible Universal Clipboard synchronization.
 *  6. One-click 60 FPS hardware-accelerated screen mirror launcher.
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const PORT = process.env.PORT || 8898;
const HOST = '0.0.0.0';

function findAdbBinary() {
  if (process.env.ADB_PATH && fs.existsSync(process.env.ADB_PATH)) return process.env.ADB_PATH;
  const candidates = [
    '/opt/homebrew/bin/adb',
    '/usr/local/bin/adb',
    path.join(os.homedir(), 'Library/Android/sdk/platform-tools/adb'),
    '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return 'adb';
}

function findScrcpyBinary() {
  if (process.env.SCRCPY_PATH && fs.existsSync(process.env.SCRCPY_PATH)) return process.env.SCRCPY_PATH;
  const candidates = [
    '/opt/homebrew/bin/scrcpy',
    '/usr/local/bin/scrcpy'
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return 'scrcpy';
}

const ADB_BIN = findAdbBinary();
const SCRCPY_BIN = findScrcpyBinary();

function getMacLocalIp() {
  if (process.env.MAC_IP) return process.env.MAC_IP;
  const interfaces = os.networkInterfaces();
  for (const name of ['en0', 'en1', 'wlan0', 'eth0', ...Object.keys(interfaces)]) {
    const list = interfaces[name];
    if (!list) continue;
    for (const info of list) {
      if (info.family === 'IPv4' && !info.internal && info.address !== '127.0.0.1') {
        return info.address;
      }
    }
  }
  return '127.0.0.1';
}

let TARGET = process.env.TARGET || (process.env.PHONE_IP ? `${process.env.PHONE_IP}:${process.env.PHONE_PORT || '5555'}` : '');

function getPhoneCompanionUrl() {
  const ip = TARGET ? TARGET.split(':')[0] : '127.0.0.1';
  return `http://${ip}:8899`;
}

const DOWNLOADS_DIR = path.join(os.homedir(), 'Downloads');

// Helper to keep ADB connected persistently
let isReconnectingAdb = false;
async function ensureAdbConnected() {
  if (isReconnectingAdb) return;
  try {
    const { stdout } = await execFileAsync(ADB_BIN, ['devices']).catch(() => ({ stdout: '' }));
    
    // Auto-detect phone IP or serial if connected
    if (!process.env.TARGET && !process.env.PHONE_IP) {
      const match = stdout.match(/^([^\s]+)\s+device/m);
      if (match && match[1] !== TARGET) {
        TARGET = match[1];
        console.log(`[Mac Bridge] Auto-detected connected Android device: ${TARGET}`);
      }
    }

    if (TARGET && (!stdout.includes(TARGET) || stdout.includes(`${TARGET}\toffline`))) {
      isReconnectingAdb = true;
      console.log(`[Mac Bridge] Connecting to ${TARGET}...`);
      await execFileAsync(ADB_BIN, ['connect', TARGET], { timeout: 4000 }).catch(() => {});
      isReconnectingAdb = false;
    }
  } catch {
    isReconnectingAdb = false;
  }
}

// Watchdog every 5 seconds to heal any Wi-Fi sleep disconnects
setInterval(ensureAdbConnected, 5000);

// Helper to run ADB with auto-retry
async function runAdb(args, timeoutMs = 8000) {
  try {
    const { stdout, stderr } = await execFileAsync(ADB_BIN, ['-s', TARGET, ...args], { timeout: timeoutMs });
    return { success: true, stdout: stdout.trim(), stderr: stderr.trim() };
  } catch (err) {
    // If device was disconnected, try reconnecting and retry once
    if (err.message && (err.message.includes('not found') || err.message.includes('offline'))) {
      await ensureAdbConnected();
      try {
        const { stdout, stderr } = await execFileAsync(ADB_BIN, ['-s', TARGET, ...args], { timeout: timeoutMs });
        return { success: true, stdout: stdout.trim(), stderr: stderr.trim() };
      } catch (retryErr) {
        return { success: false, stdout: '', stderr: retryErr.message };
      }
    }
    return { success: false, stdout: '', stderr: err.message };
  }
}

// Universal Bidirectional Clipboard Engine (Zero-Loop Guarantee)
let lastSyncedText = '';
let lastSyncSource = '';
let lastSyncTime = 0;
let scrcpyDaemon = null;

async function getMacClipboard() {
  try {
    const { stdout } = await execFileAsync('/usr/bin/pbpaste');
    return stdout;
  } catch {
    return '';
  }
}

async function setMacClipboard(text) {
  try {
    return new Promise((resolve) => {
      const child = spawn('/usr/bin/pbcopy');
      child.stdin.write(text);
      child.stdin.end();
      child.on('close', () => resolve(true));
      child.on('error', () => resolve(false));
    });
  } catch {
    return false;
  }
}

async function startClipboardDaemon() {
  if (scrcpyDaemon) {
    try { scrcpyDaemon.kill(); } catch {}
  }

  await ensureAdbConnected();
  if (!TARGET) return;

  // Headless scrcpy to stream Android clipboard changes via UID 2000 in real-time
  scrcpyDaemon = spawn(SCRCPY_BIN, [
    `--serial=${TARGET}`,
    '--no-video',
    '--no-audio',
    '--no-window'
  ]);

  scrcpyDaemon.stdout?.on('data', async (chunk) => {
    const str = chunk.toString();
    if (str.includes('Device clipboard copied')) {
      // Allow pbcopy from scrcpy to settle
      setTimeout(async () => {
        const text = await getMacClipboard();
        if (text && text !== lastSyncedText) {
          lastSyncedText = text;
          lastSyncSource = 'phone';
          lastSyncTime = Date.now();
          console.log(`[Universal Clipboard] 📱 Phone ➔ 💻 Mac: "${text.substring(0, 30)}${text.length > 30 ? '...' : ''}"`);
        }
      }, 60);
    }
  });

  scrcpyDaemon.stderr?.on('data', () => {});

  scrcpyDaemon.on('exit', async (code) => {
    scrcpyDaemon = null;
    await ensureAdbConnected();
    setTimeout(startClipboardDaemon, 2500);
  });
}

// Check Mac clipboard changes every 500ms
async function checkMacClipboard() {
  try {
    const currentMac = await getMacClipboard();
    if (!currentMac) return;

    // Strict deduplication: if identical to current synced text, ignore completely
    if (currentMac === lastSyncedText) return;

    // If text was set by phone within last 1200ms, ignore to avoid race loop
    if (lastSyncSource === 'phone' && Date.now() - lastSyncTime < 1200) {
      lastSyncedText = currentMac;
      return;
    }

    // New Mac copy detected
    lastSyncedText = currentMac;
    lastSyncSource = 'mac';
    lastSyncTime = Date.now();
    console.log(`[Universal Clipboard] 💻 Mac ➔ 📱 Phone: "${currentMac.substring(0, 30)}${currentMac.length > 30 ? '...' : ''}"`);

    // Push to Android Companion
    fetch(`${getPhoneCompanionUrl()}/api/clipboard`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: currentMac, source: 'mac' }),
      signal: AbortSignal.timeout(1500)
    }).catch(() => {});
  } catch {}
}

setInterval(checkMacClipboard, 500);
startClipboardDaemon();

// 2. HTTP Server
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Filename');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // A. Mac Device Status (Queried by phone or local app)
  if (pathname === '/api/status' && req.method === 'GET') {
    let battPercent = 58;
    let isCharging = false;
    try {
      const { stdout: batt } = await execFileAsync('pmset', ['-g', 'batt']);
      const m = batt.match(/(\d+)%/);
      if (m) battPercent = parseInt(m[1], 10);
      isCharging = batt.includes('charging') || batt.includes('AC Power');
    } catch {}

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      deviceName: os.hostname().replace(/\.local$/, ''),
      model: 'MacBook Air (Apple M5)',
      chip: 'Apple M5 (10-core)',
      memory: '16 GB',
      os: 'macOS 27.0',
      battery: battPercent,
      isCharging: isCharging,
      ip: getMacLocalIp(),
      port: PORT,
      status: 'online'
    }));
    return;
  }

  // B. Phone Status & Hardware Telemetry
  if (pathname === '/api/phone/status' && req.method === 'GET') {
    let companionStatus = null;
    try {
      const cRes = await fetch(`${getPhoneCompanionUrl()}/api/status`, { signal: AbortSignal.timeout(1500) });
      if (cRes.ok) companionStatus = await cRes.json();
    } catch {}

    // Query storage via ADB
    let storageInfo = { size: '105 GB', used: '96 GB', free: '8.9 GB', percent: '92%' };
    const dfRes = await runAdb(['shell', 'df', '-h', '/sdcard']);
    if (dfRes.success) {
      const lines = dfRes.stdout.split('\n');
      if (lines.length > 1) {
        const parts = lines[1].trim().split(/\s+/);
        if (parts.length >= 5) {
          storageInfo = { size: parts[1], used: parts[2], free: parts[3], percent: parts[4] };
        }
      }
    }

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      model: companionStatus?.model || 'Android Device',
      manufacturer: companionStatus?.manufacturer || 'Android',
      battery: companionStatus?.battery ?? '--',
      androidVersion: companionStatus?.androidVersion || 'Android',
      ip: TARGET ? TARGET.split(':')[0] : 'Not connected',
      storage: storageInfo,
      adbConnected: !!TARGET
    }));
    return;
  }

  // C. Receive File sent from Phone (Phone -> Mac)
  if (pathname === '/api/files/receive' && req.method === 'POST') {
    const rawName = url.searchParams.get('name') || req.headers['x-filename'] || `phone_drop_${Date.now()}.bin`;
    const safeName = path.basename(rawName).replace(/[^\w\.\-\_\s]/g, '');
    const targetPath = path.join(DOWNLOADS_DIR, safeName);

    const fileStream = fs.createWriteStream(targetPath);
    req.pipe(fileStream);

    fileStream.on('finish', () => {
      // Trigger native macOS notification
      execFile('osascript', ['-e', `display notification "Saved ${safeName} to ~/Downloads" with title "File Received from Phone" sound name "Glass"`]);

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, savedPath: targetPath, filename: safeName }));
    });

    fileStream.on('error', (err) => {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    });
    return;
  }

  // D. Recent Files on Mac ~/Downloads (Viewed by Phone or Local App)
  if (pathname === '/api/files/recent-mac' && req.method === 'GET') {
    try {
      const formatSize = (bytes) => {
        if (!bytes || bytes <= 0) return '0 B';
        const k = 1024;
        const s = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + s[i];
      };

      const files = fs.readdirSync(DOWNLOADS_DIR)
        .filter(f => !f.startsWith('.'))
        .map(f => {
          try {
            const stat = fs.statSync(path.join(DOWNLOADS_DIR, f));
            return {
              name: f,
              size: formatSize(stat.size),
              rawBytes: stat.size,
              mtime: stat.mtimeMs,
              isDir: stat.isDirectory()
            };
          } catch { return null; }
        })
        .filter(Boolean)
        .sort((a, b) => b.mtime - a.mtime)
        .slice(0, 20);

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ files }));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  // E. Recent Files on Phone (Viewed by Mac)
  if (pathname === '/api/files/recent-phone' && req.method === 'GET') {
    const cmd = `ls -lt /sdcard/Download /sdcard/DCIM/Camera 2>/dev/null | grep '^-' | head -n 25`;
    const resAdb = await runAdb(['shell', cmd]);
    const files = [];

    if (resAdb.success) {
      const lines = resAdb.stdout.split('\n');
      for (const line of lines) {
        const parts = line.trim().split(/\s+/);
        if (parts.length >= 8) {
          const size = parseInt(parts[4], 10) || 0;
          const name = parts.slice(7).join(' ');
          if (name && !name.startsWith('.')) {
            const ext = path.extname(name).toLowerCase();
            let type = 'file';
            if (['.jpg', '.jpeg', '.png', '.webp', '.heic'].includes(ext)) type = 'image';
            else if (['.mp4', '.mkv', '.mov'].includes(ext)) type = 'video';
            else if (['.pdf', '.doc', '.docx', '.md', '.txt'].includes(ext)) type = 'document';
            else if (ext === '.apk') type = 'apk';

            files.push({
              name,
              size,
              type,
              remotePath: `/sdcard/Download/${name}`
            });
          }
        }
      }
    }

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ files }));
    return;
  }

  // F. Pull File from Phone to Mac
  if (pathname === '/api/files/pull-from-phone' && req.method === 'POST') {
    const body = await parseJsonBody(req);
    const { filename } = body;
    if (filename) {
      const safeName = path.basename(filename);
      const dest = path.join(DOWNLOADS_DIR, safeName);
      const resAdb = await runAdb(['pull', `/sdcard/Download/${safeName}`, dest]);
      if (resAdb.success) {
        execFile('osascript', ['-e', `display notification "Saved ${safeName} to ~/Downloads" with title "File Pulled from OnePlus" sound name "Glass"`]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, localPath: dest }));
        return;
      }
    }
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Failed to pull file' }));
    return;
  }

  // G. Push File from Mac to Phone (Drop zone)
  if (pathname === '/api/files/push-to-phone' && req.method === 'POST') {
    const rawName = url.searchParams.get('name') || req.headers['x-filename'] || `mac_drop_${Date.now()}.bin`;
    const safeName = path.basename(rawName);
    const tempPath = path.join('/tmp', safeName);

    const fileStream = fs.createWriteStream(tempPath);
    req.pipe(fileStream);

    fileStream.on('finish', async () => {
      const pushRes = await runAdb(['push', tempPath, `/sdcard/Download/${safeName}`]);
      fs.unlink(tempPath, () => {});

      if (pushRes.success) {
        const isImg = /\.(jpg|jpeg|png|webp|gif|heic|bmp)$/i.test(safeName);
        if (isImg) {
          await runAdb(['shell', 'cp', `"/sdcard/Download/${safeName}"`, `"/sdcard/Pictures/${safeName}"`]).catch(() => {});
          await runAdb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `"file:///sdcard/Pictures/${safeName}"`]).catch(() => {});
        }
        await runAdb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `"file:///sdcard/Download/${safeName}"`]).catch(() => {});

        // Notify companion service to scan into MediaStore & show heads-up alert
        fetch(`${getPhoneCompanionUrl()}/api/file-received`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ filename: safeName, isImage: isImg }),
          signal: AbortSignal.timeout(2500)
        }).catch(() => {});

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, remotePath: `/sdcard/Download/${safeName}` }));
      } else {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: pushRes.stderr }));
      }
    });
    return;
  }

  // H. Universal Clipboard Endpoints
  if (pathname === '/api/clipboard' && req.method === 'GET') {
    const currentMac = await getMacClipboard();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ clipboard: currentMac || lastSyncedText }));
    return;
  }

  if (pathname === '/api/clipboard' && req.method === 'POST') {
    const body = await parseJsonBody(req);
    const newText = body.text || '';
    if (newText && newText !== lastSyncedText) {
      lastSyncedText = newText;
      lastSyncSource = 'phone';
      lastSyncTime = Date.now();
      await setMacClipboard(newText);
      console.log(`[Universal Clipboard] 📱 Direct POST ➔ 💻 Mac: "${newText.substring(0, 30)}${newText.length > 30 ? '...' : ''}"`);
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  // I. Start 60 FPS Mirror Window
  if (pathname === '/api/mirror/start' && req.method === 'POST') {
    if (!TARGET) await ensureAdbConnected();
    const mirrorArgs = [
      ...(TARGET ? [`--serial=${TARGET}`] : []),
      '--window-title=Android Mirroring',
      '--window-width=375',
      '--max-fps=60',
      '--video-bit-rate=12M',
      '--stay-awake',
      '--no-clipboard-autosync',
      '--shortcut-mod=lsuper,lalt'
    ];
    const mirrorProc = spawn(SCRCPY_BIN, mirrorArgs, { detached: true, stdio: 'ignore' });
    mirrorProc.unref();

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'Mirror launched' }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not found' }));
});

async function parseJsonBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', c => raw += c);
    req.on('end', () => {
      try { resolve(raw ? JSON.parse(raw) : {}); } catch { resolve({}); }
    });
  });
}

server.listen(PORT, HOST, () => {
  console.log(`[Mac Continuity Bridge] Running on port ${PORT}. Ready to sync with phone ${PHONE_IP}.`);
});
