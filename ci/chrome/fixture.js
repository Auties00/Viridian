// Writes IndexedDB, localStorage and sessionStorage with every kind of value Viridian reads, then reads it all back
// and describes it in the canonical form ChromeProfileTest builds from Viridian's own reading. The two descriptions
// must match line for line. The page also writes IndexedDB into a named storage bucket, and, loaded in a frame another
// site embeds, IndexedDB and localStorage of its partitioned storage key; the lines of those contexts start with
// "@bucket:<name>" or "@partitioned:<top-level site>" and a tab.

const hex16 = string => {
  let result = '';
  for (let i = 0; i < string.length; i++) {
    result += string.charCodeAt(i).toString(16).padStart(4, '0');
  }
  return result;
};

const hexBytes = bytes => Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('');

const number = value => Object.is(value, -0) ? '-0' : String(value);

const indexKey = key => /^(0|[1-9][0-9]*)$/.test(key) && Number(key) < 4294967295;

// The canonical description of a value: objects get an id when first met, and later meetings name the id
async function describe(value, seen = new Map()) {
  if (value === undefined) return 'u';
  if (value === null) return 'n';
  switch (typeof value) {
    case 'boolean': return value ? 'b1' : 'b0';
    case 'number': return 'N' + number(value);
    case 'bigint': return 'Z' + value.toString();
    case 'string': return 'S' + hex16(value);
  }
  if (seen.has(value)) return '@' + seen.get(value);
  seen.set(value, seen.size);
  if (Array.isArray(value)) {
    const keys = Object.keys(value);
    let result = 'A' + value.length + '[';
    for (const key of keys.filter(indexKey)) result += key + '=' + await describe(value[key], seen) + ';';
    result += '|';
    for (const key of keys.filter(key => !indexKey(key))) result += hex16(key) + '=' + await describe(value[key], seen) + ';';
    return result + ']';
  }
  if (value instanceof Date) return 'D' + number(value.getTime());
  if (value instanceof RegExp) return 'R' + hex16(value.source) + '/' + value.flags;
  if (value instanceof Map) {
    let result = 'M{';
    for (const [key, entry] of value) result += await describe(key, seen) + '=>' + await describe(entry, seen) + ';';
    return result + '}';
  }
  if (value instanceof Set) {
    let result = 'T{';
    for (const entry of value) result += await describe(entry, seen) + ';';
    return result + '}';
  }
  if (value instanceof ArrayBuffer) return 'B' + hexBytes(value) + (value.resizable ? '~' + value.maxByteLength : '');
  if (ArrayBuffer.isView(value)) {
    const type = value.constructor.name;
    return 'V' + type + '(' + value.byteOffset + ',' + value.byteLength + ',' + await describe(value.buffer, seen) + ')';
  }
  if (value instanceof Boolean || value instanceof Number || value instanceof String || value instanceof BigInt) {
    return 'W' + await describe(value.valueOf(), seen);
  }
  if (value instanceof DOMException) return 'X' + hex16(value.name) + ',' + hex16(value.message);
  if (value instanceof Error) {
    let result = 'E' + value.name + '(' + hex16(value.message) + ')';
    if (Object.hasOwn(value, 'cause')) result += 'c' + await describe(value.cause, seen);
    return result;
  }
  if (value instanceof CryptoKey) {
    const format = value.type === 'secret' ? 'raw' : value.type === 'private' ? 'pkcs8' : 'spki';
    const data = value.extractable ? hexBytes(await crypto.subtle.exportKey(format, value)) : '-';
    return 'K' + value.type + ',' + value.algorithm.name + ',' + [...value.usages].sort().join('+') + ',' + (value.extractable ? 1 : 0) + ',' + data;
  }
  if (value instanceof File) {
    return 'F' + hex16(value.name) + ',' + hex16(value.type) + ',' + value.size + ',' + value.lastModified + ',' + hexBytes(await value.arrayBuffer());
  }
  if (value instanceof Blob) return 'L' + hex16(value.type) + ',' + value.size + ',' + hexBytes(await value.arrayBuffer());
  if (value instanceof ImageData) return 'I' + value.width + 'x' + value.height + ',' + value.colorSpace + ',' + hexBytes(value.data.buffer);
  if (value instanceof DOMMatrixReadOnly) {
    const values = value.is2D ? [value.a, value.b, value.c, value.d, value.e, value.f]
      : [value.m11, value.m12, value.m13, value.m14, value.m21, value.m22, value.m23, value.m24, value.m31, value.m32, value.m33, value.m34, value.m41, value.m42, value.m43, value.m44];
    return 'G' + (value instanceof DOMMatrix ? 'm' : 'M') + values.map(number).join(',');
  }
  if (value instanceof DOMQuad) return 'Q' + [value.p1, value.p2, value.p3, value.p4].map(p => [p.x, p.y, p.z, p.w].map(number).join(',')).join('|');
  if (value instanceof DOMPointReadOnly) return 'P' + (value instanceof DOMPoint ? 'm' : 'M') + [value.x, value.y, value.z, value.w].map(number).join(',');
  if (value instanceof DOMRectReadOnly) return 'C' + (value instanceof DOMRect ? 'm' : 'M') + [value.x, value.y, value.width, value.height].map(number).join(',');
  let result = 'O{';
  for (const key of Object.keys(value)) result += hex16(key) + '=' + await describe(value[key], seen) + ';';
  return result + '}';
}

