import assert from 'node:assert';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const PHONE_IP = process.env.PHONE_IP || '127.0.0.1';
const COMPANION_URL = process.env.COMPANION_URL || `http://${PHONE_IP}:8899`;
const MAC_BRIDGE_URL = process.env.MAC_BRIDGE_URL || `http://127.0.0.1:8898`;

async function getMacClipboard() {
  const { stdout } = await execFileAsync('/usr/bin/pbpaste');
  return stdout;
}

async function setMacClipboard(text) {
  return new Promise((resolve) => {
    const child = spawn('/usr/bin/pbcopy');
    child.stdin.write(text);
    child.stdin.end();
    child.on('close', () => resolve(true));
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runTest() {
  console.log('======================================================');
  console.log('   BIDIRECTIONAL UNIVERSAL CLIPBOARD VERIFICATION     ');
  console.log('======================================================\n');

  // TEST 1: Mac -> Phone Clipboard Sync
  console.log('[Test 1] Testing Mac ➔ Phone sync...');
  const macText = `MacToken_${Date.now()}`;
  await setMacClipboard(macText);
  console.log(`         Copied on Mac: "${macText}"`);
  
  // Wait up to 2 seconds for sync
  let phoneReceived = false;
  for (let i = 0; i < 10; i++) {
    await sleep(250);
    const res = await fetch(`${COMPANION_URL}/api/clipboard`).catch(() => null);
    if (res && res.ok) {
      const data = await res.json();
      if (data.clipboard === macText) {
        phoneReceived = true;
        break;
      }
    }
  }
  assert.ok(phoneReceived, `Phone should receive "${macText}" within 2.5s`);
  console.log('         ✓ Verified Phone received Mac clipboard successfully!');

  // TEST 2: Loop check on Mac -> Phone
  console.log('\n[Test 2] Checking for echo loops (Mac ➔ Phone)...');
  await sleep(1000);
  const macClipAfter = await getMacClipboard();
  assert.strictEqual(macClipAfter, macText, 'Mac clipboard should remain unchanged without echo jitter');
  console.log('         ✓ No echo loop detected on Mac ➔ Phone sync.');

  // TEST 3: Phone -> Mac Clipboard Sync
  console.log('\n[Test 3] Testing Phone ➔ Mac sync...');
  const phoneText = `PhoneToken_${Date.now()}`;
  const phonePushRes = await fetch(`${COMPANION_URL}/api/clipboard`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: phoneText })
  });
  assert.strictEqual(phonePushRes.status, 200, 'Posting to phone clipboard must succeed');
  console.log(`         Copied on Phone: "${phoneText}"`);

  // Wait up to 2.5 seconds for Mac to receive it
  let macReceived = false;
  for (let i = 0; i < 10; i++) {
    await sleep(250);
    const curMac = await getMacClipboard();
    if (curMac === phoneText) {
      macReceived = true;
      break;
    }
  }
  assert.ok(macReceived, `Mac should receive "${phoneText}" within 2.5s`);
  console.log('         ✓ Verified Mac received Phone clipboard successfully!');

  // TEST 4: Loop check on Phone -> Mac
  console.log('\n[Test 4] Checking for echo loops (Phone ➔ Mac)...');
  await sleep(1000);
  const phoneClipAfterRes = await fetch(`${COMPANION_URL}/api/clipboard`);
  const phoneClipAfter = await phoneClipAfterRes.json();
  assert.strictEqual(phoneClipAfter.clipboard, phoneText, 'Phone clipboard should remain unchanged without bounce loop');
  console.log('         ✓ No echo loop detected on Phone ➔ Mac sync.');

  // TEST 5: Alternating Rapid Ping-Pong Test
  console.log('\n[Test 5] Running rapid alternating bidirectional sync test...');
  for (let cycle = 1; cycle <= 3; cycle++) {
    const cycleMac = `PingMac_${cycle}_${Date.now()}`;
    await setMacClipboard(cycleMac);
    await sleep(700);
    const pRes = await fetch(`${COMPANION_URL}/api/clipboard`);
    const pData = await pRes.json();
    assert.strictEqual(pData.clipboard, cycleMac, `Cycle ${cycle}: Phone must match Mac`);

    const cyclePhone = `PongPhone_${cycle}_${Date.now()}`;
    await fetch(`${COMPANION_URL}/api/clipboard`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: cyclePhone })
    });
    await sleep(700);
    const mClip = await getMacClipboard();
    assert.strictEqual(mClip, cyclePhone, `Cycle ${cycle}: Mac must match Phone`);
    console.log(`         ✓ Cycle ${cycle} completed: Mac <-> Phone in harmony.`);
  }

  console.log('\n======================================================');
  console.log('   ALL BIDIRECTIONAL & LOOP-FREE TESTS PASSED!        ');
  console.log('======================================================\n');
}

runTest().catch((err) => {
  console.error('\n[FAIL] Clipboard test failed:', err);
  process.exit(1);
});
