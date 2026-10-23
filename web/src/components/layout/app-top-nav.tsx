import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import { PanelLeftOpen } from "lucide-react";

import { AppDrawer } from "@/components/ui/product/app-drawer";
import { ModelSetupGuide } from "@/components/layout/model-setup-guide";
import { WorkspaceSidebarNav } from "@/components/layout/workspace-sidebar-nav";
import { readWorkspaceSidebarCollapsed, writeWorkspaceSidebarCollapsed } from "@/components/layout/workspace-sidebar-state";
import { useDesktopUpdateBootstrap } from "@/hooks/use-desktop-update";
import { cn } from "@/lib/utils";
import { isSpatialWorkbenchPath } from "@/lib/workspace-routes";
import { useUserStore } from "@/stores/use-user-store";
import { isLocalWorkspaceMode } from "@/services/workspace-mode";

const WorkspaceCommandPalette = lazy(() => import("@/components/layout/workspace-command-palette").then((module) => ({ default: module.WorkspaceCommandPalette })));

export function AppWorkspaceShell({ children }: { children: ReactNode }) {
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const [mobileSidebarExpanded, setMobileSidebarExpanded] = useState(false);
    const [desktopSidebarCollapsed, setDesktopSidebarCollapsed] = useState(readWorkspaceSidebarCollapsed);
    const [paletteOpen, setPaletteOpen] = useState(false);
    const sessionHydrated = useUserStore((state) => state.hydrated);
    const storageMode = useUserStore((state) => state.storageMode);
    const user = useUserStore((state) => state.user);
    const localMode = isLocalWorkspaceMode() || storageMode === "local" || user?.username === "local";
    useDesktopUpdateBootstrap();

    const hideChrome = pathname.startsWith("/admin") || /^\/canvas\/[^/]+/.test(pathname);
    const spatialWorkbench = isSpatialWorkbenchPath(pathname);
    const creationWorkspace = pathname === "/create";

    const [mobileViewport, setMobileViewport] = useState(() => window.matchMedia("(max-width: 1023px)").matches);
    useEffect(() => {
        const query = window.matchMedia("(max-width: 1023px)");
        const update = () => { setMobileViewport(query.matches); setMobileSidebarExpanded(false); };
        query.addEventListener("change", update);
        return () => query.removeEventListener("change", update);
    }, []);
    useEffect(() => { setMobileSidebarExpanded(false); }, [pathname]);

    const toggleSidebar = () => {
        if (mobileViewport) {
            setMobileSidebarExpanded((current) => !current);
            return;
        }
        setDesktopSidebarCollapsed((current) => {
            const next = !current;
            writeWorkspaceSidebarCollapsed(next);
            return next;
        });
    };

    const expandDesktopSidebar = () => {
        setDesktopSidebarCollapsed(false);
        writeWorkspaceSidebarCollapsed(false);
    };

    const collapseDesktopSidebar = () => {
        setDesktopSidebarCollapsed(true);
        writeWorkspaceSidebarCollapsed(true);
    };

    const handleNavClick = () => {
        setMobileSidebarExpanded(false);
    };

    // ⌘K / Ctrl+K 全局呼出搜索面板。
    useEffect(() => {
        const handler = (event: KeyboardEvent) => {
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
                event.preventDefault();
                setPaletteOpen((open) => !open);
            }
        };
        window.addEventListener("keydown", handler);
        return () => window.removeEventListener("keydown", handler);
    }, []);

    useEffect(() => {
        const handleWorkspaceNavigation = (rawEvent: Event) => {
            const event = rawEvent as CustomEvent<{ to?: string }>;
            if (!event.detail?.to) return;
            event.preventDefault();
            navigate(event.detail.to);
        };
        window.addEventListener("workspace:navigate", handleWorkspaceNavigation);
        return () => window.removeEventListener("workspace:navigate", handleWorkspaceNavigation);
    }, [navigate]);

    return (
        <>
            <div className={cn("app-workspace-shell flex h-dvh min-h-0 w-full flex-col overflow-hidden", spatialWorkbench && "is-spatial", creationWorkspace && "is-creation-workspace")}>


                <div className="app-workspace-main-row flex min-h-0 min-w-0 flex-1 overflow-hidden">
                    {!hideChrome && !mobileViewport ? (
                        <aside
                            className={cn(
                                "app-workspace-sidebar flex h-full shrink-0 flex-col overflow-hidden",
                                mobileSidebarExpanded && "is-mobile-expanded",
                                desktopSidebarCollapsed && "is-collapsed",
                            )}
                        >
                            <WorkspaceSidebarNav
                                collapsed={desktopSidebarCollapsed}
                                onNavigate={handleNavClick}
                                onOpenSearch={() => setPaletteOpen(true)}
                                onExpand={expandDesktopSidebar}
                                onCollapse={collapseDesktopSidebar}
                            />
                        </aside>
                    ) : null}

                    <div className="app-workspace-stage relative flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
                        {!hideChrome ? (
                            <button type="button" className="app-workspace-mobile-menu app-workspace-topbar-icon-button app-workspace-floating-menu" aria-label="展开侧栏" aria-expanded={mobileSidebarExpanded} aria-controls="workspace-mobile-navigation" onClick={toggleSidebar}>
                                <PanelLeftOpen className="size-4" strokeWidth={1.7} />
                            </button>
                        ) : null}
                        <div className="relative min-h-0 min-w-0 flex-1 overflow-hidden">{children}</div>
                    </div>
                </div>

                {paletteOpen ? <Suspense fallback={null}><WorkspaceCommandPalette open onClose={() => setPaletteOpen(false)} /></Suspense> : null}
            </div>
            {mobileViewport && <AppDrawer open={!hideChrome && mobileSidebarExpanded} placement="left" size="min(280px, calc(100vw - 48px))" flush closable={false} onClose={() => setMobileSidebarExpanded(false)} rootClassName="workspace-mobile-navigation app-user-workspace app-product-workspace">
                <div id="workspace-mobile-navigation" className="h-full min-h-0">
                    <WorkspaceSidebarNav collapsed={false} onNavigate={handleNavClick} onOpenSearch={() => { setMobileSidebarExpanded(false); setPaletteOpen(true); }} onExpand={() => setMobileSidebarExpanded(true)} onCollapse={() => setMobileSidebarExpanded(false)} />
                </div>
            </AppDrawer>}
            <ModelSetupGuide hidden={!sessionHydrated || localMode || pathname === "/login" || pathname === "/register" || pathname.startsWith("/admin") || pathname.startsWith("/assets")} />
        </>
    );
}
