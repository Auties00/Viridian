// What generate.mjs and read.mjs share: serving the fixture page on 127.0.0.1, a page of another site that embeds it,
// and running Chrome on a user data directory with IndexedDB and DOM Storage both on one backend.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const here = path.dirname(fileURLToPath(import.meta.url));

// The site whose page embeds the fixture page in a frame, so that the frame has a partitioned storage key: localhost is
// a site other than 127.0.0.1, whatever the ports
export const EMBEDDER_SITE = 'http://localhost';

// Serves fixture.html and fixture.js on `port`, or on a free port for 0, and on a free port of the embedder's site a
// page that embeds the fixture page. The address rather than localhost gives the fixture's origin a dot in its host, as
// most sites' have
export async function serve(port) {
  const page = fs.readFileSync(path.join(here, 'fixture.html'));
  const script = fs.readFileSync(path.join(here, 'fixture.js'));
  const server = http.createServer((request, response) => {
    const isScript = request.url === '/fixture.js';
    response.writeHead(200, { 'content-type': isScript ? 'text/javascript' : 'text/html' });
    response.end(isScript ? script : page);
  });
  await new Promise((resolve, reject) => server.once('error', reject).listen(port, '127.0.0.1', resolve));
  const site = `http://127.0.0.1:${server.address().port}`;
  const embedderServer = http.createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(`<!DOCTYPE html><title>Embedder</title><iframe src="${site}/fixture.html"></iframe>`);
  });
  await new Promise((resolve, reject) => embedderServer.once('error', reject).listen(0, '127.0.0.1', resolve));
  const close = () => { server.close(); embedderServer.close(); };
  return { server: { close }, site, embedder: `${EMBEDDER_SITE}:${embedderServer.address().port}` };
}

// Opens a tab at the embedder's page and returns the fixture page's frame in it, once its script has loaded
export async function embedded(browser, embedder, site) {
  const tab = await browser.newPage();
  tab.on('console', message => console.log('frame:', message.text()));
  await tab.goto(`${embedder}/embed.html`);
  const frame = await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error('The embedded fixture page did not load')), 30000);
    const look = () => {
      const found = tab.frames().find(candidate => candidate.url().startsWith(site));
      if (found) {
        clearTimeout(deadline);
        resolve(found);
      } else {
        setTimeout(look, 100);
      }
    };
    look();
  });
  await frame.waitForFunction(() => typeof window.writePartitioned === 'function');
  return { tab, frame };
}

// Starts Chrome on `userDataDir` with both kinds of storage on `backend`, whatever the build's own defaults and field
// trials are, and returns the browser with a tab at the fixture page of `site`
export async function open(chrome, userDataDir, backend, site) {
  const features = backend === 'sqlite'
    ? ['--enable-features=IdbSqliteBackingStore,DomStorageSqlite']
    : ['--disable-features=IdbSqliteBackingStore,IdbSqliteOnDiskRollout,DomStorageSqlite,DomStorageSqliteNewDatabases,DomStorageSqliteMigration'];
  const browser = await puppeteer.launch({
    executablePath: chrome,
    userDataDir,
    headless: true,
    // Ubuntu's AppArmor denies the unprivileged user namespaces Chrome's sandbox needs on CI runners
    args: [...features, '--no-first-run', '--no-default-browser-check', ...(process.platform === 'linux' ? ['--no-sandbox'] : [])],
  });
  const tab = await browser.newPage();
  tab.on('console', message => console.log('page:', message.text()));
  tab.on('pageerror', error => console.error('page error:', error));
  await tab.goto(`${site}/fixture.html`);
  return { browser, tab };
}

export function usage(script, extra) {
  console.error(`Usage: node ${script} <chrome executable> <user data directory> <leveldb|sqlite> ${extra}`);
  process.exit(2);
}
