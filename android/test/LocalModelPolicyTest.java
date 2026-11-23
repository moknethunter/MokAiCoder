package org.mokai.coder;

public final class LocalModelPolicyTest {
    public static void main(String[] args) {
        String request = "نزّل نموذج Qwen3-0.6B";
        if (!LocalModelCatalog.downloadCommand(request) ||
                LocalModelCatalog.requested(request) != LocalModelCatalog.Model.SMALL ||
                LocalModelCatalog.downloadCommand("هل يمكن تنزيل نموذج Qwen3-0.6B؟") ||
                LocalModelCatalog.requested("/download model https://example.test/a.gguf") != null ||
                !LocalModelCatalog.coding("اكتب دالة بلغة Python") ||
                LocalModelCatalog.requiredMemory(LocalModelCatalog.Model.SMALL.size) <= LocalModelCatalog.Model.SMALL.size) {
            throw new AssertionError("Local model routing or resource policy failed");
        }
        System.out.println("Local model routing policy passed.");
    }
}
