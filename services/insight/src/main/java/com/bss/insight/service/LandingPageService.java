package com.bss.insight.service;

import com.bss.insight.dto.LandingPageRequest;
import com.bss.insight.dto.LandingPageView;
import com.bss.insight.dto.LeadCapture;
import com.bss.insight.dto.LeadForm;
import com.bss.insight.entity.LandingPage;
import com.bss.insight.repository.LandingPageRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Campaign landing pages + lead capture — a standalone acquisition surface. Author
 * a page (headline, copy, campaign source); a public URL renders it with a
 * consent-first form; a ticked submission becomes a CONSENTED prospect stamped
 * with the campaign, so a prospect audience {source = campaign} nurtures the lead.
 * Consent is not optional — an unticked form captures nothing.
 */
@Service
public class LandingPageService {

    private final LandingPageRepository pages;
    private final ProspectService prospects;
    private final TenantScope tenantScope;

    public LandingPageService(LandingPageRepository pages, ProspectService prospects, TenantScope tenantScope) {
        this.pages = pages;
        this.prospects = prospects;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public LandingPageView create(LandingPageRequest dto) {
        String slug = slugify(str(dto.slug(), str(dto.headline(), "page")));
        String tenant = tenantScope.currentTenantId();
        LandingPage p = pages.findByTenantIdAndSlug(tenant, slug).orElseGet(LandingPage::new);
        if (p.getId() == null) {
            p.setId(UUID.randomUUID().toString());
            p.setTenantId(tenant);
            p.setSlug(slug);
            p.setCreatedAt(OffsetDateTime.now());
        }
        p.setHeadline(str(dto.headline(), "An offer for you"));
        p.setSubhead(str(dto.subhead(), null));
        p.setCtaLabel(str(dto.ctaLabel(), "Get the offer"));
        p.setUtmSource(str(dto.utmSource(), slug));
        // Customization — URLs and the colour are sanitized so a page can never
        // become an injection vector (only http/https/relative URLs, only #hex).
        p.setLogoUrl(safeUrl(str(dto.logoUrl(), null)));
        p.setHeroImageUrl(safeUrl(str(dto.heroImageUrl(), null)));
        p.setBrandColor(safeColor(str(dto.brandColor(), null)));
        p.setCtaUrl(safeUrl(str(dto.ctaUrl(), null)));
        p.setPrivacyUrl(safeUrl(str(dto.privacyUrl(), null)));
        return view(pages.save(p));
    }

    @Transactional(readOnly = true)
    public List<LandingPageView> list() {
        return pages.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(LandingPageService::view).toList();
    }

    /** Edit an existing page by id — the console's pre-filled edit form saves here.
     * The slug (the page's public URL/identity) is deliberately not changed. */
    @Transactional
    public LandingPageView patch(String id, LandingPageRequest dto) {
        LandingPage p = pages.findById(id)
                .orElseThrow(() -> com.bss.insight.exception.NotFoundException.forResource("LandingPage", id));
        p.setHeadline(str(dto.headline(), p.getHeadline()));
        p.setSubhead(str(dto.subhead(), null));
        p.setCtaLabel(str(dto.ctaLabel(), p.getCtaLabel()));
        p.setUtmSource(str(dto.utmSource(), p.getUtmSource()));
        p.setLogoUrl(safeUrl(str(dto.logoUrl(), null)));
        p.setHeroImageUrl(safeUrl(str(dto.heroImageUrl(), null)));
        p.setBrandColor(safeColor(str(dto.brandColor(), null)));
        p.setCtaUrl(safeUrl(str(dto.ctaUrl(), null)));
        p.setPrivacyUrl(safeUrl(str(dto.privacyUrl(), null)));
        return view(pages.save(p));
    }

    /** Delete a page (RLS scopes findById to the caller's tenant, so a foreign id is a no-op). */
    @Transactional
    public void delete(String id) {
        pages.findById(id).ifPresent(pages::delete);
    }

    /** Capture a consented lead → a CONSENTED prospect stamped with the campaign. */
    @Transactional
    public LeadCapture captureLead(String slug, LeadForm body) {
        LandingPage page = pages.findByTenantIdAndSlug(tenantScope.currentTenantId(), slug).orElse(null);
        if (page == null) {
            return LeadCapture.NotFound.page();
        }
        String email = str(body.email(), null);
        if (!body.consented()) {
            return LeadCapture.Rejected.declined();
        }
        if (email == null || email.isBlank()) {
            return LeadCapture.Rejected.invalid("email is required");
        }
        // The lead's source is the page's campaign (or a utm override on the submit).
        String source = str(body.utmSource(), page.getUtmSource());
        prospects.captureLead(email, str(body.name(), null), source, "landing-page-optin");
        return LeadCapture.Captured.from(source);
    }

    /** The public landing page — a self-contained HTML page with a consent-first form. */
    @Transactional(readOnly = true)
    public String renderHtml(String slug, String utmOverride) {
        LandingPage page = pages.findByTenantIdAndSlug(tenantScope.currentTenantId(), slug).orElse(null);
        if (page == null) {
            return htmlShell("Not found", "<p>This page isn't available.</p>", null);
        }
        String utm = utmOverride != null && !utmOverride.isBlank() ? utmOverride : page.getUtmSource();
        String accent = page.getBrandColor() != null ? page.getBrandColor() : "#0f766e";
        String logo = page.getLogoUrl() == null ? ""
                : "<img class=\"logo\" alt=\"logo\" src=\"" + esc(page.getLogoUrl()) + "\">";
        String hero = page.getHeroImageUrl() == null ? ""
                : "<img class=\"hero\" alt=\"\" src=\"" + esc(page.getHeroImageUrl()) + "\">";
        String secondary = page.getCtaUrl() == null ? ""
                : "<a class=\"secondary\" href=\"" + esc(page.getCtaUrl()) + "\">Learn more →</a>";
        String footer = page.getPrivacyUrl() == null ? ""
                : "<p class=\"footer\"><a href=\"" + esc(page.getPrivacyUrl()) + "\">Privacy</a></p>";
        String body = logo + hero + "<h1>" + esc(page.getHeadline()) + "</h1>"
                + (page.getSubhead() == null ? "" : "<p class=\"sub\">" + esc(page.getSubhead()) + "</p>")
                + "<form id=\"lead\" onsubmit=\"return submitLead(event)\">"
                + "<input name=\"name\" placeholder=\"Your name\" autocomplete=\"name\">"
                + "<input name=\"email\" type=\"email\" required placeholder=\"Your email\" autocomplete=\"email\">"
                + "<label class=\"consent\"><input type=\"checkbox\" name=\"consent\" required> "
                + "Yes, send me this offer and related marketing. I can unsubscribe anytime.</label>"
                + "<button type=\"submit\">" + esc(page.getCtaLabel()) + "</button>"
                + "<p id=\"msg\" class=\"msg\"></p></form>"
                + "<script>async function submitLead(e){e.preventDefault();var f=e.target,"
                + "d={name:f.name.value,email:f.email.value,consent:f.consent.checked,utmSource:" + jsStr(utm) + "};"
                + "var r=await fetch(location.pathname.replace(/\\/view$/,'')+'/lead',{method:'POST',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});var j=await r.json();"
                + "document.getElementById('msg').textContent=j.captured?'Thanks — check your inbox!':"
                + "(j.reason||'Something went wrong.');if(j.captured)f.querySelector('button').disabled=true;"
                + "return false;}</script>" + secondary + footer;
        return htmlShell(page.getHeadline(), body, accent);
    }

    private static String htmlShell(String title, String body, String accent) {
        String a = accent == null ? "#0f766e" : accent;
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + esc(title)
                + "</title><style>:root{--accent:" + a + "}"
                + "body{font:16px/1.6 -apple-system,Segoe UI,Roboto,sans-serif;color:#20262b;"
                + "background:linear-gradient(160deg,color-mix(in srgb,var(--accent) 6%,#fff),#fff);margin:0}"
                + ".wrap{max-width:34rem;margin:0 auto;padding:3rem 1.25rem}"
                + ".logo{max-height:44px;margin:0 0 1.4rem;display:block}"
                + ".hero{width:100%;border-radius:14px;margin:0 0 1.4rem;object-fit:cover}"
                + "h1{font-size:2rem;line-height:1.2;color:var(--accent);margin:.2rem 0 .6rem}"
                + ".sub{color:#55606a;font-size:1.1rem;margin:0 0 1.6rem}"
                + "form{display:flex;flex-direction:column;gap:.7rem;background:#fff;padding:1.4rem;"
                + "border:1px solid #e3e8ea;border-radius:14px;box-shadow:0 6px 24px #00000010}"
                + "input[type=text],input[type=email],input:not([type]){padding:.7rem .8rem;font-size:1rem;"
                + "border:1px solid #cbd5d8;border-radius:8px}"
                + ".consent{display:flex;gap:.5rem;align-items:flex-start;font-size:.85rem;color:#55606a}"
                + "button{padding:.75rem 1rem;font-size:1rem;font-weight:600;color:#fff;background:var(--accent);"
                + "border:0;border-radius:8px;cursor:pointer}button:disabled{opacity:.5}"
                + ".msg{margin:.2rem 0 0;font-size:.9rem;color:var(--accent)}"
                + ".secondary{display:inline-block;margin:1rem 0 0;color:var(--accent);text-decoration:none;font-weight:600}"
                + ".footer{margin:2rem 0 0;font-size:.8rem}.footer a{color:#8894a0}</style></head>"
                + "<body><div class=\"wrap\">" + body + "</div></body></html>";
    }

    static LandingPageView view(LandingPage p) {
        return new LandingPageView(p.getId(), p.getSlug(), p.getHeadline(), p.getSubhead(), p.getCtaLabel(),
                p.getUtmSource(), p.getLogoUrl(), p.getHeroImageUrl(), p.getBrandColor(), p.getCtaUrl(), p.getPrivacyUrl(),
                "/insight/v1/landing/" + p.getSlug() + "/view", p.getCreatedAt());
    }

    /** Only http(s) or root-relative URLs — never javascript:/data: (XSS via src/href). */
    private static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        return (u.startsWith("https://") || u.startsWith("http://") || u.startsWith("/")) ? u : null;
    }

    /** Only a #hex colour — so the accent can't inject arbitrary CSS. */
    private static String safeColor(String color) {
        if (color == null) {
            return null;
        }
        String c = color.trim();
        return c.matches("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?") ? c : null;
    }

    private static String slugify(String s) {
        String base = s == null ? "page" : s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return base.isBlank() ? "page" : base;
    }

    private static String str(String o, String dflt) {
        return o == null || o.isBlank() ? dflt : o;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsStr(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }
}
