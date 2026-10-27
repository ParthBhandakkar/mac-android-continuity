import assert from 'node:assert';
import { spawn } from 'node:child_process';

const TEST_PORT = 8991;

async function runSelfCheck() {
  console.log('[Test] Launching Mac-Android Continuity Server on test port ' + TEST_PORT + '...');
  const serverProc = spawn('node', ['server.mjs'], {
    cwd: new URL('.', import.meta.url).pathname,
    env: { ...process.env, PORT: String(TEST_PORT) }
  });

  // Wait for server to boot
  await new Promise((resolve) => setTimeout(resolve, 1500));

  try {
    const baseUrl = `http://127.0.0.1:${TEST_PORT}`;

    // 1. Status check
    console.log('[Test] Checking /api/status...');
    const statusRes = await fetch(`${baseUrl}/api/status`);
    assert.strictEqual(statusRes.status, 200, 'Status endpoint must return 200');
    const statusData = await statusRes.json();
    assert.strictEqual(statusData.status, 'ok', 'Status must be ok');
    assert.ok(statusData.adbPath, 'ADB path must be detected');

    // 2. Clipboard Sync check
    console.log('[Test] Testing /api/clipboard...');
    const clipSetRes = await fetch(`${baseUrl}/api/clipboard`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: 'Hello from Continuity Self-Check', target: 'both', autoSync: true })
    });
    assert.strictEqual(clipSetRes.status, 200, 'Clipboard POST must return 200');

    const clipGetRes = await fetch(`${baseUrl}/api/clipboard`);
    const clipGetData = await clipGetRes.json();
    assert.ok(Array.isArray(clipGetData.history), 'Clipboard history must be an array');
    assert.ok(clipGetData.history.some(h => h.text === 'Hello from Continuity Self-Check'), 'Pushed clipboard item must exist in history');

    // 3. Contacts check
    console.log('[Test] Testing /api/contacts...');
    const contactsRes = await fetch(`${baseUrl}/api/contacts`);
    assert.strictEqual(contactsRes.status, 200, 'Contacts endpoint must return 200');
    const contactsData = await contactsRes.json();
    assert.ok(contactsData.contacts.length > 0, 'Contacts must return a list');

    // 4. Messages check
    console.log('[Test] Testing /api/messages...');
    const msgRes = await fetch(`${baseUrl}/api/messages`);
    assert.strictEqual(msgRes.status, 200, 'Messages endpoint must return 200');
    const msgData = await msgRes.json();
    assert.ok(msgData.messages.length > 0, 'Messages must return conversation threads');

    // 5. Send message
    console.log('[Test] Testing /api/messages/send...');
    const sendRes = await fetch(`${baseUrl}/api/messages/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ number: '+1 (415) 890-2341', text: 'Continuity Verification Message' })
    });
    assert.strictEqual(sendRes.status, 200, 'Send message must return 200');
    const sendData = await sendRes.json();
    assert.strictEqual(sendData.success, true, 'Message send must succeed');

    // 6. Remote input: Tap & Keyevent
    console.log('[Test] Testing /api/control/tap & /api/control/key...');
    const tapRes = await fetch(`${baseUrl}/api/control/tap`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x: 100, y: 200 })
    });
    assert.strictEqual(tapRes.status, 200, 'Tap control must return 200');

    const keyRes = await fetch(`${baseUrl}/api/control/key`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keycode: 3 }) // Home button
    });
    assert.strictEqual(keyRes.status, 200, 'Key control must return 200');

    // 7. Dashboard HTML check
    console.log('[Test] Checking GET / (Dashboard HTML)...');
    const htmlRes = await fetch(`${baseUrl}/`);
    assert.strictEqual(htmlRes.status, 200, 'Dashboard HTML must return 200');
    const htmlText = await htmlRes.text();
    assert.ok(htmlText.includes('Mac-Android Continuity'), 'Dashboard must contain brand title');
    assert.ok(htmlText.includes('Universal Clipboard'), 'Dashboard must contain Universal Clipboard section');

    console.log('\n[SUCCESS] All Mac-Android Continuity self-checks passed!\n');
  } finally {
    serverProc.kill('SIGTERM');
  }
}

runSelfCheck().catch((err) => {
  console.error('\n[FAIL] Self-check failed:', err);
  process.exit(1);
});
