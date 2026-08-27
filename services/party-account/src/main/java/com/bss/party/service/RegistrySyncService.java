package com.bss.party.service;

import com.bss.party.client.RegistryClient;
import com.bss.party.dto.IndividualDto;
import com.bss.party.entity.Individual;
import com.bss.party.entity.RegistryFeedCursor;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.mapper.IndividualMapper;
import com.bss.party.repository.IndividualRepository;
import com.bss.party.repository.RegistryFeedCursorRepository;
import com.bss.party.security.PartyScope;
import com.bss.party.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry re-sync (Norway rails, P1): the party stores a person-id link, a
 * worker polls the registry's sequential event feed per tenant, re-fetches
 * the person on relevant events and applies the truth:
 *
 * <ul>
 *   <li>addressChange → re-fetch; an address updates the party's postal
 *       contact medium and re-emits the EXISTING PartyAddressVerifiedEvent
 *       (the CDP re-home already listens — no new event type for updates);
 *   <li>a re-fetch answering no_data/protected flips {@code addressProtected}
 *       ON: street data is scrubbed from storage (never cache what you may
 *       not hold), masked in every read, and PartyAddressProtectedEvent says
 *       so once — verification short-circuits gracefully, no error anywhere;
 *   <li>nameChange → re-fetch, update the name;
 *   <li>death → {@code deceased=true} + PartyDeceasedFlaggedEvent. A FLAG,
 *       never an automatic termination — care flows decide.
 * </ul>
 */
@Service
public class RegistrySyncService {

    private static final Logger log = LoggerFactory.getLogger(RegistrySyncService.class);

    private final RegistryClient registryClient;
    private final IndividualRepository individuals;
    private final RegistryFeedCursorRepository cursors;
    private final IndividualMapper mapper;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final String country;
    private final String provider;

    public RegistrySyncService(RegistryClient registryClient, IndividualRepository individuals,
            RegistryFeedCursorRepository cursors, IndividualMapper mapper, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope,
            @Value("${bss.registry.country:NO}") String country,
            @Value("${bss.registry.provider:freg}") String provider) {
        this.registryClient = registryClient;
        this.individuals = individuals;
        this.cursors = cursors;
        this.mapper = mapper;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.country = country;
        this.provider = provider;
    }

    /**
     * Staff links a party to its registry person id — the consent moment of
     * the customer relationship is what qualifies the operator for feed
     * access, so the link is back-office data, never self-service. The
     * immediate fetch classifies the person: a protected answer flips the
     * flag right away (graceful, no error), an available answer just proves
     * the ref resolves. Address updates come from the FEED, not from here.
     */
    @Transactional
    public IndividualDto linkRegistryPerson(String id, String personRef) {
        requireBackOffice();
        if (personRef == null || personRef.isBlank()) {
            throw new BadRequestException("personRef is required");
        }
        Individual person = individuals.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Individual", id));
        person.setRegistryPersonRef(personRef.trim());
        RegistryClient.PersonRecord record = registryClient.fetchPerson(person.getRegistryPersonRef());
        if ("protected".equals(record.status())) {
            flagProtected(person);
        } else if ("ok".equals(record.status()) && person.isAddressProtected()) {
            // the registry shares an address again — the flag follows the truth
            person.setAddressProtected(false);
        }
        return mapper.toDto(individuals.save(person));
    }

    /**
     * One poll of the feed for the CURRENT tenant (request scope or an
     * enclosing TenantContext): fetch everything past the cursor, apply in
     * order, advance the cursor. Idempotent between polls — the cursor is
     * the dedup. Returns {processed, lastSeq} so tests and consoles can see
     * the advance.
     */
    @Transactional
    public Map<String, Object> syncCurrentTenant() {
        String tenantId = tenantScope.currentTenantId();
        RegistryFeedCursor cursor = cursors.findByTenantId(tenantId).orElseGet(() -> {
            RegistryFeedCursor fresh = new RegistryFeedCursor();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenantId);
            fresh.setLastSeq(0);
            return fresh;
        });
        List<RegistryClient.FeedEvent> feed = registryClient.fetchEvents(cursor.getLastSeq());
        int processed = 0;
        for (RegistryClient.FeedEvent event : feed) {
            try {
                apply(event, tenantId);
            } catch (Exception e) {
                // one poisoned event must not wedge the feed forever: log,
                // advance, and let the next full re-fetch heal the party
                log.warn("registry event #{} ({}) failed: {}", event.seq(), event.type(), e.getMessage());
            }
            cursor.setLastSeq(event.seq());
            processed++;
        }
        cursor.setUpdatedAt(OffsetDateTime.now());
        cursors.save(cursor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", processed);
        out.put("lastSeq", cursor.getLastSeq());
        return out;
    }

