# GemiNeko（杰米猫）服务器部署指南

适用于单台 Linux 服务器的小规模部署：Docker Compose 运行 PostgreSQL（pgvector）、
Redis、RabbitMQ、SeaweedFS、API、Worker 和 Web；宿主机 Caddy 负责域名与 HTTPS，
可配合 Cloudflare 代理使用。

## 1. 服务器建议与基础环境

- Ubuntu 24.04 LTS，推荐 4 vCPU / 8 GB RAM / 60 GB 以上磁盘（最低 2 vCPU / 4 GB）。
- 仅对外开放 `22`、`80`、`443`；PostgreSQL、Redis、RabbitMQ、SeaweedFS 和 API
  在生产配置中均不暴露公网端口。

安装 Docker、Git 与 Git LFS：

```bash
sudo apt update
sudo apt install -y ca-certificates curl git git-lfs rsync ufw
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
```

重新登录后执行 `docker version`、`docker compose version` 和 `git lfs version` 确认可用。

## 2. 拉取代码与 LFS 模型

仓库使用 Git LFS 保存 133 MB 的语义模型 `server/models/e5-small-v2.onnx`，首次克隆必须先启用 LFS：

```bash
sudo mkdir -p /opt/mangashelf
sudo chown -R "$USER":"$USER" /opt/mangashelf

git lfs install
git clone https://github.com/link-wind/mangashelf.git /opt/mangashelf
cd /opt/mangashelf
git lfs pull
ls -lh server/models/e5-small-v2.onnx
```

> 确认 `server/models/e5-small-v2.onnx` 大小约 `127M`（不是几百字节的 LFS 指针文本）。

## 3. 配置生产环境变量

```bash
cd /opt/mangashelf
cp .env.example .env
chmod 600 .env
# 生成随机强密码（执行多次）
openssl rand -base64 32
```

编辑 `.env`，至少替换以下密码与发信配置：

```dotenv
POSTGRES_USER=mangashelf
POSTGRES_PASSWORD=<32位随机密码>
POSTGRES_DB=mangashelf

S3_ACCESS_KEY=<随机访问键>
S3_SECRET_KEY=<32位随机密码>
S3_BUCKET=mangashelf

MANGASHELF_PLATFORM_ID=gemineko-prod-1
MANGASHELF_ADMIN_PASSWORD=<管理员强密码>

# 注册 6 位邮箱验证码（Resend SMTP）
MANGASHELF_MAIL_ENABLED=true
MANGASHELF_MAIL_HOST=smtp.resend.com
MANGASHELF_MAIL_PORT=465
MANGASHELF_MAIL_SSL=true
MANGASHELF_MAIL_STARTTLS=false
MANGASHELF_MAIL_USERNAME=resend
MANGASHELF_MAIL_PASSWORD=<你的 Resend API Key>
MANGASHELF_MAIL_FROM="GemiNeko <noreply@your.verified.domain>"
```

注意：
- `MANGASHELF_ADMIN_PASSWORD` 仅在数据库首次初始化时创建 `admin` 账号；从本机迁移数据库时将保留原库账号与密码。
- 如果服务器网络无法直连 Bangumi / 哔哩 / 拷贝漫画，再配置对应 `*_PROXY`（容器内不能填 `127.0.0.1`）。
- `.env` 永远不要提交到 Git。

## 4. 启动服务（二选一：迁移本机数据 或 全新空库）

先在当前 Shell 设置生产 Compose 变量：

```bash
cd /opt/mangashelf
export COMPOSE_FILE=compose.yaml:deploy/compose.prod.yaml
export COMPOSE_PROJECT_NAME=mangashelf
```

### 方式 A：从本机迁移现有番剧与漫画库（推荐）

1. 在**本机开发机**执行备份并传到服务器：

```bash
./scripts/backup.sh
# 将生成的 backups/<时间戳> 目录传到服务器
scp -r backups/<时间戳> user@SERVER_IP:/opt/mangashelf/backups/
```

2. 在**服务器**恢复数据后再启动全栈：

```bash
./scripts/restore.sh backups/<时间戳>
docker compose --profile full up -d --build
docker compose ps
```

### 方式 B：全新空库初始化

由于生产 `api` 容器关闭了启动期自动建表（`MANGASHELF_DB_MIGRATE="false"`），首次空库必须先执行一次 Flyway 迁移：

```bash
docker compose up -d db
docker compose --profile multi run --rm --build migrate
docker compose --profile full up -d --build
docker compose ps
```

### 验证本机回环服务

```bash
curl -I http://127.0.0.1:8080/
docker compose exec web wget -qO- http://api:8080/actuator/health
docker compose logs -f --tail=100 api worker
```

## 5. 域名、HTTPS 与 Cloudflare 配置

### 5.1 安装 Caddy

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

编辑 `/etc/caddy/Caddyfile`（将 `example.com` 替换为你的域名）：

```caddyfile
example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080
}
```

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

### 5.2 Cloudflare 配合步骤

1. 在 Cloudflare DNS 添加 `A` 记录：`@`（或子域名）→ 服务器公网 IP。
2. **建议先将代理状态设为「仅 DNS（灰云）」**，等待 1 分钟并访问 `https://你的域名` 确认 Caddy 已成功签发 Let's Encrypt 证书。
3. 确认 HTTPS 正常打开后，到 Cloudflare **SSL/TLS → 概述** 将加密模式设为 **完全（严格）/ Full (strict)**。
4. 再回到 DNS 把记录切回 **已代理（小黄云）**，即可同时获得源站 IP 隐藏与全链路 HTTPS 加密。
   - 如你希望长期开启小黄云且不依赖 HTTP-01 自动续签，也可在 Cloudflare **SSL/TLS → 源服务器** 创建 15 年有效期源证书，由 Caddy 通过 `tls /etc/caddy/origin.pem /etc/caddy/origin.key` 加载。

### 5.3 防火墙

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

## 6. 日常备份与版本更新

### 备份

```bash
cd /opt/mangashelf
export COMPOSE_FILE=compose.yaml:deploy/compose.prod.yaml
export COMPOSE_PROJECT_NAME=mangashelf
./scripts/backup.sh
```

### 更新发布

```bash
cd /opt/mangashelf
export COMPOSE_FILE=compose.yaml:deploy/compose.prod.yaml
export COMPOSE_PROJECT_NAME=mangashelf
git pull
# 若本次拉取包含新的 Flyway SQL 迁移（server/src/main/resources/db/migration/），先执行一次：
docker compose --profile multi run --rm --build migrate
docker compose --profile full up -d --build
docker image prune -f
```

> 严禁执行 `docker compose down -v`，`-v` 会永久清空数据库与对象存储卷。

## 7. 上线检查清单

- [ ] `.env` 权限为 `600`，数据库 / S3 / 管理员密码均已替换为随机强密码
- [ ] `server/models/e5-small-v2.onnx` 为完整模型文件（约 127M）
- [ ] `docker compose ps` 中 `db / redis / rabbitmq / storage / api / worker / web` 全部为 `Up`
- [ ] `https://你的域名` 可正常打开，favicon 与 GemiNeko 图标正常显示
- [ ] 注册页能正常收到 6 位邮箱验证码并完成注册与登录
- [ ] `ufw status` 仅放行 `22 / 80 / 443`
