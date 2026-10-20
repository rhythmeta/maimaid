import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'];
export const PUBLIC_BASE = 'https://assets.rhythmeta.org';
export const BUCKET = 'maimaid-assets';
export const LATEST_KEY = 'android/ci/main/latest.json';
export const JOURNAL_KEY = 'android/ci/main/publication.json';
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

export function createBundle({ apkDirectory, outputDirectory, runId, attempt, branch, sha, builtAt, commits }) {
  if (!/^\d+$/.test(runId) || !/^\d+$/.test(attempt) || !/^[a-f0-9]{40}$/.test(sha)) {
    throw new Error('Invalid CI build identity');
  }
  if (!branch || !Number.isFinite(Date.parse(builtAt)) || commits[0]?.sha !== sha) {
    throw new Error('Invalid build metadata or commit history');
  }
  const metadata = JSON.parse(readFileSync(join(apkDirectory, 'output-metadata.json'), 'utf8'));
  if (metadata.applicationId !== 'org.rhythmeta.maimaid') throw new Error('Unexpected application ID');
  const prefix = `android/ci/builds/${runId}-${attempt}`;
  const objects = [];
  const artifacts = ABIS.map((abi) => {
    const element = metadata.elements.find((entry) => entry.filters.some((filter) => filter.filterType === 'ABI' && filter.value === abi));
    if (!element || !Number.isSafeInteger(element.versionCode) || element.versionCode <= 0 || !element.versionName) {
      throw new Error(`Missing or invalid APK metadata for ${abi}`);
    }
    if (element.outputFile !== `app-${abi}-release.apk`) throw new Error(`Unexpected APK filename for ${abi}`);
    const file = join(apkDirectory, element.outputFile);
    const bytes = readFileSync(file);
    if (bytes.length === 0) throw new Error(`Empty APK for ${abi}`);
    const key = `${prefix}/maimaid-${abi}.apk`;
    objects.push({ key, file, contentType: 'application/vnd.android.package-archive', size: bytes.length, sha256: sha256(bytes) });
    return { abi, url: `${PUBLIC_BASE}/${key}`, size: bytes.length, sha256: sha256(bytes) };
  });
  const first = metadata.elements[0];
  if (metadata.elements.some((entry) => entry.versionCode !== first.versionCode || entry.versionName !== first.versionName)) {
    throw new Error('APK versions do not match');
  }
  const manifest = {
    schemaVersion: 1,
    packageName: metadata.applicationId,
    versionName: first.versionName,
    versionCode: first.versionCode,
    minSdk: 29,
    branch,
    sha,
    author: commits[0].author,
    builtAt: new Date(builtAt).toISOString(),
    historyUrl: `${PUBLIC_BASE}/${prefix}/history.json`,
    artifacts,
  };
  mkdirSync(outputDirectory, { recursive: true });
  for (const [name, value] of [['history', { schemaVersion: 1, commits }], ['manifest', manifest]]) {
    const file = join(outputDirectory, `${name}.json`);
    const bytes = Buffer.from(`${JSON.stringify(value)}\n`);
    writeFileSync(file, bytes);
    objects.push({ key: `${prefix}/${name}.json`, file, contentType: 'application/json', size: bytes.length, sha256: sha256(bytes) });
  }
  return { manifest, objects };
}

function validateKeys(keys) {
  if (!Array.isArray(keys) || keys.some((key) => typeof key !== 'string' || !/^android\/ci\/builds\/\d+-\d+\/(?:maimaid-(?:arm64-v8a|armeabi-v7a|x86_64|x86)\.apk|history\.json|manifest\.json)$/.test(key))) {
    throw new Error('Unsafe publication cleanup paths');
  }
  return keys;
}

function manifestKeys(manifest) {
  if (!manifest) return [];
  const urls = [...manifest.artifacts.map((artifact) => artifact.url), manifest.historyUrl, manifest.historyUrl.replace(/history\.json$/, 'manifest.json')];
  return validateKeys(urls.map((url) => {
    if (!url.startsWith(`${PUBLIC_BASE}/`)) throw new Error('Unexpected existing artifact origin');
    return url.slice(PUBLIC_BASE.length + 1);
  }));
}

