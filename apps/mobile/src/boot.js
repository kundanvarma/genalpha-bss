/* Boot-time glue: auth completion + first-login party provisioning. */
import { ensureParty } from './api.js';
export { beginLogin, completeLogin, isSignedIn } from './auth.js';

export async function ensurePartyOnce() {
  try {
    await ensureParty();
  } catch {
    // party-account absent or transient — the app still renders
  }
}
