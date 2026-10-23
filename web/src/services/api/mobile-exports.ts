import { http } from "@/services/api/request";
import { assertUserScope, captureUserScope } from "@/lib/user-scope-guard";
export type MobileExportReceipt = { id: string; state: "preparing" | "queued" | "awaiting" | "saved" | "cancelled" | "failed"; error?: string };
export async function saveMobileExport(fileName: string, source: Blob | { resourceId: string }): Promise<"saved" | "cancelled"> {
    const scope = captureUserScope();
    let request: FormData | { fileName: string; resourceId: string };
    if (source instanceof Blob) {
        const form = new FormData();
        form.append("file", source, fileName);
        // Explicit name comes after the file so MIME never replaces .srt/.zip.
        form.append("fileName", fileName);
        request = form;
    } else request = { fileName, resourceId: source.resourceId };
    const created = await http.post<MobileExportReceipt>("/mobile/exports", request, { timeout: 600_000 });
    assertUserScope(scope);
    const deadline = Date.now() + 60 * 60 * 1000;
    while (Date.now() < deadline) {
        const result = await http.get<MobileExportReceipt>(`/mobile/exports/${encodeURIComponent(created.id)}`);
        assertUserScope(scope);
        if (result.state === "saved" || result.state === "cancelled") return result.state;
        if (result.state === "failed") throw new Error(result.error || "文件保存失败");
        await new Promise(resolve => setTimeout(resolve, 500));
    }
    throw new Error("系统保存尚未完成，请关闭保存窗口后重试");
}

