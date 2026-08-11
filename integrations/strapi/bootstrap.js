'use strict';

/*
 * Replaces the scaffolded src/index.js. On boot it grants the PUBLIC role the
 * Upload content-api permissions, so the generic HTTP connector can POST to
 * /api/upload anonymously (the same shape the mock allowed) — no API token to
 * provision for the proof. Idempotent: only creates a permission if missing.
 *
 * This is a throwaway proof instance, not a production Strapi hardening guide —
 * a real operator would use a scoped API token, not open public upload.
 */
module.exports = {
  register() {},

  async bootstrap({ strapi }) {
    try {
      const role = await strapi.db
        .query('plugin::users-permissions.role')
        .findOne({ where: { type: 'public' } });
      if (!role) {
        strapi.log.warn('[bss] no public role found — cannot grant upload');
        return;
      }
      const actions = [
        'plugin::upload.content-api.upload',
        'plugin::upload.content-api.find',
        'plugin::upload.content-api.findOne',
      ];
      for (const action of actions) {
        const existing = await strapi.db
          .query('plugin::users-permissions.permission')
          .findOne({ where: { action, role: role.id } });
        if (!existing) {
          await strapi.db
            .query('plugin::users-permissions.permission')
            .create({ data: { action, role: role.id } });
          strapi.log.info('[bss] granted public ' + action);
        }
      }
      strapi.log.info('[bss] public upload permission ready');
    } catch (e) {
      strapi.log.error('[bss] bootstrap grant failed: ' + e.message);
    }
  },
};