function describeKey(key) {
  if (typeof key === 'number') return 'N' + number(key);
  if (typeof key === 'string') return 'S' + hex16(key);
  if (key instanceof Date) return 'D' + number(key.getTime());
  if (key instanceof ArrayBuffer) return 'X' + hexBytes(key);
  if (ArrayBuffer.isView(key)) return 'X' + hexBytes(key.buffer.slice(key.byteOffset, key.byteOffset + key.byteLength));
  if (Array.isArray(key)) return '[' + key.map(describeKey).join(';') + ']';
  throw new Error('Not a key: ' + key);
}

function describeKeyPath(path) {
  if (path === null) return '-';
  if (Array.isArray(path)) return '[' + path.map(hex16).join(';') + ']';
  return 'S' + hex16(path);
}

const request = r => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });

function open(name, version, upgrade, factory = indexedDB) {
  const opening = factory.open(name, version);
  opening.onupgradeneeded = () => upgrade(opening.result);
  return request(opening);
}

function done(transaction) {
  return new Promise((resolve, reject) => { transaction.oncomplete = resolve; transaction.onerror = () => reject(transaction.error); transaction.onabort = () => reject(transaction.error); });
}

async function values() {
  const cyclic = { name: 'cycle' };
  cyclic.self = cyclic;
  const shared = { shared: true };
  const holes = [1, , 3];
  holes.extra = 'property';
  const sparse = [];
  sparse[1000000] = 'far';
  sparse[2] = 'near';
  const buffer = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16]).buffer;
  const resizable = new ArrayBuffer(8, { maxByteLength: 64 });
  new Uint8Array(resizable).set([9, 8, 7]);
  const error = new TypeError('bad type', { cause: 'the cause' });
  const selfCaused = new RangeError('loop');
  selfCaused.cause = selfCaused;
  const large = 'large value '.repeat(9000);
  const medium = 'medium value '.repeat(80);

  const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
  const hidden = await crypto.subtle.generateKey({ name: 'AES-CBC', length: 128 }, false, ['encrypt']);
  const hmac = await crypto.subtle.generateKey({ name: 'HMAC', hash: 'SHA-384' }, true, ['sign', 'verify']);
  const ecdsa = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const ed25519 = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const x25519 = await crypto.subtle.generateKey({ name: 'X25519' }, true, ['deriveBits']);
  const rsa = await crypto.subtle.generateKey({ name: 'RSA-PSS', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const hkdf = await crypto.subtle.importKey('raw', new Uint8Array(32).fill(7), 'HKDF', false, ['deriveBits']);

  const bytes = new Uint8Array(1500000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31) & 0xFF;

  return [
    ['undefined', undefined], ['null', null], ['true', true], ['false', false],
    ['int', 42], ['negative', -7], ['big int32', 2147483647], ['uint32', 4294967295], ['double', 1.5],
    ['-0', -0], ['NaN', NaN], ['infinity', -Infinity], ['2^53', 9007199254740992], ['1e21', 1e21], ['tiny', 1.5e-7],
    ['bigint', 123456789012345678901234567890n], ['negative bigint', -42n], ['zero bigint', 0n],
    ['latin1', 'café'], ['two-byte', '你好 😀'], ['lone surrogate', 'a\ud800b'], ['empty', ''],
    ['object', { a: 1, b: { c: [1, 'two', { d: null }] }, 2: 'numeric key', 1.5: 'fractional key' }],
    ['cycle', cyclic], ['shared', [shared, shared, { again: shared }]],
    ['holes', holes], ['sparse', sparse],
    ['map', new Map([['key', 1], [shared, 'object key'], [2, new Map()]])], ['set', new Set([1, 'two', shared])],
    ['date', new Date(1700000000123)], ['invalid date', new Date(NaN)],
    ['regexp', /a+(b)?/dgimsuy], ['unicode sets', /[\p{L}--[a-z]]/v],
    ['wrappers', [new Boolean(false), new Number(-0), new String('boxed'), Object(10n)]],
    ['buffer', buffer.slice(0)], ['views', [new Uint8Array(buffer, 1, 4), new Int16Array(buffer, 2, 2), new Uint8ClampedArray(buffer),
      new Int8Array(buffer, 3, 1), new Uint16Array(buffer, 4, 2), new Int32Array(buffer, 4, 1), new Uint32Array(buffer, 8, 2),
      new Float32Array(buffer, 0, 2), new Float64Array(buffer, 8, 1), new BigInt64Array(buffer, 0, 1), new BigUint64Array(buffer, 8, 1),
      new DataView(buffer, 5, 7), new Float16Array(buffer, 2, 3)]],
    ['resizable', [resizable, new Uint8Array(resizable)]],
    ['errors', [error, selfCaused, new Error('plain'), new SyntaxError('syntax'), new EvalError(''), new URIError('uri'), new ReferenceError('ref')]],
    ['large', large], ['medium', medium],
    ['keys', [aes, hidden, hmac, ecdsa.publicKey, ecdsa.privateKey, ed25519.publicKey, ed25519.privateKey, x25519.privateKey, rsa.privateKey, rsa.publicKey, hkdf]],
    ['blobs', [new Blob(['hello blob'], { type: 'text/plain' }), new File(['file body'], 'name.txt', { type: 'text/plain', lastModified: 1600000000000 }),
      new Blob([bytes], { type: 'application/octet-stream' })]],
    ['geometry', [new DOMPoint(1, 2, 3, 4), new DOMPointReadOnly(5, 6), new DOMRect(1, 2, 3, 4), new DOMRectReadOnly(0, 0, 9, 9),
      new DOMQuad(new DOMPoint(0, 0), new DOMPoint(1, 0), new DOMPoint(1, 1), new DOMPoint(0, 1)),
      new DOMMatrix([1, 2, 3, 4, 5, 6]), new DOMMatrixReadOnly([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 7, 8, 9, 1])]],
    ['image', new ImageData(new Uint8ClampedArray([255, 0, 0, 255, 0, 255, 0, 128]), 2, 1)],
    ['dom exception', new DOMException('it is gone', 'NotFoundError')],
  ];
}

window.writeFixture = async function () {
  const database = await open('viridian', 3, db => {
    db.createObjectStore('values');
    const people = db.createObjectStore('people', { keyPath: 'id' });
    people.createIndex('byName', 'name', { unique: true });
    people.createIndex('tags', 'tags', { multiEntry: true });
    people.createIndex('pair', ['first', 'last']);
    db.createObjectStore('auto', { autoIncrement: true });
    db.createObjectStore('keys');
    db.createObjectStore('empty', { keyPath: ['a', 'b'], autoIncrement: false });
    // A key generator past every key, and generated keys Chromium stores without the value and Blink injects
    db.createObjectStore('counter', { autoIncrement: true });
    const notes = db.createObjectStore('notes', { keyPath: 'id', autoIncrement: true });
    notes.createIndex('byId', 'id', { unique: true });
    notes.createIndex('byText', 'text');
    db.createObjectStore('nested', { keyPath: 'meta.id', autoIncrement: true });
  });
  const entries = await values();
  let transaction = database.transaction(['values', 'people', 'auto', 'keys', 'counter', 'notes', 'nested'], 'readwrite');
  for (const [key, value] of entries) transaction.objectStore('values').put(value, key);
  transaction.objectStore('people').put({ id: 2, name: 'bob', tags: ['b', 'shared'], first: 'Bob', last: 'B' });
  transaction.objectStore('people').put({ id: 1, name: 'alice', tags: ['a', 'shared'], first: 'Alice', last: 'A' });
  for (const value of ['first', 'second', { third: 3 }]) transaction.objectStore('auto').add(value);
  for (const value of ['one', 'two', 'three', 'four', 'five']) transaction.objectStore('counter').add(value);
  for (const value of [{ text: 'generated' }, { text: 'given', id: 10 }, { text: 'after the given one' }]) transaction.objectStore('notes').add(value);
  for (const value of [{ text: 'no meta' }, { meta: { other: 1 } }, { meta: { id: 'a string key' } }]) transaction.objectStore('nested').add(value);
  const keys = [0, -1.5, Infinity, -Infinity, new Date(5), new Date(-5), '', 'a', 'é', '你', '￿', 'a\ud800',
    new Uint8Array([]), new Uint8Array([0]), new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9]), [], [1, 'a', [new Date(0)]], [[[]]]];
  for (const key of keys) transaction.objectStore('keys').put(describeKey(key), key);
  await done(transaction);

  // Overwritten and deleted records, which must not come back
  transaction = database.transaction('values', 'readwrite');
  transaction.objectStore('values').put('overwritten once', 'int');
  transaction.objectStore('values').put(42, 'int');
  transaction.objectStore('values').put('deleted', 'gone');
  transaction.objectStore('values').delete('gone');
  await done(transaction);
  transaction = database.transaction('counter', 'readwrite');
  transaction.objectStore('counter').delete(IDBKeyRange.lowerBound(4));
  await done(transaction);

  const second = await open('second database', 1, db => db.createObjectStore('only').put('one', 1));
  second.close();
  database.close();

  // A named storage bucket, which Chromium keeps in a directory of WebStorage its quota database names
  const drafts = await navigator.storageBuckets.open('drafts');
  const inBucket = await open('in a bucket', 2, db => {
    db.createObjectStore('values');
    db.createObjectStore('generated', { keyPath: 'id', autoIncrement: true }).createIndex('byText', 'text');
  }, drafts.indexedDB);
  transaction = inBucket.transaction(['values', 'generated'], 'readwrite');
  transaction.objectStore('values').put({ draft: 'first', at: new Date(7) }, 'draft');
  transaction.objectStore('values').put(new Blob(['in a bucket'], { type: 'text/plain' }), 'blob');
  for (const text of ['one', 'two']) transaction.objectStore('generated').add({ text });
  await done(transaction);
  inBucket.close();

  localStorage.setItem('latin', 'café');
  localStorage.setItem('unicode', '你好 😀');
  localStorage.setItem('é key', 'ascii value');
  // Past the SQLite backend's 350-byte threshold, so it compresses them
  localStorage.setItem('long latin', 'compressible '.repeat(100));
  localStorage.setItem('long unicode', 'é你 '.repeat(200));
  sessionStorage.setItem('session key', 'session value é你');
  sessionStorage.setItem('long', 'session '.repeat(100));
  return describeProfile();
};

