package dev.vitrail.render.dlss;

/**
 * Quality modes supported by NVIDIA DLSS Super Resolution.
 */
public enum DlssMode {

	OFF("options.off", -1, 1.0F),
	QUALITY("options.vitrail.dlss.quality", 2, 0.6666667F),
	BALANCED("options.vitrail.dlss.balanced", 1, 0.58F),
	PERFORMANCE("options.vitrail.dlss.performance", 0, 0.50F),
	ULTRA_PERFORMANCE("options.vitrail.dlss.ultra_performance", 3, 0.3333333F),
	DLAA("options.vitrail.dlss.dlaa", 5, 1.0F);

	private final String translationKey;
	private final int ngxPerfQualityValue;
	private final float fallbackScale;

	DlssMode(String translationKey, int ngxPerfQualityValue, float fallbackScale) {
		this.translationKey = translationKey;
		this.ngxPerfQualityValue = ngxPerfQualityValue;
		this.fallbackScale = fallbackScale;
	}

	public String translationKey() {
		return this.translationKey;
	}

	public int ngxPerfQualityValue() {
		return this.ngxPerfQualityValue;
	}

	public float fallbackScale() {
		return this.fallbackScale;
	}

	public boolean enabled() {
		return this != OFF;
	}
}
