package com.bss.intelligence.churn;

import java.util.Arrays;

/**
 * The production-quality provision, honestly sized: standardized logistic
 * regression, trained in-service. This is where most real telco churn
 * systems START — transparent coefficients a data team can audit, no
 * dependencies, and it keeps learning as snapshots and outcomes accumulate.
 * When an operator outgrows it, a gradient-boosted or deep model is the
 * next implementation behind the same interface.
 */
public final class LogisticModel {

    public static final String[] FEATURES = {
            "daysToCommitmentEnd", "maxUsageRatio", "ticketsLast30d", "openTicketDuringOutage"};

    private final double[] means;
    private final double[] stds;
    private final double[] weights;
    private final double bias;

    public LogisticModel(double[] means, double[] stds, double[] weights, double bias) {
        this.means = means;
        this.stds = stds;
        this.weights = weights;
        this.bias = bias;
    }

    /** Batch gradient descent on standardized features. */
    public static LogisticModel train(double[][] x, boolean[] y) {
        int n = x.length, d = FEATURES.length;
        double[] means = new double[d], stds = new double[d];
        for (int j = 0; j < d; j++) {
            for (double[] row : x) {
                means[j] += row[j] / n;
            }
            double var = 0;
            for (double[] row : x) {
                var += Math.pow(row[j] - means[j], 2) / n;
            }
            stds[j] = Math.max(Math.sqrt(var), 1e-9);
        }
        double[][] z = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                z[i][j] = (x[i][j] - means[j]) / stds[j];
            }
        }
        double[] w = new double[d];
        double b = 0;
        double lr = 0.5;
        for (int iter = 0; iter < 500; iter++) {
            double[] gw = new double[d];
            double gb = 0;
            for (int i = 0; i < n; i++) {
                double p = sigmoid(dot(w, z[i]) + b);
                double err = p - (y[i] ? 1 : 0);
                for (int j = 0; j < d; j++) {
                    gw[j] += err * z[i][j] / n;
                }
                gb += err / n;
            }
            for (int j = 0; j < d; j++) {
                w[j] -= lr * gw[j];
            }
            b -= lr * gb;
        }
        return new LogisticModel(means, stds, w, b);
    }

    public double predict(double[] features) {
        double[] z = new double[features.length];
        for (int j = 0; j < features.length; j++) {
            z[j] = (features[j] - means[j]) / stds[j];
        }
        return sigmoid(dot(weights, z) + bias);
    }

    public double[] getMeans() { return Arrays.copyOf(means, means.length); }
    public double[] getStds() { return Arrays.copyOf(stds, stds.length); }
    public double[] getWeights() { return Arrays.copyOf(weights, weights.length); }
    public double getBias() { return bias; }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double sigmoid(double v) {
        return 1.0 / (1.0 + Math.exp(-v));
    }
}
