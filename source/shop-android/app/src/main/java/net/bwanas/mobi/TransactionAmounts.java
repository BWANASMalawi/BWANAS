package net.bwanas.mobi;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class TransactionAmounts {
    private TransactionAmounts() { }

    static BigDecimal quantity(String input, boolean allowZero, boolean whole) {
        BigDecimal amount = new BigDecimal(input.trim()).stripTrailingZeros();
        if (amount.signum() < 0 || (!allowZero && amount.signum() == 0)
                || amount.scale() > (whole ? 0 : 3)
                || amount.compareTo(new BigDecimal("100000000000")) >= 0) {
            throw new NumberFormatException("Unsupported quantity");
        }
        return amount;
    }

    static BigDecimal lineTotal(double quantity, double price) {
        return BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(price))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
