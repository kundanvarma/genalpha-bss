import { useEffect, useState } from 'react';
import { myAgreements, myMarketingPreference, myParty, setMarketingPreference, updateMyParty } from '../api.js';
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
