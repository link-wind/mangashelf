# MangaShelf 服务器部署

适用于单台 Linux 服务器的小规模部署：Docker Compose 运行 PostgreSQL、RabbitMQ、
SeaweedFS、API、Worker 和 Web；宿主机 Caddy/Nginx 负责域名与 TLS。

## 1. 服务器建议

- Ubuntu 24.04 LTS，4 vCPU / 8 GB RAM / 60 GB 以上磁盘。
- 至少 2 vCPU / 4 GB 也可以启动，但首次构建 Worker（Chromium、OCR、FFmpeg）较慢，
  处理大文件时容易内存不足。
- 只需对外开放 `22`、`80`、`443`。PostgreSQL、RabbitMQ、SeaweedFS 和 API
  不直接暴露到公网。

安装 Docker：

```bash
sudo apt update
sudo apt install -y ca-certificates curl git rsync ufw
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
```

重新登录后执行 `docker version` 和 `docker compose version` 确认可用。

## 2. 上传代码

当前工作区有未提交修改，且本地仓库没有配置 Git remote。先选择一种方式同步：

- 推荐：提交并推送到私有仓库，服务器 `git clone` / `git pull`。
- 临时方式：从本机 rsync 到服务器。

仓库用 Git LFS 保存 133MB 的 e5 模型。另一台机器首次拉取前先安装 LFS：

```bash
git lfs install
git clone <仓库地址> mangashelf
cd mangashelf
git lfs pull
```

```bash
rsync -avz --progress \
  --exclude '.git' \
  --exclude '.env' \
  --exclude 'data' \
  --exclude 'backups' \
  --exclude 'server/target' \
  --exclude 'web/node_modules' \
  --exclude 'web/dist' \
  ./ user@SERVER_IP:/opt/mangashelf/
```

服务器端：

```bash
sudo mkdir -p /opt/mangashelf
sudo chown -R "$USER":"$USER" /opt/mangashelf
cd /opt/mangashelf
```

## 3. 生产环境变量

复制模板并生成强密码：

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

至少修改下面这些值：

```dotenv
POSTGRES_USER=mangashelf
POSTGRES_PASSWORD=<32位随机密码>
POSTGRES_DB=mangashelf

S3_ACCESS_KEY=<随机访问键>
S3_SECRET_KEY=<32位随机密码>
S3_BUCKET=mangashelf

MANGASHELF_PLATFORM_ID=mangashelf-prod-1
MANGASHELF_ADMIN_PASSWORD=<管理员强密码>
```

注意：

- `MANGASHELF_ADMIN_PASSWORD` 只在数据库首次初始化时使用；已有管理员密码不会被覆盖。
- 生产环境不要使用模板里的默认密码。
- 如果服务器无法直连 Bangumi、哔哩漫画或拷贝漫画，才填写对应代理。容器里的
  `127.0.0.1` 指向容器自己，不能填宿主机的 `127.0.0.1`。
- `.env` 不要提交到 Git。

## 4. 构建并启动

镜像现在会在 Docker 内完成 Java 和前端构建，服务器不需要安装 JDK、Maven 或 Node。

```bash
cd /opt/mangashelf

export COMPOSE_FILE=compose.yaml:deploy/compose.prod.yaml
export COMPOSE_PROJECT_NAME=mangashelf

docker compose --profile full up -d --build
docker compose ps
```

生产覆盖层只把 Web 绑定到宿主机 `127.0.0.1:8080`。检查：

```bash
curl -I http://127.0.0.1:8080/
docker compose exec web wget -qO- http://api:8080/actuator/health
docker compose logs -f --tail=100 api worker
```

首次构建需要下载镜像和依赖，Worker 镜像较大，耗时取决于服务器网络和 CPU。

## 5. 配置域名和 HTTPS

先给域名添加 `A` 记录指向服务器公网 IP，再安装 Caddy：

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

编辑 `/etc/caddy/Caddyfile`：

```caddyfile
mangashelf.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080
}
```

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Caddy 会自动申请和续期证书。然后配置防火墙：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

没有域名时，不要直接把 `8080` 暴露公网；可以用 SSH 隧道临时访问：

```bash
ssh -L 8080:127.0.0.1:8080 user@SERVER_IP
```

本机打开 `http://localhost:8080`。

## 6. 首次登录

- 地址：`https://你的域名`
- 管理员账号：`admin`
- 密码：`.env` 里的 `MANGASHELF_ADMIN_PASSWORD`

注册接口是公开的。如果暂时不开放注册，可在 Caddy 增加访问限制，或先通过防火墙/
反向代理限制来源。

## 7. 备份与更新

数据库和对象存储必须一起备份：

```bash
cd /opt/mangashelf
export COMPOSE_FILE=compose.yaml:deploy/compose.prod.yaml
export COMPOSE_PROJECT_NAME=mangashelf
./scripts/backup.sh
```

恢复脚本会清空目标数据库和对象存储，只能在确认过的空环境使用：

```bash
./scripts/restore.sh backups/20261112-120000
```

更新版本：

```bash
cd /opt/mangashelf
git pull
docker compose --profile full up -d --build
docker image prune -f
```

如果使用 rsync 上传，重复第 2 节的 rsync 后执行同一条 `docker compose ... up -d --build`。
不要执行 `docker compose down -v`，`-v` 会删除数据库和对象存储卷。

## 8. 部署检查清单

- [ ] `.env` 权限为 `600`，所有默认密码已替换
- [ ] `docker compose ps` 中六个服务均为 Up
- [ ] `http://127.0.0.1:8080/` 返回前端页面
- [ ] 域名 HTTPS 可访问，HTTP 自动跳转 HTTPS
- [ ] `admin` 可登录，且密码不是开发默认值
- [ ] `ufw` 只开放 22/80/443
- [ ] `./scripts/backup.sh` 能生成 `db.dump` 和 `storage-data.tar.gz`
- [ ] 至少完成一次恢复演练