// Writes the storage of the page's partitioned key, as the frame another site embeds it in has
window.writePartitioned = async function (topLevelSite) {
  const db = await open('partitioned', 2, db => {
    db.createObjectStore('values');
    db.createObjectStore('generated', { keyPath: 'id', autoIncrement: true });
  });
  const transaction = db.transaction(['values', 'generated'], 'readwrite');
  transaction.objectStore('values').put({ embedded: true, at: new Date(5), list: [1, 'two'] }, 'state');
  transaction.objectStore('values').put(new File(['partitioned file'], 'frame.txt', { type: 'text/plain', lastModified: 1700000000000 }), 'file');
  transaction.objectStore('generated').add({ text: 'embedded' });
  await done(transaction);
  db.close();
  localStorage.setItem('partitioned item', 'é in a frame');
  return window.describePartitioned(topLevelSite);
};

// Describes the storage of the page's partitioned key: its databases and its localStorage
window.describePartitioned = async function (topLevelSite) {
  const lines = [...await describeDatabases(indexedDB)];
  for (const key of Object.keys(localStorage)) lines.push(['local', hex16(key), hex16(localStorage.getItem(key))].join('\t'));
  return lines.map(line => '@partitioned:' + topLevelSite + '\t' + line).join('\n');
};

// Describes every database of the origin, in name order, and its localStorage and this tab's sessionStorage, then
// the databases of its named bucket: what a profile holds, whoever wrote it
window.describeProfile = async function () {
  const lines = await describeDatabases(indexedDB);
  for (const key of Object.keys(localStorage)) lines.push(['local', hex16(key), hex16(localStorage.getItem(key))].join('\t'));
  for (const key of Object.keys(sessionStorage)) lines.push(['session', hex16(key), hex16(sessionStorage.getItem(key))].join('\t'));
  const drafts = await navigator.storageBuckets.open('drafts');
  for (const line of await describeDatabases(drafts.indexedDB)) lines.push('@bucket:drafts\t' + line);
  return lines.join('\n');
};

