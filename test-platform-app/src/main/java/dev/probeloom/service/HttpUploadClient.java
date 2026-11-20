package dev.probeloom.service;

import dev.probeloom.model.PlatformModels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

final class HttpUploadClient {
    UploadResult upload(String baseUrl, PlatformModels.CaseUpload upload, PlatformModels.TestFileAsset asset,
                        Map<String, String> headers, Map<String, String> formFields) {
        HttpURLConnection connection = null;
        try {
            String boundary = "----TestPlatform" + UUID.randomUUID().toString().replace("-", "");
            connection = (HttpURLConnection) new URI(resolveUrl(baseUrl, upload.path)).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(8192);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!"content-type".equalsIgnoreCase(header.getKey())) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            try { connection.connect(); }
            catch (java.net.ConnectException e) {
                throw new IllegalStateException("文件上传连接失败，请求未送达：" + safeMessage(e), e);
            }
            int status;
            String response;
            try {
                try (OutputStream output = connection.getOutputStream()) {
                    for (Map.Entry<String, String> field : formFields.entrySet()) {
                        writeTextPart(output, boundary, field.getKey(), field.getValue());
                    }
                    writeFilePart(output, boundary, upload, asset);
                    output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                status = connection.getResponseCode();
                response = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            } catch (IOException e) {
                throw new RemoteOutcomeUnknownException("文件上传结果未知，需核查远端处理状态：" + safeMessage(e), e);
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("文件上传失败，HTTP " + status + "：" + response);
            }
            UploadResult result = new UploadResult();
            result.requestUrl = connection.getURL().toString();
            result.responseBody = response;
            return result;
        } catch (IllegalStateException | RemoteOutcomeUnknownException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文件上传失败：" + safeMessage(e), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String resolveUrl(String baseUrl, String path) {
        return HttpProtocolAdapter.resolveUrl(baseUrl, path);
    }

    private void writeTextPart(OutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(OutputStream output, String boundary, PlatformModels.CaseUpload upload, PlatformModels.TestFileAsset asset) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + upload.fileField + "\"; filename=\"" + upload.fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + upload.contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(asset.content);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String read(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = source.read(buffer)) >= 0) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static final class UploadResult {
        String requestUrl;
        String responseBody;
    }
}
