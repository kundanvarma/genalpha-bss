# The spawn-rights half of the workforce package: node + docker CLI, with
# the host docker socket mounted by the operator's compose profile — the
# scoped, opt-in container rights the core BSS deliberately never holds.
FROM node:20-alpine
RUN apk add --no-cache docker-cli
COPY server.js /app/server.js
EXPOSE 8080
CMD ["node", "/app/server.js"]
