package com.bss.cart;

import com.bss.cart.entity.ShoppingCart;
import com.bss.cart.repository.ShoppingCartRepository;
import com.bss.cart.service.ShoppingCartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShoppingCartApiTest {

    private static final String BASE = "/tmf-api/shoppingCart/v4/shoppingCart";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShoppingCartRepository repository;

    @Autowired
    private ShoppingCartService service;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("customer"));
    }

    private static RequestPostProcessor agent() {
        return jwt().jwt(j -> j.subject("agent-1").claim("org", "genalpha-retail"))
                .authorities(new SimpleGrantedAuthority("agent"));
    }

    private String guestCart() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItem\": [{\"id\": \"line-1\", \"quantity\": 1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void guestCart_worksById_thenLoginClaimsIt_andLocksOutStrangers() throws Exception {
        String id = guestCart();

        // Anonymous updates by id (the id is the secret).
        mockMvc.perform(patch(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItem\": [{\"id\": \"line-1\", \"quantity\": 3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartItem[0].quantity").value(3));

        // First authenticated touch claims it.
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-c1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedParty[0].id").value("cust-c1"));

        // Claimed: anonymous and foreign customers get 404, the owner gets it.
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + id).with(customer("cust-thief"))).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + id).with(customer("cust-c1"))).andExpect(status().isOk());

        // Assisted checkout: an agent can read the claimed cart.
        mockMvc.perform(get(BASE + "/" + id).with(agent())).andExpect(status().isOk());
    }

    @Test
    void checkedOutCart_isImmutableHistory() throws Exception {
        String id = guestCart();
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-c2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "checkedOut",
                                 "relatedEntity": [{"id": "order-9", "@referredType": "ProductOrder"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("checkedOut"))
                .andExpect(jsonPath("$.relatedEntity[0].id").value("order-9"));

        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-c2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItem\": []}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listingRequiresIdentity_andScopesToTheCustomer() throws Exception {
        mockMvc.perform(get(BASE + "?limit=100")).andExpect(status().isBadRequest());

        String id = guestCart();
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-c3"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-c3")))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-c4")))
                .andExpect(jsonPath("$.length()").value(0));
        // Agents filter by party for assisted flows.
        mockMvc.perform(get(BASE + "?limit=100&relatedPartyId=cust-c3").with(agent()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sweeper_abandonsIdleOwnedCarts_once_guestCartsExpireSilently() {
        ShoppingCart owned = new ShoppingCart();
        owned.setId(UUID.randomUUID().toString());
        owned.setStatus(ShoppingCart.ACTIVE);
        owned.setOwnerPartyId("cust-idle");
        owned.setLastUpdate(OffsetDateTime.now().minusDays(3));
        repository.save(owned);

        ShoppingCart guest = new ShoppingCart();
        guest.setId(UUID.randomUUID().toString());
        guest.setStatus(ShoppingCart.ACTIVE);
        guest.setLastUpdate(OffsetDateTime.now().minusDays(3));
        repository.save(guest);

        assertThat(service.sweepAbandoned()).isEqualTo(1);
        assertThat(repository.findById(owned.getId()).orElseThrow().getStatus())
                .isEqualTo(ShoppingCart.ABANDONED);
        assertThat(repository.findById(guest.getId()).orElseThrow().getStatus())
                .isEqualTo(ShoppingCart.ACTIVE);
        assertThat(service.sweepAbandoned()).isZero();
    }
}