// latest.json is the commit point. The journal recovers cleanup after an interrupted run.
export async function publishBundle(bundle, { upload, verify, readLatest, readJournal, writeJournal, remove, clearJournal }) {
  if (bundle.manifest.branch !== 'main') return false;
  const latest = await readLatest();
  if (latest && (latest.schemaVersion !== 1 || latest.packageName !== bundle.manifest.packageName || !Number.isSafeInteger(latest.versionCode))) {
    throw new Error('Invalid existing update manifest');
  }
  const previousKeys = manifestKeys(latest);
  const interruptedKeys = validateKeys(await readJournal());
  for (const key of interruptedKeys) if (!previousKeys.includes(key)) await remove(key);
  if (interruptedKeys.length > 0) await clearJournal();
  if (latest && latest.versionCode >= bundle.manifest.versionCode) return false;
  const currentKeys = validateKeys(bundle.objects.map((object) => object.key));
  await writeJournal([...new Set([...previousKeys, ...currentKeys])]);
  for (const object of bundle.objects) await upload(object, 'public, max-age=31536000, immutable');
  for (const object of bundle.objects) await verify(object);
  const manifestObject = bundle.objects.find((object) => object.key.endsWith('/manifest.json'));
  const latestObject = { ...manifestObject, key: LATEST_KEY };
  await upload(latestObject, 'no-store, max-age=0');
  await verify(latestObject);
  for (const key of previousKeys) if (!currentKeys.includes(key)) await remove(key);
  await clearJournal();
  return true;
}

function commitHistory() {
  const output = execFileSync('git', ['log', '--topo-order', '--format=%H%x00%P%x00%s%x00%an', 'HEAD'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return output.trimEnd().split('\n').map((line) => {
    const [sha, parents, title, author] = line.split('\0');
    return { sha, parents: parents ? parents.split(' ') : [], title, author };
  });
}

async function remoteFetch(key) {
  const response = await fetch(`${PUBLIC_BASE}/${key}?publication=${Date.now()}`, { headers: { 'Cache-Control': 'no-cache' }, signal: AbortSignal.timeout(120_000) });
  return response;
}

async function main() {
  if (process.env.GITHUB_REF_NAME !== 'main') {
    console.log('Non-main APKs are retained in GitHub Artifacts only.');
    return;
  }
  const outputDirectory = resolve('android/app/build/ci-update');
  const bundle = createBundle({
    apkDirectory: resolve('android/app/build/outputs/apk/release'),
    outputDirectory,
    runId: process.env.GITHUB_RUN_ID,
    attempt: process.env.GITHUB_RUN_ATTEMPT,
    branch: process.env.GITHUB_REF_NAME,
    sha: process.env.GITHUB_SHA,
    builtAt: process.env.MAIMAID_BUILD_TIME,
    commits: commitHistory(),
  });
  const upload = async (object, cacheControl) => {
      execFileSync('pnpm', ['exec', 'wrangler', 'r2', 'object', 'put', `${BUCKET}/${object.key}`, '--remote', '--file', object.file, '--content-type', object.contentType, '--cache-control', cacheControl], { cwd: resolve('static-builder'), stdio: 'inherit' });
  };
  const remove = async (key) => {
    execFileSync('pnpm', ['exec', 'wrangler', 'r2', 'object', 'delete', `${BUCKET}/${key}`, '--remote'], { cwd: resolve('static-builder'), stdio: 'inherit' });
  };
  const readJson = async (key) => {
    const response = await remoteFetch(key);
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`Unable to read ${key}: HTTP ${response.status}`);
    return response.json();
  };
  const advanced = await publishBundle(bundle, {
    upload,
    remove,
    readJournal: async () => (await readJson(JOURNAL_KEY))?.keys ?? [],
    writeJournal: async (keys) => {
      const file = join(outputDirectory, 'publication.json');
      writeFileSync(file, JSON.stringify({ keys }));
      await upload({ key: JOURNAL_KEY, file, contentType: 'application/json' }, 'no-store, max-age=0');
    },
    clearJournal: async () => remove(JOURNAL_KEY),
    verify: async (object) => {
      for (let attempt = 0; ; attempt++) {
        try {
          const response = await remoteFetch(object.key);
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const bytes = Buffer.from(await response.arrayBuffer());
          if (bytes.length !== object.size || sha256(bytes) !== object.sha256) throw new Error('Size or SHA-256 mismatch');
          return;
        } catch (error) {
          if (attempt >= 3) throw new Error(`Anonymous verification failed for ${object.key}`, { cause: error });
          await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)));
        }
      }
    },
    readLatest: async () => readJson(LATEST_KEY),
  });
  console.log(advanced ? 'Published main update; previous build removed.' : 'Main update manifest is already current.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => { console.error(error); process.exitCode = 1; });
}
