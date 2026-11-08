# 前端镜像：vite 构建 → nginx 静态 + 反向代理（DESIGN 19 同源部署）。
FROM node:25-alpine AS build
WORKDIR /w
COPY web/package.json web/package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY web .
RUN npm run build

FROM nginx:alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /w/dist /usr/share/nginx/html
EXPOSE 80
