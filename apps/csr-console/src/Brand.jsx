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
  /*
   * THE NAME IS BUILT FROM THE CONTENT, not from an aria-label that replaces it.
   *
   * An `aria-label` of "<tenant> CSR home" over visible text reading "csr
   * console" is a WCAG 2.5.3 (Label in Name) failure: a speech-input user says
   * what they can see and the control does not answer to it. axe-core 4.14
   * promoted that rule out of experimental and found it here.
   *
   * Putting the tenant's name and the word "home" in the content — hidden from
   * the screen, present in the name — satisfies the rule BY CONSTRUCTION,
   * because a name assembled from content always contains the visible text. It
   * also survives the narrow viewport where `.area` is display:none: the
   * visible text shrinks and the name shrinks with it, which an aria-label
   * could never do. The name is still the TENANT's, read from the gateway's
   * per-channel config and never compiled in (CSR-UX-001).
   *
   * `title` stays for the tooltip only; content wins over it for the name.
   */
  return (
    <Link className="brand brandhome" to="/" data-testid="brand-home" title={home}>
      {/* the fallback stays: a tenant without a logo document must not show a broken image */}
      <img className="brandlogo" src={logo} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
      {brandName && <span className="vh">{brandName} </span>}
      <span className="area">csr console</span>
      {org && <span className="orgbadge">{org}</span>}
      <span className="vh"> — home</span>
    </Link>
  );
}
