package dev.vitrail.render.dlss;

/**
 * Optimal rendering settings recommended by the DLSS runtime for a given target resolution.
 */
public record DlssOptimalSettings(
		int renderWidth,
		int renderHeight,
		float sharpness,
		int minRenderWidth,
		int minRenderHeight
) {
	public boolean valid() {
		return this.renderWidth > 0 && this.renderHeight > 0;
	}
}
