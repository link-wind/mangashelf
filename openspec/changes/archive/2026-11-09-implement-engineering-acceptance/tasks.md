# 任务：M5 工程验收

## 部署

- [ ] server/Dockerfile（多阶段：maven 构建 → temurin 25-jre；角色经 MANGASHELF_ROLE）
- [ ] server/Dockerfile.worker：python3 + venv（requirements + rapidocr 模型缓存拷入）+ ffmpeg + jar
- [ ] web/Dockerfile：vite 构建 → nginx 静态 + /api、/media 反代（nginx.conf）
- [ ] compose.yaml 增加 api/worker/web（profiles: full）；e5 模型只读挂载；健康检查
- [ ] `docker compose --profile full build` 成功；`--profile full up -d` 全栈健康；
      浏览器经 nginx 访问站点可用（登录/阅读）
- [ ] README 部署一节更新（含 e5 模型文件前置说明）

## 可观测性

- [ ] RequestObservabilityFilter：request_id 生成入 MDC + X-Request-Id 响应头 + user_id
- [ ] 日志 pattern 注入 %X{request_id} %X{user_id}；抽样验证一条请求日志含两 ID
- [ ] micrometer-registry-prometheus + actuator/prometheus 端点可访问

## 中断恢复矩阵补测

- [ ] API 退出后 outbox 补投：complete 后立即杀 API → Worker 侧派发器补投 → 任务完成
- [ ] 下架动画 → playback 404（已签发地址短有效期边界记录）
- [ ] 矩阵总表：M0–M4 已测 9 场景 + M5 补测 2 场景，逐项标注验证位置（21.1）

## 备份恢复演练

- [ ] scripts/backup.sh：pg_dump custom 格式 + SeaweedFS 数据卷 tar + 时间戳目录
- [ ] scripts/restore.sh：空库恢复 + 卷回放
- [ ] 演练：备份 → down -v → up → restore → 指定作品列表在、对象 GET 200（20.3）
- [ ] 演练步骤与结果记录（含"只备份数据库不能恢复漫画原图"的实证提醒）

## 容量测量（DESIGN 21.3）

- [ ] 固定数据集脚本（2 作品 × 12 页 + 1 动画）
- [ ] 测量：目录列表 / 页面窗口 / 搜索 p50、p95（N=50/50/30）+ 索引吞吐（页/秒）
- [ ] 数字与规格边界记录（不外推生产容量）

## 收尾

- [ ] docs/validation/m5.md：矩阵总表、部署/备份/容量结果、日志样本、已知限制
- [ ] README 里程碑表更新（M0–M5 状态）
