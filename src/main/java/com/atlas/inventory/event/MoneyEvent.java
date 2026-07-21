package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record MoneyEvent(@NotNull BigDecimal amount, String currency) {}
