package com.bss.inventory;

import com.bss.inventory.entity.Product;
import com.bss.inventory.repository.ProductRepository;
import com.bss.inventory.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A terminated service closes ITS product — never a different one of the same
 * name (#357). A swap ends the old benefit (ordering closes its product at
 * once) and adds a new one of the same name; when the old service's
 * termination arrives late, the owner + name fallback must not close the new.
 */
@SpringBootTest
@ActiveProfiles("test")
class TerminatedServiceTest {

    @Autowired
    private ProductService products;

    @Autowired
    private ProductRepository repository;

    private Product product(String owner, String status, String serviceId) {
        Product p = new Product();
        p.setId(UUID.randomUUID().toString());
        p.setTenantId("genalpha");
        p.setOwnerPartyId(owner);
        p.setName("Lydbok+ Audiobooks");
        p.setStatus(status);
        p.setRealizingServiceId(serviceId);
        return repository.save(p);
    }

    @Test
    void aLateTerminationOfTheOldService_leavesTheNewProductOfTheSameNameActive() {
        String owner = UUID.randomUUID().toString();
        String oldService = UUID.randomUUID().toString();
        product(owner, "cancelled", oldService);                     // the order already closed it
        Product replacement = product(owner, "active", UUID.randomUUID().toString());

        products.closeForTerminatedService("genalpha", owner, "Lydbok+ Audiobooks", oldService);

        assertThat(repository.findById(replacement.getId()).orElseThrow().getStatus()).isEqualTo("active");
    }

    @Test
    void aServiceNoProductCarries_stillFallsBackToOwnerAndName() {
        String owner = UUID.randomUUID().toString();
        Product legacy = product(owner, "active", null);   // an old row from before lineage existed
        products.closeForTerminatedService("genalpha", owner, "Lydbok+ Audiobooks", UUID.randomUUID().toString());
        assertThat(repository.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
    }
}
