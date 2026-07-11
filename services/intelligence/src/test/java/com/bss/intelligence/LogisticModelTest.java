package com.bss.intelligence;

import com.bss.intelligence.churn.LogisticModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** The trainer must genuinely learn, not just run: fit synthetic churn data. */
class LogisticModelTest {

    @Test
    void learnsThatFrictionAndExpiryMeanChurn() {
        // Synthetic operator history: churners have imminent commitment ends
        // and recent tickets; loyal customers have neither.
        Random rnd = new Random(42);
        int n = 400;
        double[][] x = new double[n][];
        boolean[] y = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean churner = i % 2 == 0;
            x[i] = new double[] {
                    churner ? rnd.nextDouble() * 40 : 100 + rnd.nextDouble() * 400,
                    0.3 + rnd.nextDouble() * 0.6,
                    churner ? 2 + rnd.nextInt(4) : rnd.nextInt(2),
                    churner && rnd.nextBoolean() ? 1 : 0,
            };
            y[i] = churner;
        }
        LogisticModel model = LogisticModel.train(x, y);

        int correct = 0;
        for (int i = 0; i < n; i++) {
            if ((model.predict(x[i]) >= 0.5) == y[i]) {
                correct++;
            }
        }
        assertThat((double) correct / n).isGreaterThan(0.9);

        // And it generalizes to customers it never saw.
        assertThat(model.predict(new double[] {10, 0.5, 4, 1})).isGreaterThan(0.8);
        assertThat(model.predict(new double[] {350, 0.4, 0, 0})).isLessThan(0.2);
    }
}
