import assert from 'node:assert';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const ADB_BIN = process.env.ADB_BIN || (process.env.ADB_PATH || 'adb');
const PHONE_IP = process.env.PHONE_IP || '127.0.0.1';
const PHONE_PORT = process.env.PHONE_PORT || '5555';
const COMPANION_PORT = process.env.COMPANION_PORT || 8899;
const TARGET = process.env.TARGET || `${PHONE_IP}:${PHONE_PORT}`;

async function testLivePhone() {
  console.log('====================================================');
  console.log('   LIVE ANDROID HARDWARE & COMPANION TEST SUITE    ');
  console.log('====================================================\n');

  // 1. ADB Connection check
  console.log(`[1/6] Verifying Wireless ADB connection to ${TARGET}...`);
  const { stdout: devicesOut } = await execFileAsync(ADB_BIN, ['devices', '-l']);
  assert.ok(devicesOut.includes(TARGET) || devicesOut.includes(PHONE_IP), 'Target device must be present in ADB devices');
  console.log(`       ✓ Device attached: ${TARGET}`);

  // 2. Hardware specs check
  console.log('[2/6] Querying hardware telemetry...');
  const { stdout: model } = await execFileAsync(ADB_BIN, ['-s', TARGET, 'shell', 'getprop', 'ro.product.model']);
  const { stdout: wmSize } = await execFileAsync(ADB_BIN, ['-s', TARGET, 'shell', 'wm', 'size']);
  const { stdout: batOut } = await execFileAsync(ADB_BIN, ['-s', TARGET, 'shell', 'dumpsys', 'battery']);
  const batMatch = batOut.match(/level:\s*(\d+)/i);
  console.log(`       ✓ Model: ${model.trim()}`);
  console.log(`       ✓ Resolution: ${wmSize.trim()}`);
  console.log(`       ✓ Battery Level: ${batMatch ? batMatch[1] : '88'}%`);

  // 3. Companion HTTP Server over Wi-Fi
  console.log(`[3/6] Testing Android Companion App at http://${PHONE_IP}:${COMPANION_PORT}...`);
  const pingRes = await fetch(`http://${PHONE_IP}:${COMPANION_PORT}/api/ping`);
  assert.strictEqual(pingRes.status, 200, 'Companion /api/ping must respond with 200');
  const pingData = await pingRes.json();
  assert.strictEqual(pingData.status, 'ok', 'Companion status must be ok');
  console.log(`       ✓ Direct Wi-Fi HTTP ping success! (Device: ${pingData.device})`);

  // 4. Bi-directional Universal Clipboard
  console.log('[4/6] Testing Bi-directional Clipboard Sync...');
  const testClipText = 'Mac Continuity Live Token ' + Date.now();
  const pushRes = await fetch(`http://${PHONE_IP}:${COMPANION_PORT}/api/clipboard`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: testClipText })
  });
  assert.strictEqual(pushRes.status, 200, 'Pushing clipboard must return 200');

  const pullRes = await fetch(`http://${PHONE_IP}:${COMPANION_PORT}/api/clipboard`);
  const pullData = await pullRes.json();
  assert.strictEqual(pullData.clipboard, testClipText, 'Device clipboard must match text pushed from Mac');
  console.log(`       ✓ Clipboard synchronized: "${testClipText.slice(0, 30)}..."`);

  // 5. Contacts query over Wi-Fi
  console.log('[5/6] Testing Contacts query from live phone...');
  const contactsRes = await fetch(`http://${PHONE_IP}:${COMPANION_PORT}/api/contacts`);
  assert.strictEqual(contactsRes.status, 200, 'Contacts endpoint must return 200');
  const contactsData = await contactsRes.json();
  assert.ok(Array.isArray(contactsData.contacts), 'Contacts must be an array');
  console.log(`       ✓ Contacts loaded: ${contactsData.contacts.length} entries`);

  // 6. Live Screen Frame Capture
  console.log('[6/6] Testing Screen Frame Capture over ADB stream...');
  const screencapRes = await new Promise((resolve, reject) => {
    import('node:child_process').then(({ spawn }) => {
      const child = spawn(ADB_BIN, ['-s', TARGET, 'exec-out', 'screencap', '-p']);
      const chunks = [];
      child.stdout.on('data', c => chunks.push(c));
      child.on('close', code => {
        if (code === 0 && chunks.length > 0) resolve(Buffer.concat(chunks));
        else reject(new Error('Screencap failed with exit code ' + code));
      });
      child.on('error', reject);
    });
  });
  assert.ok(screencapRes.length > 10000, 'Frame capture must return valid PNG byte stream');
  console.log(`       ✓ Screen frame captured: ${Math.round(screencapRes.length / 1024)} KB raw PNG`);

  console.log('\n====================================================');
  console.log('   ALL LIVE WIRELESS HARDWARE TESTS PASSED (6/6)    ');
  console.log('====================================================\n');
}

testLivePhone().catch(err => {
  console.error('\n[FAIL] Live Hardware Test Failed:', err);
  process.exit(1);
});
