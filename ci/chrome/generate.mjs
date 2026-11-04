// Has a real Chrome write a profile for Viridian's tests to read: it serves fixture.html on localhost, lets the page
// write IndexedDB, localStorage and sessionStorage, records what the page reads back as the manifest, and closes the
// browser so that it flushes its storage.
//
// Usage: node generate.mjs <chrome executable> <user data directory> <leveldb|sqlite> <manifest file>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const [chrome, userDataDir, backend, manifestFile] = process.argv.slice(2);
if (!chrome || !userDataDir || !['leveldb', 'sqlite'].includes(backend) || !manifestFile) {
  console.error('Usage: node generate.mjs <chrome> <user data dir> <leveldb|sqlite> <manifest>');
  process.exit(2);
}

const here = path.dirname(fileURLToPath(import.meta.url));
const page = fs.readFileSync(path.join(here, 'fixture.html'));
const script = fs.readFileSync(path.join(here, 'fixture.js'));
const server = http.createServer((request, response) => {
  if (request.url === '/fixture.js') {
    response.writeHead(200, { 'content-type': 'text/javascript' });
    response.end(script);
  } else {
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(page);
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;

const features = backend === 'sqlite'
  ? ['--enable-features=IdbSqliteBackingStore']
  : ['--disable-features=IdbSqliteBackingStore,IdbSqliteOnDiskRollout'];
const browser = await puppeteer.launch({
  executablePath: chrome,
  userDataDir,
  headless: true,
  // Ubuntu's AppArmor denies the unprivileged user namespaces Chrome's sandbox needs on CI runners
  args: [...features, '--no-first-run', '--no-default-browser-check', ...(process.platform === 'linux' ? ['--no-sandbox'] : [])],
});
let manifest;
try {
  const tab = await browser.newPage();
  tab.on('console', message => console.log('page:', message.text()));
  tab.on('pageerror', error => console.error('page error:', error));
  await tab.goto(`http://localhost:${port}/fixture.html`);
  manifest = await tab.evaluate(() => window.writeFixture());
  // DOM Storage commits on a delay of a few seconds. The tab stays open: closing it could drop its sessionStorage,
  // which a browser that closes keeps for session restore
  await new Promise(resolve => setTimeout(resolve, 8000));
} finally {
  await browser.close();
  server.close();
}

const origin = `http_localhost_${port}`;
const indexedDb = path.join(userDataDir, 'Default', 'IndexedDB');
const expected = backend === 'sqlite' ? path.join(indexedDb, origin) : path.join(indexedDb, `${origin}.indexeddb.leveldb`);
if (!fs.existsSync(expected)) {
  console.error(`Chrome did not write the ${backend} store: ${expected} is missing`);
  console.error('IndexedDB holds:', fs.existsSync(indexedDb) ? fs.readdirSync(indexedDb) : 'nothing');
  process.exit(1);
}
fs.writeFileSync(manifestFile, `origin\thttp://localhost:${port}\nbackend\t${backend}\n${manifest}`);
console.log(`Chrome wrote a ${backend} profile for http://localhost:${port}: ${manifest.split('\n').length} manifest lines`);
