import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync } from "node:fs";
import { chromium } from "playwright";

const enabled = process.env.BEEFTV_MOBILE_BROWSER_ENABLED === "1";
const base = "http://127.0.0.1:18790";
const evidence = "../mobile-browser-evidence";
let browser, context;
before(async () => {
    if (!enabled) return;
    mkdirSync(evidence, { recursive: true });
    browser = await chromium.launch({
        headless: true,
        executablePath: process.env.BEEFTV_TEST_CHROMIUM_EXECUTABLE || undefined,
        args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
    });
    context = await browser.newContext({ viewport: { width: 960, height: 480 }, hasTouch: true, isMobile: true });
    context.setDefaultTimeout(10000);
    context.setDefaultNavigationTimeout(15000);
    await context.addCookies([{ name: "BeefTVSession", value: "browser-fixture-session", url: base, httpOnly: true, sameSite: "Strict" }]);
    await context.route("**/*", route => new URL(route.request().url()).origin === base ? route.continue() : route.abort());
});
after(async () => { await context?.close(); await browser?.close(); });
async function visit(path) {
    const page = await context.newPage();
    page.on("pageerror", error => console.error("workspace page error:", error.message));
    await page.goto(base + path);
    return page;
}
async function screenshot(page, name) { await page.screenshot({ path: `${evidence}/${name}.png` }); }

test("packaged home drawer navigates and closes despite saved desktop collapse", { skip: !enabled, timeout: 30000 }, async () => {
    const page = await visit("/");
    try {
        await page.locator(".beeftv-home").waitFor();
        const welcome = page.getByRole("dialog", { name: "完成第一次创作" });
        await welcome.waitFor();
        await screenshot(page, "first-run-guide");
        await welcome.getByRole("button", { name: /稍\s*后/ }).tap();
        await welcome.waitFor({ state: "hidden" });
        await page.evaluate(() => localStorage.setItem("infinite-canvas:workspace-sidebar-collapsed", "1"));
        await page.reload();
        await page.locator(".beeftv-home").waitFor();
        await page.getByRole("button", { name: "展开侧栏", exact: true }).tap();
        const assets = page.locator(".workspace-mobile-navigation [data-nav-id='assets']");
        await assets.waitFor();
        await screenshot(page, "mobile-navigation");
        await assets.tap();
        await page.waitForURL("**/assets");
        await page.locator(".workspace-mobile-navigation .ant-drawer-body").waitFor({ state: "detached" });
        await page.getByRole("button", { name: "展开侧栏", exact: true }).tap();
        await page.locator(".workspace-mobile-navigation [aria-label='收起侧栏']").tap();
        await page.locator(".workspace-mobile-navigation .ant-drawer-body").waitFor({ state: "detached" });
    } finally { await screenshot(page, "navigation-final").catch(() => {}); await page.close(); }
});

test("packaged model form remains editable in short landscape viewport", { skip: !enabled, timeout: 30000 }, async () => {
    const page = await visit("/settings?section=channels");
    try {
        await page.locator(".settings-pane-header-actions button").filter({ hasText: "添加模型服务" }).click();
        const modal = page.locator(".model-service-modal");
        await modal.locator("input[aria-label='服务地址']").waitFor();
        for (const label of ["连接名称", "服务地址"]) {
            const input = modal.locator(`input[aria-label='${label}']`);
            await input.scrollIntoViewIfNeeded();
            await input.fill(label === "连接名称" ? "手机回归测试" : "https://model.example/v1");
            const box = await input.boundingBox();
            assert.ok(box && box.height <= 36 && box.y >= 0 && box.y + box.height <= 480, `${label} is clipped or oversized`);
        }
        await screenshot(page, "model-form-landscape");
        await modal.locator(".ant-modal-close").click();
        await modal.waitFor({ state: "hidden" });
    } finally { await screenshot(page, "model-form-final").catch(() => {}); await page.close(); }
});

test("packaged director retains a useful stage and reachable scene/property panels", { skip: !enabled, timeout: 45000 }, async () => {
    const page = await visit("/canvas?mode=new&start=director");
    try {
        const root = page.locator("[data-director-workbench]");
        await root.waitFor({ timeout: 30000 });
        assert.equal(await root.getAttribute("data-left-dock-collapsed"), "true");
        const stage = await root.locator("main").boundingBox();
        assert.ok(stage && stage.width > 960 * .7 && stage.height > 480 * .65, "Director stage is squeezed by docks");
        const guide = page.getByRole("region", { name: "导演台上手引导" });
        const next = guide.getByRole("button", { name: "下一步", exact: true });
        await next.waitFor();
        const contrast = await next.evaluate(button => {
            const style = getComputedStyle(button);
            const luminance = color => {
                const rgb = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
                return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
            };
            const text = luminance(style.color), background = luminance(style.backgroundColor);
            return (Math.max(text, background) + .05) / (Math.min(text, background) + .05);
        });
        assert.ok(contrast >= 4.5, "Director onboarding action text is unreadable");
        await next.tap();
        await guide.getByText("把演员移动到表演位置", { exact: true }).waitFor();
        await guide.getByRole("button", { name: "跳过引导", exact: true }).tap();
        await guide.waitFor({ state: "hidden" });
        await screenshot(page, "director-landscape");
        await page.getByRole("button", { name: "展开场景面板", exact: true }).tap();
        assert.equal(await root.getAttribute("data-left-dock-collapsed"), "false");
        await page.getByRole("button", { name: "折叠场景面板", exact: true }).tap();
        await page.getByRole("button", { name: "展开属性面板", exact: true }).tap();
        assert.ok((await page.locator("[data-director-right-dock]").boundingBox())?.width > 200);
        await screenshot(page, "director-properties");
        await page.getByRole("button", { name: "收起属性面板", exact: true }).tap();
        await page.getByRole("button", { name: "关闭导演台", exact: true }).tap();
        await root.waitFor({ state: "detached" });
    } finally { await screenshot(page, "director-final").catch(() => {}); await page.close(); }
});
