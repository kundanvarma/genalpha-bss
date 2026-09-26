import { Link } from 'react-router-dom';

/** CSR-UX-001: the brand is the way back to the desk's default workspace.
 *
 * It used to be an image and two spans — the position every web application
 * puts "home" in, wired to nothing. One link now covers the logo, the area
 * wordmark and the org badge, so a click anywhere in the brand returns to
 * Customers (the landing route today; a home page later, without the
 * affordance changing). The accessible name is the TENANT's brand name from
 * the gateway's per-channel config, never a name compiled into the build.
 */
export default function Brand({ org }) {
  const brandName = ((window.BSS_CSR_CONFIG || {}).brandName || '').trim();
  const home = brandName ? `${brandName} CSR home` : 'CSR home';
  const logo = (window.BSS_CSR_CONFIG || {}).logoUrl
    || '/tmf-api/documentManagement/v4/document/brand-logo';
  return (
    <Link className="brand brandhome" to="/" data-testid="brand-home" aria-label={home} title={home}>
      {/* the fallback stays: a tenant without a logo document must not show a broken image */}
      <img className="brandlogo" src={logo} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
      <span className="area">csr console</span>
      {org && <span className="orgbadge">{org}</span>}
    </Link>
  );
}
