package com.bss.intelligence.llm;

/**
 * The vendor seam: everything above this line is prompt assembly, redaction
 * and audit; everything below is one HTTP dialect. Swapping the model is
 * configuration ("bss.intelligence.provider"), never a code change —
 * the same rule the BSS applies to IdPs, databases and brokers.
 */
public interface LlmAdapter {

    String complete(String system, String user);

    /** Which provider/model answered — recorded in the audit ledger. */
    String provider();

    String model();
}
