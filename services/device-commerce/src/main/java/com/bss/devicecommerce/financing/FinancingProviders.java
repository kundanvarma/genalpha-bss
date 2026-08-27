package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Model → driver. An unknown model is a client error, not a fallback. */
@Component
public class FinancingProviders {

    private final Map<String, FinancingProvider> byModel;

    public FinancingProviders(List<FinancingProvider> providers) {
        this.byModel = providers.stream()
                .collect(Collectors.toMap(FinancingProvider::model, Function.identity()));
    }

    public FinancingProvider forModel(String model) {
        FinancingProvider provider = byModel.get(model);
        if (provider == null) {
            throw new BadRequestException("financingModel must be one of " + byModel.keySet());
        }
        return provider;
    }
}
