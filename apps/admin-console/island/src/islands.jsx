import { Bills } from './bills/Bills.jsx';
import { Overview } from './billing/Overview.jsx';
import { Health } from './Health.jsx';

/* Every island the back office can mount, by name. A desk in the vanilla shell
 * names one; nothing else couples the two. */
export const Islands = {
  bills: Bills,
  billingOverview: Overview,
  health: Health,
};
