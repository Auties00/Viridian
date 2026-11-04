// Has a real Chrome write a profile for Viridian's tests to read: it serves fixture.html, lets the page write
// IndexedDB, localStorage and sessionStorage, records what the page reads back as the manifest, and closes the browser
// so that it flushes its storage.
//
// Usage: node generate.mjs <chrome executable> <user data directory> <leveldb|sqlite> <manifest file>
import fs from 'node:fs';
import path from 'node:path';
import { EMBEDDER_SITE, embedded, open, serve, usage } from './chrome.mjs';

const [chrome, userDataDir, backend, manifestFile] = process.argv.slice(2);
if (!chrome || !userDataDir || !['leveldb', 'sqlite'].includes(backend) || !manifestFile) {
  usage('generate.mjs', '<manifest>');
}

const { server, site, embedder } = await serve(0);
const { browser, tab } = await open(chrome, userDataDir, backend, site);
let manifest;
try {
  manifest = await tab.evaluate(() => window.writeFixture());
  const { frame } = await embedded(browser, embedder, site);
  manifest += '\n' + await frame.evaluate(topLevelSite => window.writePartitioned(topLevelSite), EMBEDDER_SITE);
  // DOM Storage commits on a delay of a few seconds. The tab stays open: closing it could drop its sessionStorage,
  // which a browser that closes keeps for session restore
  await new Promise(resolve => setTimeout(resolve, 8000));
} finally {
  await browser.close();
  server.close();
}

const origin = new URL(site).host.replace(':', '_');
const profile = path.join(userDataDir, 'Default');
const indexedDb = path.join(profile, 'IndexedDB');
const expected = backend === 'sqlite'
  ? [path.join(indexedDb, `http_${origin}`), path.join(profile, 'LocalStorage'), path.join(profile, 'SessionStorage')]
  : [path.join(indexedDb, `http_${origin}.indexeddb.leveldb`), path.join(profile, 'Local Storage', 'leveldb'), path.join(profile, 'Session Storage')];
const missing = expected.filter(store => !fs.existsSync(store));
if (missing.length > 0) {
  console.error(`Chrome did not write the ${backend} stores: ${missing.join(', ')} missing`);
  console.error('The profile holds:', fs.existsSync(profile) ? fs.readdirSync(profile) : 'nothing');
  console.error('IndexedDB holds:', fs.existsSync(indexedDb) ? fs.readdirSync(indexedDb) : 'nothing');
  process.exit(1);
}
if (!fs.existsSync(path.join(profile, 'WebStorage', 'QuotaManager'))) {
  console.error('Chrome did not write the quota database the storage buckets need');
  process.exit(1);
}
fs.writeFileSync(manifestFile, `origin\t${site}\nbackend\t${backend}\n${manifest}`);
console.log(`Chrome wrote a ${backend} profile for ${site}: ${manifest.split('\n').length} manifest lines`);
