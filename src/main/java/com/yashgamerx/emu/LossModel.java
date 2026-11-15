package com.yashgamerx.emu;

import java.util.Random;

/** Packet-loss process applied on an emulated link. */
public interface LossModel {
    boolean drop(Random rnd);

    /**
     * Loss decision for a packet sent {@code slots} link packet-times after the previous one.
     * Memoryless models ignore it; burst models evolve their channel state over elapsed time.
     */
    default boolean drop(Random rnd, long slots) {
        return drop(rnd);
    }

    /** Long-run average loss probability, for reporting. */
    double meanLoss();

    LossModel copy();

    static LossModel none() {
        return new Bernoulli(0);
    }

    /** Independent losses with probability p (what netem's plain "loss p%" does). */
    record Bernoulli(double p) implements LossModel {
        public boolean drop(Random rnd) {
            return p > 0 && rnd.nextDouble() < p;
        }

        public double meanLoss() {
            return p;
        }

        public LossModel copy() {
            return this;
        }
    }

    /**
     * Two-state Gilbert-Elliott burst-loss channel. pGB: P(good -> bad) per packet,
     * pBG: P(bad -> good); lossGood / lossBad: loss probability inside each state.
     */
    final class GilbertElliott implements LossModel {
        final double pGB, pBG, lossGood, lossBad;
        private boolean bad;

        public GilbertElliott(double pGB, double pBG, double lossGood, double lossBad) {
            this.pGB = pGB;
            this.pBG = pBG;
            this.lossGood = lossGood;
            this.lossBad = lossBad;
        }

        public boolean drop(Random rnd) {
            return drop(rnd, 1);
        }

        /**
         * n-step transition of the two-state chain in closed form:
         * P(bad after n) = piB + (1{bad now} - piB) * (1 - pGB - pBG)^n.
         * With n = elapsed packet slots, a burst is a period of channel time (as in a fade) rather
         * than a count of packets, so a slow sender (e.g. Stop-and-Wait in RTO backoff) is not kept
         * inside one burst for seconds. With back-to-back packets n = 1 and this is the classic per-packet model.
         */
        public boolean drop(Random rnd, long slots) {
            double piB = pGB / (pGB + pBG);
            double pBad = piB + ((bad ? 1 : 0) - piB) * Math.pow(1 - pGB - pBG, Math.max(1, slots));
            bad = rnd.nextDouble() < pBad;
            return rnd.nextDouble() < (bad ? lossBad : lossGood);
        }

        public double meanLoss() {
            double piBad = pGB / (pGB + pBG);
            return piBad * lossBad + (1 - piBad) * lossGood;
        }

        public LossModel copy() {
            return new GilbertElliott(pGB, pBG, lossGood, lossBad);
        }

        @Override
        public String toString() {
            return "GE(pGB=" + pGB + ",pBG=" + pBG + ",lossGood=" + lossGood + ",lossBad=" + lossBad + ")";
        }
    }
}
