/* The first staff login of a tenant ONBOARDED on the laptop demo fleet (#321, #324).
 * The product generates one per operator and shows it once; the demo fleet names one
 * in docker-compose (ONBOARDING_STAFF_PASSWORD) so suites can sign in to tenants
 * that other suites onboarded. It meets the 15-character policy.
 *
 * A tenant onboarded BEFORE the policy keeps its old "demo" password — a policy is
 * checked when a password is set, never on existing ones — so a suite signing in
 * to a tenant it did not onboard itself tries the current password, then that one. */
const ONBOARDED_STAFF_PASSWORD = process.env.ONBOARDING_STAFF_PASSWORD || 'demo-staff-sign-in';
const LEGACY_ONBOARDED_STAFF_PASSWORD = 'demo';

/** signIn(password) -> token or falsy; the first password that signs in wins. */
async function onboardedStaffToken(signIn) {
  for (const pw of [ONBOARDED_STAFF_PASSWORD, LEGACY_ONBOARDED_STAFF_PASSWORD]) {
    const t = await Promise.resolve(signIn(pw)).catch(() => null);
    if (t) return t;
  }
  return null;
}

module.exports = { ONBOARDED_STAFF_PASSWORD, onboardedStaffToken };
