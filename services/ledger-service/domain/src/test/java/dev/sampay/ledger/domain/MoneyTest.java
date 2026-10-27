package dev.sampay.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesScaleSoEqualValuesAreEqual() {
        assertThat(Money.of("10")).isEqualTo(Money.of("10.00"));
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of("-1.00"))
            .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> Money.of("10.005"))
            .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsNonNumericText() {
        assertThatThrownBy(() -> Money.of("abc"))
            .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void addsWithoutFloatingPointError() {
        assertThat(Money.of("0.10").plus(Money.of("0.20"))).isEqualTo(Money.of("0.30"));
    }

    @Test
    void rejectsSubtractionResultingInNegative() {
        assertThatThrownBy(() -> Money.of("5.00").minus(Money.of("10.00")))
            .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void operationsDoNotChangeOriginal() {
        Money original = Money.of("10.00");

        original.plus(Money.of("5.00"));

        assertThat(original).isEqualTo(Money.of("10.00"));
    }
}
