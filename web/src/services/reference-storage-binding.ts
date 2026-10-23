import { isAndroidMobile } from "@/lib/mobile-platform";
import { http } from "@/services/api/request";
import { getDesktopAppBinding, type DesktopRuntimeBinding, type ReferenceStorageConfig } from "@/services/desktop-runtime";
type ReferenceStorageBinding = Pick<DesktopRuntimeBinding, "ReferenceStorageSettings" | "SaveReferenceStorage" | "UploadReferenceMedia" | "ReferenceUploadStatus" | "TestReferenceStorage">;
const mobileBinding: ReferenceStorageBinding = {
    ReferenceStorageSettings: () => http.get<ReferenceStorageConfig>("/mobile/reference-storage"),
    SaveReferenceStorage: config => http.post<ReferenceStorageConfig>("/mobile/reference-storage", config),
    UploadReferenceMedia: ids => http.post<Record<string,string>>("/mobile/reference-storage/upload", {ids}, {timeout: 610_000}),
    TestReferenceStorage: () => http.post<void>("/mobile/reference-storage/test", {}, {timeout: 70_000}),
};
export function getReferenceStorageBinding(): ReferenceStorageBinding | undefined {
    return isAndroidMobile ? mobileBinding : getDesktopAppBinding();
}

