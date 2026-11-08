#!/usr/bin/env node
// PoW 服务端压测:只测库负责的两段成本——签发(随机数 + 摘要 + 存储写)与校验(摘要比对 + 存储读改)。
// 解题成本在客户端浏览器里,不计入这里,否则测的是本脚本的 CPU。
//
// 用法:
//   node tools/pow-bench.mjs                       # 默认 http://localhost:8080,32 并发,20 秒
//   B=http://host:8080 CONC=64 DURATION=30000 node tools/pow-bench.mjs
//
// 前置:demo 的签发限流会先撞 429,压测务必带 ISSUE_RATE_DISABLED=true 启动 demo。
//
// 读数的坑:签发的 p90/p99 对测试进程自己的 GC 很敏感,单发会被 GC 停顿污染成上百毫秒。
// 所以每轮并行发一批(默认 8 个),再统一串行求解校验,把脚本抖动摊掉。

const B = process.env.B || 'http://localhost:8080';
const CONC = Number(process.env.CONC || 32);
const BATCH = Number(process.env.BATCH || 8);
const DURATION_MS = Number(process.env.DURATION || 20000);

const encoder = new TextEncoder();
const sha256 = async (text) => {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text)));
  return [...digest].map((b) => b.toString(16).padStart(2, '0')).join('');
};

const issueLatency = [];
const verifyLatency = [];
const counts = { issued: 0, issueFailed: 0, verified: 0, verifyFailed: 0, unsolved: 0 };
const statuses = {};

const bump = (key, status) => {
  counts[key] += 1;
  if (status) statuses[status] = (statuses[status] || 0) + 1;
};

async function issueOnce() {
  const started = performance.now();
  const res = await fetch(B + '/pow/challenge');
  issueLatency.push(performance.now() - started);
  if (!res.ok) {
    bump('issueFailed', 'HTTP ' + res.status);
    return null;
  }
  bump('issued');
  return res.json();
}

async function solve(challenge) {
  for (let n = 0; n <= challenge.maxnumber; n++) {
    if (await sha256(challenge.salt + n) === challenge.challenge) return n;
  }
  return -1;
}

async function verifyOnce(challenge) {
  const number = await solve(challenge);
  if (number < 0) {
    bump('unsolved');
    return;
  }
  const payload = Buffer.from(JSON.stringify({
    algorithm: challenge.algorithm,
    challenge: challenge.challenge,
    number,
    salt: challenge.salt,
    signature: challenge.signature,
  })).toString('base64');
  const started = performance.now();
  const res = await fetch(B + '/pow/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payload }),
  });
  verifyLatency.push(performance.now() - started);
  const body = await res.json();
  bump(body.success ? 'verified' : 'verifyFailed', body.status);
}

async function worker() {
  while (Date.now() - startedAt < DURATION_MS) {
    const batch = (await Promise.all(Array.from({ length: BATCH }, issueOnce))).filter(Boolean);
    await Promise.all(batch.map(verifyOnce));
  }
}

const startedAt = Date.now();
await Promise.all(Array.from({ length: CONC }, worker));
const seconds = (Date.now() - startedAt) / 1000;

const percentile = (values, p) => {
  if (values.length === 0) return 0;
  const sorted = values.slice().sort((a, b) => a - b);
  return Number(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(2));
};

const row = (op, values) => ({
  op,
  samples: values.length,
  rps: Number((values.length / seconds).toFixed(1)),
  p50: percentile(values, 0.5),
  p90: percentile(values, 0.9),
  p99: percentile(values, 0.99),
  max: percentile(values, 1),
});

console.log(JSON.stringify({ target: B, concurrency: CONC, seconds: Number(seconds.toFixed(1)), counts, statuses }));
console.table([row('issue', issueLatency), row('verify', verifyLatency)]);
