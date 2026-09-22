package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.client.DownstreamClients;
import com.bss.quote.dto.ConfigRuleView;
import com.bss.quote.dto.ConfigurationCheck;
import com.bss.quote.dto.ConfigurationCheck.RuleViolation;
import com.bss.quote.dto.EntityRef;
import com.bss.quote.dto.GuidedSelling;
import com.bss.quote.dto.GuidedSelling.Recommendation;
import com.bss.quote.dto.HandoffBodies.AgreementItem;
import com.bss.quote.dto.HandoffBodies.AgreementRequest;
import com.bss.quote.dto.HandoffBodies.NameValue;
import com.bss.quote.dto.HandoffBodies.NarrativeContext;
import com.bss.quote.dto.HandoffBodies.OrderItem;
import com.bss.quote.dto.HandoffBodies.ProductOrderRequest;
import com.bss.quote.dto.LeadSignal;
import com.bss.quote.dto.LineItem;
import com.bss.quote.dto.Money;
import com.bss.quote.dto.PricingRuleView;
import com.bss.quote.dto.QuoteItem;
import com.bss.quote.dto.QuoteRequests.ConfigRuleRequest;
import com.bss.quote.dto.QuoteRequests.GuidedQuestionRequest;
import com.bss.quote.dto.QuoteRequests.GuidedRecommendationRequest;
import com.bss.quote.dto.QuoteRequests.PricingRuleRequest;
import com.bss.quote.dto.QuoteRequests.QuotePatch;
import com.bss.quote.dto.QuoteRequests.QuoteRequest;
import com.bss.quote.dto.QuoteRequests.SignRequest;
import com.bss.quote.dto.QuoteView;
import com.bss.quote.dto.RelatedPartyRef;
import com.bss.quote.entity.GuidedQuestion;
import com.bss.quote.entity.GuidedRecommendation;
import com.bss.quote.entity.Quote;
import com.bss.quote.entity.QuoteConfigRule;
import com.bss.quote.entity.QuotePricingRule;
import com.bss.quote.events.DomainEventPublisher;
import com.bss.quote.exception.BadRequestException;
import com.bss.quote.exception.ConflictException;
import com.bss.quote.exception.NotFoundException;
import com.bss.quote.repository.GuidedQuestionRepository;
import com.bss.quote.repository.GuidedRecommendationRepository;
import com.bss.quote.repository.QuoteConfigRuleRepository;
import com.bss.quote.repository.QuotePricingRuleRepository;
import com.bss.quote.repository.QuoteRepository;
import com.bss.quote.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF648, the commercial half of the intent loop: the OSS's proposal
 * priced. A quote is born FROM an intent — every proposed service item is
 * matched to a catalog offering with its real prices and token allowances,
 * an optional AI narrative explains the deal in the customer's language,
 * and acceptance hands straight into product ordering. Lead to order,
 * no swivel chairs.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private static final TypeReference<List<QuoteItem>> ITEMS = new TypeReference<>() {
    };

    private final QuoteRepository quotes;
    private final QuoteConfigRuleRepository configRules;
    private final GuidedQuestionRepository guidedQuestions;
    private final GuidedRecommendationRepository guidedRecos;
    private final QuotePricingRuleRepository pricingRules;
    private final DownstreamClients downstream;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final BigDecimal discountThreshold;

    public QuoteService(QuoteRepository quotes,
            QuoteConfigRuleRepository configRules,
            GuidedQuestionRepository guidedQuestions,
            GuidedRecommendationRepository guidedRecos,
            QuotePricingRuleRepository pricingRules,
            DownstreamClients downstream, DomainEventPublisher events, TenantScope tenantScope,
            ObjectMapper objectMapper,
            @Value("${bss.quote.discount-approval-threshold:20}") String threshold) {
        this.quotes = quotes;
        this.configRules = configRules;
        this.guidedQuestions = guidedQuestions;
        this.guidedRecos = guidedRecos;
        this.pricingRules = pricingRules;
        this.downstream = downstream;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.discountThreshold = new BigDecimal(threshold);
    }

    @Transactional
    public QuoteView createFromIntent(QuoteRequest dto) {
        if (dto.intentId() == null) {
            throw new BadRequestException("intentId is required — quotes are born from intents here");
        }
        JsonNode intent = downstream.intent(dto.intentId());
        JsonNode report = intent.path("intentReport");
        if (!report.isObject() || !report.path("feasible").asBoolean(false)) {
            throw new ConflictException("the intent is not feasibility-checked; nothing to quote");
        }

        Map<String, JsonNode> catalog = new LinkedHashMap<>();
        for (JsonNode offering : downstream.offerings()) {
            // only sellable lifecycles — a retired offering must fail fast at
            // quote time, not survive to be refused at order accept
            String lifecycle = text(offering.path("lifecycleStatus"));
            if (!"Active".equals(lifecycle) && !"Launched".equals(lifecycle)) continue;
            catalog.put(offering.path("name").asText(), offering);
        }
        JsonNode allowances = downstream.allowances();

        List<QuoteItem> items = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        String currency = "EUR";
        for (JsonNode proposed : report.path("proposedItems")) {
            String offeringName = proposed.path("offeringName").asText();
            JsonNode offering = catalog.get(offeringName);
            if (offering == null) {
                throw new ConflictException("proposed offering '" + offeringName + "' is not in the catalog");
            }
            String offeringId = offering.path("id").asText();
            Money unitPrice = null;
            JsonNode priceRefs = offering.path("productOfferingPrice");
            if (priceRefs.isArray() && priceRefs.size() > 0 && priceRefs.get(0).isObject()) {
                JsonNode price = downstream.offeringPrice(priceRefs.get(0).path("id").asText());
                JsonNode money = price.path("price");
                if (money.isObject() && money.hasNonNull("value")) {
                    BigDecimal value = new BigDecimal(money.get("value").asText());
                    currency = money.path("unit").asText();
                    String period = text(price.path("recurringChargePeriodType"));
                    unitPrice = new Money(value, currency, period);
                    if ("month".equals(period)) {
                        monthly = monthly.add(value);
                    }
                }
            }
            // Token economics on the line item: what is included, what overage costs.
            QuoteItem.Allowance allowance = null;
            for (JsonNode a : allowances) {
                JsonNode ref = a.path("productOffering");
                if (ref.isObject() && offeringId.equals(ref.path("id").asText())) {
                    allowance = new QuoteItem.Allowance(text(a.path("usageType")),
                            a.get("allowance"), a.get("overagePrice"));
                }
            }
            items.add(QuoteItem.proposed(EntityRef.of(offeringId, offering.path("name").asText()),
                    text(proposed.path("reason")), unitPrice, allowance));
        }

        Quote quote = new Quote();
        quote.setId(UUID.randomUUID().toString());
        quote.setTenantId(tenantScope.currentTenantId());
        quote.setHref(ApiConstants.BASE_PATH + "/quote/" + quote.getId());
        quote.setDescription(dto.description() == null ? intent.path("name").asText() : dto.description());
        quote.setState(Quote.IN_PROGRESS);
        quote.setIntentId(dto.intentId());
        JsonNode parties = intent.path("relatedParty");
        if (parties.isArray() && parties.size() > 0 && parties.get(0).isObject()) {
            quote.setOwnerPartyId(parties.get(0).path("id").asText());
        }
        writeItems(quote, items);
        quote.setMonthlyTotal(monthly);
        quote.setCurrency(currency);
        quote.setNarrative(downstream.quoteNarrative(
                new NarrativeContext(quote.getDescription(), items, monthly, currency)));
        quote.setCreatedAt(OffsetDateTime.now());
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = QuoteView.of(quote, items);
        events.publish("QuoteCreateEvent", "quote", result);
        return result;
    }

    /**
     * CPQ C1 — the opportunity → quote hand-off. Build a quote from a
     * developed opportunity's negotiated line items: MRR from the recurring
     * lines, one-off from the rest. State opens at inProgress like any quote.
     */
    @Transactional
    public QuoteView createFromLineItems(String description, String ownerPartyId,
            String currency, List<LineItem> lineItems) {
        // The configuration rules gate the build: a quote that violates
        // requires/excludes/min/max cannot be created.
        ConfigurationCheck check = validate(lineItems);
        if (!check.valid()) {
            String msgs = check.violations().stream().map(RuleViolation::message)
                    .reduce((x, y) -> x + "; " + y).orElse("configuration invalid");
            throw new ConflictException("configuration rules violated: " + msgs);
        }
        String cur = currency == null ? "USD" : currency;
        List<QuoteItem> items = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        List<QuotePricingRule> tiers = pricingRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId());
        // The buyer's CDP segments (resolved once) — the same governed segment
        // definition marketing targets on. Fail-soft: no CDP → list/volume only.
        Set<String> buyerSegments = tiers.stream().anyMatch(t -> t.getSegment() != null)
                ? downstream.partySegments(ownerPartyId) : Set.of();
        for (LineItem li : lineItems) {
            boolean recurring = li.isRecurring();
            int qty = li.quantityOrOne();
            BigDecimal listUnit = li.unitPriceOrZero();
            String offeringName = String.valueOf(li.offeringName());
            // Most-specific-wins: a matching SEGMENT price beats a volume tier.
            BigDecimal segmentDiscount = BigDecimal.ZERO;
            BigDecimal volumeDiscount = BigDecimal.ZERO;
            String segmentApplied = null;
            for (QuotePricingRule t : tiers) {
                if (!t.getOfferingName().equalsIgnoreCase(offeringName) || qty < t.getMinQuantity()) continue;
                if (t.getSegment() != null) {
                    if (buyerSegments.contains(t.getSegment())
                            && t.getDiscountPercent().compareTo(segmentDiscount) > 0) {
                        segmentDiscount = t.getDiscountPercent();
                        segmentApplied = t.getSegment();
                    }
                } else if (t.getDiscountPercent().compareTo(volumeDiscount) > 0) {
                    volumeDiscount = t.getDiscountPercent();
                }
            }
            boolean bySegment = segmentDiscount.signum() > 0;
            BigDecimal discount = bySegment ? segmentDiscount : volumeDiscount;
            BigDecimal unit = discount.signum() > 0
                    ? listUnit.multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)))
                    : listUnit;
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty));
            EntityRef offering = EntityRef.of(li.offeringId() == null ? "" : li.offeringId(), li.offeringName());
            Money unitPrice = recurring ? Money.monthly(unit, cur) : Money.oneTime(unit, cur);
            QuoteItem item;
            if (discount.signum() <= 0) {
                item = QuoteItem.priced(offering, qty, unitPrice, recurring);
            } else if (bySegment) {
                item = QuoteItem.bySegment(offering, qty, unitPrice, listUnit, discount, segmentApplied, recurring);
            } else {
                item = QuoteItem.byVolume(offering, qty, unitPrice, listUnit, discount, recurring);
            }
            items.add(item);
            if (recurring) monthly = monthly.add(lineTotal); else oneTime = oneTime.add(lineTotal);
        }
        Quote quote = new Quote();
        quote.setId(UUID.randomUUID().toString());
        quote.setTenantId(tenantScope.currentTenantId());
        quote.setHref(ApiConstants.BASE_PATH + "/quote/" + quote.getId());
        quote.setDescription(description == null ? "Opportunity quote" : description);
        quote.setState(Quote.IN_PROGRESS);
        quote.setOwnerPartyId(ownerPartyId);
        writeItems(quote, items);
        quote.setMonthlyTotal(monthly);
        quote.setOneTimeTotal(oneTime);
        quote.setCurrency(cur);
        quote.setCreatedAt(OffsetDateTime.now());
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = QuoteView.of(quote, items);
        events.publish("QuoteCreateEvent", "quote", result);
        return result;
    }

    // ---------------- CPQ C2: configuration rules ----------------

    @Transactional
    public ConfigRuleView createRule(ConfigRuleRequest dto) {
        String type = dto.ruleType();
        if (!List.of(QuoteConfigRule.REQUIRES, QuoteConfigRule.EXCLUDES,
                QuoteConfigRule.MIN_QTY, QuoteConfigRule.MAX_QTY).contains(type)) {
            throw new BadRequestException("ruleType must be requires/excludes/minQty/maxQty");
        }
        if (dto.subjectOffering() == null) {
            throw new BadRequestException("subjectOffering is required");
        }
        QuoteConfigRule r = new QuoteConfigRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setRuleType(type);
        r.setSubjectOffering(dto.subjectOffering());
        r.setObjectOffering(dto.objectOffering());
        r.setQty(dto.qty());
        r.setMessage(dto.message() == null ? defaultRuleMessage(r) : dto.message());
        r.setCreatedAt(OffsetDateTime.now());
        configRules.save(r);
        return ConfigRuleView.of(r);
    }

    @Transactional(readOnly = true)
    public List<ConfigRuleView> listRules() {
        return configRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(ConfigRuleView::of).toList();
    }

    /**
     * The CPQ decision endpoint — pure, no mutation: check a set of line items
     * against the configuration rules and return the violations. An agent (or
     * the quote builder) calls this before committing a configuration.
     */
    @Transactional(readOnly = true)
    public ConfigurationCheck validate(List<LineItem> lineItems) {
        Map<String, Integer> qtyByName = new LinkedHashMap<>();
        for (LineItem li : lineItems) {
            qtyByName.merge(String.valueOf(li.offeringName()), li.quantityOrOne(), Integer::sum);
        }
        List<RuleViolation> violations = new ArrayList<>();
        for (QuoteConfigRule r : configRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())) {
            if (!qtyByName.containsKey(r.getSubjectOffering())) continue; // rule's subject not on the deal
            boolean ok = switch (r.getRuleType()) {
                case QuoteConfigRule.REQUIRES -> qtyByName.containsKey(r.getObjectOffering());
                case QuoteConfigRule.EXCLUDES -> !qtyByName.containsKey(r.getObjectOffering());
                case QuoteConfigRule.MIN_QTY -> r.getQty() == null
                        || qtyByName.get(r.getSubjectOffering()) >= r.getQty();
                case QuoteConfigRule.MAX_QTY -> r.getQty() == null
                        || qtyByName.get(r.getSubjectOffering()) <= r.getQty();
                default -> true;
            };
            if (!ok) violations.add(RuleViolation.of(r));
        }
        return ConfigurationCheck.of(violations);
    }

    /** The CDP's lead signal for an email — used by lead scoring. */
    @Transactional(readOnly = true)
    public LeadSignal leadSignal(String email) {
        return email == null ? LeadSignal.NONE : downstream.leadSignal(email);
    }

    /** Approve a pending discount so the quote can proceed (the human gate). */
    @Transactional
    public QuoteView approveDiscount(String id) {
        Quote quote = own(id);
        if (!Quote.APPR_PENDING.equals(quote.getApprovalStatus())) {
            throw new ConflictException("this quote has no discount pending approval");
        }
        quote.setApprovalStatus(Quote.APPR_APPROVED);
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = toView(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    // ---------------- CPQ C2: guided selling ----------------

    @Transactional
    public GuidedSelling.QuestionView createGuidedQuestion(GuidedQuestionRequest dto) {
        if (dto.questionKey() == null || dto.prompt() == null) {
            throw new BadRequestException("questionKey and prompt are required");
        }
        GuidedQuestion q = new GuidedQuestion();
        q.setId(UUID.randomUUID().toString());
        q.setTenantId(tenantScope.currentTenantId());
        q.setQuestionKey(dto.questionKey());
        q.setPrompt(dto.prompt());
        q.setSortOrder(dto.sortOrder() == null ? 0 : dto.sortOrder());
        q.setCreatedAt(OffsetDateTime.now());
        guidedQuestions.save(q);
        return GuidedSelling.QuestionView.of(q);
    }

    @Transactional(readOnly = true)
    public List<GuidedSelling.QuestionView> listGuidedQuestions() {
        return guidedQuestions.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantScope.currentTenantId())
                .stream().map(GuidedSelling.QuestionView::of).toList();
    }

    @Transactional
    public GuidedSelling.RecommendationRuleView createGuidedRecommendation(GuidedRecommendationRequest dto) {
        if (dto.questionKey() == null || dto.answerValue() == null || dto.offeringName() == null) {
            throw new BadRequestException("questionKey, answerValue and offeringName are required");
        }
        GuidedRecommendation r = new GuidedRecommendation();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setQuestionKey(dto.questionKey());
        r.setAnswerValue(dto.answerValue());
        r.setOfferingName(dto.offeringName());
        r.setQuantity(dto.quantity() == null ? 1 : Math.max(1, dto.quantity()));
        r.setCreatedAt(OffsetDateTime.now());
        guidedRecos.save(r);
        return GuidedSelling.RecommendationRuleView.of(r);
    }

    @Transactional(readOnly = true)
    public List<GuidedSelling.RecommendationRuleView> listGuidedRecommendations() {
        return guidedRecos.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(GuidedSelling.RecommendationRuleView::of).toList();
    }

    /**
     * The guided-selling decision (pure, no mutation): given answers, return the
     * recommended offerings. Agent-callable — an LLM can drive the questionnaire
     * and get a configuration back. The answers are an open document keyed by
     * question, either flat or under {@code answers}.
     */
    @Transactional(readOnly = true)
    public GuidedSelling.Recommendations recommend(JsonNode answers) {
        JsonNode answerMap = answers == null ? objectMapper.createObjectNode()
                : answers.path("answers").isObject() ? answers.get("answers") : answers;
        // Merge duplicate offerings by summing the recommended quantity.
        Map<String, Integer> byOffering = new LinkedHashMap<>();
        Map<String, String> because = new LinkedHashMap<>();
        for (GuidedRecommendation r : guidedRecos.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())) {
            JsonNode given = answerMap.get(r.getQuestionKey());
            if (given != null && !given.isNull() && r.getAnswerValue().equalsIgnoreCase(given.asText())) {
                byOffering.merge(r.getOfferingName(), r.getQuantity(), Integer::sum);
                because.putIfAbsent(r.getOfferingName(), r.getQuestionKey() + "=" + r.getAnswerValue());
            }
        }
        List<Recommendation> recommendations = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byOffering.entrySet()) {
            recommendations.add(new Recommendation(e.getKey(), e.getValue(), because.get(e.getKey())));
        }
        return new GuidedSelling.Recommendations(recommendations);
    }

    // ---------------- CPQ: volume pricing rules ----------------

    @Transactional
    public PricingRuleView createPricingRule(PricingRuleRequest dto) {
        if (dto.offeringName() == null || dto.discountPercent() == null) {
            throw new BadRequestException("offeringName and discountPercent are required");
        }
        QuotePricingRule r = new QuotePricingRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setOfferingName(dto.offeringName());
        r.setMinQuantity(dto.minQuantity() == null ? 1 : Math.max(1, dto.minQuantity()));
        r.setSegment(dto.segment());
        r.setDiscountPercent(dto.discountPercent());
        r.setCreatedAt(OffsetDateTime.now());
        pricingRules.save(r);
        return PricingRuleView.of(r);
    }

    @Transactional(readOnly = true)
    public List<PricingRuleView> listPricingRules() {
        return pricingRules.findByTenantIdOrderByCreatedAt(tenantScope.currentTenantId())
                .stream().map(PricingRuleView::of).toList();
    }

    // ---------------- CPQ: e-signature ----------------

    /** The e-sign callback: the customer signed the quote document. */
    @Transactional
    public QuoteView sign(String id, SignRequest dto) {
        Quote quote = own(id);
        if (dto.signedBy() == null) throw new BadRequestException("signedBy is required");
        quote.setSignatureStatus("signed");
        quote.setSignedBy(dto.signedBy());
        quote.setSignedAt(OffsetDateTime.now());
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = toView(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    private String defaultRuleMessage(QuoteConfigRule r) {
        return switch (r.getRuleType()) {
            case QuoteConfigRule.REQUIRES -> r.getSubjectOffering() + " requires " + r.getObjectOffering();
            case QuoteConfigRule.EXCLUDES -> r.getSubjectOffering() + " cannot be sold with " + r.getObjectOffering();
            case QuoteConfigRule.MIN_QTY -> r.getSubjectOffering() + " needs a quantity of at least " + r.getQty();
            case QuoteConfigRule.MAX_QTY -> r.getSubjectOffering() + " allows at most " + r.getQty();
            default -> "configuration rule violated";
        };
    }

    /** A branded, printable quote document (HTML) the rep can send. */
    @Transactional(readOnly = true)
    public String renderDocument(String id) {
        Quote quote = own(id);
        StringBuilder rows = new StringBuilder();
        for (QuoteItem item : readItems(quote)) {
            String name = item.offering() != null ? String.valueOf(item.offering().name()) : "—";
            Money up = item.unitPrice();
            String price = up != null ? up.value() + " " + up.unit() + "/" + up.period() : "—";
            String qty = String.valueOf(item.quantity() == null ? 1 : item.quantity());
            rows.append("<tr><td>").append(esc(name)).append("</td><td style=\"text-align:right\">")
                    .append(esc(qty)).append("</td><td style=\"text-align:right\">")
                    .append(esc(price)).append("</td></tr>");
        }
        String cur = quote.getCurrency();
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Quote "
                + esc(quote.getId()) + "</title><style>body{font-family:system-ui,sans-serif;"
                + "max-width:720px;margin:2rem auto;color:#1a1a1a}h1{font-size:1.4rem}"
                + "table{width:100%;border-collapse:collapse;margin:1rem 0}"
                + "th,td{padding:0.5rem;border-bottom:1px solid #ddd;text-align:left}"
                + ".totals{margin-top:1rem;font-size:1.05rem}.dim{color:#666}</style></head><body>"
                + "<h1>Quotation</h1><p class=\"dim\">" + esc(quote.getDescription()) + "</p>"
                + "<p class=\"dim\">Quote " + esc(quote.getId()) + " · status " + esc(quote.getState()) + "</p>"
                + "<table><thead><tr><th>Item</th><th style=\"text-align:right\">Qty</th>"
                + "<th style=\"text-align:right\">Price</th></tr></thead><tbody>" + rows + "</tbody></table>"
                + "<div class=\"totals\"><b>Monthly (recurring): " + quote.getMonthlyTotal() + " " + esc(cur)
                + "</b><br><b>One-time: " + quote.getOneTimeTotal() + " " + esc(cur) + "</b></div>"
                + ("signed".equals(quote.getSignatureStatus())
                        ? "<div style=\"margin-top:2rem;padding:1rem;border:2px solid #2a7;border-radius:6px\">"
                          + "<b>✓ Signed</b> by " + esc(quote.getSignedBy()) + " on "
                          + esc(String.valueOf(quote.getSignedAt())) + "</div>"
                        : "<div style=\"margin-top:2rem;padding:1rem;border:1px dashed #999;border-radius:6px\" class=\"dim\">"
                          + "Signature: ____________________  (sign to accept)</div>")
                + "<p class=\"dim\" style=\"margin-top:1.5rem\">This quotation is valid subject to the terms of service.</p>"
                + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Transactional(readOnly = true)
    public List<QuoteView> findAll() {
        return quotes.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public QuoteView findById(String id) {
        return toView(own(id));
    }

    @Transactional
    public QuoteView patch(String id, QuotePatch patch) {
        Quote quote = own(id);
        // A discount over the threshold needs manager approval before the quote
        // can advance — the human gate on a (possibly agent-proposed) discount.
        if (patch.discountPercent() != null) {
            BigDecimal disc = patch.discountPercent();
            quote.setDiscountPercent(disc);
            quote.setApprovalStatus(disc.compareTo(discountThreshold) > 0
                    ? Quote.APPR_PENDING : Quote.APPR_NOT_REQUIRED);
        }
        if (patch.state() != null) {
            String target = patch.state();
            if (!List.of(Quote.APPROVED, Quote.REJECTED).contains(target)
                    || !Quote.IN_PROGRESS.equals(quote.getState())) {
                throw new ConflictException("only inProgress quotes move to approved/rejected");
            }
            if (Quote.APPROVED.equals(target) && Quote.APPR_PENDING.equals(quote.getApprovalStatus())) {
                throw new ConflictException("the discount on this quote is pending approval — "
                        + "a manager must approve it before the quote can be approved");
            }
            quote.setState(target);
        }
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = toView(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    /** The handoff: an approved quote becomes a product order AND a contract
     *  (TMF651 agreement), linked back here — atomically once. */
    @Transactional
    public QuoteView accept(String id) {
        Quote quote = own(id);
        if (!Quote.APPROVED.equals(quote.getState())) {
            throw new ConflictException("only approved quotes can be accepted");
        }
        String party = quote.getOwnerPartyId() == null ? "unknown" : quote.getOwnerPartyId();
        List<OrderItem> orderItems = new ArrayList<>();
        List<AgreementItem> agreementItems = new ArrayList<>();
        for (QuoteItem item : readItems(quote)) {
            EntityRef offering = EntityRef.of(item.offering().id(), item.offering().name());
            orderItems.add(OrderItem.add(offering));
            agreementItems.add(new AgreementItem(offering));
        }
        JsonNode order = downstream.placeOrder(new ProductOrderRequest(orderItems,
                List.of(RelatedPartyRef.customer(party))));
        quote.setProductOrderId(text(order.path("id")));
        // The contract: a TMF651 agreement for the same party + items, tagged
        // with the quote it came from.
        try {
            JsonNode agreement = downstream.createAgreement(new AgreementRequest(
                    "Agreement — " + quote.getDescription(), "commercial", "active",
                    List.of(RelatedPartyRef.customer(party)), agreementItems,
                    List.of(new NameValue("quoteId", quote.getId()))));
            String agreementId = text(agreement.path("id"));
            if (agreementId != null) {
                quote.setAgreementId(agreementId);
            }
        } catch (RestClientException e) {
            // Fail-soft: the order stands; the contract can be reconciled. Do
            // not lose the accepted order to a contract hiccup.
            log.warn("agreement creation failed for quote {}: {}", quote.getId(), e.getMessage());
        }
        quote.setState(Quote.ACCEPTED);
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        QuoteView result = toView(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    private Quote own(String id) {
        return quotes.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Quote", id));
    }

    private void writeItems(Quote quote, List<QuoteItem> items) {
        try {
            quote.setItems(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException("items serialization failed", e);
        }
    }

    /** The stored lines, as written: money keeps the scale it was stored with. */
    private List<QuoteItem> readItems(Quote quote) {
        try {
            return objectMapper.readValue(quote.getItems(), ITEMS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private QuoteView toView(Quote quote) {
        return QuoteView.of(quote, readItems(quote));
    }

    /** A node's text, or null when it is absent or JSON null. */
    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
