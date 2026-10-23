FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:stable-alpine
ARG OPSFLOW_REVISION=local
LABEL org.opencontainers.image.revision=$OPSFLOW_REVISION
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/
RUN printf '{"revision":"%s"}\n' "$OPSFLOW_REVISION" > /usr/share/nginx/html/release.json
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q --spider http://127.0.0.1/healthz || exit 1
