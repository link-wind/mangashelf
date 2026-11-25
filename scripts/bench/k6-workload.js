import http from 'k6/http';
import { check } from 'k6';

/**
 * MangaShelf 标准 k6 并发负载脚本（benchmark-db-and-hotspot-cache）：
 * 用法：
 *   k6 run -e BASE_URL=http://127.0.0.1:8080 -e LOGIN=admin -e PASSWORD=mangashelf-admin scripts/bench/k6-workload.js
 */
export const options = {
  vus: Number(__ENV.VUS || 24),
  duration: __ENV.DURATION || '15s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const LOGIN = __ENV.LOGIN || 'admin';
const PASSWORD = __ENV.PASSWORD || 'mangashelf-admin';

export function setup() {
  const initRes = http.get(`${BASE}/api/v1/works`);
  const xsrf = initRes.cookies['XSRF-TOKEN'] ? initRes.cookies['XSRF-TOKEN'][0].value : '';
  const loginRes = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ login: LOGIN, password: PASSWORD }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': xsrf,
      },
    }
  );
  check(loginRes, { 'login ok': (r) => r.status === 200 });
  const session = loginRes.cookies['SESSION'] ? loginRes.cookies['SESSION'][0].value : '';
  return { session };
}

export default function (data) {
  const params = {
    headers: { Cookie: `SESSION=${data.session}` },
  };
  const r = Math.random();
  let url;
  let tag;
  if (r < 0.30) {
    url = `${BASE}/api/v1/catalog-items?limit=24`;
    tag = 'catalog_first_page';
  } else if (r < 0.45) {
    url = `${BASE}/api/v1/catalog-items?limit=24&sort=episodes&page=20`;
    tag = 'catalog_episodes_deep';
  } else if (r < 0.60) {
    url = `${BASE}/api/v1/catalog-items?limit=24&category=30&status=ongoing`;
    tag = 'catalog_cat_ongoing';
  } else if (r < 0.80) {
    url = `${BASE}/api/v1/calendar`;
    tag = 'calendar';
  } else if (r < 0.90) {
    url = `${BASE}/api/v1/works?limit=20`;
    tag = 'works_list';
  } else {
    url = `${BASE}/api/v1/catalog-items?limit=24&q=%E6%9F%AF%E5%8D%97`;
    tag = 'catalog_search';
  }
  const res = http.get(url, Object.assign({ tags: { endpoint: tag } }, params));
  check(res, { 'status 200': (resp) => resp.status === 200 });
}
