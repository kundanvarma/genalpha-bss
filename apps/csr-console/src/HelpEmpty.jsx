/** CSR-UX-007, the immediate half: help never opens into a dead end.
 *
 * The drawer used to answer "No help written for this page yet." — it created
 * an expectation and broke it in the same breath, with nowhere to go. When no
 * article matches, offer the next step instead: search all help, ask about this
 * page (where the agent holds ai:use), and four questions worth asking on the
 * screen the agent is actually standing on. Picking one fills the search box,
 * which both searches and arms Ask.
 *
 * NOT this file: the contextual knowledge assistant the ticket describes as its
 * later half (page guidance, customer/service context, guided troubleshooting).
 */

// Keyed by the drawer's own shelf tag (csr:<route>), so a question set belongs
// to a screen and not to a guess about one.
const SUGGESTIONS = {
  'csr:customers': [
    'What does suspended service mean?',
    'How do I replace an eSIM?',
    'How do I dispute this bill?',
    'What should I check if roaming is not working?',
  ],
  'csr:tickets': [
    'How do I escalate a ticket?',
    'What does a ticket on hold mean?',
    'How do I link a ticket to a known outage?',
    'Who owns a ticket after a handover?',
  ],
  'csr:chats': [
    'How do I hand a chat to another agent?',
    'How do I wrap up a chat?',
    'What does the intent on a chat mean?',
  ],
  'csr:knowledge': [
    'How do I search for a procedure?',
    'What do I do when no article answers the question?',
  ],
  'csr:stock': [
    'How do I reserve stock for a customer?',
    'What does out of stock mean for an order already placed?',
  ],
  'csr:devices': [
    'How do I swap a faulty device?',
    'How is a device residual value worked out?',
  ],
  'csr:migrations': [
    'How do I check where a migration stopped?',
    'What happens to a customer mid-migration?',
  ],
  'csr:collections': [
    'How do I dispute this bill?',
    'What does a dunning step do to the service?',
    'How do I set up a payment arrangement?',
  ],
  'csr:registry': [
    'How do I verify a customer against the national registry?',
    'What do I do when the registry finds no match?',
  ],
};

const ANYWHERE = [
  'How do I find a customer?',
  'What does suspended service mean?',
  'How do I dispute this bill?',
];

export default function HelpEmpty({ q, context, canAsk, onPick }) {
  const term = q.trim();
  const ideas = (SUGGESTIONS[context] || ANYWHERE)
    .filter((s) => s.toLowerCase() !== term.toLowerCase())
    .slice(0, 4);
  const brandName = ((window.BSS_CSR_CONFIG || {}).brandName || '').trim();
  const where = brandName ? `${brandName} help` : 'all help';
  return (
    <div className="help-none" data-testid="help-empty">
      <p className="help-none-lead">
        {term ? `No article matches “${term}” yet. ` : ''}How can I help?
      </p>
      <p className="dim small" data-testid="help-next-step">
        Search {where}{canAsk ? ', or ask a question about this page' : ''} — one of these is a good start:
      </p>
      <ul className="help-ideas">
        {ideas.map((s) => (
          <li key={s}>
            <button type="button" className="ghost small" data-testid="help-suggestion"
              onClick={() => onPick(s)}>{s}</button>
          </li>
        ))}
      </ul>
    </div>
  );
}