// Describes every database `factory` has, in name order: stores, records, index entries and key generators
async function describeDatabases(factory) {
  const lines = [];
  const names = (await factory.databases()).map(info => info.name).sort();
  for (const name of names) {
    const db = await request(factory.open(name));
    lines.push(['database', hex16(db.name), db.version].join('\t'));
    const read = db.transaction(db.objectStoreNames, 'readonly');
    for (const storeName of db.objectStoreNames) {
      const store = read.objectStore(storeName);
      const indexes = [...store.indexNames].map(name => {
        const index = store.index(name);
        return hex16(name) + ':' + describeKeyPath(index.keyPath) + ':' + (index.unique ? 1 : 0) + ':' + (index.multiEntry ? 1 : 0);
      }).join(',');
      lines.push(['store', hex16(db.name), hex16(storeName), describeKeyPath(store.keyPath), store.autoIncrement ? 1 : 0, indexes].join('\t'));
      const storeKeys = await request(store.getAllKeys());
      const storeValues = await request(store.getAll());
      for (let i = 0; i < storeKeys.length; i++) {
        lines.push(['record', hex16(db.name), hex16(storeName), describeKey(storeKeys[i]), await describe(storeValues[i])].join('\t'));
      }
      // Every entry of every index, in index order: what a store copied by another writer must reproduce
      for (const indexName of store.indexNames) {
        const pairs = [];
        await new Promise((resolve, reject) => {
          const cursor = store.index(indexName).openKeyCursor();
          cursor.onsuccess = () => {
            if (!cursor.result) return resolve();
            pairs.push(describeKey(cursor.result.key) + '=' + describeKey(cursor.result.primaryKey));
            cursor.result.continue();
          };
          cursor.onerror = () => reject(cursor.error);
        });
        lines.push(['index', hex16(db.name), hex16(storeName), hex16(indexName), pairs.join(';')].join('\t'));
      }
    }
    // The key each store's generator gives next: added, then rolled back, which IndexedDB rolls the generator back with
    for (const storeName of db.objectStoreNames) {
      const probe = db.transaction(storeName, 'readwrite');
      const aborted = new Promise(resolve => { probe.onabort = resolve; });
      const store = probe.objectStore(storeName);
      if (!store.autoIncrement) {
        probe.abort();
        await aborted;
        continue;
      }
      const key = await request(store.add(store.keyPath === null ? 'probe' : {}));
      probe.abort();
      await aborted;
      lines.push(['generator', hex16(db.name), hex16(storeName), describeKey(key)].join('\t'));
    }
    db.close();
  }
  return lines;
}
