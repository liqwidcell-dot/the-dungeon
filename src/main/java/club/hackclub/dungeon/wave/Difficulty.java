package club.hackclub.dungeon.wave;

/**
 * The difficulty curve.
 *
 * <p>Every number here comes from config.yml, so the whole game can be
 * rebalanced by editing YAML and restarting — no recompiling while you are
 * tuning it.
 *
 * <p>Health is deliberately not {@code wave * k}. That makes waves 1-3 trivial
 * and waves 9-10 impossible. An exponent above 1 keeps early waves survivable
 * and makes the late ones spike, which is what makes a run feel like it is
 * climbing towards something.
 */
public final class Difficulty {

    private final double baseHealth;
    private final double healthCoefficient;
    private final double healthExponent;
    private final int baseCount;
    private final double countPerWave;
    private final double baseDamage;
    private final double damagePerWave;

    public Difficulty(DoubleValues v) {
        this.baseHealth = v.baseHealth();
        this.healthCoefficient = v.healthCoefficient();
        this.healthExponent = v.healthExponent();
        this.baseCount = v.baseCount();
        this.countPerWave = v.countPerWave();
        this.baseDamage = v.baseDamage();
        this.damagePerWave = v.damagePerWave();
    }

    /** Max health for a mob on the given wave. */
    public double healthFor(int wave) {
        return Math.round(baseHealth * (1.0 + healthCoefficient * Math.pow(wave, healthExponent)) * 10.0) / 10.0;
    }

    /** How many mobs to spawn on the given wave. */
    public int countFor(int wave) {
        return baseCount + (int) Math.floor(countPerWave * wave);
    }

    /** Attack damage for a mob on the given wave. */
    public double damageFor(int wave) {
        return Math.round((baseDamage + damagePerWave * wave) * 10.0) / 10.0;
    }

    /**
     * The tuning numbers, as read from config.
     *
     * <p>A record rather than seven constructor parameters, because seven
     * adjacent same-typed doubles in a row is genuinely easy to get wrong.
     */
    public record DoubleValues(
            double baseHealth,
            double healthCoefficient,
            double healthExponent,
            int baseCount,
            double countPerWave,
            double baseDamage,
            double damagePerWave) {
    }
}
