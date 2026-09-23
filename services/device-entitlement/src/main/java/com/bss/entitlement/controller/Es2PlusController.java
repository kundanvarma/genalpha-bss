package com.bss.entitlement.controller;

import com.bss.entitlement.api.ApiConstants;
import com.bss.entitlement.dto.DownloadProgressInfo;
import com.bss.entitlement.dto.Es2PlusReply;
import com.bss.entitlement.security.TenantContext;
import com.bss.entitlement.service.SubscriberService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SM-DP+ → operator notification (SGP.22 ES2+ §5.3.5
 * {@code handleDownloadProgressInfo}): the profile server tells us how far a
 * download got. One door per tenant — the SM-DP+ knows the operator by the
 * requester identifier it was given, which is the path segment here. Under
 * {@code /ts43/**} so the gateway already routes it; production fronts it
 * with the mutual-TLS the SM-DP+ contract requires.
 */
@RestController
@RequestMapping(ApiConstants.TS43_PATH + "/es2plus")
public class Es2PlusController {

    private final SubscriberService subscribers;

    public Es2PlusController(SubscriberService subscribers) {
        this.subscribers = subscribers;
    }

    @PostMapping("/{tenantId}/handleDownloadProgressInfo")
    public Es2PlusReply progress(@PathVariable("tenantId") String tenantId,
            @RequestBody DownloadProgressInfo body) {
        Es2PlusReply.ProfileProgress result;
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            result = subscribers.profileProgress(tenantId, body.iccidText(), body.eidText(),
                    body.point(), body.status());
        }
        return Es2PlusReply.executedSuccess(result);
    }
}
