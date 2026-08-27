import { useEffect, useState } from 'react';
import { myAgreements, myDirectorySettings, myMarketingPreference, myParty, setDirectorySetting,
  setMarketingPreference, updateMyParty } from '../api.js';
import { tokenClaims } from '../auth.js';
import { ADDRESS_FIELDS, addressOf, isComplete, withPostalAddress } from '../address.js';

function AgreementRows() {
  const [agreements, setAgreements] = useState(null);
  useEffect(() => {
    myAgreements().then(setAgreements).catch(() => setAgreements([]));
  }, []);
  if (!agreements || !agreements.length) return null;
  return (
    <>
      <h2>My agreements</h2>
      <div className="rows">
        {agreements.map((a) => (
          <div className="row" key={a.id} data-testid="agreement-row">
            <span>
              <strong>{a.name}</strong>
              {a.agreementPeriod?.endDateTime && (
                <span className="dim"> — until {a.agreementPeriod.endDateTime.slice(0, 10)}</span>
              )}
            </span>
            <span className={`state ${a.status}`}>{a.status}</span>
          </div>
        ))}
      </div>
    </>
  );
}

export default function Account() {
  const [party, setParty] = useState(null);
  const [address, setAddress] = useState({});
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    myParty().then((p) => {
      setParty(p);
      setAddress(addressOf(p) || {});
    }).catch((e) => setError(e.message));
  }, []);

  const claims = tokenClaims();

  async function save() {
    setSaved(false);
    try {
      const updated = await updateMyParty({ contactMedium: withPostalAddress(party, address) });
      setParty(updated);
      setSaved(true);
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <>
      <h1>Account</h1>
      {error && <p className="error">{error}</p>}
      <div className="rows">
        <div className="row"><span className="dim">Name</span>
          <span>{party ? `${party.givenName || ''} ${party.familyName || ''}`.trim() : '…'}</span></div>
        <div className="row"><span className="dim">Email</span><span>{claims.email || '—'}</span></div>
        <div className="row"><span className="dim">Customer id</span><span className="small">{claims.sub}</span></div>
      </div>

      <h2>Shipping address</h2>
      {party?.addressProtected && (
        <p className="dim small" data-testid="address-protected">
          🔒 Your address is protected in the national registry — we never show or share the
          street address, and deliveries work best to a pickup point.
        </p>
      )}
      <div className="addressgrid">
        {ADDRESS_FIELDS.map((f) => (
          <label className="charfield" key={f.name}>
            <span>{f.label}</span>
            <input name={f.name} value={address[f.name] || ''}
                   onChange={(e) => { setAddress({ ...address, [f.name]: e.target.value }); setSaved(false); }} />
          </label>
        ))}
      </div>
      <div className="cartactions">
        {saved ? <span className="dim">Saved.</span> : <span />}
        <button className="primary" onClick={save} disabled={!isComplete(address)}>Save address</button>
      </div>
      <AgreementRows />
      <MarketingPreferences />
      <DirectoryPrivacy />
    </>
  );
}

// exposure levels, in decreasing openness — the words match the obligation
const EXPOSURES = [
  { value: 'full', label: 'Full listing', hint: 'name, number and address in number-directory services' },
  { value: 'partial', label: 'Name and number only', hint: 'listed, but your address never ships' },
  { value: 'reserved', label: 'Reserved', hint: 'not listed anywhere' },
];

/**
 * DIRECTORY PRIVACY — how number-directory services may list you. The free
 * secret-number service suppresses everything and forces Reserved; the
 * backend owns that rule, this face just says it.
 */
function DirectoryPrivacy() {
  const [setting, setSetting] = useState(null); // {exposure, secretNumber} | 'default' | null
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    myDirectorySettings()
      .then((list) => setSetting(list.find((s) => !s.serviceRef) || list[0] || 'default'))
      .catch(() => setSetting(null)); // component not deployed: say nothing
  }, []);
  if (setting === null) return null;

  const current = setting === 'default' ? null : setting;
  const secret = Boolean(current?.secretNumber);
  const exposure = secret ? 'reserved' : current?.exposure || null;

  async function apply(dto) {
    setBusy(true); setError(null);
    try {
      setSetting(await setDirectorySetting(dto));
    } catch (e) { setError(e.message); }
    setBusy(false);
  }

  return (
    <>
      <h2>Directory listing</h2>
      <p className="dim small">
        Providers must hand subscriber listings to number-directory services — this is how
        far yours goes.{!current && ' You haven’t chosen yet: the legal default applies.'}
      </p>
      <div className="rows" data-testid="directory-privacy">
        {EXPOSURES.map((e) => (
          <div className="row" key={e.value}>
            <label style={{ display: 'flex', gap: 10, alignItems: 'baseline', cursor: 'pointer' }}>
              <input type="radio" name="exposure" value={e.value}
                     data-testid={`exposure-${e.value}`}
                     checked={exposure === e.value}
                     disabled={busy || secret}
                     onChange={() => apply({ exposure: e.value })} />
              <span><strong>{e.label}</strong>
                <span className="dim"> — {e.hint}</span></span>
            </label>
          </div>
        ))}
        <div className="row" data-testid="secret-number-row">
          <span>
            <strong>Secret number</strong>
            <span className="dim"> — free; unlisted everywhere, calls never show your number,
              and the number is never recycled. Forces Reserved.</span>
            {secret && <span className="regbadge" data-testid="secret-number-on"> ✓ active</span>}
          </span>
          <button className="ghost" data-testid="secret-number-toggle" disabled={busy}
                  onClick={() => apply({ secretNumber: !secret })}>
            {secret ? 'Turn off' : 'Turn on'}
          </button>
        </div>
      </div>
      {error && <p className="error small" data-testid="directory-error">{error}</p>}
    </>
  );
}

function MarketingPreferences() {
  const [optedOut, setOptedOut] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    myMarketingPreference().then((p) => setOptedOut(Boolean(p.marketingOptOut))).catch(() => setOptedOut(false));
  }, []);
  const toggle = async () => {
    setBusy(true);
    try {
      const res = await setMarketingPreference(!optedOut);
      setOptedOut(Boolean(res.marketingOptOut));
    } catch { /* leave state as-is on error */ }
    setBusy(false);
  };
  return (
    <>
      <h2>Marketing preferences</h2>
      <div className="rows">
        <div className="row" data-testid="marketing-pref">
          <span>
            <strong>Marketing messages</strong>
            <span className="dim"> — offers, tips and campaigns. You'll always get essential service and billing notices.</span>
          </span>
          <button className="ghost" onClick={toggle} disabled={busy || optedOut === null} data-testid="marketing-toggle">
            {optedOut === null ? '…' : optedOut ? 'Opted out — turn back on' : 'Opt out'}
          </button>
        </div>
      </div>
    </>
  );
}