    /** Trigger endpoint face: staff only. */
    @Transactional
    public Map<String, Object> runOnce() {
        requireBackOffice();
        return syncCurrentTenant();
    }

    private void apply(RegistryClient.FeedEvent event, String tenantId) {
        List<Individual> linked = individuals.findByTenantIdAndRegistryPersonRef(tenantId, event.personRef());
        if (linked.isEmpty()) {
            return; // not our customer — the feed carries the whole population
        }
        for (Individual person : linked) {
            switch (event.type()) {
                case "addressChange" -> applyAddressChange(person);
                case "nameChange" -> applyNameChange(person);
                case "death" -> applyDeath(person);
                default -> log.debug("registry event type '{}' ignored", event.type());
            }
        }
    }

    private void applyAddressChange(Individual person) {
        RegistryClient.PersonRecord record = registryClient.fetchPerson(person.getRegistryPersonRef());
        if ("unavailable".equals(record.status())) {
            return; // outage: the next poll re-fetches — never guess
        }
        if ("protected".equals(record.status())) {
            flagProtected(person);
            individuals.save(person);
            return;
        }
        if (person.isAddressProtected()) {
            person.setAddressProtected(false);
        }
        person.setContactMediumJson(IndividualMapper.withRegisteredAddress(
                person.getContactMediumJson(), record.registeredAddress()));
        IndividualDto updated = mapper.toDto(individuals.save(person));
        // REUSE the existing verification event — an update IS a verification
        // (the registry itself said so); the CDP re-home path stays one path.
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("partyId", person.getId());
        verification.put("country", country);
        verification.put("provider", provider);
        verification.put("registeredAddress", record.registeredAddress());
        verification.put("verifiedAt", OffsetDateTime.now().toString());
        events.publish("PartyAddressVerifiedEvent", "addressVerification", verification);
        events.publish("IndividualAttributeValueChangeEvent", "individual", updated);
    }

    private void applyNameChange(Individual person) {
        RegistryClient.PersonRecord record = registryClient.fetchPerson(person.getRegistryPersonRef());
        if (!"ok".equals(record.status()) || record.name() == null || record.name().isBlank()) {
            return;
        }
        String[] parts = record.name().trim().split("\\s+");
        if (parts.length == 1) {
            person.setFamilyName(parts[0]);
        } else {
            person.setGivenName(String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)));
            person.setFamilyName(parts[parts.length - 1]);
        }
        IndividualDto updated = mapper.toDto(individuals.save(person));
        events.publish("IndividualAttributeValueChangeEvent", "individual", updated);
    }

    private void applyDeath(Individual person) {
        if (person.isDeceased()) {
            return;
        }
        person.setDeceased(true);
        individuals.save(person);
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("partyId", person.getId());
        flag.put("flaggedAt", OffsetDateTime.now().toString());
        flag.put("relatedParty", List.of(Map.of("id", person.getId(), "role", "customer")));
        events.publish("PartyDeceasedFlaggedEvent", "deceasedFlag", flag);
    }

    /**
     * The protected-address obligation, in one place: flip the flag (once),
     * SCRUB street data already stored — never cache an address you may not
     * hold — and say so on the bus. Reads mask on top of this (defense in
     * depth); postal code + city survive so parcels can still route to a
     * pickup point.
     */
    private void flagProtected(Individual person) {
        boolean wasProtected = person.isAddressProtected();
        person.setAddressProtected(true);
        person.setContactMediumJson(
                IndividualMapper.scrubStreetData(person.getContactMediumJson()));
        if (!wasProtected) {
            Map<String, Object> protection = new LinkedHashMap<>();
            protection.put("partyId", person.getId());
            protection.put("protectedAt", OffsetDateTime.now().toString());
            protection.put("relatedParty", List.of(Map.of("id", person.getId(), "role", "customer")));
            events.publish("PartyAddressProtectedEvent", "addressProtection", protection);
        }
    }

    /** Registry plumbing is back-office: customer-scoped tokens see 404-shaped nothing. */
    private void requireBackOffice() {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("registry administration is a back-office operation");
        }
    }
}
