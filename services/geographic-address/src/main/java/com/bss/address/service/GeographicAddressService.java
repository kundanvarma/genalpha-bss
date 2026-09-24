package com.bss.address.service;

import com.bss.address.api.ApiConstants;
import com.bss.address.api.OffsetPageRequest;
import com.bss.address.api.PagedResult;
import com.bss.address.dto.AddressValidationRequest;
import com.bss.address.dto.AddressValidationResult;
import com.bss.address.dto.GeographicAddressRequest;
import com.bss.address.dto.GeographicAddressView;
import com.bss.address.dto.RegistryMatch;
import com.bss.address.dto.StandardizedAddress;
import com.bss.address.entity.GeographicAddress;
import com.bss.address.exception.BadRequestException;
import com.bss.address.exception.NotFoundException;
import com.bss.address.registry.RegistryAdapter;
import com.bss.address.registry.RegistryRouter;
import com.bss.address.repository.GeographicAddressRepository;
import com.bss.address.security.TenantRegistry;
import com.bss.address.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import static com.bss.address.api.Wire.idOf;

/**
 * TMF673: one place that knows what a deliverable address looks like.
 * Validation is the anonymous shop-window operation — normalize, apply the
 * per-country postcode rule, and either bless a standardized form or say
 * exactly what is wrong. Validated addresses can be stored so orders and
 * appointments reference an id instead of re-typing structures.
 */
@Service
public class GeographicAddressService {

    /** Dev footprint: Nordic market, digits-only postcodes. */
    /** Country-keyed postcode rules (the geography seam's reference set). A served
     * country without a specific rule gets the generic one — never a silent rejection. */
    private static final Map<String, Pattern> POSTCODE_RULES = Map.of(
            "SE", Pattern.compile("\\d{5}"),
            "NO", Pattern.compile("\\d{4}"),
            "DK", Pattern.compile("\\d{4}"),
            "FI", Pattern.compile("\\d{5}"),
            // Guyana Post Office 7-digit code: region, locality, district, post office (2), sub-locality (2)
            "GY", Pattern.compile("\\d{7}"));
    private static final Pattern GENERIC_POSTCODE = Pattern.compile("[A-Z0-9-]{3,10}");
    /** What an operator serves when its registry entry lists nothing: the platform's reference markets. */
    private static final Set<String> DEFAULT_SERVED = Set.of("SE", "NO", "DK", "FI");
    private static final Set<String> REQUIRED = Set.of("street1", "postCode", "city", "country");

    private final GeographicAddressRepository repository;
    private final TenantScope tenantScope;
    private final RegistryRouter registryRouter;
    private final TenantRegistry tenants;

