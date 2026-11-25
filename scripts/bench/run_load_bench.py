#!/usr/bin/env python3
"""
MangaShelf 零外部依赖并发压测与 EXPLAIN (ANALYZE, BUFFERS) 采集器。
与 scripts/bench/k6-workload.js 保持完全一致的混合流量分布，用于固定硬件与数据集下的三阶段对照实验。
"""
import argparse
import concurrent.futures
import http.client
import json
import math
import random
import subprocess
import sys
import threading
import time
from collections import defaultdict
from urllib.parse import urlparse


ENDPOINTS = [
    (0.30, "catalog_first_page", "/api/v1/catalog-items?limit=24"),
    (0.45, "catalog_episodes_deep", "/api/v1/catalog-items?limit=24&sort=episodes&page=20"),
    (0.60, "catalog_cat_ongoing", "/api/v1/catalog-items?limit=24&category=30&status=ongoing"),
    (0.80, "calendar", "/api/v1/calendar"),
    (0.90, "works_list", "/api/v1/works?limit=20"),
    (1.00, "catalog_search", "/api/v1/catalog-items?limit=24&q=%E6%9F%AF%E5%8D%97"),
]


def pick_endpoint(rng: random.Random):
    r = rng.random()
    for threshold, name, path in ENDPOINTS:
        if r < threshold:
            return name, path
    return ENDPOINTS[-1][1], ENDPOINTS[-1][2]


def parse_cookies(headers):
    cookies = {}
    for k, v in headers:
        if k.lower() == "set-cookie":
            pair = v.split(";", 1)[0]
            if "=" in pair:
                ck, cv = pair.split("=", 1)
                cookies[ck.strip()] = cv.strip()
    return cookies


def login_and_get_cookie(host: str, port: int, login: str, password: str) -> str:
    conn = http.client.HTTPConnection(host, port, timeout=15)
    conn.request("GET", "/api/v1/works")
    r1 = conn.getresponse()
    r1.read()
    cookies = parse_cookies(r1.getheaders())
    xsrf = cookies.get("XSRF-TOKEN", "")
    cookie_hdr = "; ".join(f"{k}={v}" for k, v in cookies.items())

    body = json.dumps({"login": login, "password": password}).encode("utf-8")
    conn.request("POST", "/api/v1/auth/login", body=body, headers={
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": xsrf,
        "Cookie": cookie_hdr,
    })
    r2 = conn.getresponse()
    raw = r2.read()
    if r2.status != 200:
        raise RuntimeError(f"Login failed: HTTP {r2.status} {raw.decode('utf-8', 'replace')}")
    cookies.update(parse_cookies(r2.getheaders()))
    conn.close()
    return "; ".join(f"{k}={v}" for k, v in cookies.items())


def percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0.0
    idx = int(math.ceil((pct / 100.0) * len(sorted_vals))) - 1
    return sorted_vals[max(0, min(idx, len(sorted_vals) - 1))]


