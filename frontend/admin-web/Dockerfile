# 管理后台：pnpm 构建静态产物 → nginx 托管（/api 与 /img 反代到 mall-admin）

FROM node:22-alpine AS build
WORKDIR /app

# pnpm 11 的 allowBuilds 只能从 pnpm-workspace.yaml 读，
# 必须在 install 之前 COPY 进来，否则会以 ERR_PNPM_IGNORED_BUILDS 失败
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN npm i -g pnpm@11 && pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
