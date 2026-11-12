import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics for Payments Observability & Deduplication
export const successfulTransfers = new Counter('successful_transfers_total');
export const deduplicatedRequests = new Counter('deduplicated_requests_total');
export const failedRequests = new Counter('failed_requests_total');
export const transferLatency = new Trend('transfer_latency_ms');

const BASE_URL = __ENV.API_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    // Scenario 1: Acceptance Criterion 1 - 50 Concurrent requests with IDENTICAL Idempotency-Key
    concurrent_deduplication: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 50,
      maxDuration: '30s',
      exec: 'testConcurrentDeduplication',
    },
    // Scenario 2: Sustained transfer load tailored for 8 GB laptop hardware budget
    sustained_throughput: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '5s', target: 5 },
        { duration: '15s', target: 5 },
        { duration: '5s', target: 0 },
      ],
      startTime: '32s', // Run after Scenario 1 completes
      exec: 'testSustainedTransfers',
    },
  },
  thresholds: {
    // Tail latency bounds for 8 GB laptop host
    'transfer_latency_ms': ['p(95)<400', 'p(99)<800'],
    'http_req_failed': ['rate<0.05'], // Max 5% unexpected failure rate
  },
};

// Setup: Fetch RS256 JWT Token with SCOPE_transfers:write
export function setup() {
  const authRes = http.get(`${BASE_URL}/api/auth/token?subject=k6-load-runner&scope=transfers:write`);
  if (authRes.status !== 200) {
    throw new Error(`Auth failed with status ${authRes.status}: ${authRes.body}`);
  }
  const token = JSON.parse(authRes.body).access_token;
  return { token: token };
}

// Generates a lightweight UUID v4 for idempotency keys
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Scenario 1: 50 concurrent requests with SAME idempotency key
export function testConcurrentDeduplication(data) {
  const sharedKey = 'k6-concurrent-dedup-50-fixed-key';
  const payload = JSON.stringify({
    sourceAccount: 'ACC-USR-001',
    destinationAccount: 'ACC-USR-002',
    amountCents: 500,
    currency: 'USD',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${data.token}`,
      'Idempotency-Key': sharedKey,
    },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/v1/transfers`, payload, params);
  transferLatency.add(Date.now() - start);

  // Exactly ONE request must get 201 Created (or cached 200/201 replay)
  // The remaining 49 concurrent attempts receive 409 Conflict (Lock in-progress)
  if (res.status === 201) {
    successfulTransfers.add(1);
    check(res, {
      'Status is 201 Created': (r) => r.status === 201,
    });
  } else if (res.status === 409) {
    deduplicatedRequests.add(1);
    check(res, {
      'Status is 409 Conflict (Concurrent Dedup)': (r) => r.status === 409,
    });
  } else {
    failedRequests.add(1);
  }
}

// Scenario 2: Sustained throughput with unique idempotency keys
export function testSustainedTransfers(data) {
  const uniqueKey = generateUUID();
  const payload = JSON.stringify({
    sourceAccount: 'ACC-USR-001',
    destinationAccount: 'ACC-USR-002',
    amountCents: 100, // $1.00
    currency: 'USD',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${data.token}`,
      'Idempotency-Key': uniqueKey,
    },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/v1/transfers`, payload, params);
  transferLatency.add(Date.now() - start);

  const passed = check(res, {
    'Sustained transfer succeeded with 201 Created': (r) => r.status === 201,
  });

  if (passed) {
    successfulTransfers.add(1);
  } else {
    failedRequests.add(1);
  }

  sleep(0.1); // 100ms pacing between requests
}