def run_benchmark(base_url: str, login: str, password: str, vus: int, duration_s: int, warmup_s: int):
    parsed = urlparse(base_url)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    cookie = login_and_get_cookie(host, port, login, password)

    def worker(worker_id: int, run_seconds: float, record: bool):
        rng = random.Random(20261124 + worker_id)
        conn = http.client.HTTPConnection(host, port, timeout=15)
        latencies = defaultdict(list)
        bytes_sum = defaultdict(int)
        errors = defaultdict(int)
        deadline = time.perf_counter() + run_seconds
        headers = {"Cookie": cookie, "Connection": "keep-alive"}
        while time.perf_counter() < deadline:
            name, path = pick_endpoint(rng)
            t0 = time.perf_counter()
            try:
                conn.request("GET", path, headers=headers)
                resp = conn.getresponse()
                data = resp.read()
                elapsed_ms = (time.perf_counter() - t0) * 1000.0
                if record:
                    if resp.status == 200:
                        latencies[name].append(elapsed_ms)
                        bytes_sum[name] += len(data)
                    else:
                        errors[name] += 1
            except Exception:
                if record:
                    errors[name] += 1
                try:
                    conn.close()
                except Exception:
                    pass
                conn = http.client.HTTPConnection(host, port, timeout=15)
        try:
            conn.close()
        except Exception:
            pass
        return latencies, bytes_sum, errors

    if warmup_s > 0:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(vus, 8)) as pool:
            futs = [pool.submit(worker, i, warmup_s, False) for i in range(min(vus, 8))]
            for f in futs:
                f.result()

    t_start = time.perf_counter()
    merged_lat = defaultdict(list)
    merged_bytes = defaultdict(int)
    merged_err = defaultdict(int)
    with concurrent.futures.ThreadPoolExecutor(max_workers=vus) as pool:
        futs = [pool.submit(worker, i, duration_s, True) for i in range(vus)]
        for f in futs:
            lat, bs, err = f.result()
            for k, v in lat.items():
                merged_lat[k].extend(v)
            for k, v in bs.items():
                merged_bytes[k] += v
            for k, v in err.items():
                merged_err[k] += v
    elapsed = time.perf_counter() - t_start

    all_lat = []
    total_ok = 0
    total_err = sum(merged_err.values())
    total_bytes = sum(merged_bytes.values())
    by_endpoint = {}
    for _, name, _ in ENDPOINTS:
        vals = sorted(merged_lat[name])
        all_lat.extend(vals)
        cnt = len(vals)
        total_ok += cnt
        by_endpoint[name] = {
            "count": cnt,
            "errors": merged_err[name],
            "rps": round(cnt / elapsed, 2),
            "p50_ms": round(percentile(vals, 50), 2),
            "p95_ms": round(percentile(vals, 95), 2),
            "p99_ms": round(percentile(vals, 99), 2),
            "avg_bytes": round(merged_bytes[name] / cnt, 1) if cnt else 0.0,
        }
    all_lat.sort()
    summary = {
        "vus": vus,
        "duration_s": round(elapsed, 2),
        "total_requests": total_ok + total_err,
        "ok_requests": total_ok,
        "error_requests": total_err,
        "error_rate": round(total_err / max(1, total_ok + total_err), 4),
        "rps": round(total_ok / elapsed, 2),
        "p50_ms": round(percentile(all_lat, 50), 2),
        "p95_ms": round(percentile(all_lat, 95), 2),
        "p99_ms": round(percentile(all_lat, 99), 2),
        "avg_response_kb": round((total_bytes / max(1, total_ok)) / 1024.0, 2),
        "throughput_mb_s": round((total_bytes / elapsed) / (1024.0 * 1024.0), 2),
        "by_endpoint": by_endpoint,
    }
    return summary


def run_explain(docker_container: str, label: str, sql: str):
    cmd = [
        "docker", "exec", docker_container,
        "psql", "-U", "mangashelf", "-d", "mangashelf", "-X", "-q", "-t", "-A",
        "-c", f"EXPLAIN (ANALYZE, BUFFERS) {sql}"
    ]
    out = subprocess.check_output(cmd, text=True)
    return {"label": label, "plan": out.strip()}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://127.0.0.1:18088")
    p.add_argument("--login", default="admin")
    p.add_argument("--password", default="mangashelf-admin")
    p.add_argument("--vus", type=int, default=24)
    p.add_argument("--duration", type=int, default=12)
    p.add_argument("--warmup", type=int, default=2)
    p.add_argument("--db-container", default="mangashelf-db-1")
    p.add_argument("--skip-explain", action="store_true")
    args = p.parse_args()

    result = run_benchmark(args.base, args.login, args.password, args.vus, args.duration, args.warmup)
    if not args.skip_explain:
        explains = [
            run_explain(args.db_container, "catalog_first_page",
                        "SELECT id, source_id, vod_id, name, cover_url, remarks, category_id, category_name, play_from, '' AS play_groups, episode_count, work_id, bangumi_subject_id, resource_updated_at, synced_at FROM external_catalog_items WHERE (COALESCE(resource_updated_at, synced_at), id) < ('9999-12-31T23:59:59Z'::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC LIMIT 24"),
            run_explain(args.db_container, "catalog_episodes_deep",
                        "SELECT id, source_id, vod_id, name, cover_url, remarks, category_id, category_name, play_from, '' AS play_groups, episode_count, work_id, bangumi_subject_id, resource_updated_at, synced_at FROM external_catalog_items ORDER BY episode_count DESC, id DESC OFFSET 480 LIMIT 24"),
            run_explain(args.db_container, "catalog_cat_ongoing",
                        "SELECT id, source_id, vod_id, name, cover_url, remarks, category_id, category_name, play_from, '' AS play_groups, episode_count, work_id, bangumi_subject_id, resource_updated_at, synced_at FROM external_catalog_items WHERE category_id = 30 AND remarks LIKE '更新至%' AND (COALESCE(resource_updated_at, synced_at), id) < ('9999-12-31T23:59:59Z'::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC LIMIT 24"),
            run_explain(args.db_container, "catalog_search_trgm",
                        "SELECT id, name FROM external_catalog_items WHERE name ILIKE '%柯南%' ORDER BY COALESCE(resource_updated_at, synced_at) DESC, id DESC LIMIT 24"),
        ]
        result["explains"] = explains

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
