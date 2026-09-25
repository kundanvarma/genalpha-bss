# Static catalog console served by unprivileged nginx (runs as non-root,
# listens on 8080 — consistent with the service images).
# The React islands: one bundle the vanilla shell mounts into a desk's panel
# (see apps/admin-console/island). Built here so nothing generated is committed.
FROM node:22-alpine AS island
WORKDIR /island
COPY island/package.json island/package-lock.json ./
RUN npm ci
COPY island/ ./
RUN ISLAND_OUT=/usr/share/nginx/html/island npm run build

FROM nginxinc/nginx-unprivileged:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY site/ /usr/share/nginx/html/
COPY --from=island /usr/share/nginx/html/island /usr/share/nginx/html/island
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
