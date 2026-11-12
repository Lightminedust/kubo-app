# The click counter

Counts presses on the download, GitHub and portfolio buttons of kubo.gemmi-e.com. Nothing else.

It exists because Kubo is used offline and tells you nothing, and the GitHub download numbers count robots
as readily as people. A press on a button is a person who decided something.

## What it stores

One file, `counts.json`, of integers:

```json
{
  "since": "2026-10-17",
  "total": { "download-exe": 41, "github": 7, "portfolio": 3 },
  "days":  { "2026-10-17": { "download-exe": 2 } }
}
```

**What it does not store, deliberately:** no IP address, no user agent, no cookie, no identifier of any
kind, no per-click timestamp. There is nothing in that file that could tell two visitors apart, and nothing
that needs a cookie banner.

That is not an accident. Kubo's whole claim is that nothing about you leaves your machine. A site that
profiled its visitors while the app promised the opposite is a contradiction somebody would find and
screenshot. Counts answer "is anybody pressing these", which was the only question.

## Install it on the server

Node 18 or later. No dependencies.

```bash
sudo mkdir -p /opt/kubo-counter
sudo cp server.js /opt/kubo-counter/
sudo chown -R "$USER" /opt/kubo-counter

pm2 start /opt/kubo-counter/server.js --name kubo-counter
pm2 save
```

It listens on `127.0.0.1:8787` and writes `/opt/kubo-counter/counts.json`. Both can be changed:

```bash
KUBO_COUNTER_PORT=9000 KUBO_COUNTER_FILE=/var/lib/kubo/counts.json pm2 start ...
```

## Put it behind nginx

In the `server` block for `kubo.gemmi-e.com`:

```nginx
# The rate limit is the whole defence. A counter reachable from the internet without one is a number
# anybody can set to whatever they like with a loop and curl.
limit_req_zone $binary_remote_addr zone=kubocount:1m rate=30r/m;

location = /api/count {
    limit_req zone=kubocount burst=10 nodelay;
    proxy_pass http://127.0.0.1:8787/count;
    proxy_set_header Host $host;
    access_log off;
}
```

`limit_req_zone` goes in the `http` block, not inside `server`. Reload with `sudo nginx -t && sudo systemctl reload nginx`.

## Read the numbers

The service answers `GET /counts`. **Do not proxy that one publicly** unless you want the figures to be
public. Read it over SSH instead:

```bash
curl -s localhost:8787/counts | jq
```

Or just `cat /opt/kubo-counter/counts.json`.

## Switch it off

In `index.html`, set `COUNTER_URL` to `""`. Nothing is sent, and nothing on the page changes.

## What the numbers are worth

They are an indicator, not a measurement.

- Anyone can inflate them with `curl` in a loop, within the rate limit. Nothing here authenticates a click,
  and nothing could, short of the kind of tracking this deliberately does not do.
- A press is not an install. Somebody who clicks and cancels the download is counted.
- They undercount: a visitor blocking scripts presses the button and is never counted. The button still
  works, which is the right way round.

What they are good for is comparison. Which of the three gets pressed, which package people take, whether a
post somewhere moved anything. That is the question, and counts answer it.
