package dev.vitrail.render.dlss;

import dev.vitrail.render.RenderScale;

import org.joml.Matrix4f;

/**
 * Generates low-discrepancy sub-pixel camera jitter offsets for temporal supersampling (DLSS).
 * <p>
 * Uses a Halton(2, 3) sequence with a cycle phase of 16 or 32 samples.
 * The offsets are mapped to pixel-space fractions in {@code [-0.5, 0.5]}, which are converted
 * to NDC offsets in the projection matrix and fed to {@link DlssBridge#evaluate}.
 */
public final class DlssJitter {

	private static final int PHASE_COUNT = 32;

	private static final float[] JITTER_X = new float[PHASE_COUNT];
	private static final float[] JITTER_Y = new float[PHASE_COUNT];

	private static final float JITTER_SIGN_X =
			Float.parseFloat(System.getProperty("vitrail.dlss.jitterSignX", "1.0"));
	private static final float JITTER_SIGN_Y =
			Float.parseFloat(System.getProperty("vitrail.dlss.jitterSignY", "1.0"));

	private static int currentPhase = 0;
	private static float currentJitterX = 0.0F;
	private static float currentJitterY = 0.0F;

	static {
		for (int i = 0; i < PHASE_COUNT; i++) {
			// Halton sequence index i + 1 (1-based to avoid starting at (0, 0))
			JITTER_X[i] = halton(i + 1, 2) - 0.5F;
			JITTER_Y[i] = halton(i + 1, 3) - 0.5F;
		}
	}

	private DlssJitter() {
	}

	private static float halton(int index, int base) {
		float f = 1.0F;
		float result = 0.0F;
		int current = index;
		while (current > 0) {
			f /= (float) base;
			result += f * (float) (current % base);
			current /= base;
		}
		return result;
	}

	private static final boolean JITTER_ENABLED =
			Boolean.parseBoolean(System.getProperty("vitrail.dlss.jitter", "true"));

	/**
	 * Advances the jitter sequence for the current frame if DLSS is active.
	 */
	public static void advance() {
		if (JITTER_ENABLED && RenderScale.usingDlss()) {
			currentPhase = (currentPhase + 1) % PHASE_COUNT;
			currentJitterX = JITTER_X[currentPhase];
			currentJitterY = JITTER_Y[currentPhase];
		} else {
			currentPhase = 0;
			currentJitterX = 0.0F;
			currentJitterY = 0.0F;
		}
	}

	/**
	 * Pixel-space jitter X offset in range {@code [-0.5, 0.5]}.
	 */
	public static float jitterX() {
		return currentJitterX;
	}

	/**
	 * Pixel-space jitter Y offset in range {@code [-0.5, 0.5]}.
	 */
	public static float jitterY() {
		return currentJitterY;
	}

	public static int currentPhase() {
		return currentPhase;
	}

	public static int phaseCount() {
		return PHASE_COUNT;
	}

	public static boolean isJitterEnabled() {
		return JITTER_ENABLED;
	}

	/**
	 * Modifies the perspective projection matrix in place to inject sub-pixel camera jitter.
	 * <p>
	 * In NDC space, offset is {@code 2.0 * jitter / renderDimension}.
	 * Standard NVIDIA NGX DLSS convention specifies:
	 * {@code jitteredProj.m20 = unjitteredProj.m20 + (2.0 * jitterX) / renderWidth}
	 * {@code jitteredProj.m21 = unjitteredProj.m21 + (2.0 * jitterY) / renderHeight}
	 *
	 * @param projection   the level projection matrix to perturb
	 * @param renderWidth  the render resolution width
	 * @param renderHeight the render resolution height
	 */
	public static void applyJitter(Matrix4f projection, int renderWidth, int renderHeight) {
		if (!JITTER_ENABLED || !RenderScale.usingDlss() || renderWidth <= 0 || renderHeight <= 0) {
			return;
		}

		float ndcX = (2.0F * currentJitterX * JITTER_SIGN_X) / (float) renderWidth;
		float ndcY = (2.0F * currentJitterY * JITTER_SIGN_Y) / (float) renderHeight;

		// Adding NDC jitter to m20 and m21 according to DLSS standard specification
		projection.m20(projection.m20() + ndcX);
		projection.m21(projection.m21() + ndcY);
	}
}
