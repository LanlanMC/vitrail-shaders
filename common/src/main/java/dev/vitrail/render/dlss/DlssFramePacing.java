package dev.vitrail.render.dlss;

/**
 * Frame pacing and tick timing tracker for DLSS and DLSS Frame Generation diagnostics.
 */
public final class DlssFramePacing {

	private static long lastRenderStartNanos = 0L;
	private static long smoothedTickNs = 14_000_000L; // Default ~70fps base (14.0ms)

	private DlssFramePacing() {
	}

	public static void onRenderStart() {
		long now = System.nanoTime();
		if (lastRenderStartNanos > 0L) {
			long tickDelta = now - lastRenderStartNanos;
			// Filter out window resize pauses or extreme hitches (>100ms or <2ms)
			if (tickDelta >= 2_000_000L && tickDelta <= 100_000_000L) {
				smoothedTickNs = (smoothedTickNs * 31 + tickDelta) / 32;
			}
		}
		lastRenderStartNanos = now;
	}

	public static long smoothedTickNs() {
		return smoothedTickNs;
	}

	public static void reset() {
		lastRenderStartNanos = 0L;
		smoothedTickNs = 14_000_000L;
	}
}
