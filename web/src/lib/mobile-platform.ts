/** Set by the Android distribution build, independent of browser user-agent. */
export const isAndroidMobile = import.meta.env.VITE_BEEFTV_PLATFORM === "android";

export type MobileDensity = "compact" | "comfortable";
export function readMobileDensity(): MobileDensity {
    try { return localStorage.getItem("beeftv:mobile-density") === "comfortable" ? "comfortable" : "compact"; }
    catch { return "compact"; }
}
export function applyMobileDensity(value: MobileDensity) {
    if (!isAndroidMobile) return;
    document.documentElement.dataset.beeftvPlatform = "android";
    document.documentElement.dataset.mobileDensity = value;
    try { localStorage.setItem("beeftv:mobile-density", value); } catch { /* In-memory density still works. */ }
}

