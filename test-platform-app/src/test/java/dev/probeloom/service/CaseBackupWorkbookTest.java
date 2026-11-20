package dev.probeloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class CaseBackupWorkbookTest {
    @Test
    void backupBytesStayStableAcrossTimeButKeepBodyAndAttachmentChanges() throws Exception {
        CaseWorkbookCodec codec = new CaseWorkbookCodec(new ObjectMapper());
        PlatformModels.TestCase testCase = codec.parse(new ByteArrayInputStream(codec.template(Collections.emptyList(), Collections.emptyList())));
        byte[] first = CaseBackupService.stableBackupWorkbook(codec.write(testCase));
        Thread.sleep(3100);
        byte[] second = CaseBackupService.stableBackupWorkbook(codec.write(testCase));
        assertArrayEquals(first, second);
        PlatformModels.TestCase restored = codec.parse(new ByteArrayInputStream(second));
        assertEquals(testCase.actions.size(), restored.actions.size());
        PlatformModels.CaseAction http = testCase.actions.stream().filter(action -> "HTTP".equals(action.actionType)).findFirst().get();
        String originalRequest = http.requestBody;
        http.requestBody = originalRequest.replace("hello", "changed body");
        assertNotEquals(originalRequest, http.requestBody);
        assertFalse(Arrays.equals(first, CaseBackupService.stableBackupWorkbook(codec.write(testCase))));
        http.requestBody = originalRequest;
        PlatformModels.CaseAction upload = testCase.actions.stream().filter(action -> "UPLOAD".equals(action.actionType)).findFirst().get();
        upload.upload.contentBase64 = java.util.Base64.getEncoder().encodeToString("changed attachment".getBytes(StandardCharsets.UTF_8));
        byte[] attachmentChanged = CaseBackupService.stableBackupWorkbook(codec.write(testCase));
        assertFalse(Arrays.equals(first, attachmentChanged));
        PlatformModels.TestCase attachmentRestored = codec.parse(new ByteArrayInputStream(attachmentChanged));
        assertEquals(upload.upload.contentBase64, attachmentRestored.actions.stream().filter(action -> "UPLOAD".equals(action.actionType)).findFirst().get().upload.contentBase64);
    }
}
