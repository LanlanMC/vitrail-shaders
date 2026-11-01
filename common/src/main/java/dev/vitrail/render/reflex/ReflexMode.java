package dev.vitrail.render.reflex;

/**
 * Operating mode for NVIDIA Reflex 2 low latency technology.
 */
public enum ReflexMode {
	OFF("options.vitrail.reflex.off", 0),
	ON("options.vitrail.reflex.on", 1),
	ON_BOOST("options.vitrail.reflex.on_boost", 2);

	private final String translationKey;
	private final int value;

	ReflexMode(String translationKey, int value) {
		this.translationKey = translationKey;
		this.value = value;
	}

	public String translationKey() {
		return translationKey;
	}

	public int value() {
		return value;
	}

	public boolean enabled() {
		return this != OFF;
	}

	public boolean boost() {
		return this == ON_BOOST;
	}
}
