package com.bss.entitlement.api;

public final class ApiConstants {

    /** The BSS face: subscribers, devices, companions, the request log. */
    public static final String BASE_PATH = "/tmf-api/deviceEntitlement/v1";

    /** The DEVICE face: GSMA TS.43 Entitlement Configuration Server door.
     * Phones reach it through the operator's ECS hostname; auth is EAP-AKA
     * (or a token it issued), never a BSS login. */
    public static final String TS43_PATH = "/ts43";

    private ApiConstants() {
    }
}
