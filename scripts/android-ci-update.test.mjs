import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ABIS, createBundle, publishBundle, LATEST_KEY, PUBLIC_BASE } from './android-ci-update.mjs';

function fixture(t, versionCode = 12, runId = '120') {
  const directory = mkdtempSync(join(tmpdir(), 'maimaid-update-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(join(directory, 'output-metadata.json'), JSON.stringify({
    applicationId: 'org.rhythmeta.maimaid',
    elements: ABIS.map((abi) => ({ filters: [{ filterType: 'ABI', value: abi }], versionCode, versionName: '1.2.8', outputFile: `app-${abi}-release.apk` })),
  }));
  for (const abi of ABIS) writeFileSync(join(directory, `app-${abi}-release.apk`), `raw-apk-${abi}`);
  const options = { apkDirectory: directory, outputDirectory: join(directory, 'out'), runId, attempt: '1', branch: 'main', sha: 'a'.repeat(40), builtAt: '2026-10-20T10:00:00Z', commits: [{ sha: 'a'.repeat(40), parents: [], title: 'Changes', author: 'Author' }] };
  return { bundle: createBundle(options), options };
}

function remote(latest = null, journal = []) {
  const calls = [];
  const state = { latest, journal: [...journal], calls };
  return Object.assign(state, {
    upload: async (object, cache) => { calls.push(['upload', object.key, cache]); if (object.key === LATEST_KEY) state.latest = JSON.parse(readFileSync(object.file, 'utf8')); },
    verify: async (object) => { calls.push(['verify', object.key]); },
    readLatest: async () => state.latest,
    readJournal: async () => state.journal,
    writeJournal: async (keys) => { state.journal = keys; calls.push(['journal']); },
    clearJournal: async () => { state.journal = []; calls.push(['clear']); },
    remove: async (key) => { calls.push(['remove', key]); },
  });
}

test('bundle describes four raw APKs with consistent versions, hashes and history', (t) => {
  const { bundle } = fixture(t);
  assert.equal(bundle.objects.length, 6);
  assert.deepEqual(bundle.manifest.artifacts.map((item) => item.abi), ABIS);
  for (const artifact of bundle.manifest.artifacts) {
    assert.match(artifact.url, /\.apk$/);
    assert.match(artifact.sha256, /^[a-f0-9]{64}$/);
    assert.ok(artifact.size > 0);
  }
  assert.equal(bundle.manifest.versionCode, 12);
  assert.equal(bundle.manifest.author, 'Author');
});

test('missing ABI and mismatched APK versions prevent publication', (t) => {
  const { options } = fixture(t);
  rmSync(join(options.apkDirectory, 'app-x86-release.apk'));
  assert.throws(() => createBundle(options));
  writeFileSync(join(options.apkDirectory, 'app-x86-release.apk'), 'apk');
  const file = join(options.apkDirectory, 'output-metadata.json');
  const metadata = JSON.parse(readFileSync(file, 'utf8'));
  metadata.elements[1].versionCode++;
  writeFileSync(file, JSON.stringify(metadata));
  assert.throws(() => createBundle(options), /versions do not match/);
});

test('main pointer advances only after every object passes anonymous verification', async (t) => {
  const { bundle } = fixture(t);
  const api = remote();
  assert.equal(await publishBundle(bundle, api), true);
  const pointer = api.calls.findIndex(([kind, key]) => kind === 'upload' && key === LATEST_KEY);
  assert.equal(api.calls.slice(0, pointer).filter(([kind]) => kind === 'verify').length, 6);
  assert.equal(api.calls[pointer][2], 'no-store, max-age=0');
  assert.deepEqual(api.journal, []);
});

test('upload or verification failure retains previous build and recovery journal', async (t) => {
  const { bundle } = fixture(t);
  for (const operation of ['upload', 'verify']) {
    const api = remote();
    api[operation] = async () => { throw new Error('failed'); };
    await assert.rejects(publishBundle(bundle, api));
    assert.equal(api.latest, null);
    assert.equal(api.journal.length, 6);
    assert.equal(api.calls.filter(([kind]) => kind === 'remove').length, 0);
  }
});

test('only the latest main build is retained, old objects removed after pointer verification', async (t) => {
  const old = fixture(t, 11, '110').bundle;
  const { bundle } = fixture(t);
  const api = remote(old.manifest);
  await publishBundle(bundle, api);
  const pointerVerified = api.calls.findIndex(([kind, key]) => kind === 'verify' && key === LATEST_KEY);
  const deleted = api.calls.filter(([kind]) => kind === 'remove');
  assert.deepEqual(deleted.map(([, key]) => key).sort(), old.objects.map(({ key }) => key).sort());
  assert.ok(api.calls.findIndex(([kind]) => kind === 'remove') > pointerVerified);
});

test('older builds and repeated runs cannot overwrite latest or upload orphan APKs', async (t) => {
  const { bundle } = fixture(t);
  for (const code of [12, 13]) {
    const api = remote({ ...bundle.manifest, versionCode: code });
    assert.equal(await publishBundle(bundle, api), false);
    assert.equal(api.calls.filter(([kind]) => kind === 'upload').length, 0);
  }
});

test('non-main builds do not touch R2', async (t) => {
  const { bundle } = fixture(t);
  bundle.manifest.branch = 'feature/update';
  const api = remote();
  assert.equal(await publishBundle(bundle, api), false);
  assert.deepEqual(api.calls, []);
});

test('interrupted publication is cleaned up without deleting the active build', async (t) => {
  const old = fixture(t, 11, '110').bundle;
  const { bundle } = fixture(t);
  for (const pointerWasWritten of [false, true]) {
    const active = pointerWasWritten ? bundle : old;
    const api = remote(active.manifest, [...old.objects, ...bundle.objects].map(({ key }) => key));
    const noNewerBuild = { ...bundle, manifest: { ...bundle.manifest, versionCode: 10 } };
    assert.equal(await publishBundle(noNewerBuild, api), false);
    const activeKeys = active.objects.map(({ key }) => key);
    assert.equal(api.calls.filter(([kind]) => kind === 'remove').length, 6);
    assert.ok(api.calls.filter(([kind]) => kind === 'remove').every(([, key]) => !activeKeys.includes(key)));
    assert.deepEqual(api.journal, []);
  }
});

test('cleanup rejects objects outside the CI build prefix', async (t) => {
  const { bundle } = fixture(t);
  const api = remote(null, ['covers/keep-this.png']);
  await assert.rejects(publishBundle(bundle, api), /Unsafe/);
  assert.equal(api.calls.length, 0);
  const poisoned = remote({ ...bundle.manifest, historyUrl: `${PUBLIC_BASE}/covers/history.json` });
  await assert.rejects(publishBundle(bundle, poisoned), /Unsafe/);
});
