package dev.vitrail.render.dlss;

/**
 * Model preset hints supported by NVIDIA DLSS 3.1+.
 */
public enum DlssPreset {

	DEFAULT("options.vitrail.dlss.preset.default", 0),
	PRESET_A("options.vitrail.dlss.preset.a", 1),
	PRESET_B("options.vitrail.dlss.preset.b", 2),
	PRESET_C("options.vitrail.dlss.preset.c", 3),
	PRESET_D("options.vitrail.dlss.preset.d", 4),
	PRESET_E("options.vitrail.dlss.preset.e", 5),
	PRESET_F("options.vitrail.dlss.preset.f", 6),
	PRESET_G("options.vitrail.dlss.preset.g", 7),
	PRESET_J("options.vitrail.dlss.preset.j", 10),
	PRESET_K("options.vitrail.dlss.preset.k", 11),
	PRESET_L("options.vitrail.dlss.preset.l", 12),
	PRESET_M("options.vitrail.dlss.preset.m", 13);

	private final String translationKey;
	private final int ngxValue;

	DlssPreset(String translationKey, int ngxValue) {
		this.translationKey = translationKey;
		this.ngxValue = ngxValue;
	}

	public String translationKey() {
		return this.translationKey;
	}

	public int ngxValue() {
		return this.ngxValue;
	}
}
