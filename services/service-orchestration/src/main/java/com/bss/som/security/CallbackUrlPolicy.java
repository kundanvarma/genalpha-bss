package com.bss.som.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.bss.som.exception.BadRequestException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where a wholesale order's callback may point.
 *
 * A retailer's BSS puts a {@code callbackUrl} in the order body and we POST to
 * it when the access order completes. The door is signed now, so the caller is
 * a known operator — but the URL is still a string they chose, and we fetch it
 * from inside the fleet's own network. That is a request-forgery primitive:
 * name an internal address and we will knock on it with our own network
 * position, which nothing outside can reach.
 *
 * Two layers, both needed:
 *
 *  1. Shapes that are never a legitimate retailer callback, refused always —
 *     a scheme that is not http(s), credentials embedded in the URL, and any
 *     address that is loopback, link-local (169.254/16, which is where cloud
 *     instance metadata lives), multicast or the wildcard address.
 *  2. An allow-list of hosts. This is the real control, because a retailer's
 *     callback host is a fact the operator knows when they federate, and
 *     everything else on the private network looks exactly like a legitimate
 *     internal address. With no list configured, a callback is REFUSED rather
 *     than sent: the safe direction for a notification, and loud in the log.
 *
 * Checked when the order is accepted, so a bad URL is never stored and the
 * caller is told, and again before the callback fires, so a row stored before
 * this existed cannot be used.
 *
 * Honest limit: the host is resolved here and again by the HTTP client, so a
 * name that answers differently the second time (DNS rebinding) would slip
 * past. Closing that means checking at connection time, which the JDK client
 * does not expose cheaply. The allow-list is what actually carries the weight.
 */
@Component
public class CallbackUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(CallbackUrlPolicy.class);

    private final Set<String> allowedHosts;

    public CallbackUrlPolicy(
            @Value("${bss.wholesale.callback-allowed-hosts:}") String allowedHosts) {
        this.allowedHosts = List.of(allowedHosts.split(",")).stream()
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedHosts.isEmpty()) {
            log.warn("wholesale callbacks are DISABLED: bss.wholesale.callback-allowed-hosts is empty, "
                    + "so every retailer callback will be refused. Name the retailer hosts to enable them.");
        }
    }

    /**
     * Refuse the order outright — the caller gets told what is wrong.
     *
     * The component's own BadRequestException, not a ResponseStatusException:
     * this door is anonymous at the security layer (the signature is the
     * credential, checked inside), and an exception that reaches the security
     * filter on an anonymous request comes back as 401 with a Bearer
     * challenge. A federating retailer would be told to authenticate when the
     * truth is that their callback host is not on the list.
     */
    public void requireAllowed(String url) {
        String why = reasonToRefuse(url);
        if (why != null) {
            throw new BadRequestException("callbackUrl " + why);
        }
    }

    /** Is this URL still safe to call? Used at the moment the callback fires. */
    public boolean allows(String url) {
        String why = reasonToRefuse(url);
        if (why != null) {
            log.warn("refusing to call a wholesale callback: {} — {}", url, why);
            return false;
        }
        return true;
    }

    /** null when the URL is acceptable; otherwise the reason, in plain words. */
    private String reasonToRefuse(String url) {
        if (url == null || url.isBlank()) {
            return "must not be empty";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return "is not a URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "must be http or https";
        }
        if (uri.getUserInfo() != null) {
            return "must not carry credentials";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "has no host";
        }
        host = host.toLowerCase(Locale.ROOT);

        if (allowedHosts.isEmpty()) {
            return "is not on the allowed-host list, which is empty — no retailer host is configured";
        }
        if (!allowedHosts.contains(host)) {
            return "host '" + host + "' is not on the allowed-host list";
        }
        // an allowed NAME can still resolve somewhere it should not
        String address = reasonAddressIsRefused(host);
        if (address != null) {
            return address;
        }
        return null;
    }

    private String reasonAddressIsRefused(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // A name that does not resolve is not a forgery risk — the request
            // simply fails. Refusing the order for it would reject a legitimate
            // retailer over a DNS blip at exactly the wrong moment. This check
            // exists for the other case: an allowed NAME pointing somewhere it
            // should not.
            return null;
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress()) {
                return "resolves to a loopback address";
            }
            if (a.isLinkLocalAddress()) {
                // 169.254.169.254 is the cloud instance-metadata address
                return "resolves to a link-local address";
            }
            if (a.isMulticastAddress()) {
                return "resolves to a multicast address";
            }
            if (a.isAnyLocalAddress()) {
                return "resolves to the wildcard address";
            }
        }
        return null;
    }
}
