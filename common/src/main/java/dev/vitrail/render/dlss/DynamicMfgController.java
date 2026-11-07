package dev.vitrail.render.dlss;

import net.minecraft.client.Minecraft;

/**
 * Controller for Dynamic Multi-Frame Generation (Adaptive MFG).
 * <p>
 * Dynamically adjusts DLSS-G intermediate generated frame counts (2x, 3x, 4x)
 * based on real-time base framerate and display target framerate.
 * <p>
 * Includes:
 * <ul>
 *     <li>Exponential Moving Average (EMA) frame time filtering to eliminate noise.</li>
 *     <li>Low-FPS latency guardrail (locks to 2x when base FPS &lt; 60 to prevent input queue lag).</li>
 *     <li>Hysteresis deadbands and cooldown windows to prevent micro-stutter from rapid multiplier oscillation.</li>
 *     <li>Step-limited transitions (&plusmn;1 step at a time).</li>
 * </ul>
 */
public final class DynamicMfgController {

	public static final DynamicMfgController INSTANCE = new DynamicMfgController();

	/**
	 * Guardrail threshold: if real base FPS falls below 60, high intermediate frame multipliers
	 * (3x / 4x) cause severe input latency and motion interpolation artifacts.
	 * Lock to standard 2x presentation in this regime.
	 */
	public static final double LOW_BASE_FPS_GUARD = 60.0;

	/** EMA smoothing factor for base frame duration. */
	private static final double EMA_ALPHA = 0.15;

	/** Minimum duration (in nanoseconds) between multiplier shifts to eliminate flutter. */
	private static final long SWITCH_COOLDOWN_NS = 500_000_000L; // 500ms

	private double smoothedFrameTimeMs = 16.66;
	private long lastFrameTimeNs = 0L;
	private long lastSwitchTimeNs = 0L;

	private int currentMultiplier = 1; // 1 = 2x, 2 = 3x, 3 = 4x
	private int candidateMultiplier = 1;
	private int candidateFrames = 0;

	private int cachedTargetFps = 144;
	private boolean guardedByLowFps = false;

	private DynamicMfgController() {
	}

	/**
	 * Reset dynamic controller state (e.g. on level reload or menu exit).
	 */
	public void reset() {
		smoothedFrameTimeMs = 16.66;
		lastFrameTimeNs = 0L;
		lastSwitchTimeNs = 0L;
		currentMultiplier = 1;
		candidateMultiplier = 1;
		candidateFrames = 0;
		guardedByLowFps = false;
	}

	/**
	 * Update controller state with the newly finished base frame.
	 *
	 * @param nowNs current system monotonic timestamp in nanoseconds
	 * @param maxAdmitted maximum intermediate frame count supported by DLSS-G runtime (e.g. 3 for 4x)
	 * @return effective number of intermediate generated frames (1 = 2x, 2 = 3x, 3 = 4x)
	 */
	public int update(long nowNs, int maxAdmitted) {
		if (lastFrameTimeNs != 0L) {
			long deltaNs = nowNs - lastFrameTimeNs;
			if (deltaNs > 1_000_000L && deltaNs < 500_000_000L) { // 1ms .. 500ms sanity check
				double deltaMs = deltaNs / 1_000_000.0;
				smoothedFrameTimeMs = EMA_ALPHA * deltaMs + (1.0 - EMA_ALPHA) * smoothedFrameTimeMs;
			}
		}
		lastFrameTimeNs = nowNs;

		cachedTargetFps = resolveTargetFps();
		double baseFps = smoothedBaseFps();

		int desiredMultiplier;
		if (baseFps < LOW_BASE_FPS_GUARD) {
			// Low base FPS guardrail: lock to 2x (1 generated frame)
			desiredMultiplier = 1;
			guardedByLowFps = true;
		} else {
			guardedByLowFps = false;
			// Scale intermediate multiplier up if needed to saturate the target framerate
			if (baseFps * 2.0 >= cachedTargetFps) {
				desiredMultiplier = 1; // 2x presentation is sufficient
			} else if (baseFps * 3.0 >= cachedTargetFps) {
				desiredMultiplier = 2; // 3x presentation needed
			} else {
				desiredMultiplier = 3; // 4x presentation needed
			}
		}

		int clampedDesired = Math.clamp(desiredMultiplier, 1, Math.max(1, maxAdmitted));

		// Debounce and hysteresis check
		if (clampedDesired != currentMultiplier) {
			if (clampedDesired == candidateMultiplier) {
				candidateFrames++;
			} else {
				candidateMultiplier = clampedDesired;
				candidateFrames = 1;
			}

			// Require sustained signal (15 frames for upshift, 25 frames for downshift) and cooldown
			int requiredFrames = (candidateMultiplier > currentMultiplier) ? 15 : 25;
			boolean cooldownElapsed = (nowNs - lastSwitchTimeNs) >= SWITCH_COOLDOWN_NS;

			if (candidateFrames >= requiredFrames && cooldownElapsed) {
				// Step by at most 1 to avoid abrupt presentation shifts
				if (candidateMultiplier > currentMultiplier) {
					currentMultiplier++;
				} else {
					currentMultiplier--;
				}
				lastSwitchTimeNs = nowNs;
				candidateFrames = 0;
			}
		} else {
			candidateFrames = 0;
			candidateMultiplier = currentMultiplier;
		}

		return currentMultiplier;
	}

	private static int resolveTargetFps() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) {
				int limit = mc.options.framerateLimit().get();
				if (limit > 0 && limit < 260) {
					return limit;
				}
			}
		} catch (Throwable ignored) {
		}
		return 144; // Default target refresh rate for high-refresh displays
	}

	public double smoothedBaseFps() {
		return smoothedFrameTimeMs > 0.001 ? 1000.0 / smoothedFrameTimeMs : 60.0;
	}

	public int targetFps() {
		return cachedTargetFps;
	}

	public int currentMultiplier() {
		return currentMultiplier;
	}

	public boolean isGuardedByLowFps() {
		return guardedByLowFps;
	}
}
