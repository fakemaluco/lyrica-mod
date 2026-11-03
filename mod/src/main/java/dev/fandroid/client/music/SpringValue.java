package dev.fandroid.client.music;

/** Frame-rate-independent damped spring used by the lyric renderer. */
public final class SpringValue {
    private static final double EPSILON = 1E-5;
    private double frequency;
    private double damping;
    private double goal;
    private double position;
    private double velocity;

    public SpringValue(double start, double frequency, double damping) {
        retune(frequency,damping);
        goal = start;
        position = start;
    }

    /** Change response without discarding the current position or momentum. */
    public void retune(double frequency,double damping) {
        if(!Double.isFinite(frequency) || frequency<=0)throw new IllegalArgumentException("frequency must be positive");
        if(!Double.isFinite(damping) || damping<0)throw new IllegalArgumentException("damping cannot be negative");
        this.frequency=frequency;this.damping=damping;
    }
    public void setGoal(double goal) {
        this.goal = goal;
    }

    public double position() {
        return position;
    }

    public void snap(double value) {
        goal = value;
        position = value;
        velocity = 0;
    }

    public boolean isAtRest() {
        return Math.abs(position - goal) < EPSILON && Math.abs(velocity) < EPSILON;
    }

    public double step(double delta) {
        if (delta <= 0) return position;
        if (isAtRest()) {
            position = goal;
            velocity = 0;
            return position;
        }
        double angular = frequency * 2 * Math.PI;
        double offset = position - goal;
        if (damping == 1) {
            double decay = Math.exp(-angular * delta);
            double weighted = delta * decay;
            position = offset * (decay + weighted * angular) + velocity * weighted + goal;
            velocity = velocity * (decay - weighted * angular) - offset * weighted * angular * angular;
        } else if (damping < 1) {
            double c = Math.sqrt(1 - damping * damping);
            double decay = Math.exp(-damping * angular * delta);
            double cosine = Math.cos(delta * angular * c);
            double sine = Math.sin(delta * angular * c);
            double z = c > EPSILON ? sine / c : delta * angular;
            double yy = angular * c > EPSILON ? sine / (angular * c) : delta;
            position = (offset * (cosine + z * damping) + velocity * yy) * decay + goal;
            velocity = (velocity * (cosine - z * damping) - offset * z * angular) * decay;
        } else {
            double c = Math.sqrt(damping * damping - 1);
            double firstRoot = -angular * (damping + c);
            double secondRoot = -angular * (damping - c);
            double firstDecay = Math.exp(firstRoot * delta);
            double secondDecay = Math.exp(secondRoot * delta);
            double secondCoefficient = (velocity - offset * firstRoot) / (2 * angular * c);
            double firstCoefficient = firstDecay * (offset - secondCoefficient);
            position = firstCoefficient + secondCoefficient * secondDecay + goal;
            velocity = firstCoefficient * firstRoot + secondCoefficient * secondDecay * secondRoot;
        }
        return position;
    }
}
