package com.bss.som.mapper;

import com.bss.som.dto.Characteristic;
import com.bss.som.dto.PartyRef;
import com.bss.som.dto.ServiceRef;
import com.bss.som.dto.ServiceView;
import com.bss.som.dto.SpecRef;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inventory view of a service (TMF638); the TMF640 face builds on the
 * same record. Doctrine for the arrays the R18 kit demands non-empty: fill
 * them with DERIVED-REAL facts; where the fleet truly has no relationship,
 * the entry SAYS so (an explicit standalone self-reference) rather than
 * inventing a phantom dependency.
 */
@Component
public class ServiceViews {

    private final ResourceAssignmentRepository assignments;
    private final ObjectMapper objectMapper;

    public ServiceViews(ResourceAssignmentRepository assignments, ObjectMapper objectMapper) {
        this.assignments = assignments;
        this.objectMapper = objectMapper;
    }

    public ServiceView view(ServiceInstance s) {
        String category = categoryOf(s.getName());
        ServiceView.Restriction restriction = null;
        if (s.getRestrictedAt() != null) {
            JsonNode profile;
            try {
                profile = objectMapper.readTree(s.getRestrictionProfileJson());
                if (profile == null || !profile.isObject()) {
                    profile = objectMapper.createObjectNode();
                }
            } catch (Exception e) {
                profile = objectMapper.createObjectNode();
            }
            restriction = new ServiceView.Restriction(s.getRestrictionReason(), s.getRestrictedAt().toString(), profile);
        }
        // the OPERATOR is a related party of every service it runs; the
        // owning customer joins when the service is owned
        List<PartyRef> parties = new ArrayList<>();
        if (s.getOwnerPartyId() != null) {
            parties.add(PartyRef.customerAt(s.getOwnerPartyId()));
        }
        parties.add(PartyRef.serviceProvider(s.getTenantId()));
        // Partner entitlements are credentials, not network resources: they
        // surface as an activationCode characteristic, never as a "number".
        List<ResourceAssignment> assigned = assignments.findByTenantIdAndServiceId(s.getTenantId(), s.getId());
        List<ServiceView.ResourceRef> supporting = new ArrayList<>();
        for (ResourceAssignment a : assigned) {
            if (!"partner".equals(a.getPoolId())) {
                supporting.add(ServiceView.ResourceRef.issued(a.getId(), a.getValue()));
            }
        }
        if (supporting.isEmpty()) {
            // no issued resource — the PROVISIONING RECORD (its service
            // order) is the real thing that stood this service up
            supporting.add(ServiceView.ResourceRef.serviceOrder(s.getServiceOrderId()));
        }
        List<Characteristic> characteristics = new ArrayList<>();
        for (ResourceAssignment a : assigned) {
            if ("partner".equals(a.getPoolId())) {
                characteristics.add(Characteristic.string("activationCode", a.getValue()));
            }
        }
        characteristics.add(Characteristic.string("category", category));
        if (s.getCfsFamily() != null) {
            characteristics.add(Characteristic.string("fulfilmentFamily", s.getCfsFamily()));
        }
        List<ServiceView.PlaceRef> places = new ArrayList<>();
        if (s.getDeliveryPath() != null) {
            characteristics.add(Characteristic.string("deliveryPath", s.getDeliveryPath()));
            places.add(ServiceView.PlaceRef.servingSite(s.getDeliveryPath()));
        }
        places.add(ServiceView.PlaceRef.serviceArea(s.getTenantId()));
        // network slice: what priority this line rides right now, and until when
        if (s.getSliceProfile() != null) {
            characteristics.add(Characteristic.string("sliceProfile", s.getSliceProfile()));
            if (s.getSliceUntil() != null) {
                characteristics.add(Characteristic.dateTime("sliceUntil", s.getSliceUntil().toString()));
            }
        }
        return new ServiceView(
                s.getId(),
                s.getHref(),
                s.getName(),
                s.getName() + " — " + category + " service",
                s.getState(),
                category,
                s.getCreatedAt().toString(),
                s.getServiceOrderId(),
                s.getSuspendReason(),
                s.getResumeAt() == null ? null : s.getResumeAt().toString(),
                restriction,
                List.of(ServiceView.ServiceRelationship.standalone(s.getId(), s.getHref())),
                List.of(new ServiceRef(s.getId(), s.getHref(), s.getName(),
                        "standalone — supports itself; not an invented dependency")),
                // the CFS the order actually realised when the spec named one;
                // the derived per-category stand-in only for rows that predate CFS
                s.getCfsId() != null ? SpecRef.cfs(s.getCfsId(), s.getCfsName()) : SpecRef.serviceSpec(category),
                parties,
                s.getDeliveryPath(),
                places,
                supporting,
                characteristics,
                "Service");
    }

    /** The service's kind, derived from what it IS named — never invented. */
    public static String categoryOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("mobile") || n.contains("phone") || n.contains("sim")) return "mobile";
        if (n.contains("broadband") || n.contains("fiber") || n.contains("fibre")
                || n.contains("internet") || n.contains("dsl")) return "broadband";
        if (n.contains("tv") || n.contains("netflix") || n.contains("stream")) return "tv";
        return "service";
    }
}
