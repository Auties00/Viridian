// Has a real Chrome read a profile Viridian wrote: it serves fixture.html on the manifest's origin, has the page, and
// the page in a frame another site embeds, describe every database, index, key generator and localStorage item of the
// default bucket, the named bucket and the partitioned key, and fails unless that matches the manifest Chrome made
// when it wrote the original profile. sessionStorage belongs to a browsing session, which a copy does not restore, so it is
// left out. Then Chrome writes to the copy, restarts, and must find what it wrote: a store Viridian made stays usable.
//
// Usage: node read.mjs <chrome executable> <user data directory> <leveldb|sqlite> <manifest of the original>
import fs from 'node:fs';
import { EMBEDDER_SITE, embedded, open, serve, usage } from './chrome.mjs';

const [chrome, userDataDir, backend, manifestFile] = process.argv.slice(2);
if (!chrome || !userDataDir || !['leveldb', 'sqlite'].includes(backend) || !manifestFile) {
  usage('read.mjs', '<manifest of the original>');
}

const manifest = fs.readFileSync(manifestFile, 'utf8').split('\n');
const site = manifest[0].split('\t')[1];
const compared = line => /^(@[^\t]+\t)?(database|store|record|index|generator|local)\t/.test(line);
const isLocal = line => /^(@[^\t]+\t)?local\t/.test(line);
// localStorage lists its keys in no fixed order
const sortLocal = lines => [...lines.filter(line => !isLocal(line)), ...lines.filter(isLocal).sort()];
const { server, embedder } = await serve(Number(new URL(site).port));

async function inChrome(action, ...args) {
  const { browser, tab } = await open(chrome, userDataDir, backend, site);
  try {
    return await tab.evaluate(action, ...args);
  } finally {
    await browser.close();
  }
}

// The description of the profile from the page, then from the page in a frame another site embeds
async function describe() {
  const { browser, tab } = await open(chrome, userDataDir, backend, site);
  try {
    const own = await tab.evaluate(() => window.describeProfile());
    const { frame } = await embedded(browser, embedder, site);
    return own + '\n' + await frame.evaluate(topLevelSite => window.describePartitioned(topLevelSite), EMBEDDER_SITE);
  } finally {
    await browser.close();
  }
}

try {
  const want = sortLocal(manifest.slice(2).filter(compared));
  const got = sortLocal((await describe()).split('\n').filter(compared));
  for (let i = 0; i < Math.max(want.length, got.length); i++) {
    if (want[i] !== got[i]) {
      const cut = line => line === undefined ? '<none>' : line.length > 400 ? `${line.slice(0, 400)}...` : line;
      console.error(`Line ${i} differs:\n  original: ${cut(want[i])}\n  copy:     ${cut(got[i])}`);
      process.exit(1);
    }
  }
  console.log(`Chrome read the ${backend} copy back as written: ${got.length} lines`);

  // A record in a store with out-of-line keys, and a localStorage item; DOM Storage commits on a delay, hence the wait
  const stored = await inChrome(async () => {
    const request = r => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
    for (const { name } of await indexedDB.databases()) {
      const db = await request(indexedDB.open(name));
      const storeName = [...db.objectStoreNames].find(candidate => {
        const store = db.transaction(candidate).objectStore(candidate);
        return store.keyPath === null && !store.autoIncrement;
      });
      if (storeName) {
        const transaction = db.transaction(storeName, 'readwrite');
        transaction.objectStore(storeName).put('written after the copy', 'viridian mutation');
        await new Promise((resolve, reject) => { transaction.oncomplete = resolve; transaction.onerror = () => reject(transaction.error); });
        db.close();
        localStorage.setItem('viridian mutation', 'written after the copy');
        await new Promise(resolve => setTimeout(resolve, 8000));
        return [name, storeName];
      }
      db.close();
    }
    throw new Error('No store with out-of-line keys to write to');
  });
  const found = await inChrome(async location => {
    const request = r => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
    const [name, storeName] = location;
    const db = await request(indexedDB.open(name));
    const value = await request(db.transaction(storeName).objectStore(storeName).get('viridian mutation'));
    db.close();
    return [value, localStorage.getItem('viridian mutation')];
  }, stored);
  if (found[0] !== 'written after the copy' || found[1] !== 'written after the copy') {
    console.error(`What Chrome wrote to the copy did not survive a restart: ${JSON.stringify(found)}`);
    process.exit(1);
  }
  console.log(`Chrome kept writing to the copy: ${stored.join(' / ')} and localStorage survived a restart`);
} finally {
  server.close();
}
