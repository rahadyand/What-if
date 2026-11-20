package id.whatif;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact fraction used internally for weighted grades, thresholds and ceiling operations. */
record Exact(BigInteger numerator, BigInteger denominator) implements Comparable<Exact> {
    static final Exact ZERO = of(0);
    static final Exact ONE = of(1);
    static final Exact HUNDRED = of(100);

    Exact {
        if (denominator.signum() == 0) throw new ArithmeticException("Penyebut nol.");
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor);
        denominator = denominator.divide(divisor);
    }

    static Exact of(int value) {
        return new Exact(BigInteger.valueOf(value), BigInteger.ONE);
    }

    static Exact of(BigDecimal value) {
        if (value.scale() < 0) return new Exact(value.toBigIntegerExact(), BigInteger.ONE);
        return new Exact(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }

    Exact add(Exact other) {
        return new Exact(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    Exact subtract(Exact other) {
        return add(new Exact(other.numerator.negate(), other.denominator));
    }

    Exact multiply(Exact other) {
        return new Exact(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    Exact divide(Exact other) {
        return new Exact(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    BigDecimal decimal(int scale, RoundingMode rounding) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, rounding);
    }

    @Override public int compareTo(Exact other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
}
