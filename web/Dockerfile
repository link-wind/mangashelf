# 前端镜像：本机构建 dist + nginx 静态 + 反向代理（DESIGN 19 同源部署）。
# 前置：web/ 下 npm run build（CI/CD 中由流水线执行）。
FROM nginx:alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/dist /usr/share/nginx/html
EXPOSE 80
