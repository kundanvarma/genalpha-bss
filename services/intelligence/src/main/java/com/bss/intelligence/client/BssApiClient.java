package com.bss.intelligence.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Read-only machine access to the BSS APIs the scorer needs, always with
 * the acting tenant's own identity (the interceptor picks the token per
 * tenant). The scorer never writes to any domain — its only output is
 * events on its own topic.
 */
@Component
public class BssApiClient {

    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final RestClient agreementClient;
    private final RestClient recommendationClient;
    private final RestClient insightClient;
    private final RestClient inventoryClient;
    private final RestClient usageClient;
    private final RestClient ticketClient;
    private final RestClient assuranceClient;
    private final RestClient billingClient;
    private final ObjectMapper objectMapper;
    private final RestClient processClient;
    private final RestClient orderingClient;
    private final RestClient partyClient;

    public BssApiClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String agreementBaseUrl,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBaseUrl,
            @Value("${bss.downstream.ticket-base-url:http://localhost:8092}") String ticketBaseUrl,
            @Value("${bss.downstream.assurance-base-url:http://localhost:8105}") String assuranceBaseUrl,
            @Value("${bss.downstream.recommendation-base-url:http://localhost:8102}") String recommendationBaseUrl,
            @Value("${bss.downstream.insight-base-url:http://localhost:8119}") String insightBaseUrl,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8083}") String inventoryBaseUrl,
            @Value("${bss.downstream.billing-base-url:http://localhost:8088}") String billingBaseUrl,
            @Value("${bss.downstream.process-base-url:http://localhost:8116}") String processBaseUrl,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String orderingBaseUrl,
            @Value("${bss.downstream.party-base-url:http://localhost:8084}") String partyBaseUrl) {
        this.processClient = builder.baseUrl(processBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.agreementClient = builder.baseUrl(agreementBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.billingClient = builder.baseUrl(billingBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.usageClient = builder.baseUrl(usageBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.ticketClient = builder.baseUrl(ticketBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.assuranceClient = builder.baseUrl(assuranceBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.recommendationClient = builder.baseUrl(recommendationBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.insightClient = builder.baseUrl(insightBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.inventoryClient = builder.baseUrl(inventoryBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.orderingClient = builder.baseUrl(orderingBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.partyClient = builder.baseUrl(partyBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    /** The failed flow, with tasks and its cross-system timeline. */
    public Map<String, Object> processFlow(String flowId) {
        try {
            String body = processClient.get()
                    .uri("/tmf-api/processFlowManagement/v4/processFlow/" + flowId)
                    .retrieve().body(String.class);
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Design intent: the specs the flows are supposed to follow. */
    public List<Map<String, Object>> processSpecs() {
        try {
            String body = processClient.get()
                    .uri("/tmf-api/processFlowManagement/v4/processFlowSpecification")
                    .retrieve().body(String.class);
            return objectMapper.readValue(body, JSON_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** L0's only write: a ticket carrying the diagnosis as a NOTE — the
     * machine is the visible author, the assurance pattern. */
    public String openTicket(String name, String description, String partyId) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("name", name);
            body.put("description", description);
            body.put("severity", "major");
            body.put("ticketType", "incident");
            if (partyId != null) {
                body.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
            }
            String created = ticketClient.post()
                    .uri("/tmf-api/troubleTicket/v4/troubleTicket")
                    .body(body).retrieve().body(String.class);
            Map<String, Object> ticket =
                    objectMapper.readValue(created, new TypeReference<Map<String, Object>>() { });
            return ticket == null ? null : String.valueOf(ticket.get("id"));
        } catch (Exception e) {
            return null;
        }
    }

    public void addTicketNote(String ticketId, String text) {
        try {
            ticketClient.patch()
                    .uri("/tmf-api/troubleTicket/v4/troubleTicket/" + ticketId)
                    .body(Map.of("note", List.of(Map.of("text", text))))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            // the diagnosis still lives in the trace — a note failure is logged upstream
        }
    }

    public List<Map<String, Object>> ticketsOf(String partyId) {
        String body = ticketClient.get()
                .uri("/tmf-api/troubleTicket/v4/troubleTicket?relatedPartyId=" + partyId + "&limit=50")
                .retrieve().body(String.class);
        return parse(body);
    }

    /** The care backlog: acknowledged tickets nobody has started yet —
     * the workforce queue's ticket source. */
    public List<Map<String, Object>> unworkedTickets() {
        String body = ticketClient.get()
                .uri("/tmf-api/troubleTicket/v4/troubleTicket?status=acknowledged&limit=100")
                .retrieve().body(String.class);
        return parse(body);
    }

    /** One ticket, for completion VERIFICATION: a workforce task closes only
     * when the work behind it actually happened. Null when it is gone. */
    public Map<String, Object> ticketById(String ticketId) {
        try {
            String body = ticketClient.get()
                    .uri("/tmf-api/troubleTicket/v4/troubleTicket/" + ticketId)
                    .retrieve().body(String.class);
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** The AR backlog: unapplied cash — the workforce queue's money source. */
    public List<Map<String, Object>> unappliedCash() {
        String body = billingClient.get()
                .uri("/tmf-api/customerBillManagement/v4/remittance/unapplied")
                .retrieve().body(String.class);
        return parse(body);
    }

    public List<Map<String, Object>> openServiceProblems() {
        String body = assuranceClient.get()
                .uri("/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open")
                .retrieve().body(String.class);
        return parse(body);
    }

    /** ALL active agreements, paginated — a sweep that only reads page one
     * goes blind the day the table outgrows it (it did). */
    public List<Map<String, Object>> activeAgreements() {
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (int offset = 0; offset < 5000; offset += 100) {
            String body = agreementClient.get()
                    .uri("/tmf-api/agreementManagement/v4/agreement?status=active&limit=100&offset=" + offset)
                    .retrieve().body(String.class);
            List<Map<String, Object>> page = parse(body);
            all.addAll(page);
            if (page.size() < 100) {
                break;
            }
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> usageMeters(String partyId) {
        String body = usageClient.get()
                .uri("/tmf-api/usageConsumption/v4/queryUsageConsumption?relatedPartyId=" + partyId)
                .retrieve().body(String.class);
        try {
            Map<String, Object> report = objectMapper.readValue(body, Map.class);
            return report.get("bucket") instanceof List<?> buckets
                    ? (List<Map<String, Object>>) buckets : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The TMF680 ranking (already interest-fused) — NBO reasons over it. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recommendationItems(String partyId) {
        try {
            String body = recommendationClient.get()
                    .uri("/tmf-api/recommendationManagement/v4/recommendation?relatedPartyId=" + partyId)
                    .retrieve().body(String.class);
            List<Map<String, Object>> recs = parse(body);
            return recs.isEmpty() ? List.of()
                    : recs.get(0).get("recommendationItem") instanceof List<?> items
                            ? (List<Map<String, Object>>) items : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> interestsOf(String partyId) {
        try {
            Map<String, Object> profile = objectMapper.readValue(insightClient.get()
                    .uri("/insight/v1/partyProfile?partyId=" + partyId)
                    .retrieve().body(String.class), Map.class);
            return profile.get("interests") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> holdingsOf(String partyId) {
        try {
            String body = inventoryClient.get()
                    .uri("/tmf-api/productInventory/v4/product?relatedPartyId=" + partyId + "&limit=50")
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The allowance LADDER: every offering's allowance per usage type —
     * the upgrade path nobody declared, assembled from data that always
     * existed. */
    public List<Map<String, Object>> usageAllowances() {
        try {
            String body = usageClient.get()
                    .uri("/tmf-api/usageManagement/v4/usageAllowance?limit=200")
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ---- TMF696 risk signals: exactly what the fleet's data knows ---- */

    /** Every order this party owns (velocity computes from orderDate). */
    public List<Map<String, Object>> ordersOf(String partyId) {
        try {
            String body = orderingClient.get()
                    .uri("/tmf-api/productOrderingManagement/v4/productOrder?relatedPartyId={p}&limit=100", partyId)
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The party's unpaid bills (state=new). */
    public List<Map<String, Object>> unpaidBills(String partyId) {
        try {
            String body = billingClient.get()
                    .uri("/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId={p}&state=new&limit=100", partyId)
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The party's credit notes — money the operator gave back. */
    public List<Map<String, Object>> creditNotesOf(String partyId) {
        try {
            String body = billingClient.get()
                    .uri("/tmf-api/customerBillManagement/v4/creditNote?relatedPartyId={p}", partyId)
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The party's roles — PartyRole.createdAt is the only tenure signal. */
    public List<Map<String, Object>> partyRolesOf(String partyId) {
        try {
            String body = partyClient.get()
                    .uri("/tmf-api/partyRoleManagement/v4/partyRole?partyId={p}", partyId)
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parse(String body) {
        try {
            return objectMapper.readValue(body, JSON_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
