import { useEffect, useState } from 'react';
import { myParty } from '../api.js';
import { tokenClaims } from '../auth.js';

export default function Account() {
  const [party, setParty] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    myParty().then(setParty).catch((e) => setError(e.message));
  }, []);

  const claims = tokenClaims();

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
    </>
  );
}
