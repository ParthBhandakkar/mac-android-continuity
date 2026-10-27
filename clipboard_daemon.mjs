#!/usr/bin/env node

/**
 * Invisible Universal Clipboard Daemon for Mac <-> Android Continuity
 * 
 * Runs silently in the background.
 * - Monitors Mac pasteboard (/usr/bin/pbpaste). On change -> pushes to Android port 8899.
 * - Monitors Android clipboard via HTTP (port 8899). On change -> copies to Mac (/usr/bin/pbcopy).
 * - Zero UI, completely invisible, just like Apple Universal Clipboard.
 */

import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const COMPANION_URL = process.env.COMPANION_URL || (process.env.PHONE_IP ? `http://${process.env.PHONE_IP}:8899` : 'http://127.0.0.1:8899');

let lastHostClip = '';
let lastDeviceClip = '';

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

async function syncLoop() {
  try {
    // 1. Check Mac host clipboard
    const currentHost = await getMacClipboard();
    if (currentHost && currentHost !== lastHostClip) {
      lastHostClip = currentHost;
      lastDeviceClip = currentHost;
      
      // Push silently to Android phone
      await fetch(`${COMPANION_URL}/api/clipboard`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: currentHost }),
        signal: AbortSignal.timeout(1200)
      }).catch(() => {});
    }

    // 2. Poll phone clipboard
    const res = await fetch(`${COMPANION_URL}/api/clipboard`, {
      signal: AbortSignal.timeout(1200)
    }).catch(() => null);

    if (res && res.ok) {
      const data = await res.json().catch(() => null);
      if (data && data.clipboard && data.clipboard !== lastDeviceClip && data.clipboard !== currentHost) {
        lastDeviceClip = data.clipboard;
        lastHostClip = data.clipboard;
        await setMacClipboard(data.clipboard);
      }
    }
  } catch {}
}

console.log(`[Clipboard Daemon] Active. Syncing with ${COMPANION_URL}`);
setInterval(syncLoop, 1000);
