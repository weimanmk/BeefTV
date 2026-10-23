import "@fontsource-variable/inter";
import { isAndroidMobile, readMobileDensity, applyMobileDensity } from "@/lib/mobile-platform";
import "@/styles/android-mobile.css";
if (isAndroidMobile) applyMobileDensity(readMobileDensity());
import "@fontsource-variable/jetbrains-mono";
import { startCheckedWorkspace } from "@/services/diagnostics/startup-preflight";

void startCheckedWorkspace(() => import("./bootstrap-workspace").then((module) => module.startApplication()));

