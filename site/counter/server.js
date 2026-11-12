'use strict';

/**
 * The click counter behind kubo.gemmi-e.com.
 *
 * Counts presses on the download, GitHub and portfolio buttons, and nothing else. It is deliberately the
 * smallest thing that answers the question "is anybody pressing these": no database, no dependencies, no
 * framework. One JSON file of integers.
 *
 * What it does NOT store, on purpose, and this is the whole design:
 *
 *   - no IP address
 *   - no user agent
 *   - no cookie, no identifier of any kind
 *   - no timestamp per click, only a day bucket
 *   - nothing that could tell two visitors apart
 *
 * Kubo's entire claim is that nothing about you leaves your machine. A site that counted its visitors
 * individually while the app promised the opposite would be the kind of contradiction somebody finds and
 * screenshots. Counts are enough to decide what to build next; identities were never needed for that.
 *
 * It listens on the loopback address only. nginx is what the internet talks to.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.KUBO_COUNTER_PORT || 8787);
const FILE = process.env.KUBO_COUNTER_FILE || path.join(__dirname, 'counts.json');

/**
 * The only names that may ever be counted.
 *
 * An allowlist rather than "whatever was posted". The name becomes a key in a file this process writes, so
 * accepting free text would let anyone fill the disk with keys of their choosing, one request at a time.
 */
const ALLOWED = new Set([
  'download-exe',
  'download-zip',
  'download-deb',
  'download-rpm',
  'download-targz',
  'github',
  'portfolio'
]);

/** Bodies are a few dozen bytes. Anything larger is not a click. */
const MAX_BODY = 512;

/** How long to let presses gather before writing. A busy minute should not be a hundred disk writes. */
const FLUSH_MS = 5000;

let counts = load();
let dirty = false;

function load() {
  try {
    const read = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    return read && typeof read === 'object' ? read : empty();
  } catch (missingOrBroken) {
    return empty();
  }
}

function empty() {
  return { since: today(), total: {}, days: {} };
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Writes through a temporary file and a rename.
 *
 * A rename within one directory is atomic, so a reader never sees half a file and a crash mid-write never
 * leaves the counts truncated. Writing in place would risk both.
 */
function flush() {
  if (!dirty) {
    return;
  }
  dirty = false;
  const temporary = FILE + '.tmp';
  try {
    fs.writeFileSync(temporary, JSON.stringify(counts, null, 2));
    fs.renameSync(temporary, FILE);
  } catch (cannotWrite) {
    // Keep counting in memory. A disk that cannot be written to is not a reason to stop answering.
    dirty = true;
    console.error('counter: could not write ' + FILE + ': ' + cannotWrite.message);
  }
}

function bump(what) {
  const day = today();
  counts.total[what] = (counts.total[what] || 0) + 1;
  if (!counts.days[day]) {
    counts.days[day] = {};
  }
  counts.days[day][what] = (counts.days[day][what] || 0) + 1;
  dirty = true;
}

function readBody(request, done) {
  let body = '';
  let over = false;
  request.on('data', function (chunk) {
    if (over) {
      return;
    }
    body += chunk;
    if (body.length > MAX_BODY) {
      over = true;
      // Stop reading rather than buffering whatever somebody decided to send.
      request.destroy();
    }
  });
  request.on('end', function () {
    done(over ? null : body);
  });
  request.on('error', function () {
    done(null);
  });
}

const server = http.createServer(function (request, response) {
  const url = (request.url || '').split('?')[0];

  if (request.method === 'POST' && url === '/count') {
    readBody(request, function (body) {
      let what = null;
      try {
        what = JSON.parse(body || '{}').what;
      } catch (notJson) {
        what = null;
      }
      // No error page for a bad name. Nothing is counted, and the browser is told it was heard: a visitor
      // pressing a download button must never see a failure from a counter.
      if (typeof what === 'string' && ALLOWED.has(what)) {
        bump(what);
      }
      response.writeHead(204).end();
    });
    return;
  }

  if (request.method === 'GET' && url === '/counts') {
    response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store'
    }).end(JSON.stringify(counts, null, 2));
    return;
  }

  response.writeHead(404).end();
});

setInterval(flush, FLUSH_MS).unref();
['SIGINT', 'SIGTERM'].forEach(function (signal) {
  process.on(signal, function () {
    flush();
    process.exit(0);
  });
});

// Loopback only. Everything from outside arrives through nginx, which is what does the rate limiting.
server.listen(PORT, '127.0.0.1', function () {
  console.log('counter: listening on 127.0.0.1:' + PORT + ', writing ' + FILE);
});
