package dev.sampay.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        if (amount == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        if (amount.signum() < 0) {
            throw new InvalidAmountException("Amount must not be negative: " + amount);
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidAmountException("Amount must have at most 2 decimal places: " + amount, e);
        }
    }

    public static Money of(String value) {
        if (value == null) {
            throw new InvalidAmountException("Amount must not be null");
        }
        try {
            return new Money(new BigDecimal(value));
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("Amount is not a valid number: " + value, e);
        }
    }

    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public boolean isGreaterThan(Money other) {
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }
}
