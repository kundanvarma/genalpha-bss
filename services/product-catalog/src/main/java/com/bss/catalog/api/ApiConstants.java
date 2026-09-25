package com.bss.catalog.api;

public final class ApiConstants {

    public static final String BASE_PATH = "/tmf-api/productCatalogManagement/v4";

    /** TMF633 Service Catalog Management — CFS/RFS service specifications. */
    public static final String SERVICE_CATALOG_BASE_PATH = "/tmf-api/serviceCatalogManagement/v4";

    /**
     * The R18 (v3) dialect of TMF633 rides beside v4 on the same rows and the
     * same controllers: identical resources, hrefs stay canonical (v4), the only
     * difference is the status the older generation expects from a PATCH (201).
     */
    public static final String SERVICE_CATALOG_V3_BASE_PATH = "/tmf-api/serviceCatalogManagement/v3";

    /** TMF634 Resource Catalog Management — resource specifications (the seams the RFS realise). ADR-0021. */
    public static final String RESOURCE_CATALOG_BASE_PATH = "/tmf-api/resourceCatalogManagement/v4";

    /** The canonical schema home for TMF634 payloads. */
    public static final String RESOURCE_SCHEMA_BASE =
            "https://raw.githubusercontent.com/tmforum-apis/Open_Api_And_Data_Model/master/schemas/Resource/";

    /** The canonical schema home for TMF633 payloads (the TM Forum Open API and Data Model repository). */
    public static final String SERVICE_SCHEMA_BASE =
            "https://raw.githubusercontent.com/tmforum-apis/Open_Api_And_Data_Model/master/schemas/Service/";

    /** Is this request on the R18 (v3) face of the service catalog? */
    public static boolean isServiceCatalogV3(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(SERVICE_CATALOG_V3_BASE_PATH);
    }

    private ApiConstants() {
    }
}
