/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.util;

import java.io.IOException;
import java.net.SocketTimeoutException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpUtil {

    public static Response executeWithRetries(OkHttpClient client, Request request, String operationName) throws Exception {
        final int maxAttempts = 3;
        long waitMs = 1200;

        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Response response = client.newCall(request).execute();

                if (response.isSuccessful()) {
                    return response;
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                int statusCode = response.code();
                response.close();

                boolean retryableStatus = statusCode == 408 || statusCode == 429 || statusCode >= 500;
                if (!retryableStatus || attempt == maxAttempts) {
                    String suffix = responseBody == null || responseBody.isBlank()
                            ? ""
                            : " - " + responseBody;
                    throw new RuntimeException(operationName + " API error: " + statusCode + suffix);
                }
            } catch (SocketTimeoutException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw new RuntimeException(operationName + " timeout na meerdere pogingen.", e);
                }
            } catch (IOException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw new RuntimeException(operationName + " netwerkfout na meerdere pogingen.", e);
                }
            }

            Thread.sleep(waitMs);
            waitMs *= 2;
        }

        if (lastException != null) {
            throw new RuntimeException(operationName + " fout bij uitvoeren van request.", lastException);
        }

        throw new RuntimeException(operationName + " kon niet worden uitgevoerd.");
    }

    public static boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}