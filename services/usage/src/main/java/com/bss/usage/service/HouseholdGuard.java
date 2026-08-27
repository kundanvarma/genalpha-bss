package com.bss.usage.service;

import com.bss.usage.client.PartyClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The household-role checks the gifting move already relies on, shared by the
 * policy layer: who is a child, who runs a household, who belongs to one.
 * All checks fail CLOSED — an unreachable party source authorizes nothing.
 */
@Component
public class HouseholdGuard {

    private final PartyClient partyClient;

    public HouseholdGuard(PartyClient partyClient) {
        this.partyClient = partyClient;
    }

    /** A minor: an ACTIVE child link in a household (the guardian machinery's definition). */
    public boolean isMinor(String partyId) {
        Map<String, Object> link = linkOf(partyId);
        return active(link) && "child".equals(link.get("role"));
    }

    /** Caller runs the household anchored on ownerId: is the owner, or an ACTIVE admin linked to them. */
    public boolean managesHousehold(String callerId, String ownerId) {
        if (callerId.equals(ownerId)) {
            return true;
        }
        Map<String, Object> link = linkOf(callerId);
        return active(link) && "admin".equals(link.get("role"))
                && ownerId.equals(String.valueOf(link.get("id")));
    }

    /** partyId is the owner, or an ACTIVE member of the owner's household. */
    public boolean inHousehold(String partyId, String ownerId) {
        if (partyId.equals(ownerId)) {
            return true;
        }
        Map<String, Object> link = linkOf(partyId);
        return active(link) && ownerId.equals(String.valueOf(link.get("id")));
    }

    /** Caller is the payer or an ACTIVE admin of the household the ACTIVE CHILD belongs to. */
    public boolean guardianOf(String callerId, String childId) {
        Map<String, Object> childLink = linkOf(childId);
        if (!active(childLink) || !"child".equals(childLink.get("role"))) {
            return false;
        }
        return managesHousehold(callerId, String.valueOf(childLink.get("id")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> linkOf(String partyId) {
        try {
            return partyClient.individualOf(partyId)
                    .map(ind -> ind.get("householdPayer") instanceof Map<?, ?> link
                            ? (Map<String, Object>) link : null)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;    // unreachable party source (or unmintable token): no link, no rights
        }
    }

    private static boolean active(Map<String, Object> link) {
        return link != null && "active".equals(link.get("status"));
    }
}
