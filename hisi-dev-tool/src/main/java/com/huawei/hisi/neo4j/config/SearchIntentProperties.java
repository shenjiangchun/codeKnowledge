package com.huawei.hisi.neo4j.config;

import com.huawei.hisi.neo4j.model.IntentType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configurable weights and parameters for intent-aware multi-channel search.
 *
 * <p>Bound to {@code search.intent-weights} and {@code search.rrf} in application.yml.
 * All values have sensible defaults so the application works without explicit config.</p>
 */
@Component
@ConfigurationProperties(prefix = "search")
public class SearchIntentProperties {

    /** Per-intent-type raw weights (before log-scaling). */
    private Map<IntentType, Double> intentWeights = defaultIntentWeights();

    /** RRF smoothing constant K. */
    private int rrfK = 60;

    /** Log-scale alpha: w_eff = 1 + alpha * ln(w_raw / w_base). */
    private double logScaleAlpha = 0.5;

    /** Log-scale base weight (denominator). */
    private double logScaleBase = 1.0;

    /** Max multiplier for multi-hit normalization: cap = max(w_eff) * this ratio. */
    private double maxMultiHitRatio = 2.0;

    /** Callee weight propagation ratio (0.0–1.0). */
    private double calleePropagationRatio = 0.3;

    /** Post-filter annotation bonus score. */
    private double annotationBonusScore = 0.5;

    /** Confidence threshold for dual-channel search. */
    private double dualChannelThreshold = 0.7;

    /** Whether to enable required-word filtering for specialized channels. */
    private boolean requiredWordFilterEnabled = true;

    // --- Getters / Setters ---

    public Map<IntentType, Double> getIntentWeights() {
        return intentWeights;
    }

    public void setIntentWeights(Map<IntentType, Double> intentWeights) {
        this.intentWeights = intentWeights;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getLogScaleAlpha() {
        return logScaleAlpha;
    }

    public void setLogScaleAlpha(double logScaleAlpha) {
        this.logScaleAlpha = logScaleAlpha;
    }

    public double getLogScaleBase() {
        return logScaleBase;
    }

    public void setLogScaleBase(double logScaleBase) {
        this.logScaleBase = logScaleBase;
    }

    public double getMaxMultiHitRatio() {
        return maxMultiHitRatio;
    }

    public void setMaxMultiHitRatio(double maxMultiHitRatio) {
        this.maxMultiHitRatio = maxMultiHitRatio;
    }

    public double getCalleePropagationRatio() {
        return calleePropagationRatio;
    }

    public void setCalleePropagationRatio(double calleePropagationRatio) {
        this.calleePropagationRatio = calleePropagationRatio;
    }

    public double getAnnotationBonusScore() {
        return annotationBonusScore;
    }

    public void setAnnotationBonusScore(double annotationBonusScore) {
        this.annotationBonusScore = annotationBonusScore;
    }

    public double getDualChannelThreshold() {
        return dualChannelThreshold;
    }

    public void setDualChannelThreshold(double dualChannelThreshold) {
        this.dualChannelThreshold = dualChannelThreshold;
    }

    public boolean isRequiredWordFilterEnabled() {
        return requiredWordFilterEnabled;
    }

    public void setRequiredWordFilterEnabled(boolean requiredWordFilterEnabled) {
        this.requiredWordFilterEnabled = requiredWordFilterEnabled;
    }

    /**
     * Compute the effective (log-scaled) weight for an intent type.
     * Formula: w_eff = 1 + α · ln(w_raw / w_base)
     * When w_raw == w_base, w_eff = 1.0 (no boost).
     */
    public double effectiveWeight(IntentType intentType) {
        double wRaw = intentWeights.getOrDefault(intentType, intentType.getDefaultRawWeight());
        if (wRaw <= logScaleBase) {
            return 1.0; // no boost for at-or-below-base weights
        }
        return 1.0 + logScaleAlpha * Math.log(wRaw / logScaleBase);
    }

    private static Map<IntentType, Double> defaultIntentWeights() {
        Map<IntentType, Double> map = new EnumMap<>(IntentType.class);
        for (IntentType t : IntentType.values()) {
            map.put(t, t.getDefaultRawWeight());
        }
        return map;
    }
}
