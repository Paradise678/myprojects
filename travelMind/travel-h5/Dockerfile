# ===== 构建阶段：用 Node 编译出静态文件 =====
FROM node:22-alpine AS build
WORKDIR /app

# 先拷贝依赖清单并安装，利用 Docker 层缓存加速重复构建
COPY package.json package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com && npm ci

COPY . .
RUN npm run build

# ===== 运行阶段：Nginx 托管静态文件并反向代理后端 =====
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
