# A REAL, self-hosted Strapi (the open-source 2nd CMS) — for proving the generic
# HTTP connector against the genuine article, not the mock. Heavy by nature
# (scaffold + npm install + admin build), so it is an OPT-IN compose profile.
#
# Scaffolds Strapi 5 (sqlite, JS) at build time, swaps in a bootstrap that opens
# public upload, builds the admin, and runs in production. Throwaway proof
# instance; secrets are dev placeholders supplied by compose.
FROM node:22-bookworm-slim

WORKDIR /srv
# Scaffold + install deps at build time (the slow part is cached in the layer).
RUN npx --yes create-strapi@latest app \
      --no-run --skip-cloud --no-example --no-git-init \
      --js --use-npm --install --non-interactive --dbclient sqlite

WORKDIR /srv/app
# Grant public upload on bootstrap.
COPY bootstrap.js src/index.js

# Build the admin panel now so container start is just a boot, not a compile.
ENV NODE_ENV=production
RUN npm run build

EXPOSE 1337
CMD ["npm", "run", "start"]
