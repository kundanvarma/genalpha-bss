import { t } from '../../i18n.js';

/* A bill's credit notes, each with its own PDF — customers RECEIVE credit notes.
 * One or two read as chips beside the bill. More collapse into one summary that
 * opens the list: a bill carrying ten small goodwill credits pushed its own Pay
 * and Dispute buttons off the screen. */

function openPdf(cn, onError) {
  import('../../auth.js').then(async ({ authFetch }) => {
    const res = await authFetch(`/tmf-api/customerBillManagement/v4/creditNote/${cn.id}/document.pdf`);
    if (!res.ok) { onError('PDF: HTTP ' + res.status); return; }
    window.open(URL.createObjectURL(await res.blob()), '_blank');
  });
}

export default function CreditNotes({ notes, onError }) {
  if (!notes.length) return null;
  const chip = (cn) => (
    <a key={cn.id} className="state settled" data-testid="credit-note-chip"
       title={cn.reason} style={{ textDecoration: 'none', cursor: 'pointer' }}
       onClick={(e) => { e.preventDefault(); openPdf(cn, onError); }}>
      {t('credit note')} {cn.creditNoteNo} · −{Number(cn.amount.value).toFixed(2)} {cn.amount.unit}
    </a>
  );
  if (notes.length <= 2) return <>{notes.map(chip)}</>;
  // a total only when every note is in one currency — never a sum across units
  const units = new Set(notes.map((cn) => cn.amount.unit));
  const total = notes.reduce((sum, cn) => sum + Number(cn.amount.value), 0);
  return (
    <details className="creditnotes" data-testid="credit-notes">
      <summary className="state settled" data-testid="credit-notes-summary">
        {notes.length} {t('credit notes')}{units.size === 1 ? ` · −${total.toFixed(2)} ${[...units][0]}` : ''}
      </summary>
      <div className="creditnotes-list">{notes.map(chip)}</div>
    </details>
  );
}