    public GeographicAddressService(GeographicAddressRepository repository, TenantScope tenantScope,
            RegistryRouter registryRouter, TenantRegistry tenants) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.registryRouter = registryRouter;
        this.tenants = tenants;
    }

    /** The countries THIS tenant serves: its registry list, else the platform's rule set. */
    private Set<String> servedCountries() {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        if (tenant != null && tenant.getServedCountries() != null && !tenant.getServedCountries().isEmpty()) {
            return tenant.getServedCountries().stream()
                    .map(c -> String.valueOf(c).trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        }
        return DEFAULT_SERVED;
    }

    /** Anonymous: normalize + judge. Returns the TMF673 validation shape. */
    public AddressValidationResult validate(AddressValidationRequest request) {
        if (!(request.submittedGeographicAddress() instanceof Map<?, ?> submittedRaw)) {
            throw new BadRequestException("submittedGeographicAddress is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> submitted = (Map<String, Object>) submittedRaw;

        Map<String, Object> standardized = new LinkedHashMap<>();
        StringBuilder problem = new StringBuilder();
        for (String field : new String[] {"street1", "street2", "postCode", "city", "stateOrProvince", "country"}) {
            String value = submitted.get(field) == null ? null : String.valueOf(submitted.get(field)).trim();
            if ((value == null || value.isEmpty()) && REQUIRED.contains(field)) {
                problem.append(problem.isEmpty() ? "" : "; ").append(field).append(" is required");
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            standardized.put(field, switch (field) {
                case "country" -> value.toUpperCase(Locale.ROOT);
                case "postCode" -> value.replace(" ", "");
                case "city" -> titleCase(value);
                default -> value;
            });
        }
        if (problem.isEmpty()) {
            String country = String.valueOf(standardized.get("country"));
            Pattern rule = POSTCODE_RULES.getOrDefault(country, GENERIC_POSTCODE);
            if (!servedCountries().contains(country)) {
                problem.append("country '").append(country).append("' is not served");
            } else if (!rule.matcher(String.valueOf(standardized.get("postCode")).toUpperCase(Locale.ROOT)).matches()) {
                problem.append("postCode '").append(standardized.get("postCode"))
                        .append("' is not a valid ").append(country).append(" postcode");
            }
        }

        String id = UUID.randomUUID().toString();
        if (!problem.isEmpty()) {
            return AddressValidationResult.failed(id, submitted, problem.toString());
        }
        AddressValidationResult result = AddressValidationResult.success(id, submitted,
                standardOf(standardized).withType());
        return registryMatch(request, standardized).map(result::withRegistryMatch).orElse(result);
    }

    /** The normalised fields, still in the order the loop wrote them. */
    private static StandardizedAddress standardOf(Map<String, Object> standardized) {
        return new StandardizedAddress(str(standardized.get("street1")),
                str(standardized.get("street2")), str(standardized.get("postCode")),
                str(standardized.get("city")), str(standardized.get("stateOrProvince")),
                str(standardized.get("country")), null);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * The registry half (freg-address-plan F-P1): when the caller supplies a
     * party context, ask the country's national registry whether that person
     * is registered at the standardized address. AUTHENTICATED callers only —
     * the anonymous shop window must not become a who-lives-where oracle. A
     * country with no registry bound answers outcome=unavailable (the honest
     * degrade the risk seam reads); no party context = pure postal wash,
     * no registryMatch part at all.
     */
    private java.util.Optional<RegistryMatch> registryMatch(
            AddressValidationRequest request, Map<String, Object> standardized) {
        if (!(request.relatedParty() instanceof Map<?, ?> partyRaw)) {
            return java.util.Optional.empty();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            return java.util.Optional.empty();
        }
        String name = partyRaw.get("name") == null ? null : String.valueOf(partyRaw.get("name")).trim();
        if (name == null || name.isEmpty()) {
            return java.util.Optional.empty();
        }
        String birthDate = partyRaw.get("birthDate") == null ? null : String.valueOf(partyRaw.get("birthDate"));
        String country = String.valueOf(standardized.get("country"));
        Map<String, Object> claimed = new LinkedHashMap<>(standardized);
        claimed.remove("@type");
        // Who is the verification ABOUT? Un-named: the caller themselves. A
        // back-office caller (address:write) may name another party — the CSR
        // re-verify. Anyone else naming a party gets the OUTCOME but no event:
        // nobody can plant a verified-address event on a party they don't
        // hold, and it never mis-attributes to the caller either.
        String callerSub = jwt.getToken().getSubject();
        String claimedParty = idOf(partyRaw);
        boolean backOffice = auth.getAuthorities().stream()
                .anyMatch(a -> "address:write".equals(a.getAuthority()));
        String partyId = claimedParty == null ? callerSub : (backOffice ? claimedParty : null);
        return java.util.Optional.of(registryRouter
                .match(tenantScope.currentTenantId(), country,
                        new RegistryAdapter.Person(name, birthDate), claimed, callerSub, partyId)
                .map(RegistryMatch.class::cast)
                .orElseGet(() -> RegistryMatch.Unavailable.noRegistry(country)));
    }

    @Transactional
    public GeographicAddressView create(GeographicAddressRequest dto) {
        AddressValidationResult validation = validate(AddressValidationRequest.of(dto));
        if (!validation.isSuccess()) {
            throw new BadRequestException(validation.validationReason());
        }
        StandardizedAddress std = validation.standardizedGeographicAddress();
        GeographicAddress entity = new GeographicAddress();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/geographicAddress/" + id);
        entity.setStreet1(std.street1());
        entity.setStreet2(std.street2());
        entity.setPostCode(std.postCode());
        entity.setCity(std.city());
        entity.setStateOrProvince(std.stateOrProvince());
        entity.setCountry(std.country());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return GeographicAddressView.of(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public GeographicAddressView findById(String id) {
        return GeographicAddressView.of(repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("GeographicAddress", id)));
    }

    @Transactional(readOnly = true)
    public PagedResult<GeographicAddressView> findAll(int offset, int limit, Map<String, String> filters) {
        GeographicAddress probe = new GeographicAddress();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "postCode" -> probe.setPostCode(f.getValue());
                case "city" -> probe.setCity(f.getValue());
                case "country" -> probe.setCountry(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        Page<GeographicAddress> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(GeographicAddressView::of).toList(),
                page.getTotalElements());
    }

    private String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean start = true;
        for (char c : value.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return out.toString();
    }

}
